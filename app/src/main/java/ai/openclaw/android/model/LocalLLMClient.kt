package ai.openclaw.android.model

import android.content.Context
import android.os.Build
import android.util.Log
import ai.openclaw.android.LogManager
import ai.openclaw.android.util.CrashRecord
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.LogSeverity
import com.google.ai.edge.litertlm.Message as LiteRTMessage
import com.google.ai.edge.litertlm.OpenApiTool
import com.google.ai.edge.litertlm.Role
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ToolCall
import com.google.ai.edge.litertlm.ToolProvider
import com.google.ai.edge.litertlm.tool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import ai.openclaw.android.agent.decision.DecisionRequest
import ai.openclaw.android.agent.decision.DecisionResult
import ai.openclaw.android.agent.decision.DecisionStakes
import ai.openclaw.android.agent.decision.OnDeviceDecisionRunner
import ai.openclaw.android.agent.decision.DecisionPrompts

/**
 * Local LLM Client using LiteRT-LM framework
 *
 * Runs Gemma 4 E4B on-device with .litertlm format model.
 * LiteRT-LM handles KV-cache, prompt templating, and GPU acceleration internally.
 *
 * Performance: ~18 tok/s (CPU) / ~22 tok/s (GPU) on Snapdragon 8 Gen 3+
 * Model size: ~3.5 GB (mixed 4-bit/8-bit quantization, embedding mmap)
 */
class LocalLLMClient(private val context: Context) : ModelClient {

    enum class LoadState { IDLE, LOADING, LOADED, ERROR }

    private var engine: Engine? = null
    private var _modelFileName: String? = null
    private val sessionMutex = Mutex()

    /**
     * 端侧决策执行器（prefill + 1 步 decode，用于「判断」类任务）。
     *
     * 与生成路径共用 [sessionMutex]：引擎同一时刻只允许一个会话，在确认
     * Session 与 Conversation 槽位关系之前一律串行（见 [runDecisionProbe] 的 M0 验证）。
     */
    val decisionRunner: OnDeviceDecisionRunner = OnDeviceDecisionRunner(
        engineProvider = { engine },
        sessionMutex = sessionMutex,
        // M0 实测：Session 与 Conversation 共享唯一槽位，决策前必须释放主会话
        onBeforeSession = { closeCachedConversation() },
    )

    /**
     * 跑端侧决策模式的离线校准（标注集一致率 / 预测分布偏置 / 位置稳定性）。
     * 端侧模型未加载时返回 null。
     */
    suspend fun runDecisionCalibration(): String? {
        if (engine == null || _state.value != LoadState.LOADED) return null
        return ai.openclaw.android.agent.decision.DecisionCalibration.run(decisionRunner)
    }

    /**
     * 实际生效的上下文窗口（= 引擎真实分配到的 KV-cache 长度，输入 + 输出共用）。
     *
     * 这不是模型宣传的窗口大小。Gemma 4 E2B/E4B 官方模型卡写 128K，但 `.litertlm` 包
     * 的物理 KV-cache 由导出时的 `cache_length` 决定，社区公开版本多数仍是 4096。
     * maxNumTokens 只是我们向引擎申请的值，超过包内容量会失败，因此初始化时
     * 逐级降级，最终以这里记录的实际值为准（见 tryInitEngine）。
     */
    @Volatile
    private var effectiveMaxNumTokens: Int = SAFE_CONTEXT_TOKENS

    /**
     * 复用的主会话。
     *
     * LiteRT 的 Conversation 持有 KV cache。原先每轮都 createConversation 并把整段历史
     * 作为 initialMessages 传入，等于每轮把全部上下文重新 prefill 一次 —— 端侧最贵的
     * 操作，也是"运行期间手机非常卡"的主因。这里改为复用同一会话，只发新增消息，
     * 仅在上下文前缀失效（历史被裁剪、换会话、system/tools 变化）时重建。
     */
    private var cachedConversation: com.google.ai.edge.litertlm.Conversation? = null
    /** 已送入引擎的上下文文本序列（不含引擎自己生成的回复） */
    private var prefilledContext: List<String>? = null
    private var cachedSystemKey: String? = null
    private var cachedToolsKey: String? = null
    /** 生成过程中出错后置 false，丢弃缓存避免复用坏会话 */
    private var conversationUsable = true
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Tool executor bridge — when LiteRT's model calls a tool internally,
     * this lambda delegates to OpenClaw's skill system.
     * Called synchronously by LiteRT on a background thread.
     */
    private var localToolExecutor: (suspend (toolName: String, argsJson: String) -> String)? = null

    fun setToolExecutor(executor: (suspend (toolName: String, argsJson: String) -> String)) {
        this.localToolExecutor = executor
    }

    private val _state = MutableStateFlow(LoadState.IDLE)
    val state: StateFlow<LoadState> = _state

    companion object {
        private const val TAG = "LocalLLMClient"
        private const val MODEL_DIR = "models"
        private const val MODEL_DIR_SD = "/sdcard/Download"
        private const val PREFS_NAME = "local_llm_prefs"
        private const val KEY_GPU_INIT_PENDING = "gpu_init_pending"
        private const val KEY_GPU_CRASH_COUNT = "gpu_crash_count"

        /** Supported models in priority order (first found wins) */
        private val SUPPORTED_MODELS = listOf(
            "gemma-4-E4B-it.litertlm",
            "gemma-4-E2B-it.litertlm",
        )

        // Sampling defaults (Double for LiteRT-LM SamplerConfig)
        private const val DEFAULT_TEMPERATURE = 0.7
        private const val DEFAULT_TOP_K = 40
        private const val DEFAULT_TOP_P = 0.95

        // Backend init timeout (ms)
        private const val TIMEOUT_NPU = 60_000L
        private const val TIMEOUT_GPU = 90_000L
        private const val TIMEOUT_CPU = 60_000L
        private const val KEY_GPU_CRASH_TIME = "gpu_crash_time"

        /**
         * CPU 推理线程数。
         *
         * 不指定时 native 引擎会按核心数开满线程，端侧大模型在 prefill/decode 期间会把
         * 整机 CPU 吃满，UI 与其他应用抢不到时间片（表现为"运行期间手机非常卡"）。
         * 这里主动留出 2 个核心给系统/UI，并在 2..6 之间收敛。
         */
        private val CPU_THREADS: Int =
            (Runtime.getRuntime().availableProcessors() - 2).coerceIn(2, 6)

        // ---- 端侧上下文预算 ----
        // maxNumTokens 是「输入 + 输出」共用的 KV-cache 上限，不是输出上限。
        // 因此必须给生成预留空间，否则 prefill 就把窗口占满，生成必然超限。
        private const val OUTPUT_RESERVE_TOKENS = 768
        /**
         * 保守兜底窗口。
         *
         * Gemma 4 E2B/E4B 官方模型卡标称 128K 上下文，但那只是模型架构（RoPE）能力。
         * 端侧真正可用的上限是 `.litertlm` 导出时 `cache_length` 分配出来的 KV-cache，
         * 社区公开的包（含 litert-community 官方的 Gemma 3n / Gemma 4 系列）普遍是
         * **4096**。这个值同时也是初始化降级链路的最低档。
         */
        private const val SAFE_CONTEXT_TOKENS = 4096
        /**
         * 对话历史的最小保障，避免 system/tools 把窗口吃光后无历史可用。
         *
         * 从 512 提到 1500：512 装不下几轮对话，**每轮都要丢历史** →
         * 送入引擎的文本前缀每轮都在变 → KV-cache 复用失效 → 每轮全量 prefill
         * （端侧实测单轮 5~12 秒）。这是「上下文超限 + 端侧慢」的共同要害。
         */
        private const val MIN_CONVERSATION_TOKENS = 1500
        /** 工具 schema 最多可占用的窗口比例（端侧注入 50+ 工具，是超限主因） */
        private const val MAX_TOOLS_SHARE = 0.35f
        /** 系统提示最多可占用的窗口比例 */
        private const val MAX_SYSTEM_SHARE = 0.45f
        // Auto-retry GPU after this duration
        private const val GPU_RETRY_INTERVAL_MS = 24 * 60 * 60 * 1000L // 24 hours

        fun getBackendPriority(): List<Pair<Backend, String>> {
            val hardware = Build.HARDWARE.lowercase()
            val board = Build.BOARD.lowercase()
            val brand = Build.BRAND.lowercase()

            if (brand.contains("huawei")) {
                LogManager.shared.log("INFO", TAG, "检测到 Huawei，跳过 NPU（兼容性限制）")
                return listOf(Backend.GPU() to "GPU", Backend.CPU(CPU_THREADS) to "CPU")
            }

            // Honor devices: GPU first (NPU support is unstable)
            if (brand.contains("honor")) {
                LogManager.shared.log("INFO", TAG, "检测到 Honor，尝试 GPU → CPU")
                return listOf(
                    Backend.GPU() to "GPU",
                    Backend.CPU(CPU_THREADS) to "CPU"
                )
            }

            if (hardware.contains("qcom") || hardware.contains("sdm") || hardware.contains("sm") ||
                board.contains("kona") || board.contains("lahaina") || board.contains("taro") ||
                brand.contains("oneplus") || brand.contains("xiaomi")) {
                return listOf(
                    Backend.NPU() to "NPU",
                    Backend.GPU() to "GPU",
                    Backend.CPU(CPU_THREADS) to "CPU"
                )
            }

            return listOf(Backend.GPU() to "GPU", Backend.CPU(CPU_THREADS) to "CPU")
        }

        /**
         * 上下文窗口候选，按降序尝试。
         *
         * 首选值按模型来（E2B 8192 / E4B 16384），但 `.litertlm` 包的 KV-cache 常常
         * 只有 4096：申请超过包内容量会让 `Engine.initialize()` 直接失败，或者更糟——
         * 初始化成功但 prefill 超过 cache 时才炸。所以逐级降到包内容量允许的最大值。
         */
        private fun candidateContextTokens(modelPath: String): List<Int> {
            val preferred = if (modelPath.contains("E2B", ignoreCase = true)) 8192 else 16384
            return listOf(preferred, 4096, 2048).distinct()
        }

        /**
         * 尝试用给定 Backend 初始化引擎，返回 (engine, 实际生效的 maxNumTokens)。
         *
         * 失败返回 null。注意 initialize() 是阻塞 native 调用，由外层线程池 + Future
         * 超时控制。GPU 会在 native 层直接 abort 进程，所以 crash 标记要提前落盘。
         */
        private fun tryInitEngine(
            modelPath: String,
            cacheDir: String,
            backend: Backend,
            backendName: String,
            prefs: SharedPreferences?,
            onLog: (String) -> Unit
        ): Pair<Engine, Int>? {
            onLog("尝试初始化: $backendName")
            if (backendName == "GPU" && prefs != null) {
                prefs.edit().putBoolean(KEY_GPU_INIT_PENDING, true).apply()
            }
            var lastError: Throwable? = null
            for (maxTokens in candidateContextTokens(modelPath)) {
                try {
                    val config = EngineConfig(
                        modelPath = modelPath,
                        backend = backend,
                        visionBackend = backend,
                        audioBackend = Backend.CPU(CPU_THREADS),
                        maxNumTokens = maxTokens,
                        cacheDir = cacheDir,
                    )
                    val eng = Engine(config).also { it.initialize() }
                    if (backendName == "GPU" && prefs != null) {
                        prefs.edit()
                            .putBoolean(KEY_GPU_INIT_PENDING, false)
                            .putInt(KEY_GPU_CRASH_COUNT, 0)
                            .putLong(KEY_GPU_CRASH_TIME, 0L)
                            .apply()
                    }
                    onLog("✅ $backendName 就绪，上下文窗口 = $maxTokens tokens")
                    return eng to maxTokens
                } catch (e: Throwable) {
                    lastError = e
                    onLog("窗口 $maxTokens 不可用: ${e.javaClass.simpleName}: ${e.message}，降级重试")
                }
            }
            // 【Bugly 埋点】记录 Backend 类型和异常
            CrashRecord.logLocalLLMError(
                "backend_init_failed_$backendName",
                lastError ?: IllegalStateException("所有候选上下文窗口均 rejected")
            )
            onLog("❌ $backendName 初始化失败: ${lastError?.message}")
            if (backendName == "GPU" && prefs != null) {
                prefs.edit().putBoolean(KEY_GPU_INIT_PENDING, false).apply()
            }
            return null
        }
    }

    // ==================== Lifecycle ====================

    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        if (_state.value == LoadState.LOADED) {
            Log.w(TAG, "Already initialized")
            return@withContext true
        }

        try {
            _state.value = LoadState.LOADING
            // 引擎换了新的，缓存会话（及其 KV cache）必须一起丢弃
            closeCachedConversation()
            LogManager.shared.log("INFO", TAG, "=== 开始初始化本地模型 ===")

            // Get user-configured model name from SharedPreferences
            val configuredModelName = context.getSharedPreferences("openclaw_config", Context.MODE_PRIVATE)
                .getString("model_name", null)
            val modelFile = findModelFile(configuredModelName)
            if (modelFile == null) {
                val msg = "模型文件未找到: ${context.filesDir}/$MODEL_DIR/"
                Log.w(TAG, msg)
                LogManager.shared.log("ERROR", TAG, msg)
                LogManager.shared.log("INFO", TAG, "请确认 gemma-4-E4B-it.litertlm 已放入 /sdcard/Download/ 或应用 filesDir/models/")
                _state.value = LoadState.ERROR
                return@withContext false
            }

            LogManager.shared.log("INFO", TAG, "模型文件: ${modelFile.name} (${formatSize(modelFile.length())})")
            _modelFileName = modelFile.name
            LogManager.shared.log("INFO", TAG, "芯片信息: ${Build.HARDWARE} / ${Build.BOARD} / ${Build.BRAND}")

            Engine.setNativeMinLogSeverity(LogSeverity.ERROR)

            val gpuCrashedLastTime = prefs.getBoolean(KEY_GPU_INIT_PENDING, false)
            val gpuCrashTime = prefs.getLong(KEY_GPU_CRASH_TIME, 0L)
            val timeSinceCrash = System.currentTimeMillis() - gpuCrashTime
            val autoRetryGpu = timeSinceCrash > GPU_RETRY_INTERVAL_MS

            if (gpuCrashedLastTime) {
                val crashCount = prefs.getInt(KEY_GPU_CRASH_COUNT, 0) + 1
                prefs.edit()
                    .putBoolean(KEY_GPU_INIT_PENDING, false)
                    .putInt(KEY_GPU_CRASH_COUNT, crashCount)
                    .putLong(KEY_GPU_CRASH_TIME, System.currentTimeMillis())
                    .apply()
                LogManager.shared.log("WARN", TAG, "检测到上次 GPU 初始化导致 native crash（第 ${crashCount} 次）")
            } else if (prefs.getInt(KEY_GPU_CRASH_COUNT, 0) > 0 && autoRetryGpu) {
                prefs.edit()
                    .putInt(KEY_GPU_CRASH_COUNT, 0)
                    .putLong(KEY_GPU_CRASH_TIME, 0L)
                    .apply()
                LogManager.shared.log("INFO", TAG, "GPU crash 标记已过期 (${timeSinceCrash / 3600_000}h)，重新尝试 GPU")
            }

            val shouldSkipGpu = gpuCrashedLastTime ||
                (prefs.getInt(KEY_GPU_CRASH_COUNT, 0) > 0 && !autoRetryGpu)
            val backends = getBackendPriority().let { list ->
                if (shouldSkipGpu) list.filter { it.second != "GPU" } else list
            }
            LogManager.shared.log("INFO", TAG, "Backend 尝试顺序: ${backends.map { it.second }.joinToString(" → ")}")

            for ((i, pair) in backends.withIndex()) {
                val (backend, backendName) = pair
                LogManager.shared.log("INFO", TAG, "[${i + 1}/${backends.size}] $backendName")

                val timeout = when (backendName) {
                    "NPU" -> TIMEOUT_NPU
                    "GPU" -> TIMEOUT_GPU
                    else -> TIMEOUT_CPU
                }

                // Use real thread-level timeout since Engine.initialize() is a blocking
                // native call that Kotlin's withTimeoutOrNull cannot cancel.
                val executor = Executors.newSingleThreadExecutor()
                val result = try {
                    executor.submit(Callable {
                        tryInitEngine(
                            modelPath = modelFile.absolutePath,
                            cacheDir = context.cacheDir.path,
                            backend = backend,
                            backendName = backendName,
                            prefs = prefs
                        ) { logMsg -> LogManager.shared.log("INFO", TAG, logMsg) }
                    }).get(timeout, TimeUnit.MILLISECONDS)
                } catch (_: TimeoutException) {
                    LogManager.shared.log("WARN", TAG, "$backendName 初始化超时 (${timeout / 1000}s)，跳过")
                    null
                } catch (e: Exception) {
                    LogManager.shared.log("WARN", TAG, "$backendName 初始化异常: ${e.message}")
                    null
                } finally {
                    executor.shutdownNow()
                }

                if (result == null) {
                    LogManager.shared.log("WARN", TAG, "$backendName 初始化失败，尝试下一个 Backend")
                } else {
                    engine = result.first
                    effectiveMaxNumTokens = result.second
                    _state.value = LoadState.LOADED
                    LogManager.shared.log(
                        "INFO", TAG,
                        "✅ 模型初始化成功！Backend: $backendName，实际上下文窗口: ${result.second} tokens"
                    )
                    return@withContext true
                }
            }

            val msg = "所有 Backend 初始化均失败 (尝试了: ${backends.map { it.second }.joinToString(", ")})"
            Log.e(TAG, msg)
            LogManager.shared.log("ERROR", TAG, msg)
            _state.value = LoadState.ERROR
            false
        } catch (e: Exception) {
            _state.value = LoadState.ERROR
            Log.e(TAG, "Failed to initialize LiteRT-LM engine", e)
            LogManager.shared.log("ERROR", TAG, "初始化异常: ${e.javaClass.simpleName}: ${e.message}")
            // 【Bugly 埋点】Native 引擎初始化失败是关键错误
            CrashRecord.logLocalLLMError("engine_init_failed", e)
            false
        }
    }

    fun release() {
        closeCachedConversation()
        try {
            engine?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing engine", e)
        }
        engine = null
        _state.value = LoadState.IDLE
        Log.i(TAG, "LiteRT-LM engine released")
    }

    // ==================== ModelClient Interface ====================

    override suspend fun chat(
        messages: List<Message>,
        tools: List<Tool>?
    ): Result<ModelResponse> {
        return try {
            val eng = requireEngine()
            val startTime = System.currentTimeMillis()
            val lastContent = buildVisionFallbackContent(messages.last())

            val result = sessionMutex.withLock {
                withContext(Dispatchers.IO) {
                    // LiteRT 引擎同一时刻只允许一个 conversation（实测报
                    // FAILED_PRECONDITION: A session already exists）。cachedConversation
                    // 是 chatStream 的主会话缓存，会占着这个槽位；chat() 是记忆抽取等
                    // 旁路（一次性、独立上下文），必须先释放它才能 createConversation。
                    // 代价：主会话 KV-cache 作废，下一次 chatStream 重建（多一次全量
                    // prefill）—— 这比直接抛异常导致记忆抽取永远失败要强。
                    // TODO: 让旁路复用主会话（等引擎支持多 conversation）后可去掉。
                    closeCachedConversation()

                    val plan = planContext(messages, tools)

                    // 端侧模型经常吐出畸形工具调用（实测：`call:weather_get_weather{location}`
                    // 缺参数值），LiteRT 直接抛 INVALID_ARGUMENT「Failed to parse tool calls」。
                    // chatStream 有 parseToolCallsFromError 兜底，chat() 没有 —— 导致记忆抽取
                    // 这类旁路 100% 失败。这里补一次「禁止工具调用」的重试。
                    //
                    // ⚠️ 重试必须在**上一个会话关闭之后**再建：引擎同一时刻只允许一个会话，
                    // 在上一个 conversation 还开着时 createConversation 会直接
                    // FAILED_PRECONDITION（M0 实测）。所以两次调用各自独占一个 use 块。
                    val result = try {
                        runOneShotConversation(eng, plan, lastContent)
                    } catch (e: Exception) {
                        if (!isToolCallParseError(e)) throw e
                        LogManager.shared.log(
                            "WARN", TAG,
                            "chat() 命中畸形工具调用解析失败，追加「禁止工具调用」约束重试一次"
                        )
                        closeCachedConversation()
                        runOneShotConversation(eng, plan, lastContent + NO_TOOL_CALL_GUARD)
                    }
                    result
                }
            }

            val duration = System.currentTimeMillis() - startTime
            LogManager.shared.log("INFO", TAG, "Generated response in ${duration}ms")

            Result.success(wrapResponse(result.first, result.second))
        } catch (e: Exception) {
            Log.e(TAG, "Chat failed", e)
            // 【Bugly 埋点】
            CrashRecord.logLocalLLMError("chat_failed", e)
            Result.failure(e)
        }
    }

    override fun chatStream(
        messages: List<Message>,
        tools: List<Tool>?
    ): Flow<ChatEvent> = flow {
        val eng = try {
            requireEngine()
        } catch (e: Exception) {
            emit(ChatEvent.Error(e.message ?: "Model not loaded"))
            return@flow
        }

        val fullText = StringBuilder()
        sessionMutex.withLock {
            try {
                val startTime = System.currentTimeMillis()
                val (text, openClawToolCalls) = generateOnce(eng, messages, tools, allowReuse = true) { token ->
                    fullText.append(token)
                    emit(ChatEvent.Token(token))
                }

                val duration = System.currentTimeMillis() - startTime
                LogManager.shared.log("INFO", TAG, "Stream complete in ${duration}ms")

                emit(ChatEvent.Complete(wrapResponse(text, openClawToolCalls)))
            } catch (e: Exception) {
                // LiteRT may fail to parse tool calls from the model's output.
                // Extract tool call info from the error message as a fallback.
                val textToolCalls = parseToolCallsFromError(e.message ?: "")
                if (textToolCalls != null) {
                    // fallback 成功，只记录 warning
                    CrashRecord.logLocalLLMWarning("stream_parse_fallback", e.message)
                    val text = stripToolCallTokens(fullText.toString())
                    Log.i(TAG, "Extracted ${textToolCalls.size} tool call(s) from text fallback")
                    emit(ChatEvent.Complete(wrapResponse(text, textToolCalls)))
                } else {
                    // 复用会话失败多半是 KV-cache 状态异常或上下文被撑爆：
                    // 丢弃缓存、按预算重建一次（这次会走截断后的历史）。
                    conversationUsable = false
                    val retried = runCatching {
                        closeCachedConversation()
                        fullText.clear()
                        val (text, calls) = generateOnce(eng, messages, tools, allowReuse = false) { token ->
                            fullText.append(token)
                            emit(ChatEvent.Token(token))
                        }
                        emit(ChatEvent.Complete(wrapResponse(text, calls)))
                    }
                    if (retried.isFailure) {
                        Log.e(TAG, "Stream failed", e)
                        // 【Bugly 埋点】
                        CrashRecord.logLocalLLMError("stream_failed", e)
                        emit(ChatEvent.Error(e.message ?: "Generation failed"))
                    }
                }
            }
        }
    }.flowOn(Dispatchers.IO)

    override fun configure(provider: ModelProvider, apiKey: String, model: String, baseUrl: String) {
        // Local model doesn't need API key
    }

    // ==================== Conversation Setup ====================

    /** 上下文分配结果：各字段都已按当前模型窗口收敛过 */
    private data class ContextPlan(
        val systemInstruction: String?,
        /** 已按预算截断的历史（不含本轮待发送的最后一条） */
        val history: List<Message>,
        /** 已按预算裁剪的工具集 */
        val tools: List<Tool>?,
        val systemTokens: Int,
        val toolTokens: Int,
        val conversationBudget: Int,
        val droppedTools: Int,
        val droppedMessages: Int,
    )

    /**
     * 按模型窗口统一分配上下文预算。
     *
     * 要点：
     * - maxNumTokens 是「输入 + 输出」共用的 KV-cache 上限，必须先给生成预留（此前完全没有预留）。
     * - 原先 E4B 的 system 8000 + 对话 14000 = 22000 已经超过 16384 窗口本身，属于必然超限。
     * - 工具 schema 在端侧常有 50+ 个（数千 token），此前从未计入预算，是超限的直接原因。
     *
     * 分配顺序：输出预留 → 工具 → 系统提示 → 对话历史。
     */
    private fun planContext(messages: List<Message>, tools: List<Tool>?): ContextPlan {
        val window = getContextWindowTokens()
        // 输出预留按窗口收敛：4K 小窗口下固定 768 会吃掉近 1/5 的可用上下文
        val reserve = OUTPUT_RESERVE_TOKENS.coerceAtMost(window / 4)
        val usable = (window - reserve).coerceAtLeast(1024)

        val rawSystem = messages.firstOrNull { it.role == "system" }?.content
        val history = messages.filter { it.role != "system" }.dropLast(1)

        // 1) 工具 schema：按传入顺序保留，装不下的直接丢弃（端侧宁可少工具也不能撑爆窗口）
        val toolBudget = (usable * MAX_TOOLS_SHARE).toInt()
        var toolTokens = 0
        val keptTools = mutableListOf<Tool>()
        var droppedTools = 0
        for (tool in tools.orEmpty()) {
            val cost = estimateToolTokens(tool)
            if (toolTokens + cost > toolBudget) {
                droppedTools++
                continue
            }
            toolTokens += cost
            keptTools.add(tool)
        }

        // 2) 系统提示：超预算则截断，保留头 + 尾以免丢掉结尾的输出格式约定
        val systemBudget = (usable * MAX_SYSTEM_SHARE).toInt()
        val systemInstruction = fitSystemPrompt(rawSystem, systemBudget)
        val systemTokens = systemInstruction?.let { estimateTokens(it) } ?: 0

        // 3) 剩余全部留给对话历史
        //    coerceAtLeast 只能兜下限，system/tools 极端膨胀时会把预算顶到 usable 之上
        //    （反而撑爆窗口），所以最后再夹一次上限。
        val conversationBudget =
            (usable - toolTokens - systemTokens)
                .coerceAtLeast(MIN_CONVERSATION_TOKENS)
                .coerceAtMost(usable)
        val trimmedHistory = truncateMessages(history, conversationBudget)

        val usedTokens = trimmedHistory.sumOf { estimateTokens(it.content) }
        if (droppedTools > 0 || trimmedHistory.size < history.size) {
            LogManager.shared.log(
                "WARN", TAG,
                "上下文超预算已收敛: 窗口=$window 系统=$systemTokens 工具=$toolTokens(丢弃${droppedTools}个) " +
                    "对话=$usedTokens/$conversationBudget(丢弃${history.size - trimmedHistory.size}条)"
            )
        } else {
            LogManager.shared.log(
                "INFO", TAG,
                "上下文预算: 窗口=$window 输出预留=$OUTPUT_RESERVE_TOKENS 系统=$systemTokens " +
                    "工具=$toolTokens(${keptTools.size}个) 对话=$usedTokens/$conversationBudget"
            )
        }

        return ContextPlan(
            systemInstruction = systemInstruction,
            history = trimmedHistory,
            tools = keptTools,
            systemTokens = systemTokens,
            toolTokens = toolTokens,
            conversationBudget = conversationBudget,
            droppedTools = droppedTools,
            droppedMessages = history.size - trimmedHistory.size,
        )
    }

    private fun buildConversationConfig(plan: ContextPlan): ConversationConfig {
        val initialMessages = plan.history.map { msg -> convertMessage(msg, plan.history) }

        val samplerConfig = SamplerConfig(
            topK = DEFAULT_TOP_K,
            topP = DEFAULT_TOP_P,
            temperature = DEFAULT_TEMPERATURE,
            seed = 0,
        )

        return ConversationConfig(
            systemInstruction = plan.systemInstruction?.let { Contents.of(it) },
            initialMessages = initialMessages,
            tools = convertTools(plan.tools),
            samplerConfig = samplerConfig,
            automaticToolCalling = false,
        )
    }

    /**
     * 执行一次生成。allowReuse 为 true 时优先复用缓存会话 —— 只 prefill 新增的那条消息，
     * 而不是把整段历史重放一遍。
     */
    private suspend fun generateOnce(
        eng: Engine,
        messages: List<Message>,
        tools: List<Tool>?,
        allowReuse: Boolean,
        onToken: suspend (String) -> Unit
    ): Pair<String, List<ai.openclaw.android.model.ToolCall>?> {
        val plan = planContext(messages, tools)
        val lastContent = buildVisionFallbackContent(
            messages.filter { it.role != "system" }.lastOrNull() ?: messages.last()
        )
        val historyTexts = plan.history.map { "${it.role}|${it.content ?: ""}" }
        val toolsKey = plan.tools?.joinToString(",") { it.function.name }
        val reusable = allowReuse && canReuseConversation(plan.systemInstruction, toolsKey, historyTexts)

        val conversation: com.google.ai.edge.litertlm.Conversation
        if (reusable) {
            conversation = cachedConversation!!
            LogManager.shared.log("INFO", TAG, "复用会话（KV cache 命中）：只 prefill 新增消息")
        } else {
            closeCachedConversation()
            conversation = eng.createConversation(buildConversationConfig(plan))
            cachedConversation = conversation
            cachedSystemKey = plan.systemInstruction
            cachedToolsKey = toolsKey
            prefilledContext = historyTexts
            LogManager.shared.log("INFO", TAG, "重建会话：prefill ${historyTexts.size} 条历史")
        }

        val text = StringBuilder()
        var responseMessage: com.google.ai.edge.litertlm.Message? = null
        conversation.sendMessageAsync(lastContent).collect { message ->
            responseMessage = message
            val token = message.contents?.toString() ?: ""
            if (token.isNotEmpty()) {
                text.append(token)
                onToken(token)
            }
        }

        // 本轮内容已进入 KV cache，并入指纹供下一轮判断能否继续复用
        prefilledContext = historyTexts + "user|$lastContent"
        conversationUsable = true

        val toolCalls = responseMessage?.toolCalls
            ?.takeIf { it.isNotEmpty() }
            ?.let { convertToolCalls(it) }
        return Pair(text.toString(), toolCalls)
    }

    /**
     * 缓存会话是否还能接着用：system/tools 未变，且新历史以已 prefill 的内容为前缀。
     * 历史被裁剪（前缀不再匹配）、换会话、工具集变化都会走重建分支。
     */
    private fun canReuseConversation(
        systemKey: String?,
        toolsKey: String?,
        historyTexts: List<String>
    ): Boolean {
        val conversation = cachedConversation ?: return false
        if (!conversationUsable || !conversation.isAlive) return false
        if (cachedSystemKey != systemKey || cachedToolsKey != toolsKey) return false
        val prefilled = prefilledContext ?: return false
        if (historyTexts.size < prefilled.size) return false
        return historyTexts.subList(0, prefilled.size) == prefilled
    }

    private fun closeCachedConversation() {
        try {
            cachedConversation?.close()
        } catch (e: Exception) {
            Log.w(TAG, "关闭复用会话失败", e)
        }
        cachedConversation = null
        prefilledContext = null
        cachedSystemKey = null
        cachedToolsKey = null
    }

    /** 系统提示超预算时截断：保留头部主体 + 尾部，避免丢掉结尾的输出格式约定 */
    private fun fitSystemPrompt(system: String?, budget: Int): String? {
        if (system.isNullOrBlank()) return null
        val tokens = estimateTokens(system)
        if (tokens <= budget) return system

        val maxChars = budget * 3
        if (maxChars <= 400) return system.take(400)
        val head = system.take((maxChars * 0.6).toInt())
        val tail = system.takeLast((maxChars * 0.25).toInt())
        Log.w(TAG, "系统提示 ${tokens} tokens 超过预算 $budget，已截断")
        return "$head\n\n[... 系统提示已按端侧上下文预算截断 ...]\n\n$tail"
    }

    /** 单个工具 schema 的 token 估算（端侧 50+ 工具必须计入预算） */
    private fun estimateToolTokens(tool: Tool): Int {
        val fn = tool.function
        var tokens = estimateTokens(fn.name) + estimateTokens(fn.description) + 16
        fn.parameters.properties.forEach { (name, prop) ->
            tokens += estimateTokens(name) + estimateTokens(prop.description) + 4
        }
        return tokens
    }

    private fun convertMessage(msg: ai.openclaw.android.model.Message, allMessages: List<ai.openclaw.android.model.Message>): com.google.ai.edge.litertlm.Message {
        return when (msg.role) {
            "user" -> {
                val content = buildVisionFallbackContent(msg)
                com.google.ai.edge.litertlm.Message.user(content)
            }
            "assistant" -> {
                val toolCalls = msg.toolCalls?.map { tc ->
                    val args = try {
                        JSONObject(tc.function.arguments).let { json ->
                            val map = mutableMapOf<String, Any>()
                            for (key in json.keys()) map[key] = json.get(key)
                            map
                        }
                    } catch (_: Exception) { emptyMap<String, Any>() }
                    ToolCall(tc.function.name, args)
                }
                if (toolCalls.isNullOrEmpty()) {
                    com.google.ai.edge.litertlm.Message.model(msg.content)
                } else {
                    com.google.ai.edge.litertlm.Message.model(
                        Contents.of(msg.content),
                        toolCalls,
                        emptyMap()
                    )
                }
            }
            "tool" -> {
                val toolName = resolveToolName(msg, allMessages)
                com.google.ai.edge.litertlm.Message.tool(
                    Contents.of(Content.ToolResponse(toolName, msg.content))
                )
            }
            else -> {
                val content = buildVisionFallbackContent(msg)
                com.google.ai.edge.litertlm.Message.user(content)
            }
        }
    }

    /**
     * Build text content for messages that may contain images.
     *
     * LiteRT-LM SDK currently does not expose a Content.image(bitmap) API for
     * multimodal input, so we fall back to appending image descriptions as text.
     *
     * TODO: Replace with Content.image(bitmap) + Contents.of(Content.text(...), Content.image(bitmap))
     *       when LiteRT-LM SDK adds multimodal Content API support.
     *       The visionBackend is already configured in EngineConfig.
     */
    private fun buildVisionFallbackContent(msg: ai.openclaw.android.model.Message): String {
        if (msg.images.isNullOrEmpty()) return msg.content

        val imageDescriptions = msg.images.mapIndexed { index, img ->
            val desc = img.description ?: "[图片${index + 1}]"
            "- $desc (${img.mediaType}, ${(img.base64.length * 3 / 4 / 1024)}KB)"
        }.joinToString("\n")

        return "${msg.content}\n\n[图片已附加，但端侧模型暂不支持视觉输入]\n$imageDescriptions"
    }

    private fun resolveToolName(toolMsg: Message, allMessages: List<Message>): String {
        val callId = toolMsg.toolCallId ?: return ""
        val idx = allMessages.indexOf(toolMsg)
        if (idx <= 0) return ""
        for (i in (idx - 1) downTo 0) {
            val prev = allMessages[i]
            if (prev.role == "assistant") {
                prev.toolCalls?.firstOrNull { it.id == callId }?.let {
                    return it.function.name
                }
            }
        }
        return ""
    }

    private fun convertTools(tools: List<Tool>?): List<ToolProvider> {
        if (tools.isNullOrEmpty()) return emptyList()
        val executor = localToolExecutor

        return tools.map { openClawTool ->
            val toolName = openClawTool.function.name
            val openApiTool = object : OpenApiTool {
                override fun getToolDescriptionJsonString(): String {
                    val params = openClawTool.function.parameters
                    val json = JSONObject().apply {
                        put("name", toolName)
                        put("description", openClawTool.function.description)
                        put("parameters", JSONObject().apply {
                            put("type", "object")
                            put("properties", JSONObject().apply {
                                params.properties.forEach { (name, prop) ->
                                    put(name, JSONObject().apply {
                                        put("type", prop.type)
                                        put("description", prop.description)
                                    })
                                }
                            })
                            put("required", org.json.JSONArray(params.required))
                        })
                    }
                    return json.toString()
                }

                override fun execute(args: String): String {
                    if (executor == null) {
                        Log.w(TAG, "Tool '$toolName' called but no executor set")
                        return "{}"
                    }
                    return try {
                        runBlocking {
                            val result = executor(toolName, args)
                            Log.d(TAG, "Tool '$toolName' executed: ${result.take(200)}")
                            result
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Tool '$toolName' execution failed: ${e.message}")
                        """{"error": "${e.message}"}"""
                    }
                }
            }
            tool(openApiTool)
        }
    }

    private fun convertToolCalls(litertToolCalls: List<ToolCall>): List<ai.openclaw.android.model.ToolCall> {
        return litertToolCalls.map { tc ->
            val argsJson = JSONObject(tc.arguments).toString()
            ai.openclaw.android.model.ToolCall(
                id = "local_tc_${System.nanoTime()}",
                type = "function",
                function = ToolCallFunction(
                    name = tc.name,
                    arguments = argsJson
                )
            )
        }
    }

    /**
     * Parse tool calls from LiteRT error messages when the model generates
     * tool calls in a format that LiteRT can't parse.
     *
     * Supported patterns:
     * - call:func_name(param1="val1", param2="val2")
     * - call:func_name{param1}
     * - {"name": "func_name", "arguments": {"key": "value"}}
     */
    private fun parseToolCallsFromError(errorMsg: String): List<ai.openclaw.android.model.ToolCall>? {
        val calls = mutableListOf<ai.openclaw.android.model.ToolCall>()

        // Pattern 1: call:func_name(key="value", ...) or call:func_name{key}
        val callPattern = Regex("""call:(\w+)\s*[\({]([^}\)]*)[}\)]""")
        callPattern.findAll(errorMsg).forEach { match ->
            val funcName = match.groupValues[1]
            val paramsStr = match.groupValues[2]
            val args = parseCallParams(paramsStr)
            calls.add(ai.openclaw.android.model.ToolCall(
                id = "local_tc_${System.nanoTime()}",
                type = "function",
                function = ToolCallFunction(name = funcName, arguments = args)
            ))
        }

        // Pattern 2: JSON tool calls {"name": "...", "arguments": {...}}
        if (calls.isEmpty()) {
            val jsonPattern = Regex("""\{"name"\s*:\s*"(\w+)"\s*,\s*"arguments"\s*:\s*(\{[^}]*\})\}""")
            jsonPattern.findAll(errorMsg).forEach { match ->
                val funcName = match.groupValues[1]
                val argsObj = match.groupValues[2]
                calls.add(ai.openclaw.android.model.ToolCall(
                    id = "local_tc_${System.nanoTime()}",
                    type = "function",
                    function = ToolCallFunction(name = funcName, arguments = argsObj)
                ))
            }
        }

        return if (calls.isNotEmpty()) calls else null
    }

    private fun parseCallParams(paramsStr: String): String {
        if (paramsStr.isBlank()) return "{}"

        // Try key="value" format
        val kvPattern = Regex("""(\w+)\s*=\s*"([^"]*)"""")
        val kvMatches = kvPattern.findAll(paramsStr).toList()
        if (kvMatches.isNotEmpty()) {
            val json = JSONObject()
            kvMatches.forEach { match ->
                json.put(match.groupValues[1], match.groupValues[2])
            }
            return json.toString()
        }

        // Try key=value format (no quotes)
        val bareKvPattern = Regex("""(\w+)\s*=\s*(\S+)""")
        val bareKvMatches = bareKvPattern.findAll(paramsStr).toList()
        if (bareKvMatches.isNotEmpty()) {
            val json = JSONObject()
            bareKvMatches.forEach { match ->
                json.put(match.groupValues[1], match.groupValues[2])
            }
            return json.toString()
        }

        // Bare param name (model didn't provide value) — use empty string
        val bareName = paramsStr.trim()
        if (bareName.isNotEmpty() && bareName.matches(Regex("""\w+"""))) {
            return """{"$bareName": ""}"""
        }

        return "{}"
    }

    private fun stripToolCallTokens(text: String): String {
        return text
            .replace(Regex("""<\|tool_call\|>"""), "")
            .replace(Regex("""<\|tool_end\|>"""), "")
            .replace(Regex("""call:\w+\s*[\({][^}\)]*[}\)]"""), "")
            .trim()
    }

    // ==================== Response Wrapping ====================

    private fun wrapResponse(content: String, toolCalls: List<ai.openclaw.android.model.ToolCall>? = null): ModelResponse {
        val completionTokens = estimateTokens(content)

        return ModelResponse(
            id = "local-${System.currentTimeMillis()}",
            choices = listOf(
                Choice(
                    index = 0,
                    message = ResponseMessage(
                        role = "assistant",
                        content = content.ifEmpty { null },
                        toolCalls = toolCalls
                    ),
                    finishReason = if (toolCalls.isNullOrEmpty()) "stop" else "tool_calls"
                )
            ),
            usage = Usage(
                promptTokens = 0,
                completionTokens = completionTokens,
                totalTokens = completionTokens
            )
        )
    }

    // ==================== Helpers ====================

    private fun requireEngine(): Engine {
        val eng = engine
        if (eng == null || _state.value != LoadState.LOADED) {
            throw IllegalStateException("Engine not loaded. Call initialize() first.")
        }
        return eng
    }

    suspend fun ensureEngineReady(): Boolean {
        if (_state.value == LoadState.LOADED) return true
        if (_state.value == LoadState.ERROR || _state.value == LoadState.IDLE) {
            Log.w(TAG, "Engine in ${_state.value} state, attempting recovery")
            release()
            return initialize()
        }
        return false
    }

    private fun truncateMessages(messages: List<Message>, tokenBudget: Int): List<Message> {
        var totalTokens = 0
        val kept = mutableListOf<Message>()
        for (msg in messages.reversed()) {
            val msgTokens = estimateTokens(msg.content)
            if (totalTokens + msgTokens > tokenBudget) break
            totalTokens += msgTokens
            kept.add(0, msg)
        }
        if (kept.size < messages.size) {
            Log.d(TAG, "Truncated ${messages.size - kept.size} messages (budget: $tokenBudget tokens)")
        }
        return kept
    }

    /**
     * Find model file, prioritizing the user-configured model name.
     *
     * Search order:
     * 1. User-configured model name (from SharedPreferences)
     * 2. SUPPORTED_MODELS priority list (E4B → E2B)
     * 3. Any .litertlm file as last resort
     */
    private fun findModelFile(configuredModelName: String? = null): File? {
        val searchDirs = listOf(
            File(context.filesDir, MODEL_DIR),  // app private storage (preferred)
            File(MODEL_DIR_SD),                   // /sdcard/Download (fallback)
        )

        for (dir in searchDirs) {
            if (!dir.exists()) continue

            // 1. User-configured model name (highest priority)
            if (!configuredModelName.isNullOrBlank()) {
                val configuredFile = File(dir, configuredModelName)
                if (configuredFile.exists()) return configuredFile
                // Case-insensitive fallback for configured name
                dir.listFiles()?.firstOrNull { it.name.equals(configuredModelName, ignoreCase = true) }
                    ?.let { return it }
            }

            // 2. Supported models priority list
            for (name in SUPPORTED_MODELS) {
                val file = File(dir, name)
                if (file.exists()) return file
            }
            // Case-insensitive match
            for (name in SUPPORTED_MODELS) {
                dir.listFiles()?.firstOrNull { it.name.equals(name, ignoreCase = true) }
                    ?.let { return it }
            }

            // 3. Any .litertlm file as last resort
            dir.listFiles()?.firstOrNull { it.name.endsWith(".litertlm") }
                ?.let { return it }
        }

        return null
    }

    private fun estimateTokens(text: String): Int {
        var tokens = 0
        var i = 0
        val len = text.length
        while (i < len) {
            val cp = text.codePointAt(i)
            when {
                cp < 0x80 -> tokens++          // ASCII
                cp < 0x2E80 -> tokens += 2     // Latin extended, etc.
                cp <= 0x9FFF || cp in 0x3000..0x9FFF -> tokens += 2 // CJK: ~1-2 chars per token
                cp > 0xFFFF -> tokens += 2
                else -> tokens += 1
            }
            i += if (cp > 0xFFFF) 2 else 1
        }
        return maxOf(1, tokens / 2)
    }

    private fun formatSize(bytes: Long): String {
        val mb = bytes / (1024.0 * 1024.0)
        return if (mb > 1024) "%.2f GB".format(mb / 1024.0) else "%.0f MB".format(mb)
    }

    // ==================== Public Info ====================

    fun isModelLoaded(): Boolean = _state.value == LoadState.LOADED

    /**
     * M0 探针：验证「Session（决策用）与 Conversation（生成用）是否共享引擎的唯一会话槽位」
     * 并实测决策路径的 prefill / decode 延迟。
     *
     * 结果写进 LogManager（tag=LocalLLMClient）与返回值，供 adb logcat 抓取。
     * 仅诊断用，不参与正常业务路径。
     */
    suspend fun runDecisionProbe(): String = sessionMutex.withLock {
        withContext(Dispatchers.IO) {
            val eng = engine
            if (eng == null || _state.value != LoadState.LOADED) {
                return@withContext "PROBE_ABORT: engine not loaded (state=${_state.value})"
            }
            val sb = StringBuilder("== DecisionProbe ==\n")

            // ---- 用例 1：Conversation 先占槽，再开 Session ----
            var conv: com.google.ai.edge.litertlm.Conversation? = null
            try {
                conv = eng.createConversation(
                    ConversationsConfigForProbe()
                )
                val t0 = System.currentTimeMillis()
                conv.sendMessage("Reply with the single word OK.")
                sb.append("[1] Conversation.sendMessage ok, ${System.currentTimeMillis() - t0}ms, alive=${conv.isAlive}\n")

                val t1 = System.currentTimeMillis()
                val sessionCreated = try {
                    eng.createSession(
                        com.google.ai.edge.litertlm.SessionConfig(
                            com.google.ai.edge.litertlm.SamplerConfig(1, 1.0, 0.0, 0)
                        )
                    ).also { sb.append("[2] Session 创建成功，${System.currentTimeMillis() - t1}ms\n") }
                } catch (e: Exception) {
                    sb.append("[2] Session 创建失败: ${e.javaClass.simpleName}: ${e.message}\n")
                    null
                }

                if (sessionCreated != null) {
                    try {
                        val prompt = DecisionPrompts.render(
                            DecisionRequest(
                                id = "probe",
                                state = "【淘宝】您有 3 个红包待领取，快来抢！",
                                question = "这条通知对用户的信息价值属于哪一档？",
                                options = listOf("无价值（营销/推广/签到）", "一般价值（社交/新闻/更新）", "高价值（验证码/银行/日程/快递）"),
                                stakes = DecisionStakes.LOW,
                            )
                        )
                        val t2 = System.currentTimeMillis()
                        sessionCreated.runPrefill(listOf(com.google.ai.edge.litertlm.InputData.Text(prompt)))
                        val prefillMs = System.currentTimeMillis() - t2
                        val t3 = System.currentTimeMillis()
                        val out = sessionCreated.runDecode()
                        val decodeMs = System.currentTimeMillis() - t3
                        val idx = DecisionPrompts.parseLetter(out, 3)
                        sb.append("[3] prefill=${prefillMs}ms decode=${decodeMs}ms raw='${out}' parsed=$idx\n")
                        sessionCreated.close()
                    } catch (e: Exception) {
                        sb.append("[3] Session 推理失败: ${e.javaClass.simpleName}: ${e.message}\n")
                    }
                }

                // 会话 A 是否还能用
                sb.append("[4] Session 创建失败后 Conversation.isAlive=${conv.isAlive}\n")
                val t4 = System.currentTimeMillis()
                try {
                    conv.sendMessage("Reply with the single word YES.")
                    sb.append("[5] Conversation 二次 sendMessage OK, ${System.currentTimeMillis() - t4}ms\n")
                } catch (e: Exception) {
                    sb.append("[5] Conversation 二次 sendMessage 失败: ${e.javaClass.simpleName}: ${e.message}\n")
                }
            } catch (e: Exception) {
                sb.append("[0] 探针异常: ${e.javaClass.simpleName}: ${e.message}\n")
            } finally {
                try {
                    conv?.close()
                } catch (_: Exception) {
                }
                // 探针会作废主会话缓存，强制下次重建
                closeCachedConversation()
            }

            // ---- 用例 2：释放 Conversation 后再开 Session，实测决策延迟 ----
            val probeReq = DecisionRequest(
                id = "probe2",
                state = "【淘宝】您有 3 个红包待领取，快来抢！",
                question = "这条通知对用户的信息价值属于哪一档？",
                options = listOf(
                    "无价值（营销/推广/签到）",
                    "一般价值（社交/新闻/更新）",
                    "高价值（验证码/银行/日程/快递）"
                ),
                stakes = DecisionStakes.LOW,
            )
            try {
                val t0 = System.currentTimeMillis()
                val sess = eng.createSession(
                    com.google.ai.edge.litertlm.SessionConfig(
                        com.google.ai.edge.litertlm.SamplerConfig(1, 1.0, 0.0, 0)
                    )
                )
                sb.append("[6] 释放后 Session 创建成功，${System.currentTimeMillis() - t0}ms\n")
                try {
                    val prompt = DecisionPrompts.render(probeReq)
                    val t1 = System.currentTimeMillis()
                    sess.runPrefill(listOf(com.google.ai.edge.litertlm.InputData.Text(prompt)))
                    val prefillMs = System.currentTimeMillis() - t1
                    val t2 = System.currentTimeMillis()
                    val out = sess.runDecode()
                    val decodeMs = System.currentTimeMillis() - t2
                    sb.append(
                        "[7] 决策 prefill=${prefillMs}ms decode=${decodeMs}ms " +
                            "raw='${out}' parsed=${DecisionPrompts.parseLetter(out, 3)}\n"
                    )
                } catch (e: Exception) {
                    sb.append("[7] Session 推理失败: ${e.javaClass.simpleName}: ${e.message}\n")
                } finally {
                    try {
                        sess.close()
                    } catch (_: Exception) {
                    }
                }
            } catch (e: Exception) {
                sb.append("[6] 释放后 Session 仍创建失败: ${e.javaClass.simpleName}: ${e.message}\n")
            }

            // ---- 用例 4：批量决策吞吐（SmartFilter 场景，决定批次大小） ----
            try {
                val batchOptions = listOf(
                    "无价值（营销/推广/签到）",
                    "一般价值（社交/新闻/更新）",
                    "高价值（验证码/银行/日程/快递）"
                )
                val batchItems = listOf(
                    "【淘宝】您有 3 个红包待领取，快来抢！",
                    "【招商银行】您尾号 8821 的账户于 09-22 23:00 支出人民币 128.00 元。",
                    "【微信】张三：晚上一起吃饭吗？",
                    "【京东】您的快递已到达菜鸟驿站，取件码 A-238。",
                    "【今日头条】热点：某地发生一件大事，点击查看。",
                )
                val sess = eng.createSession(
                    com.google.ai.edge.litertlm.SessionConfig(
                        com.google.ai.edge.litertlm.SamplerConfig(1, 1.0, 0.0, 0)
                    )
                )
                try {
                    val t0 = System.currentTimeMillis()
                    sess.runPrefill(
                        listOf(
                            com.google.ai.edge.litertlm.InputData.Text(
                                DecisionPrompts.renderBatchHeader("这条通知对用户的信息价值属于哪一档？", batchOptions)
                            )
                        )
                    )
                    sb.append("[9] 批量 header prefill=${System.currentTimeMillis() - t0}ms\n")
                    for (i in batchItems.indices) {
                        val t1 = System.currentTimeMillis()
                        sess.runPrefill(
                            listOf(
                                com.google.ai.edge.litertlm.InputData.Text(
                                    DecisionPrompts.renderBatchItem(i, batchItems[i])
                                )
                            )
                        )
                        val pf = System.currentTimeMillis() - t1
                        val t2 = System.currentTimeMillis()
                        val out = sess.runDecode()
                        val dc = System.currentTimeMillis() - t2
                        sb.append(
                            "[10.$i] prefill=${pf}ms decode=${dc}ms raw='${out}' " +
                                "parsed=${DecisionPrompts.parseLetter(out, 3)} | ${batchItems[i].take(18)}\n"
                        )
                    }
                } finally {
                    try {
                        sess.close()
                    } catch (_: Exception) {
                    }
                }
            } catch (e: Exception) {
                sb.append("[9] 批量决策失败: ${e.javaClass.simpleName}: ${e.message}\n")
            }
            closeCachedConversation()

            // ---- 用例 3：Session 关闭后，生成路径能否恢复 ----
            try {
                val t5 = System.currentTimeMillis()
                val conv2 = eng.createConversation(ConversationsConfigForProbe())
                conv2.use {
                    it.sendMessage("Reply with the single word HI.")
                    sb.append("[8] 决策后重建 Conversation 成功，sendMessage ${System.currentTimeMillis() - t5}ms\n")
                }
            } catch (e: Exception) {
                sb.append("[8] 决策后重建 Conversation 失败: ${e.javaClass.simpleName}: ${e.message}\n")
            }
            closeCachedConversation()

            val report = sb.toString()
            LogManager.shared.log("INFO", TAG, report)
            Log.i(TAG, report)
            report
        }
    }

    /**
     * 跑一次一次性会话（旁路用）：建会话 → 发一条 → 关会话，返回 (文本, 工具调用)。
     * 调用方必须保证此刻没有别的会话占着引擎槽位（见 chat() 里的 closeCachedConversation）。
     */
    private fun runOneShotConversation(
        eng: Engine,
        plan: ContextPlan,
        content: String,
    ): Pair<String, List<ai.openclaw.android.model.ToolCall>?> {
        eng.createConversation(buildConversationConfig(plan)).use { conversation ->
            val response = conversation.sendMessage(content)
            val toolCalls = response.toolCalls
            return if (!toolCalls.isNullOrEmpty()) {
                Pair(response.contents?.toString() ?: "", convertToolCalls(toolCalls))
            } else {
                Pair(response.toString(), null)
            }
        }
    }

    /** 畸形工具调用解析失败的重试约束（见 chat() 的兜底分支） */
    private val NO_TOOL_CALL_GUARD =
        "\n\n[Important] Output plain text only. Do NOT emit any tool call, function call, or `call:...()` syntax. Plain text only."

    /** 是否是 LiteRT 因解析工具调用失败而抛的错误（这类错误值得重试一次） */
    private fun isToolCallParseError(e: Exception): Boolean {
        val msg = e.message ?: return false
        return msg.contains("Failed to parse tool calls", ignoreCase = true) ||
            msg.contains("ParseCancelledError", ignoreCase = true)
    }

    /** 探针用的最小 ConversationConfig（无 system/tools/历史） */
    private fun ConversationsConfigForProbe(): com.google.ai.edge.litertlm.ConversationConfig =
        com.google.ai.edge.litertlm.ConversationConfig(
            systemInstruction = Contents.of("You are a terse assistant. Reply with one word."),
        )

    /**
     * 当前模型的上下文窗口 —— 引擎实际分配到的 KV-cache 长度（输入 + 输出共用），
     * 取初始化时降级链路的最终生效值，供上层对齐 trim 预算。
     *
     * 不要用这个值去推断模型能力：Gemma 4 E2B/E4B 标称 128K，端侧包往往只有 4096。
     */
    fun getContextWindowTokens(): Int = effectiveMaxNumTokens

    fun isModelDownloaded(): Boolean {
        val configuredModelName = context.getSharedPreferences("openclaw_config", Context.MODE_PRIVATE)
            .getString("model_name", null)
        return findModelFile(configuredModelName) != null
    }

    fun getModelSizeMB(): Long {
        val configuredModelName = context.getSharedPreferences("openclaw_config", Context.MODE_PRIVATE)
            .getString("model_name", null)
        return findModelFile(configuredModelName)?.let { it.length() / (1024 * 1024) } ?: 0
    }

    fun getState(): LoadState = _state.value

    /** 开新会话时调用：丢弃复用的会话，避免上一轮对话的 KV cache 串到新会话里 */
    fun resetConversation() {
        closeCachedConversation()
        Log.i(TAG, "Conversation cache reset")
    }

    fun resetGpuCrashFlag() {
        prefs.edit()
            .putBoolean(KEY_GPU_INIT_PENDING, false)
            .putInt(KEY_GPU_CRASH_COUNT, 0)
            .putLong(KEY_GPU_CRASH_TIME, 0L)
            .apply()
    }

    fun wasGpuSkippedDueToCrash(): Boolean =
        prefs.getInt(KEY_GPU_CRASH_COUNT, 0) > 0
}
