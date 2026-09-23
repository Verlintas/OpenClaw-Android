package ai.openclaw.android

import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.util.Log
import ai.openclaw.android.accessibility.AccessibilityBridge
import ai.openclaw.android.agent.AgentSession
import ai.openclaw.android.agent.SessionEvent
import ai.openclaw.android.agent.AgentPromptLoader
import ai.openclaw.android.data.local.AppDatabase
import ai.openclaw.android.domain.memory.FallbackMemoryExtractor
import ai.openclaw.android.domain.memory.LlmMemoryExtractor
import ai.openclaw.android.domain.memory.MemoryManager
import ai.openclaw.android.domain.session.HybridSessionManager
import ai.openclaw.android.domain.session.SessionCompressor
import ai.openclaw.android.domain.session.TokenCounter
import ai.openclaw.android.ml.TfLiteEmbeddingService
import ai.openclaw.android.model.ImageContent
import ai.openclaw.android.model.LocalLLMClient
import ai.openclaw.android.model.ModelClient
import ai.openclaw.android.model.ModelProvider
import ai.openclaw.android.model.OpenAIClient
import ai.openclaw.android.model.AnthropicClient
import ai.openclaw.android.skill.SkillManager
import ai.openclaw.android.plugin.PluginManager
import ai.openclaw.android.plugin.PluginManagerExt
import ai.openclaw.android.skill.builtin.WeatherSkill
import ai.openclaw.android.skill.builtin.MultiSearchSkill
import ai.openclaw.android.skill.builtin.TranslateSkill
import ai.openclaw.android.skill.builtin.ReminderSkill
import ai.openclaw.android.skill.builtin.CalendarSkill
import ai.openclaw.android.skill.builtin.LocationSkill
import ai.openclaw.android.skill.builtin.ContactSkill
import ai.openclaw.android.skill.builtin.SMSSkill
import ai.openclaw.android.skill.builtin.NotificationSkill
import ai.openclaw.android.skill.builtin.GenerateSkillTool
import ai.openclaw.android.skill.builtin.GenerateSkillSkill
import ai.openclaw.android.skill.DynamicSkillManager
import ai.openclaw.android.skill.ApprovalDecision
import ai.openclaw.android.feishu.FeishuClient
import ai.openclaw.android.feishu.OkHttpFeishuClient
import ai.openclaw.android.feishu.FeishuEvent
import ai.openclaw.android.permission.PermissionManager
import ai.openclaw.android.domain.DeviceCapabilities
import ai.openclaw.android.domain.agent.AgentConfigManager
import ai.openclaw.android.domain.agent.AgentRouter
import ai.openclaw.android.domain.agent.AgentSessionManager
import ai.openclaw.android.trigger.EventBus
import ai.openclaw.android.trigger.ActionExecutor
import ai.openclaw.script.bridge.UiProvider
import ai.openclaw.android.trigger.scheduler.CronScheduler
import ai.openclaw.android.trigger.models.EventSource
import okhttp3.OkHttpClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * GatewayManager - Manages all Gateway components
 * Implements GatewayContract to provide a clean API for Activity consumption
 */
class GatewayManager(private val service: GatewayService) : GatewayContract {

    companion object {
        private const val TAG = "GatewayManager"
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Components (kept private)
    private var modelClient: ModelClient? = null
    private var localLLMClient: LocalLLMClient? = null
    private var agentSession: AgentSession? = null
    private var accessibilityBridge: AccessibilityBridge? = null
    private var skillManager: SkillManager? = null
    private var pluginManager: PluginManager? = null
    private var pluginManagerExt: PluginManagerExt? = null
    private var feishuClient: FeishuClient? = null
    private var dynamicSkillManager: DynamicSkillManager? = null

    // Multi-agent routing subsystem (Tasks 1-6)
    private var agentConfigManager: AgentConfigManager? = null
    private var agentRouter: AgentRouter? = null
    private var agentSessionManager: AgentSessionManager? = null

    // Trigger subsystem
    // Trigger subsystem
    private var triggerEventBus: EventBus? = null
    private var cronScheduler: CronScheduler? = null
    private var scriptOrchestrator: ai.openclaw.script.ScriptOrchestrator? = null

    // Memory subsystem (moved from Activity)
    private var database: AppDatabase? = null
    private var embeddingService: TfLiteEmbeddingService? = null
    private var memoryManager: MemoryManager? = null
    private var sessionManager: HybridSessionManager? = null

    // Device capabilities (cached)
    private var deviceCapabilities: DeviceCapabilities? = null

    // State
    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override fun getConnectionState(): StateFlow<ConnectionState> = _connectionState

    // ========== 工具审批（方案 3 统一安全层，本地模型路径） ==========
    // 云端路径的审批由 AgentSession 经 SessionEvent.ToolApprovalRequest 冒泡；
    // 本地路径（LocalLLMClient → executeLocalTool）无法注入 SessionEvent 流，
    // 走此旁路事件流 —— UI（ChatViewModel）同时收集两路，确认卡共用。

    /** 审批等待上限（A3 修订：不可达时挂起等待而非自动批准） */
    private val localApprovalTimeoutMs = 10 * 60_000L

    private val _toolApprovalRequests = MutableSharedFlow<SessionEvent.ToolApprovalRequest>(extraBufferCapacity = 8)
    override val toolApprovalRequests: kotlinx.coroutines.flow.Flow<SessionEvent.ToolApprovalRequest> = _toolApprovalRequests
    private val pendingLocalApprovals = LinkedHashMap<String, kotlinx.coroutines.CompletableDeferred<ApprovalDecision?>>()

    /** UI 层响应用户审批决策：路由到本地路径挂起中的请求 + 各 AgentSession */
    override suspend fun respondToToolApproval(requestId: String, decision: ApprovalDecision?) {
        val deferred = synchronized(pendingLocalApprovals) { pendingLocalApprovals.remove(requestId) }
        deferred?.complete(decision)
        agentSessionManager?.respondToToolApproval(requestId, decision)
        agentSession?.respondToToolApproval(requestId, decision)
    }

    /** 本地路径审批请求：emit 不可达 = 取消（绝不自动批准），超时 = 取消 */
    private suspend fun requestLocalToolApproval(
        toolId: String,
        description: String,
        risk: ai.openclaw.android.skill.ToolRiskLevel
    ): ApprovalDecision? {
        val requestId = java.util.UUID.randomUUID().toString()
        val deferred = CompletableDeferred<ApprovalDecision?>()
        synchronized(pendingLocalApprovals) { pendingLocalApprovals[requestId] = deferred }
        try {
            val emitted = _toolApprovalRequests.tryEmit(
                SessionEvent.ToolApprovalRequest(requestId, toolId, description, risk)
            )
            if (!emitted) {
                Log.w(TAG, "Approval event unreachable for $toolId, treating as cancelled")
                return null
            }
            return withTimeoutOrNull(localApprovalTimeoutMs) { deferred.await() }
        } finally {
            synchronized(pendingLocalApprovals) { pendingLocalApprovals.remove(requestId) }
        }
    }

    sealed class ConnectionState {
        object Disconnected : ConnectionState()
        object Connecting : ConnectionState()
        object Connected : ConnectionState()
        data class Error(val message: String) : ConnectionState()
    }

    // ========== GatewayContract implementation ==========

    override fun isReady(): Boolean = agentSession != null

    override fun getModelLoadState(): LocalLLMClient.LoadState? = localLLMClient?.getState()

    override suspend fun runDecisionProbe(): String? = localLLMClient?.runDecisionProbe()

    override suspend fun runDecisionCalibration(): String? = localLLMClient?.runDecisionCalibration()

    override fun getDecisionRunner(): ai.openclaw.android.agent.decision.OnDeviceDecisionRunner? =
        localLLMClient?.decisionRunner

    override fun sendMessage(text: String, images: List<ImageContent>?): Flow<SessionEvent> {
        // Multi-agent routing path (primary)
        val router = agentRouter
        val sessionManager = agentSessionManager
        if (router != null && sessionManager != null) {
            val agentId = router.route(text)
            // 经 actor 串行进入会话：UI 重发/多入口并发时按 FIFO 排队，
            // 而不是并发直调 session 在锁上互相阻塞
            return sessionManager.streamMessage(agentId, text, images)
        }
        // Backward compatibility: single-agent fallback
        return agentSession?.handleMessageStream(text, images)
            ?: flow { emit(SessionEvent.Error("AgentSession not ready")) }
    }

    override suspend fun reconfigureModel(config: ModelConfig): Boolean {
        Log.d(TAG, "Reconfiguring model: provider=${config.provider}")

        // 1. Release old model
        localLLMClient?.release()
        localLLMClient = null

        // 2. Update config
        ConfigManager.setModelProvider(config.provider.name)
        ConfigManager.setModelApiKey(config.apiKey)
        ConfigManager.setModelName(config.modelName)
        ConfigManager.setModelBaseUrl(config.baseUrl)

        // 3. Create new model client
        modelClient = if (config.provider == ModelProvider.LOCAL) {
            val client = LocalLLMClient(service)
            localLLMClient = client
            val loaded = client.initialize()
            if (!loaded) {
                Log.e(TAG, "Failed to load local model, falling back to cloud")
                localLLMClient = null
                createCloudClient()
            } else {
                client.setToolExecutor { toolName, argsJson ->
                    executeLocalTool(toolName, argsJson)
                }
                client
            }
        } else {
            createCloudClient(config.provider)
        }

        // 3.5. Ensure SkillManager is initialized (may be null if called before start())
        if (skillManager == null) {
            Log.d(TAG, "SkillManager not initialized, initializing now")
            accessibilityBridge = AccessibilityBridge()
            skillManager = SkillManager(service).apply {
                loadBuiltinSkills(service)
            }
            // Also init PluginManager
            pluginManager = PluginManager(service)
            pluginManagerExt = PluginManagerExt(service, pluginManager!!).also { ext ->
                ext.refreshPlugins()
            }
        }

        // 3.6. Ensure DynamicSkillManager is initialized (for generate_skill tool)
        if (dynamicSkillManager == null) {
            val db = AppDatabase.getInstance(service)
            database = db
            val sm = skillManager
            if (sm == null) {
                Log.e(TAG, "Cannot create DynamicSkillManager: skillManager is null")
                return false
            }
            // createDynamicSkillManager 内部会把 UserPreferenceManager 回填到 SkillManager
            dynamicSkillManager = createDynamicSkillManager(db.dynamicSkillDao(), sm)
            dynamicSkillManager?.loadAllSaved()
            dynamicSkillManager?.setToolsChangedListener {
                agentSession?.refreshTools()
            }
            val gsm = dynamicSkillManager
            if (gsm != null) {
                val generateSkillTool = GenerateSkillTool(gsm)
                val generateSkillSkill = GenerateSkillSkill(generateSkillTool)
                sm.registerSkill(generateSkillSkill)
            }
        }

        // 3.7. Inject MemoryManager into ScriptSkill if available
        if (memoryManager != null) {
            val scriptSkill = skillManager?.getLoadedSkills()?.get("script") as? ai.openclaw.android.skill.builtin.ScriptSkill
            scriptSkill?.setMemoryManager(memoryManager)
        }

        // 4. Rebuild AgentSession for backward compatibility
        val mc = modelClient
        val sm = skillManager
        if (mc == null || sm == null) {
            Log.e(TAG, "Cannot create AgentSession: modelClient=$mc, skillManager=$sm")
            _connectionState.value = ConnectionState.Error("Model or SkillManager initialization failed")
            return false
        }
        agentSession = AgentSession(
            modelClient = mc,
            skillManager = sm
        ).apply {
            val systemPrompt = AgentPromptLoader.load(service)
            setSystemPrompt(systemPrompt)
            if (mc is ai.openclaw.android.model.LocalLLMClient) setOnDeviceMode(true)
            setToolsWithSkills(
                accessTools = accessibilityBridge?.getTools() ?: emptyList(),
                executor = { toolCall ->
                    accessibilityBridge?.execute(toolCall) ?: "Accessibility not available"
                }
            )
        }

        // 4.5. Evict cached default agent session from AgentSessionManager so the next
        //      multi-agent sendMessage creates a new session with the reconfigured model.
        //      Also update backward-compatible agentSession reference.
        agentSessionManager?.let { manager ->
            val configManager = agentConfigManager
            if (configManager != null) {
                val defaultAgentId = configManager.getDefaultAgent().id
                manager.evict(defaultAgentId)
                val defaultSession = manager.getOrCreate(defaultAgentId)
                agentSession = defaultSession
            }
        }

        // 5. Rewire memory subsystem
        // 模型已切换（抽取器可能从 LLM 版变为规则版），必须重建 MemoryManager / HybridSessionManager
        wireMemoryToSession(recreate = true)

        Log.d(TAG, "Model reconfigured successfully")
        return agentSession != null
    }

    override fun getAvailableSkills(): List<SkillInfo> =
        skillManager?.getLoadedSkills()?.map { (id, skill) ->
            SkillInfo(id, skill.name, skill.description)
        } ?: emptyList()

    override fun getAvailableAgents(): List<AgentInfo> {
        val configManager = agentConfigManager ?: return emptyList()
        val defaultAgent = try {
            configManager.getDefaultAgent()
        } catch (e: IllegalStateException) {
            null
        }
        return configManager.getAllAgents().map { agent ->
            AgentInfo(
                id = agent.id,
                name = agent.name,
                isDefault = defaultAgent?.id == agent.id
            )
        }
    }

    // ========== Screen Capture (MediaProjection) ==========

    override fun getScreenCaptureIntent(): Intent? {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.LOLLIPOP) {
            Log.e(TAG, "MediaProjection requires API 21+")
            return null
        }
        val manager = service.getSystemService(MediaProjectionManager::class.java)
        return manager.createScreenCaptureIntent()
    }

    override fun initScreenCapture(resultCode: Int, data: Intent): Boolean {
        val accessibilityService = MyAccessibilityService.getInstance()
        if (accessibilityService == null) {
            Log.e(TAG, "AccessibilityService not running")
            return false
        }
        try {
            accessibilityService.initMediaProjection(resultCode, data)
            Log.d(TAG, "Screen capture initialized")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to init screen capture: ${e.message}")
            return false
        }
    }

    // ========== Extended methods for ChatViewModel integration ==========

    override fun clearHistory() {
        agentSession?.clearHistory()
    }

    override fun setScriptUiProvider(provider: UiProvider?) {
        val scriptSkill = skillManager?.getLoadedSkills()?.get("script")
            as? ai.openclaw.android.skill.builtin.ScriptSkill
        scriptSkill?.setUiProvider(provider)
        Log.d(TAG, "ScriptSkill UI provider set: ${provider != null}")
    }

    override fun getDeviceCapabilities(): DeviceCapabilities? {
        return deviceCapabilities
    }

    // ========== Plugin management ==========

    /** 获取插件管理器扩展（供外部访问） */
    fun getPluginManagerExt(): PluginManagerExt? = pluginManagerExt

    /** 获取插件管理器（供外部访问） */
    fun getPluginManager(): PluginManager? = pluginManager

    // ========== Session management implementations ==========

    override fun getSessionListFlow(): StateFlow<List<ai.openclaw.android.data.model.SessionEntity>> {
        val sm = sessionManager
        return sm?.getSessionFlow()
            ?.stateIn(serviceScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5000), emptyList())
            ?: MutableStateFlow(emptyList())
    }

    override suspend fun createNewSession(name: String): ai.openclaw.android.data.model.SessionEntity? {
        return sessionManager?.createNamedSession(name)
    }

    override suspend fun switchToSession(sessionId: String): Result<ai.openclaw.android.data.model.SessionEntity> {
        return sessionManager?.switchToSession(sessionId)
            ?: Result.failure(Exception("SessionManager not available"))
    }

    override suspend fun renameSession(sessionId: String, newName: String): Boolean {
        val sm = sessionManager ?: return false
        val sessionDao = sm.getSessionDao()
        val session = sessionDao.getSessionById(sessionId) ?: return false
        sessionDao.updateSession(session.copy(name = newName))
        return true
    }

    override suspend fun deleteSession(sessionId: String) {
        sessionManager?.getSessionDao()?.deleteSessionById(sessionId)
    }

    override fun getCurrentSessionId(): String? {
        return sessionManager?.getCurrentSessionId()
    }

    override suspend fun loadSessionMessages(sessionId: String, limit: Int): List<ai.openclaw.android.data.model.MessageEntity> {
        return sessionManager?.getMessageDao()
            ?.getMessagesBySessionIdWithLimit(sessionId, limit = limit, offset = 0)
            ?: emptyList()
    }

    override suspend fun getMessageCount(sessionId: String): Int {
        return sessionManager?.getMessageDao()
            ?.getMessageCountBySessionId(sessionId) ?: 0
    }

    // ========== Lifecycle ==========

    fun start() {
        Log.d(TAG, "Starting Gateway...")

        if (!ConfigManager.isConfigured()) {
            _connectionState.value = ConnectionState.Error("Configuration incomplete")
            return
        }

        serviceScope.launch {
            try {
                _connectionState.value = ConnectionState.Connecting
                initializeComponents()
                _connectionState.value = ConnectionState.Connected
                Log.d(TAG, "Gateway started successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start Gateway: ${e.message}")
                _connectionState.value = ConnectionState.Error(e.message ?: "Unknown error")
            }
        }
    }

    fun stop() {
        Log.d(TAG, "Stopping Gateway...")

        localLLMClient?.release()
        localLLMClient = null

        skillManager?.getLoadedSkills()?.values?.forEach { skill ->
            try {
                skill.cleanup()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to cleanup skill: ${e.message}")
            }
        }
        skillManager = null

        // Cleanup plugin manager (release engines, unregister)
        pluginManagerExt?.listPlugins()?.forEach { plugin ->
            try {
                pluginManager?.unregisterPlugin(plugin.id)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to unregister plugin on stop: ${plugin.id}", e)
            }
        }
        pluginManagerExt = null
        pluginManager = null

        agentSession = null
        accessibilityBridge = null

        agentConfigManager = null
        agentRouter = null
        agentSessionManager?.cleanup()
        agentSessionManager = null

        sessionManager = null
        memoryManager = null
        dynamicSkillManager?.cleanup()
        dynamicSkillManager = null

        // Cleanup trigger subsystem
        cronScheduler?.cancelAll()
        cronScheduler = null
        triggerEventBus = null

        _connectionState.value = ConnectionState.Disconnected
        Log.d(TAG, "Gateway stopped")
    }

    fun cleanup() {
        stop()
        serviceScope.cancel()
    }

    // ========== Internal ==========

    private suspend fun initializeComponents() {
        Log.d(TAG, "Initializing components...")

        // Initialize device capabilities (cached)
        deviceCapabilities = DeviceCapabilities.fromContext(service)
        Log.d(TAG, "Device capabilities: profile=${deviceCapabilities?.profile}")

        // Initialize ModelClient based on provider
        val providerName = ConfigManager.getModelProvider()
        val provider = try {
            ModelProvider.valueOf(providerName)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Unknown provider: $providerName, falling back to OPENAI")
            ModelProvider.OPENAI
        }

        modelClient = if (provider == ModelProvider.LOCAL) {
            Log.i(TAG, "Using LOCAL on-device model (Gemma 4 E4B)")
            val client = LocalLLMClient(service)
            localLLMClient = client
            val loaded = client.initialize()
            if (!loaded) {
                Log.e(TAG, "Failed to load local model, falling back to cloud")
                localLLMClient = null
                createCloudClient()
            } else {
                client
            }
        } else {
            Log.i(TAG, "Using cloud model: $provider / ${ConfigManager.getModelName()}")
            createCloudClient(provider)
        }

        // Initialize AccessibilityBridge
        accessibilityBridge = AccessibilityBridge()

        // Initialize SkillManager and register skills
        skillManager = SkillManager(service).apply {
            loadBuiltinSkills(service)
        }

        // Initialize PluginManager and auto-load installed plugins
        pluginManager = PluginManager(service)
        pluginManagerExt = PluginManagerExt(service, pluginManager!!).also { ext ->
            // 扫描并自动注册已安装的插件
            ext.refreshPlugins()
            Log.i(TAG, "PluginManager initialized: ${ext.listPlugins().size} plugin(s) loaded")
        }

        // Wire LocalLLMClient tool executor to skill system
        // LiteRT calls execute() internally when the model decides to use a tool
        localLLMClient?.setToolExecutor { toolName, argsJson ->
            executeLocalTool(toolName, argsJson)
        }

        // Initialize database first (needed by DynamicSkillManager)
        database = AppDatabase.getInstance(service)

        // Initialize ScriptOrchestrator (shared across subsystems)
        scriptOrchestrator = ai.openclaw.script.ScriptOrchestrator(service)

        // Initialize DynamicSkillManager with real user confirmation flow
        dynamicSkillManager = createDynamicSkillManager(database!!.dynamicSkillDao(), skillManager!!)
        dynamicSkillManager!!.loadAllSaved()

        // 定时清理（在服务启动时执行一次）
        serviceScope.launch {
            dynamicSkillManager!!.runMaintenance()
            Log.i(TAG, "Dynamic skill maintenance completed")
        }

        dynamicSkillManager!!.setToolsChangedListener {
            agentSession?.refreshTools()
        }

        val generateSkillTool = GenerateSkillTool(dynamicSkillManager!!)
        val generateSkillSkill = GenerateSkillSkill(generateSkillTool)
        skillManager!!.registerSkill(generateSkillSkill)

        // Initialize multi-agent routing subsystem
        agentConfigManager = AgentConfigManager(service)
        agentConfigManager!!.loadFromAssets()
        agentRouter = AgentRouter(agentConfigManager!!)
        agentSessionManager = AgentSessionManager(
            context = service,
            configManager = agentConfigManager!!,
            skillManager = skillManager!!,
            accessibilityBridge = accessibilityBridge,
            permissionManager = null,
            sharedLocalLLMClient = localLLMClient
        )

        // Initialize AgentSession with SkillManager (backward compat)
        agentSession = AgentSession(
            modelClient = modelClient!!,
            skillManager = skillManager!!
        ).apply {
            // 端侧档位必须在 setToolsWithSkills() 之前打开：它决定用精简 system prompt
            // 还是完整版，以及工具是否收敛到白名单。
            if (modelClient is ai.openclaw.android.model.LocalLLMClient) setOnDeviceMode(true)
            setToolsWithSkills(
                accessTools = accessibilityBridge!!.getTools(),
                executor = { toolCall ->
                    accessibilityBridge!!.execute(toolCall)
                }
            )
        }

        // Initialize memory subsystem
        embeddingService = TfLiteEmbeddingService(service)
        embeddingService!!.initialize()

        // 先确定真正对外服务的 AgentSession，再做一次记忆装配。
        // ⚠️ 此前是「wireMemoryToSession() → 替换 agentSession → 再 wireMemoryToSession()」，
        // 单次启动会创建 2 套 MemoryManager + 2 套 HybridSessionManager，第一套立刻被丢弃。
        agentSessionManager?.let { manager ->
            val defaultAgentId = agentConfigManager!!.getDefaultAgent().id
            agentSession = manager.getOrCreate(defaultAgentId)
        }

        wireMemoryToSession()

        // Initialize FeishuClient (only if credentials are configured)
        if (ConfigManager.hasFeishuCredentials()) {
            val httpClient = OkHttpClient()
            feishuClient = OkHttpFeishuClient(httpClient).apply {
                connect(ConfigManager.getFeishuAppId(), ConfigManager.getFeishuAppSecret())
                setEventListener { event -> handleFeishuEvent(event) }
            }
        } else {
            Log.i(TAG, "Skipping FeishuClient: no credentials configured")
        }

        // Initialize Trigger System
        val actionExecutor = ActionExecutor(
            context = service,
            skillManager = skillManager!!,
            agentSessionFactory = { agentSession },
            scriptOrchestrator = scriptOrchestrator
        )

        EventBus.initialize(
            ruleDao = database!!.triggerRuleDao(),
            logDao = database!!.triggerLogDao(),
            actionExecutor = actionExecutor
        )
        val eventBus = EventBus.instance!!

        cronScheduler = CronScheduler(
            context = service,
            eventBus = eventBus
        )

        // Schedule all CRON rules
        val cronRules = database!!.triggerRuleDao().getEnabled().filter { it.source == EventSource.CRON }
        cronScheduler!!.scheduleAllCronRules(cronRules)

        // Register TriggerRuleSkill
        val triggerRuleSkill = ai.openclaw.android.trigger.skill.TriggerRuleSkill(
            ruleDao = database!!.triggerRuleDao(),
            logDao = database!!.triggerLogDao(),
            cronScheduler = cronScheduler!!
        )
        skillManager!!.registerSkill(triggerRuleSkill)

        Log.i(TAG, "Trigger system initialized: ${cronRules.size} cron rules scheduled")

        Log.d(TAG, "Components initialized")
    }

    /**
     * 装配记忆子系统并挂到当前 AgentSession。
     *
     * @param recreate true 时重建 MemoryManager / HybridSessionManager（模型切换后
     *                 抽取器需要从 LLM 版换回规则版时必须重建）；false 时复用已有实例，
     *                 只重新挂载——避免重复构造和重复 `sm.initialize()` 的 IO。
     */
    private suspend fun wireMemoryToSession(recreate: Boolean = false) {
        val db = database ?: return
        val emb = embeddingService ?: return

        val mm = if (recreate || memoryManager == null) {
            val extractor = if (localLLMClient?.isModelLoaded() == true)
                LlmMemoryExtractor(localLLMClient!!)
            else
                FallbackMemoryExtractor()

            MemoryManager(
                memoryDao = db.memoryDao(),
                vectorDao = db.memoryVectorDao(),
                embeddingService = emb,
                extractor = extractor
            ).also { memoryManager = it }
        } else {
            memoryManager!!
        }

        val sm = if (recreate || sessionManager == null) {
            val compressor = SessionCompressor(
                llmClient = localLLMClient,
                summaryDao = db.summaryDao()
            )
            HybridSessionManager(
                sessionDao = db.sessionDao(),
                messageDao = db.messageDao(),
                summaryDao = db.summaryDao(),
                sessionCompressor = compressor,
                tokenCounter = TokenCounter(),
                memoryManager = mm
            ).also {
                sessionManager = it
                it.initialize()
            }
        } else {
            sessionManager!!
        }

        attachMemoryToSessions(sm, mm)
    }

    /** 把 [sm] / [mm] 挂到 backward-compat session 与多 Agent 默认 session 上 */
    private fun attachMemoryToSessions(
        sm: HybridSessionManager,
        mm: MemoryManager
    ) {
        // Wire memory to backward-compatible agentSession
        agentSession?.setSessionManager(sm)
        agentSession?.setMemoryContextProvider {
            sm.getMemoryContext()
        }

        // Wire memory to multi-agent default session
        agentSessionManager?.let { manager ->
            val configManager = agentConfigManager ?: return@let
            val defaultAgentId = configManager.getDefaultAgent().id
            val defaultSession = manager.getOrCreate(defaultAgentId)
            defaultSession.setSessionManager(sm)
            defaultSession.setMemoryContextProvider {
                sm.getMemoryContext()
            }
            // Update backward-compat reference
            agentSession = defaultSession
        }

        // 注入 MemoryManager 到 ScriptSkill
        val scriptSkill = skillManager?.getLoadedSkills()?.get("script") as? ai.openclaw.android.skill.builtin.ScriptSkill
        scriptSkill?.setMemoryManager(mm)
    }

    private fun handleFeishuEvent(event: FeishuEvent) {
        if (event.type == "im.message.receive_v1") {
            val message = event.event?.message ?: return
            val sessionManager = agentSessionManager ?: return
            val defaultAgentId = agentConfigManager?.getDefaultAgent()?.id ?: return
            serviceScope.launch {
                try {
                    // 经 actor 串行进入 default agent 会话，与 UI 入口互不交错。
                    // TODO(PR-3): 收集流式事件回发飞书
                    sessionManager.streamMessage(defaultAgentId, message.content).collect { /* PR-3 接回发 */ }
                } catch (e: Exception) {
                    Log.e(TAG, "Feishu message handling failed: ${e.message}", e)
                }
            }
        }
    }

    private fun createCloudClient(provider: ModelProvider = ModelProvider.OPENAI): ModelClient {
        val baseUrl = ConfigManager.getEffectiveBaseUrl()
        val apiKey = ConfigManager.getModelApiKey()
        val model = ConfigManager.getModelName()

        val client: ModelClient = when (provider) {
            ModelProvider.ANTHROPIC -> AnthropicClient()
            else -> OpenAIClient()
        }
        client.configure(provider, apiKey, model, baseUrl)
        return client
    }

    /**
     * Create DynamicSkillManager.
     * 工具执行的安全审查/审批已上提到 SkillManager 统一入口，此处只负责持久化与生命周期。
     * 同时把 [UserPreferenceManager] 回填到 SkillManager（统一安全层持久化 ALWAYS_APPROVE/DENY），
     * 正常启动与 reconfigure 两条初始化路径都经过这里。
     */
    private fun createDynamicSkillManager(
        dao: ai.openclaw.android.data.local.DynamicSkillDao,
        sm: ai.openclaw.android.skill.SkillManager
    ): DynamicSkillManager {
        val prefs = sm.preferenceManager
            ?: ai.openclaw.android.skill.UserPreferenceManager(service).also { sm.preferenceManager = it }
        return DynamicSkillManager(
            context = service,
            dynamicSkillDao = dao,
            skillManager = sm,
            orchestrator = scriptOrchestrator!!,
            preferenceManager = prefs
        )
    }

    /**
     * Execute a tool call from the on-device model via the skill system.
     * Called by LocalLLMClient when LiteRT's model decides to use a tool.
     *
     * Skill 工具走 SkillManager 统一安全层（与云端路径同一审查），审批经
     * [requestLocalToolApproval] 旁路事件流冒泡到 UI。
     */
    private suspend fun executeLocalTool(toolName: String, argsJson: String): String {
        val sm = skillManager ?: return "{\"error\": \"SkillManager not ready\"}"
        val bridge = accessibilityBridge

        // Parse args
        val params = try {
            org.json.JSONObject(argsJson).let { json ->
                val map = mutableMapOf<String, Any>()
                for (key in json.keys()) map[key] = json.get(key)
                map
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse tool args: $argsJson")
            emptyMap<String, Any>()
        }

        // Skill tools have namespaced names: skillId_toolName
        if (toolName.contains("_") && toolName.split("_").size >= 2) {
            val outcome = sm.executeTool(
                toolName, params,
                requestApproval = ::requestLocalToolApproval
                // 本地路径无系统弹窗通道：缺权限 → Denied 文本（A4：不再静默）
            )
            return when (outcome) {
                is ai.openclaw.android.skill.ToolExecutionOutcome.Done ->
                    if (outcome.result.success) outcome.result.output
                    else (outcome.result.error ?: "Skill error")
                is ai.openclaw.android.skill.ToolExecutionOutcome.Denied -> outcome.reason
                // requester 非空时不会到达（审批循环在 executeTool 内完成）
                is ai.openclaw.android.skill.ToolExecutionOutcome.NeedsApproval ->
                    "工具 ${outcome.toolId} 需要用户确认后才能执行。"
            }
        }

        // Accessibility tools
        val tc = ai.openclaw.android.model.ToolCall(
            id = "local_exec_${System.nanoTime()}",
            type = "function",
            function = ai.openclaw.android.model.ToolCallFunction(
                name = toolName,
                arguments = argsJson
            )
        )
        return bridge?.execute(tc) ?: "Accessibility not available"
    }
}
