package ai.openclaw.android.skill

/**
 * 工具安全策略
 */
enum class ToolSecurityPolicy {
    /** 自动执行（幂等操作） */
    AUTO_EXECUTE,
    /** 询问用户（非幂等操作，首次） */
    ASK_USER,
    /** 拒绝执行（用户已拒绝） */
    DENY
}

/**
 * 用户审批决策
 */
enum class ApprovalDecision {
    /** 总是允许 */
    ALWAYS_APPROVE,
    /** 总是拒绝 */
    ALWAYS_DENY,
    /** 每次都询问 */
    ASK_EVERY_TIME
}

/**
 * 用户审批偏好
 *
 * @param toolId 完整工具 ID: "bitcoin_price_set_alert"
 * @param decision 用户决策
 * @param lastUsedAt 最后使用时间戳
 */
@kotlinx.serialization.Serializable
data class UserApprovalPreference(
    val toolId: String,
    val decision: ApprovalDecision,
    val lastUsedAt: Long = System.currentTimeMillis()
)

/**
 * 安全审查器 — 纯逻辑，可 JVM 单元测试
 *
 * 规则（方案 3）：
 * - READ → 直通执行（只读无副作用）
 * - WRITE → 按用户偏好：ALWAYS_APPROVE 直通 / ALWAYS_DENY 拒绝 / 无偏好或每次询问 → 询问
 * - DANGEROUS → 一律询问，即使偏好是 ALWAYS_APPROVE（不可逆操作不设白名单）
 */
object SecurityReview {
    /**
     * 审查工具，返回安全策略
     *
     * @param toolName 工具名称（完整 ID，如 "sms_send_sms"，用于审计日志）
     * @param risk 工具风险等级
     * @param preference 用户已有的审批偏好（可为 null）
     * @return 安全策略
     */
    fun reviewTool(
        toolName: String,
        risk: ToolRiskLevel,
        preference: UserApprovalPreference?
    ): ToolSecurityPolicy {
        return when (risk) {
            ToolRiskLevel.READ -> ToolSecurityPolicy.AUTO_EXECUTE
            ToolRiskLevel.WRITE -> when (preference?.decision) {
                ApprovalDecision.ALWAYS_APPROVE -> ToolSecurityPolicy.AUTO_EXECUTE
                ApprovalDecision.ALWAYS_DENY -> ToolSecurityPolicy.DENY
                else -> ToolSecurityPolicy.ASK_USER
            }
            ToolRiskLevel.DANGEROUS -> ToolSecurityPolicy.ASK_USER
        }
    }
}

/**
 * 统一执行入口的执行结果（方案 3）
 *
 * [Done] 已执行完毕（无论成功失败，[Done.result] 携带 SkillResult）
 * [NeedsApproval] 需要用户确认后才能执行 —— 由入口层（云端=确认卡 UI，本地=确认流）决策后重试
 * [Denied] 被安全策略/权限门拒绝，未执行
 */
sealed interface ToolExecutionOutcome {
    data class Done(val result: SkillResult) : ToolExecutionOutcome
    data class NeedsApproval(
        val toolId: String,
        val description: String,
        val risk: ToolRiskLevel
    ) : ToolExecutionOutcome
    data class Denied(val reason: String) : ToolExecutionOutcome
}

/**
 * 审批请求回调：由入口层提供（云端 = SessionEvent 确认卡；本地 = GatewayManager 确认流）。
 * 返回用户的决策；null 表示取消（超时/无通道），一律不执行。
 */
typealias ToolApprovalRequester = suspend (toolId: String, description: String, risk: ToolRiskLevel) -> ApprovalDecision?

/**
 * 权限请求回调：由入口层提供（云端 = 系统权限弹窗；本地/后台 = null，缺权限直接拒绝）。
 * 返回是否已获得全部权限。
 */
typealias SkillPermissionRequester = suspend (skillId: String, skillName: String, missing: List<String>) -> Boolean
