package ai.openclaw.android

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import ai.openclaw.android.agent.SessionEvent
import ai.openclaw.android.data.model.MessageEntity
import ai.openclaw.android.data.model.SessionEntity
import ai.openclaw.android.domain.DeviceCapabilities
import ai.openclaw.android.model.ImageContent
import ai.openclaw.android.model.LocalLLMClient
import ai.openclaw.android.model.ModelProvider
import ai.openclaw.android.skill.ApprovalDecision
import ai.openclaw.script.bridge.UiProvider

/**
 * Gateway 服务契约接口
 * Activity 只依赖此接口，不直接访问 GatewayManager 内部组件
 * 为将来改成远程 Service（真正跨进程）留了退路
 */
interface GatewayContract {
    fun isReady(): Boolean
    fun getModelLoadState(): LocalLLMClient.LoadState?
    fun getConnectionState(): StateFlow<GatewayManager.ConnectionState>
    fun sendMessage(text: String, images: List<ImageContent>? = null): Flow<SessionEvent>
    suspend fun reconfigureModel(config: ModelConfig): Boolean
    fun getAvailableSkills(): List<SkillInfo>
    fun getAvailableAgents(): List<AgentInfo>

    // ========== 工具审批（方案 3 统一安全层） ==========

    /**
     * 本地模型路径的工具审批请求（旁路事件流，云端路径经 sendMessage 的
     * SessionEvent 流内 [SessionEvent.ToolApprovalRequest] 冒泡）。
     * UI 层应同时收集两路并弹出同一张确认卡。
     */
    val toolApprovalRequests: Flow<SessionEvent.ToolApprovalRequest>

    /** UI 层响应用户审批决策；requestId 不匹配时为 no-op */
    suspend fun respondToToolApproval(requestId: String, decision: ApprovalDecision?)

    /**
     * Request MediaProjection permission for screenshots.
     * Returns an Intent that must be launched with startActivityForResult.
     */
    fun getScreenCaptureIntent(): android.content.Intent?

    /**
     * Initialize MediaProjection after user grants permission.
     */
    fun initScreenCapture(resultCode: Int, data: android.content.Intent): Boolean

    // ========== Extended methods for ChatViewModel integration ==========

    /** 清空当前会话历史 */
    fun clearHistory()

    /**
     * 注入 ScriptSkill UI Provider。
     *
     * 此前签名是 `Any?`，实现类只能在运行时用 `is UiProvider` 判断并打 warning，
     * 接口完全失去类型安全。这里直接用 `:script` 模块的 `UiProvider` 类型收口。
     */
    fun setScriptUiProvider(provider: UiProvider?)

    /** 获取设备能力信息 */
    fun getDeviceCapabilities(): DeviceCapabilities?

    // ========== Session management methods ==========

    /** 获取所有会话列表的 StateFlow */
    fun getSessionListFlow(): StateFlow<List<SessionEntity>>

    /** 创建新会话，返回会话实体 */
    suspend fun createNewSession(name: String = ""): SessionEntity?

    /** 切换到指定会话 */
    suspend fun switchToSession(sessionId: String): Result<SessionEntity>

    /** 重命名会话 */
    suspend fun renameSession(sessionId: String, newName: String): Boolean

    /** 删除会话 */
    suspend fun deleteSession(sessionId: String)

    /** 获取当前会话 ID */
    fun getCurrentSessionId(): String?

    /** 获取指定会话的历史消息 */
    suspend fun loadSessionMessages(sessionId: String, limit: Int = 50): List<MessageEntity>

    /** 获取会话的消息数 */
    suspend fun getMessageCount(sessionId: String): Int
}

data class ModelConfig(
    val provider: ModelProvider,
    val apiKey: String,
    val modelName: String,
    val baseUrl: String = ""
)

data class SkillInfo(
    val id: String,
    val name: String,
    val description: String
)

data class AgentInfo(
    val id: String,
    val name: String,
    val isDefault: Boolean
)
