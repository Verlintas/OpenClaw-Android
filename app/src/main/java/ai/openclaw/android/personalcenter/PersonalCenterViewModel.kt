package ai.openclaw.android.personalcenter

import android.app.Application
import android.Manifest
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import ai.openclaw.android.GatewayContract
import ai.openclaw.android.permission.PermissionManager
import ai.openclaw.android.notification.SmartNotificationListener
import ai.openclaw.android.personalcenter.models.CenterItem
import ai.openclaw.android.personalcenter.sources.CalendarSource
import ai.openclaw.android.personalcenter.sources.CallLogSource
import ai.openclaw.android.personalcenter.sources.NotificationSource
import ai.openclaw.android.personalcenter.sources.SmsSource
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.*

/**
 * 个人中心 ViewModel
 * 职责：
 *   1. 聚合四个数据源（通知、日历、短信、通话记录）
 *   2. 内容过滤（关键词 + LLM 语义）
 *   3. 跨源去重
 *   4. 按重要程度排序
 *   5. 定时兜底刷新
 */
class PersonalCenterViewModel(
    private val app: Application,
    private val permManager: PermissionManager? = null
) : androidx.lifecycle.ViewModel() {

    private val TAG = "PersonalCenterVM"

    private val notificationSource = NotificationSource(app)
    private val calendarSource = CalendarSource(app)
    private val smsSource = SmsSource(app)
    private val callLogSource = CallLogSource(app)

    // 最终输出：过滤 + 去重 + 排序后的统一列表
    private val _items = MutableStateFlow<List<CenterItem>>(emptyList())
    val items: StateFlow<List<CenterItem>> = _items.asStateFlow()

    // 加载状态
    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    // 权限状态
    private val _calendarPermissionGranted = MutableStateFlow(true)
    val calendarPermissionGranted: StateFlow<Boolean> = _calendarPermissionGranted.asStateFlow()

    private val _smsPermissionGranted = MutableStateFlow(true)
    val smsPermissionGranted: StateFlow<Boolean> = _smsPermissionGranted.asStateFlow()

    private val _callLogPermissionGranted = MutableStateFlow(false)
    val callLogPermissionGranted: StateFlow<Boolean> = _callLogPermissionGranted.asStateFlow()

    // 通知使用权（NotificationListenerService）— 不是运行时权限，只能在系统设置里开关
    private val _notificationPermissionGranted = MutableStateFlow(false)
    val notificationPermissionGranted: StateFlow<Boolean> = _notificationPermissionGranted.asStateFlow()

    // 监听服务是否真的被系统绑定。权限已开但服务没起来的情况在国产 ROM 上很常见
    // （荣耀 iaware 会拦截绑定），只看权限开关会把这种状态误判成「一切正常」
    val notificationServiceConnected: StateFlow<Boolean> = SmartNotificationListener.isConnected

    // 过滤统计（用于调试）— 使用 @Volatile 保证线程安全（Flow 在不同线程执行）
    @Volatile
    private var _filteredCount = 0
    val filteredCount: Int get() = _filteredCount

    private var refreshJob: Job? = null

    // 通话记录权限请求通道 — 使用 Channel(1) 确保事件不丢失
    // Channel 会缓冲 emit 的值直到被 consume，不受 collector 时机影响
    private val _triggerCallLogPermissionRequest = Channel<Unit>(Channel.CONFLATED)
    val triggerCallLogPermissionRequest: ReceiveChannel<Unit> = _triggerCallLogPermissionRequest

    /**
     * UI 层在权限对话框结果后调用此方法
     */
    fun onCallLogPermissionResult(granted: Boolean) {
        _callLogPermissionGranted.value = granted
        Log.d(TAG, "Call log permission result: $granted")
    }

    /**
     * 检查通话记录权限，如果未授权则触发请求
     */
    fun checkAndRequestCallLogPermission() {
        val granted = ContextCompat.checkSelfPermission(
            app, Manifest.permission.READ_CALL_LOG
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            _callLogPermissionGranted.value = true
            return
        }
        // 直接通过 Channel 触发 Activity 的权限请求
        viewModelScope.launch { _triggerCallLogPermissionRequest.send(Unit) }
    }

    /**
     * 注入 LLM 评估器（由 Activity 在 ViewModel 创建后调用）
     */
    fun setGatewayContract(contract: GatewayContract?) {
        if (contract != null) {
            SmartFilter.llmEvaluator = SmartFilter.LlmEvaluator { items ->
                evaluateWithLLM(items, contract)
            }
            PriorityClassifier.llmClassifier = PriorityClassifier.createLlmEvaluator(contract)
        }
    }

    init {
        startMerging()
        startPeriodicRefresh()
        // 启动时检查权限状态，不自动触发弹框（由 UI 层首次可见时触发）
        checkCallLogPermissionStatus()
        checkCalendarAndSmsPermissionStatus()
        checkNotificationPermissionStatus()
    }

    /**
     * 重新判定日历 / 短信权限
     *
     * 不能依赖数据源 Flow 的 .catch：SmsSource / CalendarSource 内部已经把
     * SecurityException 吞掉并返回 emptyList，异常根本不会传播到 startMerging 的 catch 块，
     * 所以这两个 flag 过去一直是初值 true、从未变过 —— 导致「未授权」状态在 UI 上不可达，
     * 统计行不会显示 ! ，对应的去授权入口也就成了死代码。
     */
    private fun checkCalendarAndSmsPermissionStatus() {
        _calendarPermissionGranted.value = ContextCompat.checkSelfPermission(
            app, Manifest.permission.READ_CALENDAR
        ) == PackageManager.PERMISSION_GRANTED
        _smsPermissionGranted.value = ContextCompat.checkSelfPermission(
            app, Manifest.permission.READ_SMS
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * 重新判定「通知使用权」是否已授予
     * 这是特殊权限，用户只能在系统设置里开关，因此每次回到页面都要重新读一次
     */
    fun checkNotificationPermissionStatus() {
        _notificationPermissionGranted.value =
            SmartNotificationListener.isNotificationListenerEnabled(app)
        Log.d(TAG, "Notification listener permission: ${_notificationPermissionGranted.value}")
    }

    /**
     * 页面重新可见时调用（用户可能刚从「通知使用权」设置页返回）
     * 重新判定权限并触发一次拉取
     */
    fun onScreenResumed() {
        checkCalendarAndSmsPermissionStatus()
        checkNotificationPermissionStatus()
        SmartNotificationListener.refreshFromSystem()
    }

    /**
     * 仅检查权限状态，不主动弹框
     */
    private fun checkCallLogPermissionStatus() {
        val granted = ContextCompat.checkSelfPermission(
            app, Manifest.permission.READ_CALL_LOG
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            _callLogPermissionGranted.value = true
        }
    }

    /**
     * UI 层在首次可见时调用，触发权限请求
     */
    fun requestCallLogPermissionIfNeeded() {
        if (_callLogPermissionGranted.value) return // 已授权则跳过
        viewModelScope.launch { _triggerCallLogPermissionRequest.send(Unit) }
    }

    /**
     * LLM 评估实现
     */
    private suspend fun evaluateWithLLM(
        items: List<CenterItem>,
        contract: GatewayContract
    ): Map<String, Float> {
        if (items.isEmpty()) return emptyMap()

        // 端侧（本地模型）走决策模式：prefill + 1 步 decode，~0.27s/条；
        // 云端继续走下面的生成式 JSON 评估（决策模式只在本地启用）。
        val runner = contract.getDecisionRunner()
        if (runner != null) {
            return evaluateWithDecision(runner, items)
        }

        val batchText = items.joinToString("\n") { item ->
            "[${item.id}] ${item.sourceApp} | ${item.title} | ${item.body.take(100)}"
        }

        val prompt = """
你是一个信息价值评估助手。请评估以下通知/消息的信息价值。

评估标准：
1. 高价值：验证码、银行通知、来电提醒、重要日程、快递通知、工作相关
2. 中价值：社交消息、新闻推送、应用更新提醒
3. 低价值：广告推广、营销信息、签到提醒、游戏通知、系统垃圾提示

对每条消息给出 0~1 的评分：
- 0.0-0.2：无价值，应该过滤掉
- 0.3-0.5：一般价值
- 0.6-1.0：高价值，必须保留

消息列表：
$batchText

只返回 JSON 格式（不要其他文字）：
[{"id": "xxx", "value": 0.8}, {"id": "yyy", "value": 0.1}]
""".trimIndent()

        return try {
            val response = StringBuilder()
            contract.sendMessage(prompt).collect { event ->
                when (event) {
                    is ai.openclaw.android.agent.SessionEvent.Token -> {
                        response.append(event.text)
                    }
                    is ai.openclaw.android.agent.SessionEvent.Complete -> {}
                    is ai.openclaw.android.agent.SessionEvent.Error -> {
                        throw RuntimeException(event.message)
                    }
                    else -> {}
                }
            }

            val result = mutableMapOf<String, Float>()
            val jsonRegex = Regex("""\{\s*"id"\s*:\s*"([^"]+)"\s*,\s*"value"\s*:\s*([0-9.]+)""")
            for (match in jsonRegex.findAll(response.toString())) {
                val id = match.groupValues[1]
                val value = match.groupValues[2].toFloatOrNull() ?: 0.5f
                result[id] = value.coerceIn(0f, 1f)
            }
            result
        } catch (e: Exception) {
            Log.e(TAG, "LLM evaluation failed: ${e.message}")
            emptyMap()
        }
    }

    // ========== 端侧决策模式评估（P3-1） ==========

    /** 通知价值三分类：下标 → SmartFilter 需要的 0~1 分值 */
    // 决策表与离线校准共用同一份（agent/decision/DecisionProtocols.NotificationValueDecision），
    // 避免「校准测的表」和「线上跑的表」各自漂移。改表后务必跑一次 TEST_DECISION_CALIB 对比。
    private val DECISION_OPTIONS = ai.openclaw.android.agent.decision.NotificationValueDecision.OPTIONS
    private val DECISION_QUESTION = ai.openclaw.android.agent.decision.NotificationValueDecision.QUESTION
    private val DECISION_VALUES = ai.openclaw.android.agent.decision.NotificationValueDecision.VALUES

    /**
     * 决策模式评估。
     *
     * 与生成式路径产出同构（Map<id, 0~1>），SmartFilter 主体不用改：
     * 沿用 `value >= 0.3f` 保留线，因此「无价值」(0.1) 被过滤、其余保留。
     *
     * 批次切分：实测 0.27s/条，整批一次跑会在 15s 超时边缘；切成 10 条一批，
     * 每批独立超时，超时的剩余条目返回 0.5（保留，宁可多显示不可误杀）。
     */
    private suspend fun evaluateWithDecision(
        runner: ai.openclaw.android.agent.decision.OnDeviceDecisionRunner,
        items: List<CenterItem>,
    ): Map<String, Float> {
        val result = mutableMapOf<String, Float>()
        val batchSize = 10
        // 实测 0.27s/条：50 条 ≈ 13.5s，会顶到 SmartFilter 的 15s 外层超时。
        // 这里留 3s 余量，超时的剩余条目返回 0.5（保留，不误杀），下一轮刷新再覆盖。
        val deadline = System.currentTimeMillis() + 12_000L
        for (chunk in items.chunked(batchSize)) {
            if (System.currentTimeMillis() > deadline) {
                Log.w(TAG, "决策评估到达总截止，剩余 ${items.size - result.size} 条按 0.5 保留")
                repeat(chunk.size) { result[chunk[it].id] = 0.5f }
                continue
            }
            val batchItems = chunk.map { item ->
                item.id to "${item.sourceApp} | ${item.title} | ${item.body.take(100)}"
            }
            // 显式给足超时：Runner 的默认 4s 是按旧的「批量共享会话」估的
            // （那时 10 条约 2.7s），改成每条独立会话后实测 371ms/条，
            // 10 条就要 3.7s，再用 4s 会在大批次上误降级。
            val results = runner.decideBatch(
                question = DECISION_QUESTION,
                options = DECISION_OPTIONS,
                items = batchItems,
                timeoutMs = 12_000L,
            )
            for (i in chunk.indices) {
                val idx = results.getOrNull(i)?.chosenIndex
                // 决策失败/解析不出 → 0.5（保守保留），并落日志供校准统计
                result[chunk[i].id] = if (idx == null) 0.5f else DECISION_VALUES[idx]
            }
            val invalid = results.count { !it.isValid }
            if (invalid > 0) {
                Log.w(TAG, "决策评估：本批 ${results.size} 条中 $invalid 条无效，按 0.5 保留")
            }
        }
        Log.d(TAG, "决策评估完成：${result.size} 条，过滤掉 ${result.count { it.value < 0.3f }} 条")
        return result
    }

    // ========== 优先级分类的三道闸（见 startMerging Step 4） ==========

    /** 上次 LLM 分类的输入指纹（条目 id 集合） */
    private var lastPriorityKey: String = ""
    /** 上次 LLM 分类的输出，按 id 复用 */
    private var lastPriorityOutput: List<CenterItem> = emptyList()
    private var lastPriorityAt: Long = 0L
    /** 两次 LLM 优先级分类之间的最小间隔：端侧一次生成 7~10s，太快就是自激 */
    private val PRIORITY_MIN_INTERVAL_MS = 60_000L

    private suspend fun classifyPrioritySafely(items: List<CenterItem>): List<CenterItem> {
        // 兜底线：这个函数抛出的任何异常都会顺着 combine 向上终止整个合并流，
        // 页面从此永远空白（真机实测过一次 NPE 直接把个人中心打成空列表）。
        // 所以这里一律不向上抛，最坏情况返回未分类的原列表。
        return try {
            classifyPriorityInternal(items)
        } catch (t: Throwable) {
            Log.e(TAG, "classifyPrioritySafely 异常，本轮按规则兜底: ${t.message}", t)
            items
        }
    }

    private suspend fun classifyPriorityInternal(items: List<CenterItem>): List<CenterItem> {
        if (items.isEmpty()) return emptyList()

        Log.d(TAG, "pc-step1: items=${items.size}")
        val key = items.joinToString("|") { it.id }
        val cachedById = lastPriorityOutput.associateBy { it.id }
        Log.d(TAG, "pc-step2: cached=${cachedById.size}")

        // 闸 ①：条目集合没变 → 复用上次分类结果（含新增/删除判定）
        if (key == lastPriorityKey && lastPriorityOutput.isNotEmpty()) {
            Log.d(TAG, "Priority classify: 条目未变化，复用上次结果")
            return items.map { cachedById[it.id] ?: it }
        }

        // 闸 ②：距上次 LLM 调用不足 60s → 先用规则兜底，不抢占引擎
        if (lastPriorityOutput.isNotEmpty() &&
            System.currentTimeMillis() - lastPriorityAt < PRIORITY_MIN_INTERVAL_MS
        ) {
            Log.d(TAG, "Priority classify: 距上次调用不足 60s，走规则兜底")
            return items
        }

        // 闸 ③：只跑一次，超时/异常都直接走规则兜底（不再重试第二次 LLM）
        Log.d(TAG, "pc-step3: 调用 classifyBatch，llmClassifier=${PriorityClassifier.llmClassifier != null}")
        val classifierOut: List<CenterItem>? = try {
            withTimeout(20_000L) {
                PriorityClassifier.classifyBatch(items)
            }
        } catch (e: TimeoutCancellationException) {
            Log.w(TAG, "PriorityClassifier timeout, using rule-based fallback")
            items
        } catch (e: Exception) {
            Log.w(TAG, "PriorityClassifier error: ${e.message}")
            items
        }
        Log.d(TAG, "pc-step4: classifyBatch 返回 ${classifierOut?.size}")
        val result = classifierOut ?: run {
            Log.e(TAG, "pc-step4: classifyBatch 返回空引用，整轮按规则兜底")
            items
        }

        lastPriorityKey = key
        lastPriorityAt = System.currentTimeMillis()
        lastPriorityOutput = result
        return result
    }

    /**
     * 合并三源数据：collect 每个源的 Flow → 过滤 → 去重 → 排序
     */
    private fun startMerging() {
        viewModelScope.launch {
            try {
                combine(
                    notificationSource.observe().catch { e ->
                        Log.e(TAG, "Notification flow error: ${e.message}")
                        emit(emptyList())
                    },
                    calendarSource.observe().catch { e ->
                        Log.w(TAG, "Calendar flow error: ${e.message}")
                        _calendarPermissionGranted.value = false
                        emit(emptyList())
                    },
                    smsSource.observe().catch { e ->
                        Log.w(TAG, "SMS flow error: ${e.message}")
                        _smsPermissionGranted.value = false
                        emit(emptyList())
                    },
                    callLogSource.observe().catch { e ->
                        Log.w(TAG, "CallLog flow error: ${e.message}")
                        _callLogPermissionGranted.value = false
                        emit(emptyList())
                    }
                ) { notifications, calendars, sms, callLogs ->
                    val all = notifications + calendars + sms + callLogs
                    val rawCount = all.size
                    Log.d(TAG, "Raw items: $rawCount (notif=${notifications.size}, cal=${calendars.size}, sms=${sms.size}, call=${callLogs.size})")

                    // Step 1: 关键词快速过滤
                    val afterContentFilter = all.filterNot { ContentFilter.isNoise(it) }
                    Log.d(TAG, "After ContentFilter: ${afterContentFilter.size} (removed ${rawCount - afterContentFilter.size})")

                    // Step 2: LLM 语义过滤（有 LLM 就用，没有就跳过）
                    val afterSmartFilter = try {
                        withTimeout(15_000L) {
                            SmartFilter.filterBatch(afterContentFilter)
                        }
                    } catch (e: TimeoutCancellationException) {
                        Log.w(TAG, "SmartFilter timeout, using ContentFilter result")
                        afterContentFilter
                    } catch (e: Exception) {
                        Log.w(TAG, "SmartFilter error: ${e.message}")
                        afterContentFilter
                    }
                    Log.d(TAG, "After SmartFilter: ${afterSmartFilter.size}")

                    // Step 3: 去重（精确 dedupKey 匹配）
                    val deduped = try {
                        DeduplicationEngine.deduplicate(afterSmartFilter)
                    } catch (e: Exception) {
                        Log.e(TAG, "Dedup error: ${e.message}")
                        afterSmartFilter.sortedByDescending { it.importance }
                    }
                    Log.d(TAG, "After dedup: ${deduped.size}")

                    // Step 3.5: 语义级合并（同一日历事件的多条提醒合并）
                    val semanticallyMerged = try {
                        DeduplicationEngine.semanticMerge(deduped)
                    } catch (e: Exception) {
                        Log.e(TAG, "Semantic merge error: ${e.message}")
                        deduped
                    }
                    Log.d(TAG, "After semantic merge: ${semanticallyMerged.size}")

                    // Step 4: LLM 优先级分类
                    //
                    // 这里原先每个合并周期（约 5s 一次）都起一次完整生成（端侧实测 7~10s），
                    // 且超时分支还会再调一次 classifyBatch —— 结果是「上一轮还没跑完、下一轮又起」，
                    // 推理请求永不停止地压着 native 引擎，是端侧发热/卡顿/间歇性 SIGSEGV 的直接诱因。
                    // 现在加三道闸：① 条目集合未变 → 直接复用上次结果；② 距上次调用不足 60s → 规则兜底；
                    // ③ 超时/异常只走规则兜底，不再重试一次 LLM。
                    val afterPriorityClassify = classifyPrioritySafely(semanticallyMerged)
                    Log.d(TAG, "After priority classify: ${afterPriorityClassify.size}")

                    // Step 5: 最低阈值过滤
                    val finalItems = afterPriorityClassify.filter {
                        // urgent / today 级别即使分值低也保留
                        if (it.priorityLevel == ai.openclaw.android.personalcenter.models.PriorityLevel.URGENT ||
                            it.priorityLevel == ai.openclaw.android.personalcenter.models.PriorityLevel.TODAY) true
                        // reference 级别且 importance < 0.15 的过滤掉
                        if (it.priorityLevel == ai.openclaw.android.personalcenter.models.PriorityLevel.REFERENCE) {
                            it.importance >= 0.15f
                        } else {
                            // 未知级别保持原逻辑
                            it.importance >= 0.1f
                        }
                    }
                    Log.d(TAG, "Final items: ${finalItems.size}")

                    _filteredCount = rawCount - finalItems.size
                    _items.value = finalItems
                    _isLoading.value = false
                }
                    // 任意一步异常都不应让整页永久空白：重新订阅四个源再来一轮。
                    // 没有这层时，一次 NPE 就能让 combine 永远停止发射，页面停在空白。
                    .retryWhen { cause, attempt ->
                        if (attempt >= 5) {
                            Log.e(TAG, "合并流连续失败 5 次，放弃重试", cause)
                            _isLoading.value = false
                            false
                        } else {
                            Log.e(
                                TAG,
                                "合并流异常，1s 后重试第 ${attempt + 1} 次: ${cause.message}",
                                cause
                            )
                            delay(1_000L)
                            true
                        }
                    }
                    .collect()
            } catch (e: Exception) {
                Log.e(TAG, "startMerging crashed: ${e.message}", e)
                _isLoading.value = false
            }
        }
    }

    /**
     * 每 60 秒兜底刷新（防止 ContentObserver 漏通知）
     * 各源通过 Flow 自动推送更新；通知源没有 ContentObserver，这里额外触发一次主动拉取。
     */
    private fun startPeriodicRefresh() {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            while (isActive) {
                delay(60_000L)
                checkCalendarAndSmsPermissionStatus()
                checkNotificationPermissionStatus()
                SmartNotificationListener.refreshFromSystem()
            }
        }
    }

    /**
     * 手动刷新
     */
    fun refresh() {
        viewModelScope.launch {
            _isLoading.value = true
            checkCalendarAndSmsPermissionStatus()
            checkNotificationPermissionStatus()
            SmartNotificationListener.refreshFromSystem()
            delay(500)
            _isLoading.value = false
        }
    }

    /**
     * 标记单条为已读
     */
    fun markAsRead(id: String) {
        _items.value = _items.value.map {
            if (it.id == id) it.copy(isRead = true) else it
        }
    }

    /**
     * 标记所有为已读
     */
    fun markAllAsRead() {
        _items.value = _items.value.map { it.copy(isRead = true) }
    }

    /**
     * 删除单条
     */
    fun removeItem(id: String) {
        _items.value = _items.value.filter { it.id != id }
    }

    override fun onCleared() {
        super.onCleared()
        refreshJob?.cancel()
        Log.d(TAG, "PersonalCenterViewModel cleared")
    }
}

/**
 * Factory for creating PersonalCenterViewModel with Application context
 */
class PersonalCenterViewModelFactory(
    private val app: Application,
    private val permManager: PermissionManager? = null
) : androidx.lifecycle.ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(PersonalCenterViewModel::class.java)) {
            return PersonalCenterViewModel(app, permManager) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
