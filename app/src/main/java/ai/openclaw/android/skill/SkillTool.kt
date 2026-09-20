package ai.openclaw.android.skill

/**
 * 工具风险等级（方案 3 统一安全层）
 *
 * - [READ] — 只读无副作用（查询/搜索/读屏等），直通执行
 * - [WRITE] — 有副作用但可逆（设置提醒/写文件/点击等），按用户偏好决定是否询问
 * - [DANGEROUS] — 不可逆或对外造成影响（shell/发短信/拨号/传输文件等），每次都询问 + 审计
 */
enum class ToolRiskLevel { READ, WRITE, DANGEROUS }

interface SkillTool {
    val name: String
    val description: String
    val parameters: Map<String, SkillParam>

    /** 风险等级，默认保守为 [ToolRiskLevel.WRITE]（首次使用需用户确认） */
    val riskLevel: ToolRiskLevel get() = ToolRiskLevel.WRITE

    suspend fun execute(params: Map<String, Any>): SkillResult
}

data class SkillParam(
    val type: String,  // "string" | "number" | "boolean"
    val description: String,
    val required: Boolean,
    val default: Any? = null
)

data class SkillResult(
    val success: Boolean,
    val output: String,
    val error: String? = null
)