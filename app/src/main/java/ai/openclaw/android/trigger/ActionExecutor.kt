package ai.openclaw.android.trigger

import ai.openclaw.android.notification.SmartNotificationListener
import ai.openclaw.android.skill.SkillManager
import ai.openclaw.android.skill.ToolExecutionOutcome
import ai.openclaw.android.trigger.models.*
import ai.openclaw.android.agent.AgentSession
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.core.app.NotificationCompat
import android.app.RemoteInput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import ai.openclaw.script.ScriptOrchestrator

/**
 * ActionExecutor — 执行 TriggerAction
 *
 * 支持四种动作类型：
 * - SkillCall: 调用 Skill 工具
 * - AgentQuery: 向 AI Agent 发送查询
 * - NotificationReply: 回复通知
 * - CustomScript: 执行自定义脚本
 */
class ActionExecutor(
    private val context: Context,
    private val skillManager: SkillManager,
    private val agentSessionFactory: suspend () -> AgentSession?,
    private val scriptOrchestrator: ScriptOrchestrator? = null
) {
    companion object {
        private const val TAG = "ActionExecutor"

        /** 通知回复专用 Channel ID */
        private const val REPLY_CHANNEL_ID = "openclaw_trigger_reply"

        /**
         * 构建变量映射表，供插值使用
         */
        fun buildVariables(event: TriggerEvent): Map<String, String> {
            return mutableMapOf<String, String>().apply {
                // Notification variables
                event.payload["text"]?.let { put("notification.text", it.toString()) }
                event.payload["title"]?.let { put("notification.title", it.toString()) }
                event.payload["package"]?.let { put("notification.package", it.toString()) }
                // Event variables
                put("event.source", event.source.name)
                put("event.timestamp", event.timestamp.toString())
                put("event.id", event.id)
            }
        }

        /**
         * 在字符串中替换 {variable} 占位符
         */
        fun interpolate(template: String, variables: Map<String, String>): String {
            var result = template
            for ((key, value) in variables) {
                result = result.replace("{$key}", value)
            }
            return result
        }
    }

    /**
     * 执行动作
     */
    suspend fun execute(action: TriggerAction, event: TriggerEvent): ActionResult {
        return when (action) {
            is TriggerAction.SkillCall -> executeSkillCall(action, event)
            is TriggerAction.AgentQuery -> executeAgentQuery(action, event)
            is TriggerAction.NotificationReply -> executeNotificationReply(action, event)
            is TriggerAction.CustomScript -> executeCustomScript(action, event)
        }
    }

    /**
     * 执行 SkillCall — 调用 Skill 工具
     *
     * 走 SkillManager 统一安全层，但不提供审批通道（后台触发器无 UI）：
     * READ 工具直通；WRITE/DANGEROUS 需审批 → 返回 NeedsApproval，不静默放行。
     * WRITE 工具若有 ALWAYS_APPROVE 偏好（用户曾在对话中确认）也会直通。
     */
    private suspend fun executeSkillCall(
        action: TriggerAction.SkillCall,
        event: TriggerEvent
    ): ActionResult = withContext(Dispatchers.IO) {
        try {
            val toolFullName = "${action.skillId}_${action.toolName}"

            // 解析参数，替换事件变量
            val params = parseAndInterpolateParams(action.paramsJson, event)

            // 调用 SkillManager 执行工具（统一安全层）
            when (val outcome = skillManager.executeTool(toolFullName, params)) {
                is ToolExecutionOutcome.Done -> {
                    val result = outcome.result
                    if (result.success) {
                        ActionResult(
                            success = true,
                            result = "Skill executed: ${action.toolName} → ${result.output.take(200)}"
                        )
                    } else {
                        ActionResult(
                            success = false,
                            error = "Skill failed: ${result.error}"
                        )
                    }
                }

                is ToolExecutionOutcome.Denied ->
                    ActionResult(success = false, error = "Skill denied: ${outcome.reason}")

                is ToolExecutionOutcome.NeedsApproval ->
                    ActionResult(
                        success = false,
                        error = "Skill $toolFullName 需要用户确认（后台触发器无确认通道），未执行。" +
                            "如需放行，请先在对话中对该工具选择「总是允许」。"
                    )
            }
        } catch (e: Exception) {
            Log.e(TAG, "SkillCall failed: ${e.message}", e)
            ActionResult(success = false, error = e.message)
        }
    }

    /**
     * 同一时刻只允许一个「规则驱动的 Agent 查询」在跑。
     *
     * 端侧模型一次推理要 5~12 秒、吃掉 84% CPU 和 1.7GB 常驻。多条规则各自匹配同一事件时，
     * 若并发调用，端侧引擎会被同时拉起多份，整机直接卡死（外加 OOM 风险）。
     * 拿不到许可就直接跳过，不排队 —— 后台触发器不该堆积任务。
     *
     * TODO: agentSessionFactory 目前返回的是 GatewayManager 的共享主会话，
     * 规则的每轮对话会写进用户的主会话历史（实测 historySize 从 10 涨到 17）。
     * 理想做法是给触发器独立会话，但那会 new 一个新的 LocalLLMClient（重新加载 2.5GB 模型），
     * 需要复用引擎实例后再改。
     */
    private val agentQueryPermit = java.util.concurrent.Semaphore(1, true)

    /**
     * 全局闸门：所有规则驱动的 Agent 查询之间至少间隔这么久。
     *
     * 单规则的冷却（EventBus.MIN_COOLDOWN_MS = 60s）只能限制同一条规则，
     * 但一个事件常常同时命中 3～4 条规则，它们会轮流点火，把间隔又压回 15～20 秒。
     * 端侧一次推理 5～12 秒、84% CPU，这个闸门把占空比压到 ~5%，
     * 是「自激循环」的最后一道保险。
     *
     * 需要更激进/宽松时改这一个常量即可。
     */
    private val AGENT_QUERY_MIN_INTERVAL_MS = 120_000L
    private val lastAgentQueryAt = java.util.concurrent.atomic.AtomicLong(0L)

    /**
     * 执行 AgentQuery — 向 AI 发送查询
     */
    private suspend fun executeAgentQuery(
        action: TriggerAction.AgentQuery,
        event: TriggerEvent
    ): ActionResult = withContext(Dispatchers.IO) {
        if (!agentQueryPermit.tryAcquire()) {
            Log.w(TAG, "AgentQuery skipped: another trigger-driven query is running")
            return@withContext ActionResult(
                success = false,
                error = "已有规则触发的 Agent 查询在执行，本次跳过（避免端侧模型被并发拉起）"
            )
        }
        try {
            // 全局频率闸门：与上一次规则驱动的推理间隔过短就直接放弃
            val now = System.currentTimeMillis()
            val last = lastAgentQueryAt.get()
            if (last != 0L && now - last < AGENT_QUERY_MIN_INTERVAL_MS) {
                Log.w(
                    TAG,
                    "AgentQuery throttled: only ${now - last}ms since last run " +
                        "(min ${AGENT_QUERY_MIN_INTERVAL_MS}ms)"
                )
                return@withContext ActionResult(
                    success = false,
                    error = "端侧推理过于频繁，本次跳过（距上次仅 ${(now - last) / 1000}s）"
                )
            }
            lastAgentQueryAt.set(now)

            val prompt = interpolatePrompt(action.prompt, event)

            val session = agentSessionFactory()
            if (session == null) {
                return@withContext ActionResult(success = false, error = "AgentSession not available")
            }

            // 发送消息给 Agent
            val response = session.handleMessage(prompt)

            ActionResult(
                success = true,
                result = "Agent response: ${response.take(200)}"
            )
        } catch (e: Exception) {
            Log.e(TAG, "AgentQuery failed: ${e.message}", e)
            ActionResult(success = false, error = e.message)
        } finally {
            agentQueryPermit.release()
        }
    }

    /**
     * 执行 NotificationReply — 回复通知
     *
     * 实现思路：
     * 1. 查找匹配包名的系统通知
     * 2. 检查是否支持远程回复（RemoteInput / ACTION_REPLY）
     * 3. 支持：构建 Notification.ReplyResult 发送回复
     * 4. 不支持：发送一条本地通知作为回复标记
     */
    private suspend fun executeNotificationReply(
        action: TriggerAction.NotificationReply,
        event: TriggerEvent
    ): ActionResult = withContext(Dispatchers.IO) {
        try {
            val packageName = event.payload["package"] as? String
            val text = interpolatePrompt(action.template, event)

            if (!action.autoReply || packageName == null) {
                return@withContext ActionResult(success = true, result = "Reply template: $text")
            }

            // 尝试通过 SmartNotificationListener 查找匹配包名的通知
            val activeNotifications = SmartNotificationListener.getActiveNotificationsList()
            val matchingSmart = activeNotifications.find { it.packageName == packageName }

            if (matchingSmart != null) {
                // SmartNotification.id = SBN.key，通过 key 查找真正的 StatusBarNotification
                val sbn = findStatusBarNotificationByKey(matchingSmart.id)
                if (sbn != null) {
                    val replySucceeded = tryReplyViaSystem(sbn, text)
                    if (replySucceeded) {
                        Log.i(TAG, "NotificationReply sent via system to $packageName")
                        return@withContext ActionResult(success = true, result = "Reply sent to $packageName via system")
                    }
                }
            }

            // 无法直接回复，发送本地通知作为标记
            sendLocalReply(packageName, text)
            Log.i(TAG, "NotificationReply sent as local notification to $packageName")
            ActionResult(success = true, result = "Reply sent to $packageName (local notification)")
        } catch (e: Exception) {
            Log.e(TAG, "NotificationReply failed: ${e.message}", e)
            ActionResult(success = false, error = e.message)
        }
    }

    /**
     * 尝试通过 Android 系统的远程回复功能回复通知
     * 检查通知是否包含 RemoteInput 或支持 ACTION_REPLY
     */
    private fun tryReplyViaSystem(sbn: android.service.notification.StatusBarNotification, replyText: String): Boolean {
        return try {
            // 检查通知是否有 RemoteInput（远程回复能力）
            val remoteInputs = extractRemoteInputs(sbn.notification)
            if (remoteInputs.isNotEmpty()) {
                // 找到支持回复的 RemoteInput，发送回复
                sendRemoteInputReply(sbn, remoteInputs[0], replyText)
                return true
            }
            false
        } catch (e: Exception) {
            Log.w(TAG, "tryReplyViaSystem failed: ${e.message}")
            false
        }
    }

    /**
     * 通过 key 从监听器获取真正的 StatusBarNotification
     * SmartNotification.id = SBN.key，需要此桥接方法
     */
    private fun findStatusBarNotificationByKey(key: String): android.service.notification.StatusBarNotification? {
        val listenerInstance = SmartNotificationListener.getInstanceForReply()
            ?: return null

        return try {
            val activeSbns = listenerInstance.getActiveNotifications()
            activeSbns?.find { it.key == key }
        } catch (e: SecurityException) {
            Log.w(TAG, "No permission to get active notifications: ${e.message}")
            null
        } catch (e: Exception) {
            Log.w(TAG, "Failed to find SBN by key: ${e.message}")
            null
        }
    }



    /**
     * 从 Notification 中提取 RemoteInput（用于直接回复）
     * 统一返回 android.app.RemoteInput，避免类型混淆
     */
    private fun extractRemoteInputs(notification: Notification): List<RemoteInput> {
        val result = mutableListOf<RemoteInput>()

        // 方式1: 检查 WearableExtender (API 19+)
        try {
            val extender = androidx.core.app.NotificationCompat.WearableExtender(notification)
            extender.getActions()?.forEach { action ->
                action.getRemoteInputs()?.forEach { ri ->
                    // 用 resultKey 重建 android.app.RemoteInput，保留原始配置
                    result.add(RemoteInput.Builder(ri.resultKey).build())
                }
            }
        } catch (e: Exception) {
            // WearableExtender 不可用
        }

        // 方式2: 检查标准 RemoteInput (API 24+)
        try {
            val actions = notification.actions
            actions?.forEach { action ->
                action.remoteInputs?.forEach { ri ->
                    // 直接使用原生 android.app.RemoteInput，不转换
                    result.add(ri)
                }
            }
        } catch (e: Exception) {
            // ignored
        }

        return result
    }

    /**
     * 通过 RemoteInput 发送回复
     */
    private fun sendRemoteInputReply(
        sbn: android.service.notification.StatusBarNotification,
        remoteInput: RemoteInput,
        replyText: String
    ) {
        try {
            // 获取回复的 PendingIntent
            val replyAction = sbn.notification.actions?.find { action ->
                action.remoteInputs?.any { it.resultKey == remoteInput.resultKey } == true
            }

            if (replyAction != null && replyAction.actionIntent != null) {
                val intent = Intent()
                RemoteInput.addResultsToIntent(
                    arrayOf(remoteInput),
                    intent,
                    Bundle().apply { putCharSequence(remoteInput.resultKey, replyText) }
                )

                replyAction.actionIntent.send(context, 0, intent)
                Log.i(TAG, "RemoteInput reply sent successfully")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to send RemoteInput reply: ${e.message}")
        }
    }

    /**
     * 发送本地通知作为回复标记
     */
    private fun sendLocalReply(packageName: String, replyText: String) {
        try {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            // 确保回复 Channel 存在
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    REPLY_CHANNEL_ID,
                    "OpenClaw 触发器回复",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Trigger 系统的通知回复标记"
                }
                notificationManager.createNotificationChannel(channel)
            }

            val notificationId = abs(packageName.hashCode()) + abs((System.currentTimeMillis() % 1000000).toInt())

            val notification = NotificationCompat.Builder(context, REPLY_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_email)
                .setContentTitle("回复 → $packageName")
                .setContentText(replyText)
                .setStyle(NotificationCompat.BigTextStyle().bigText(replyText))
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setAutoCancel(true)
                .build()

            notificationManager.notify(notificationId, notification)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send local reply notification: ${e.message}")
        }
    }

    /**
     * 执行 CustomScript — 执行自定义脚本
     *
     * 集成 ScriptOrchestrator 执行 JS 脚本
     */
    private suspend fun executeCustomScript(
        action: TriggerAction.CustomScript,
        event: TriggerEvent
    ): ActionResult = withContext(Dispatchers.IO) {
        try {
            val orchestrator = scriptOrchestrator
            if (orchestrator == null) {
                return@withContext ActionResult(
                    success = false,
                    error = "ScriptOrchestrator not available"
                )
            }

            // 将事件数据转为 JS 变量
            val variables = buildJsVariables(event)

            // 调用 orchestrator 执行脚本
            val scriptResult = orchestrator.execute(
                script = action.script,
                capabilities = emptyList(),
                customBridges = emptyList(),
                variables = variables
            )

            if (scriptResult.success) {
                ActionResult(
                    success = true,
                    result = "Script executed: ${scriptResult.output.take(200)}"
                )
            } else {
                ActionResult(
                    success = false,
                    error = "Script failed: ${scriptResult.error}"
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "CustomScript failed: ${e.message}", e)
            ActionResult(success = false, error = e.message)
        }
    }

    /**
     * 构建传递给 JS 脚本的变量
     */
    private fun buildJsVariables(event: TriggerEvent): Map<String, Any> {
        return mutableMapOf<String, Any>().apply {
            // Event metadata
            put("event_source", event.source.name)
            put("event_id", event.id)
            put("event_timestamp", event.timestamp)

            // Notification payload
            event.payload.forEach { (k, v) ->
                if (v != null) {
                    put("notification_$k", v)
                }
            }
        }
    }

    // ==================== Helpers ====================

    /**
     * 解析并插值参数
     * 使用 kotlinx.serialization 解析 JSON + 变量替换
     */
    private fun parseAndInterpolateParams(paramsJson: String, event: TriggerEvent): Map<String, Any> {
        val map = mutableMapOf<String, Any>()

        if (paramsJson.isNotBlank() && paramsJson != "{}") {
            try {
                // 先进行变量插值
                val variables = buildVariables(event)
                val interpolated = interpolate(paramsJson, variables)

                // 使用 kotlinx.serialization JSON 解析
                val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
                val element = json.parseToJsonElement(interpolated)

                if (element is kotlinx.serialization.json.JsonObject) {
                    element.forEach { (key, value) ->
                        when (value) {
                            is kotlinx.serialization.json.JsonPrimitive -> {
                                if (value.isString) {
                                    map[key] = value.content
                                } else {
                                    // 尝试解析为数字或布尔
                                    value.content.toBooleanOrNull()?.let { map[key] = it; return@forEach }
                                    value.content.toLongOrNull()?.let { map[key] = it; return@forEach }
                                    value.content.toDoubleOrNull()?.let { map[key] = it }
                                }
                            }
                            is kotlinx.serialization.json.JsonArray -> {
                                map[key] = value.map { elem ->
                                    if (elem is kotlinx.serialization.json.JsonPrimitive && elem.isString) {
                                        elem.content
                                    } else {
                                        elem.toString()
                                    }
                                }
                            }
                            else -> {
                                map[key] = value.toString()
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse params JSON: $paramsJson, ${e.message}")
                // 回退到 org.json 解析
                try {
                    val json = org.json.JSONObject(paramsJson)
                    json.keys().forEach { key ->
                        map[key] = json.get(key)
                    }
                } catch (e2: Exception) {
                    Log.w(TAG, "Fallback JSON parse also failed: ${e2.message}")
                }
            }
        }

        // 注入事件数据
        map["event_source"] = event.source.name
        map["event_id"] = event.id
        event.payload.forEach { (k, v) ->
            if (v != null) map["notification_$k"] = v.toString()
        }

        return map
    }

    /**
     * 插值 Prompt 字符串
     * 支持所有变量: {notification.text}, {notification.title}, {notification.package},
     *            {event.source}, {event.timestamp}, {event.id}
     */
    private fun interpolatePrompt(prompt: String, event: TriggerEvent): String {
        val variables = buildVariables(event)
        return interpolate(prompt, variables)
    }
}

/**
 * String 辅助：安全转 Boolean
 */
private fun String.toBooleanOrNull(): Boolean? = when {
    equals("true", ignoreCase = true) -> true
    equals("false", ignoreCase = true) -> false
    else -> null
}
