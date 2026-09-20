package ai.openclaw.android.skill

interface Skill {
    val id: String
    val name: String
    val description: String
    val version: String
    val instructions: String  // Markdown format, equivalent to SKILL.md
    val tools: List<SkillTool>

    /**
     * 技能运行所需的 Android 运行时权限（唯一真源，修 N1 两份硬编码表不同步问题）。
     * [SkillManager] 执行前统一检查；特殊权限（通知监听/MediaProjection 等）不在此声明。
     */
    val requiredPermissions: List<String> get() = emptyList()

    fun initialize(context: SkillContext)
    fun cleanup()
}