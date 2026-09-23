package ai.openclaw.android.agent.decision

import android.util.Log
import ai.openclaw.android.LogManager
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.InputData
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.Session
import com.google.ai.edge.litertlm.SessionConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 端侧决策执行器。
 *
 * 与生成路径（[ai.openclaw.android.model.LocalLLMClient] 的 chat/chatStream）的区别：
 * - 生成路径用 `Conversation`（system prompt + 工具 schema + 历史 + 自回归 decode 几十~几百 token）；
 * - 决策路径用 `Session`（纯 prefill + 1 步 decode，无 system/tools/历史），
 *   单次预算 **0.3–0.6s**，而端侧生成 100 token 要 3–5s，差 30–50 倍（见 docs/jev-decision-model-integration）。
 *
 * 并发：由外部传入与生成路径**同一个** Mutex（LocalLLMClient.sessionMutex）。
 *
 * ⚠️ M0 真机验证结论（2026-09-22，荣耀 DNP-AN00 / GPU backend）：
 * **Session 与 Conversation 共享引擎的唯一会话槽位** —— 主会话还开着时
 * `createSession()` 直接抛 `FAILED_PRECONDITION: A session already exists`。
 * 因此每次决策前必须先经 [onBeforeSession] 释放主会话，代价是主会话 KV-cache 作废
 * （下次聊天重建一次全量 prefill，与既有 `chat()` 旁路同款代价）。
 * 决策本身实测：prefill ≈ 50ms/条 + 1 步 decode ≈ 220ms/条 ≈ **0.27s/条**。
 */
class OnDeviceDecisionRunner(
    private val engineProvider: () -> Engine?,
    private val sessionMutex: Mutex,
    /** 建 Session 前的钩子：由 LocalLLMClient 传入，用于释放占着槽位的主会话 */
    private val onBeforeSession: (suspend () -> Unit)? = null,
) {
    private val TAG = "DecisionRunner"

    /** 批量决策的默认超时（实测 0.27s/条，10 条约 3s，留一点余量） */
    private val DEFAULT_BATCH_TIMEOUT_MS = 4_000L

    /** 单字母协议下合法输出的长度上限，超过就认为是模型在续写而非作答 */
    private val MAX_RAW_CHARS = 16

    /** 决策会话的采样配置：temperature=0 + topK=1 → decode 即确定性 argmax */
    private val decisionSampler: SamplerConfig
        get() = SamplerConfig(
            topK = 1,
            topP = 1.0,
            temperature = 0.0,
            seed = 0,
        )

    /**
     * 单条决策。HIGH 利害关系时跑两次（选项顺序置换），一致才算可信。
     */
    suspend fun decide(req: DecisionRequest): DecisionResult = sessionMutex.withLock {
        withContext(Dispatchers.IO) {
            val started = System.currentTimeMillis()
            val engine = engineProvider()
            if (engine == null) {
                LogManager.shared.log("WARN", TAG, "引擎未加载，决策跳过: ${req.id}")
                return@withContext DecisionResult.unavailable()
            }
            if (req.options.isEmpty() || req.options.size > DecisionPrompts.MAX_OPTIONS) {
                Log.e(TAG, "选项数量非法 (${req.options.size})，拒绝执行: ${req.id}")
                return@withContext DecisionResult.unavailable()
            }

            try {
                val first = runSingle(engine, req, req.options)
                if (req.stakes == DecisionStakes.LOW || first.chosenIndex == null) {
                    return@withContext DecisionResult(
                        chosenIndex = first.chosenIndex,
                        confidence = if (first.chosenIndex == null)
                            DecisionResult.CONFIDENCE_INCONSISTENT
                        else
                            DecisionResult.CONFIDENCE_SINGLE,
                        latencyMs = System.currentTimeMillis() - started,
                        raw = first.raw,
                    )
                }

                // HIGH：置换选项顺序再跑一次，比对「原顺序下的同一选项」是否仍被选中
                val permuted = DecisionPrompts.permute(req.options)
                val second = runSingle(engine, req, permuted)
                val secondOriginalIndex = second.chosenIndex?.let { idx ->
                    DecisionPrompts.unpermuteIndex(idx, req.options.size)
                }
                val consistent = secondOriginalIndex != null && secondOriginalIndex == first.chosenIndex
                val result = DecisionResult(
                    chosenIndex = if (consistent) first.chosenIndex else null,
                    confidence = if (consistent)
                        DecisionResult.CONFIDENCE_CONSISTENT
                    else
                        DecisionResult.CONFIDENCE_INCONSISTENT,
                    latencyMs = System.currentTimeMillis() - started,
                    raw = "${first.raw}|${second.raw}",
                )
                LogManager.shared.log(
                    "INFO", TAG,
                    "决策[${req.id}] 双跑${if (consistent) "一致" else "不一致"} " +
                        "first=${first.chosenIndex} second=${secondOriginalIndex} ${result.latencyMs}ms"
                )
                result
            } catch (e: Exception) {
                Log.e(TAG, "决策失败 ${req.id}: ${e.javaClass.simpleName}: ${e.message}")
                LogManager.shared.log("WARN", TAG, "决策失败 ${req.id}: ${e.message}")
                DecisionResult.unavailable(System.currentTimeMillis() - started)
            }
        }
    }

    /**
     * 批量决策：共享前缀（系统说明 + 选项 + 问题）只 prefill 一次，
     * 之后每条增量 prefill 自己的输入 + 1 步 decode。
     *
     * 适合通知过滤这类「同一决策表、N 条短输入」的场景，摊掉重复的说明部分。
     */
    /**
     * 批量决策。
     *
     * ⚠️ 实现已从「共享会话 + 每条增量 prefill」改为**每条独立会话**，原因见
     * docs/decision-mode-design-2026-09-22.md §5：真机校准时观察到，同一个 Session
     * 内连续 prefill 到**第 12 条左右**模型就开始失控——不再输出单个字母，而是
     * 凭记忆续写后面的 Item（内容失真），那一次 decode 还拖到了 **20 秒**。
     * 而生产 P3-1 的批次上限正是 10 条，恰好落在这个崩坏阈值边缘。
     *
     * 每条重建会话的代价只是重复 prefill 一次 header（约 50ms/批），而 decode
     * 仍占单条耗时的 80%，实测总量几乎无损，但彻底消除了长上下文失控风险。
     */
    suspend fun decideBatch(
        question: String,
        options: List<String>,
        items: List<Pair<String, String>>, // (id, state)
        timeoutMs: Long = DEFAULT_BATCH_TIMEOUT_MS,
    ): List<DecisionResult> = sessionMutex.withLock {
        withContext(Dispatchers.IO) {
            val started = System.currentTimeMillis()
            val engine = engineProvider()
            if (engine == null) {
                Log.w(TAG, "引擎未加载，批量决策跳过 (${items.size} 条)")
                return@withContext items.map { DecisionResult.unavailable() }
            }
            if (options.isEmpty() || options.size > DecisionPrompts.MAX_OPTIONS) {
                Log.e(TAG, "选项数量非法 (${options.size})，批量决策拒绝执行")
                return@withContext items.map { DecisionResult.unavailable() }
            }

            val results = mutableListOf<DecisionResult>()
            try {
                for (i in items.indices) {
                    val (id, state) = items[i]
                    if (System.currentTimeMillis() - started > timeoutMs) {
                        LogManager.shared.log(
                            "WARN", TAG,
                            "批量决策超时(${timeoutMs}ms)，已处理 ${results.size}/${items.size}，剩余按不可用降级"
                        )
                        repeat(items.size - results.size) { results.add(DecisionResult.unavailable()) }
                        return@withContext results
                    }
                    val req = DecisionRequest(
                        id = id,
                        state = state,
                        question = question,
                        options = options,
                    )
                    results.add(runSingle(engine, req, options))
                }
                val total = System.currentTimeMillis() - started
                val miss = results.count { !it.isValid }
                LogManager.shared.log(
                    "INFO", TAG,
                    "DecisionBatch: ${items.size} 条 in ${total}ms，无效 ${miss} 条"
                )
                results
            } catch (e: Exception) {
                Log.e(TAG, "批量决策失败: ${e.javaClass.simpleName}: ${e.message}")
                LogManager.shared.log("WARN", TAG, "批量决策失败: ${e.message}")
                // 已产出的保留，其余补 unavailable，保证与 items 一一对应
                repeat(items.size - results.size) { results.add(DecisionResult.unavailable()) }
                results
            }
        }
    }

    /**
     * 一次 prefill + 一步 decode。
     *
     * 附带「模型续写」检测：正常只应吐 1 个 token（" A" / "B)" 之类），
     * 超长输出说明模型没遵守单字母协议（真机上观察到它会接着编造后续 Item），
     * 这种输出解析出的字母毫无意义，一律判为无效让调用方走兜底。
     */
    private suspend fun runSingle(engine: Engine, req: DecisionRequest, options: List<String>): DecisionResult {
        val started = System.currentTimeMillis()
        return createSession(engine).use { session ->
            session.runPrefill(listOf(InputData.Text(DecisionPrompts.render(req, options))))
            val raw = session.runDecode()
            val tooLong = raw != null && raw.trim().length > MAX_RAW_CHARS
            if (tooLong) {
                Log.w(
                    TAG,
                    "决策[${req.id}] 输出 ${raw?.length} 字符，判定为模型续写（非单字母），判无效"
                )
                LogManager.shared.log("WARN", TAG, "决策输出过长被判无效: ${req.id}")
            }
            val idx = if (tooLong) null else DecisionPrompts.parseLetter(raw, options.size)
            DecisionResult(
                chosenIndex = idx,
                confidence = if (idx == null)
                    DecisionResult.CONFIDENCE_INCONSISTENT
                else
                    DecisionResult.CONFIDENCE_SINGLE,
                latencyMs = System.currentTimeMillis() - started,
                raw = raw,
            )
        }
    }

    /**
     * 建 Session 前先释放主会话槽位。
     * 不释放 → FAILED_PRECONDITION（M0 实测），决策 100% 失败。
     */
    private suspend fun createSession(engine: Engine): Session {
        onBeforeSession?.invoke()
        return engine.createSession(SessionConfig(decisionSampler))
    }
}
