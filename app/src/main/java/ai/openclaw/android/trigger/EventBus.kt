package ai.openclaw.android.trigger

import ai.openclaw.android.trigger.models.*
import ai.openclaw.android.trigger.dao.TriggerRuleDao
import ai.openclaw.android.trigger.dao.TriggerLogDao
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.collections.LinkedHashMap

/**
 * 执行动作的结果
 */
data class ActionResult(
    val success: Boolean,
    val result: String? = null,
    val error: String? = null
)

/**
 * EventBus — 事件总线 + 规则匹配 + 防抖去重
 *
 * 单例模式，全局唯一实例
 */
class EventBus private constructor(
    private val ruleDao: TriggerRuleDao,
    private val logDao: TriggerLogDao,
    private val actionExecutor: ActionExecutor
) {
    companion object {
        private const val TAG = "EventBus"

        @Volatile
        internal var instance: EventBus? = null

        fun getInstance(
            ruleDao: TriggerRuleDao,
            logDao: TriggerLogDao,
            actionExecutor: ActionExecutor
        ): EventBus = instance ?: synchronized(this) {
            instance ?: EventBus(ruleDao, logDao, actionExecutor).also { instance = it }
        }

        /**
         * 初始化 EventBus 单例（供 Application 或 CronWorker 等无 DI 场景使用）
         */
        fun initialize(
            ruleDao: TriggerRuleDao,
            logDao: TriggerLogDao,
            actionExecutor: ActionExecutor
        ) {
            if (instance == null) {
                synchronized(this) {
                    if (instance == null) {
                        instance = EventBus(ruleDao, logDao, actionExecutor)
                    }
                }
            }
        }

        fun reset() {
            instance = null
        }

        /**
         * 测试专用工厂方法 — 绕过单例，注入 Mock 依赖
         */
        internal fun forTesting(
            ruleDao: TriggerRuleDao,
            logDao: TriggerLogDao,
            actionExecutor: ActionExecutor
        ): EventBus = EventBus(ruleDao, logDao, actionExecutor)
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // 防抖: ruleId -> lastExecutedAt
    //
    // 必须用并发容器：publish 从通知回调、cron worker、UI 等多条协程进入，
    // 原先的 mutableMapOf 在并发下写入会丢失，防抖形同虚设 —— 这是端侧
    // 「规则每 5~7 秒跑一轮本地大模型、整机 84% CPU」的直接原因之一。
    private val cooldowns = ConcurrentHashMap<String, Long>()
    private val dedupCache = ConcurrentHashMap<String, Long>()

    /**
     * 单规则的最小冷却间隔。
     *
     * 端侧每条规则动辄拉起一次完整 LLM 推理（实测 5~12 秒、84% CPU），
     * 若规则自身的 cooldownMs 配置过小（或为 0），就会退化成无限循环。
     * 这里强制兜底，避免任何规则把端侧模型烧成"永动机"。
     */
    private val MIN_COOLDOWN_MS = 60_000L

    private val DEDUP_TTL_MS = 5 * 60 * 1000L // 5 minutes

    /** 去重缓存上限，超过则强制清理过期项，防止端侧长时间运行后无限膨胀 */
    private val DEDUP_MAX_SIZE = 200

    /** 清理过期的去重记录（ConcurrentHashMap 无 LRU 淘汰，需手动扫） */
    private fun evictDedupExpired(now: Long) {
        dedupCache.entries.removeAll { (_, ts) -> now - ts >= DEDUP_TTL_MS }
    }

    // ==================== 测试支持方法 ====================

    /** 返回冷却映射（供测试断言使用） */
    internal fun getCooldowns(): Map<String, Long> = cooldowns.toMap()

    /**
     * 只清冷却、不动去重缓存。供测试隔离使用：
     * 去重语义的单测需要在两次 publish 之间绕开 [MIN_COOLDOWN_MS] 地板
     * （生产策略），但必须保留去重缓存才能验证「不同 dedupKey 不去重」。
     */
    internal fun clearCooldowns() {
        cooldowns.clear()
    }

    /** 返回去重缓存（供测试断言使用） */
    internal fun getDedupCache(): Map<String, Long> = dedupCache.toMap()

    /** 重置运行时状态（冷却 + 去重），供测试隔离使用 */
    internal fun resetState() {
        cooldowns.clear()
        dedupCache.clear()
    }

    /**
     * 发布事件到总线
     */
    suspend fun publish(event: TriggerEvent) {
        Log.d(TAG, "Event published: source=${event.source}, id=${event.id}")

        val now = System.currentTimeMillis()

        // 去重检查
        event.dedupKey?.let { key ->
            dedupCache[key]?.let { lastSeen ->
                if (now - lastSeen < DEDUP_TTL_MS) {
                    Log.d(TAG, "Event deduped: $key")
                    return
                }
            }
            dedupCache[key] = now
        }
        // ConcurrentHashMap 没有 LRU 淘汰，超过容量时按 TTL 清一遍
        if (dedupCache.size > DEDUP_MAX_SIZE) evictDedupExpired(now)

        // 获取匹配的规则
        val matchedRules = getMatchingRules(event)
        Log.d(TAG, "Matched ${matchedRules.size} rules for event ${event.id}")

        for (rule in matchedRules) {
            executeRule(rule, event)
        }
    }

    /**
     * 获取匹配的规则
     */
    private suspend fun getMatchingRules(event: TriggerEvent): List<TriggerRule> {
        val rules = ruleDao.getEnabled()
        return rules.filter { rule ->
            rule.source == event.source && matchesFilters(rule, event)
        }
    }

    /**
     * 检查事件是否匹配规则的所有过滤器
     */
    private fun matchesFilters(rule: TriggerRule, event: TriggerEvent): Boolean {
        val filters = rule.getFilters()
        if (filters.isEmpty()) return true // 无过滤器 = 全部匹配

        return filters.all { filter -> filterMatches(filter, event) }
    }

    /**
     * 单个过滤器匹配
     */
    private fun filterMatches(filter: Filter, event: TriggerEvent): Boolean {
        return when (filter) {
            is Filter.PackageFilter -> {
                val pkg = event.payload["package"] as? String ?: return false
                filter.packages.any { pkg.contains(it, ignoreCase = true) }
            }

            is Filter.KeywordFilter -> {
                val text = (event.payload["title"] as? String ?: "") +
                        " " + (event.payload["text"] as? String ?: "")
                when (filter.mode) {
                    MatchMode.OR -> filter.keywords.any { text.contains(it, ignoreCase = true) }
                    MatchMode.AND -> filter.keywords.all { text.contains(it, ignoreCase = true) }
                    MatchMode.CONTAINS -> filter.keywords.any { text.contains(it, ignoreCase = true) }
                    MatchMode.EXACT -> filter.keywords.any { text.equals(it, ignoreCase = true) }
                }
            }

            is Filter.TimeFilter -> {
                val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
                if (filter.startHour <= filter.endHour) {
                    hour in filter.startHour..filter.endHour
                } else {
                    // 跨午夜: e.g., 22:00 - 07:00
                    hour >= filter.startHour || hour <= filter.endHour
                }
            }

            is Filter.CategoryFilter -> {
                val category = event.payload["category"] as? String ?: return false
                category == filter.category
            }
        }
    }

    /**
     * 执行规则（含防抖检查）
     */
    private suspend fun executeRule(rule: TriggerRule, event: TriggerEvent) {
        // 防抖检查 + 占位必须原子完成。
        //
        // 原先是「先执行 action、执行完才记冷却」，而 action（AgentQuery）要跑 5~12 秒，
        // 期间任何其他协程都能通过检查并发进来 —— 规则越多、事件越密，端侧模型越是被
        // 并发反复拉起。这里改成先占位再执行。
        val now = System.currentTimeMillis()
        val effectiveCooldown = rule.cooldownMs.coerceAtLeast(MIN_COOLDOWN_MS)
        val prev = cooldowns.putIfAbsent(rule.id, now)
        if (prev != null) {
            if (now - prev < effectiveCooldown) {
                Log.d(TAG, "Rule ${rule.id} in cooldown (${now - prev}/${effectiveCooldown}ms), skipping")
                return
            }
            cooldowns[rule.id] = now
        }

        Log.i(TAG, "Executing rule: ${rule.name} (${rule.id})")

        val action = rule.getAction() ?: run {
            Log.w(TAG, "Rule ${rule.id} has no valid action")
            return
        }

        val result = actionExecutor.execute(action, event)

        // 记录日志
        val log = TriggerLog(
            ruleId = rule.id,
            eventId = event.id,
            actionType = action::class.simpleName ?: "unknown",
            success = result.success,
            error = result.error,
            result = result.result
        )
        logDao.insert(log)

        Log.i(TAG, "Rule ${rule.id} executed: success=${result.success}")
    }

    /**
     * 手动触发规则（用于测试）
     */
    suspend fun triggerRuleManually(ruleId: String): TriggerLog {
        val rule = ruleDao.getById(ruleId)
            ?: throw IllegalArgumentException("Rule not found: $ruleId")

        val event = TriggerEvent(
            source = EventSource.USER_ACTION,
            payload = mapOf("manual" to true, "ruleId" to ruleId)
        )

        executeRule(rule, event)
        return logDao.getRecent(1).first()
    }

    /**
     * 清理过期的防抖记录
     */
    fun cleanupCooldowns() {
        val now = System.currentTimeMillis()
        cooldowns.entries.removeAll { (ruleId, lastExec) ->
            val rule = runBlocking { ruleDao.getById(ruleId) }
            rule == null || now - lastExec > (rule.cooldownMs * 2)
        }
    }

    /**
     * 清理过期的日志
     */
    suspend fun cleanupLogs() {
        val thirtyDaysAgo = System.currentTimeMillis() - (30L * 24 * 60 * 60 * 1000)
        logDao.deleteOlderThan(thirtyDaysAgo)
        Log.i(TAG, "Cleaned up logs older than 30 days")
    }
}
