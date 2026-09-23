package ai.openclaw.android.domain.agent

import ai.openclaw.android.ConfigManager
import ai.openclaw.android.accessibility.AccessibilityBridge
import ai.openclaw.android.agent.AgentSession
import ai.openclaw.android.agent.SessionEvent
import ai.openclaw.android.data.model.AgentConfig
import ai.openclaw.android.model.ImageContent
import ai.openclaw.android.model.OpenAIClient
import ai.openclaw.android.model.AnthropicClient
import ai.openclaw.android.model.LocalLLMClient
import ai.openclaw.android.model.ModelClient
import ai.openclaw.android.model.ModelProvider
import ai.openclaw.android.permission.PermissionManager
import ai.openclaw.android.skill.SkillManager
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch

/**
 * Manages multiple AgentSession instances with lazy creation, caching, and LRU eviction.
 *
 * Each agent ID maps to a lazily-created AgentSession configured with its own
 * model provider, system prompt, and tool filtering rules.
 *
 * @param context Android application context
 * @param configManager Source of agent configurations
 * @param skillManager Shared skill registry
 * @param accessibilityBridge Optional accessibility tools bridge
 * @param permissionManager Optional permission gate for skill execution
 * @param maxCachedSessions Maximum sessions to keep in cache before LRU eviction (default: 3)
 */
open class AgentSessionManager(
    private val context: Context,
    private val configManager: AgentConfigManager,
    private val skillManager: SkillManager,
    private val accessibilityBridge: AccessibilityBridge?,
    private val permissionManager: PermissionManager? = null,
    private val maxCachedSessions: Int = 3,
    private val sharedLocalLLMClient: LocalLLMClient? = null
) {

    companion object {
        private const val TAG = "AgentSessionManager"
    }

    private val sessionCache = mutableMapOf<String, AgentSession>()
    private val accessOrder = mutableListOf<String>() // LRU tracking

    // ==================== Actor 层（并发入口串行化） ====================

    /**
     * Actor 宿主：独立于调用方 scope，单消费者协程按 agent 隔离。
     * SupervisorJob 保证单个 actor 崩溃不影响其他 agent 的队列。
     */
    private val actorScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val actors = mutableMapOf<String, AgentActor>()

    /**
     * 经 actor 串行投递一轮对话，返回流式事件。
     *
     * 所有非 UI 入口（飞书、触发器、定时任务）必须走此方法而不是直接拿
     * session 调 handleMessageStream —— 多入口并发直调会在同一 session 上
     * 交错推进对话轮次（用户消息与工具结果交叉写入）。
     * AgentSession 内部的整轮锁保证正确性，actor 在此之上提供 FIFO 排队，
     * 让后到的消息等前一轮完成而不是互相阻塞。
     */
    fun streamMessage(agentId: String, text: String, images: List<ImageContent>? = null): Flow<SessionEvent> {
        val session = getOrCreate(agentId)
        val actor = synchronized(actors) {
            actors.getOrPut(agentId) { AgentActor(agentId, session, actorScope) }
        }
        return actor.submit(text, images)
    }

    /**
     * Get or create an AgentSession for the given agent ID.
     *
     * If a session already exists in cache it is returned and marked as recently
     * accessed.  Otherwise a new session is built from the agent's config and
     * cached (evicting the least-recently-used session if the cache is full).
     */
    fun getOrCreate(agentId: String): AgentSession {
        // Return cached if exists
        sessionCache[agentId]?.let { session ->
            touch(agentId)
            Log.d(TAG, "Reusing cached session for '$agentId'")
            return session
        }

        // Create new session
        val config = configManager.getAgentById(agentId) ?: configManager.getDefaultAgent()
        val modelClient = createModelClient(config)

        // 端侧模型的窗口与云端默认（8000）不是一回事：把会话 trim 预算对齐到模型窗口，
        // 并给 system prompt + 工具 schema 留出空间（端侧常注入 50+ 工具，占很大一块）。
        // 系数取 0.6 是为了让上层裁剪后的历史不再触发 LocalLLMClient 内部的二次裁剪，
        // 否则历史前缀每轮都在变，KV-cache 复用会失效。
        val localWindow = (modelClient as? LocalLLMClient)?.getContextWindowTokens()
        val maxContextTokens = localWindow?.let { (it * 0.6f).toInt() }

        val session = if (maxContextTokens != null) {
            AgentSession(
                modelClient = modelClient,
                skillManager = skillManager,
                agentConfig = config,
                permissionManager = permissionManager,
                maxContextTokens = maxContextTokens
            )
        } else {
            AgentSession(
                modelClient = modelClient,
                skillManager = skillManager,
                agentConfig = config,
                permissionManager = permissionManager
            )
        }

        if (localWindow != null) {
            Log.i(TAG, "LOCAL model: window=$localWindow, session maxContextTokens=$maxContextTokens")
        }

        // 必须在 setToolsWithSkills() 之前：它决定用哪份 system prompt、
        // 以及工具是否收敛到端侧白名单。
        if (localWindow != null) {
            session.setOnDeviceMode(true)
        }

        // Initialize tools
        session.setToolsWithSkills(
            accessTools = accessibilityBridge?.getTools() ?: emptyList(),
            executor = { toolCall ->
                accessibilityBridge?.execute(toolCall) ?: "Accessibility not available"
            }
        )

        // Reflection strategy is auto-selected by the AgentSession factory constructor
        Log.d(TAG, "Reflection strategy for '$agentId': ${config.reflectionStrategy ?: "NONE"}")

        // Cache with eviction (skip if maxCachedSessions is 0)
        if (maxCachedSessions > 0) {
            evictIfNecessary()
            sessionCache[agentId] = session
            accessOrder.add(agentId)
        }

        Log.i(TAG, "Created new session for '$agentId' (model: ${config.model}, reflection: ${config.reflectionStrategy ?: "NONE"})")
        return session
    }

    /**
     * Remove a session from cache.
     * 同时停掉该 agent 的 actor —— 缓存的 session 已被丢弃，actor 若继续
     * 持有旧引用会把后续消息写进一个不再被任何入口可见的会话。
     */
    fun evict(agentId: String) {
        synchronized(actors) { actors.remove(agentId) }?.shutdown()
        sessionCache.remove(agentId)
        accessOrder.remove(agentId)
        Log.d(TAG, "Evicted session for '$agentId'")
    }

    /**
     * Get list of active (cached) agent IDs in access order (oldest first).
     */
    fun getActiveAgentIds(): List<String> = accessOrder.toList()

    /**
     * 转发审批决策到所有缓存会话（requestId 全局唯一，只有发起会话能匹配，其余 no-op）。
     */
    fun respondToToolApproval(requestId: String, decision: ai.openclaw.android.skill.ApprovalDecision?) {
        sessionCache.values.toList().forEach { it.respondToToolApproval(requestId, decision) }
    }

    /**
     * Clean up all sessions.
     * 只 shutdown actor（consumer 协程），不取消 actorScope —— manager 若被
     * 复用（测试/重置场景），后续 streamMessage 仍可在同一 scope 上启动新
     * consumer；取消 scope 会让后续消息静默堆积在 mailbox 里无人消费。
     */
    fun cleanup() {
        synchronized(actors) {
            actors.values.forEach { it.shutdown() }
            actors.clear()
        }
        sessionCache.clear()
        accessOrder.clear()
        Log.i(TAG, "All sessions cleaned up")
    }

    /**
     * Build a ModelClient configured for the given agent.
     * Marked `protected open` so tests can override with a mock.
     */
    protected open fun createModelClient(config: AgentConfig): ModelClient {
        // When user selected LOCAL provider, all agents use on-device model
        val userProvider = ConfigManager.getModelProvider()
        if (userProvider == "LOCAL") {
            val shared = sharedLocalLLMClient
            if (shared != null && shared.isModelLoaded()) {
                Log.i(TAG, "Reusing shared LOCAL model for agent '${config.id}'")
                return shared
            }
            Log.i(TAG, "Using LOCAL on-device model for agent '${config.id}' (new instance)")
            return LocalLLMClient(context)
        }

        // Resolve effective model:
        // 1. If agent has an explicit model (non-empty), use it
        // 2. Otherwise fall back to user's ConfigManager settings
        val effectiveModel = if (config.model.isNotBlank()) config.model else ConfigManager.getModelName()

        // Parse model string: "openai/MiniMax-M3" → provider=openai, name=MiniMax-M3
        val parts = effectiveModel.split("/", limit = 2)
        // 无 "provider/" 前缀时用用户在设置里选的全局 provider（默认 OPENAI），
        // 而不是硬编码 openai——否则用户配置 ANTHROPIC + Anthropic 兼容端点时，
        // 这里会构造 OpenAIClient，把请求打到 {baseUrl}/chat/completions 上静默 404。
        val providerStr = if (parts.size > 1) parts[0] else ConfigManager.getModelProvider().lowercase()
        val modelName = if (parts.size > 1) parts[1] else effectiveModel

        val apiKey = ConfigManager.getModelApiKey()
        val baseUrl = ConfigManager.getEffectiveBaseUrl()

        val provider = try {
            ModelProvider.valueOf(providerStr.uppercase())
        } catch (_: Exception) {
            ModelProvider.OPENAI
        }

        val client: ModelClient = when (provider) {
            ModelProvider.ANTHROPIC -> AnthropicClient()
            else -> OpenAIClient()
        }
        client.configure(provider, apiKey, modelName, baseUrl)
        return client
    }

    /**
     * Mark an agent ID as recently accessed (move to end of access order).
     */
    private fun touch(agentId: String) {
        accessOrder.remove(agentId)
        accessOrder.add(agentId)
    }

    /**
     * Evict least-recently-used sessions until cache size is under the limit.
     */
    private fun evictIfNecessary() {
        while (sessionCache.size >= maxCachedSessions && accessOrder.isNotEmpty()) {
            val oldest = accessOrder.removeAt(0)
            synchronized(actors) { actors.remove(oldest) }?.shutdown()
            sessionCache.remove(oldest)
            Log.d(TAG, "LRU evicted: '$oldest'")
        }
    }
}

/**
 * 单 agent 的消息 actor：Channel.UNLIMITED mailbox + 单消费者协程。
 *
 * 把并发提交到同一 agent 的对话请求按 FIFO 串行送到同一 AgentSession。
 * AgentSession 的整轮锁已经保证正确性（不会交错写坏状态），actor 的价值
 * 是非阻塞排队语义：后到的请求先挂起等 mailbox，而不是全部堵在锁上；
 * 收集方取消只取消当前请求（经 finally 写回部分状态），actor 存活。
 */
private class AgentActor(
    private val agentId: String,
    private val session: AgentSession,
    scope: CoroutineScope
) {
    private class ChatRequest(
        val message: String,
        val images: List<ImageContent>?,
        val events: Channel<SessionEvent>
    )

    private val mailbox = Channel<ChatRequest>(Channel.UNLIMITED)
    private val consumer = scope.launch {
        for (request in mailbox) {
            try {
                session.handleMessageStream(request.message, request.images)
                    .collect { event -> request.events.send(event) }
            } catch (e: Exception) {
                // 单个请求失败/取消不杀 actor：CancellationException 也走这里，
                // finally 关闭事件通道后继续消费下一条消息
                Log.w(TAG_AGENT_ACTOR, "Request failed for '$agentId': ${e.message}")
            } finally {
                request.events.close()
            }
        }
    }

    fun submit(message: String, images: List<ImageContent>? = null): Flow<SessionEvent> = flow {
        val request = ChatRequest(message, images, Channel(Channel.BUFFERED))
        mailbox.send(request)
        try {
            for (event in request.events) emit(event)
        } finally {
            // 收集方取消 → 关闭事件通道，consumer 侧 send 抛取消，
            // 传播为 session 流的取消（其 finally 写回部分状态）
            request.events.cancel()
        }
    }.flowOn(Dispatchers.Default)

    fun shutdown() {
        consumer.cancel()
        mailbox.close()
        // drain：为已排队但未处理的请求关闭事件通道，
        // 否则对应收集方会在 `for (event in events)` 上永久挂起
        while (true) {
            val queued = mailbox.tryReceive().getOrNull() ?: break
            queued.events.close()
        }
    }

    private companion object {
        const val TAG_AGENT_ACTOR = "AgentActor"
    }
}
