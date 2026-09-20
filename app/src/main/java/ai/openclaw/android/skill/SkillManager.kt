package ai.openclaw.android.skill

import ai.openclaw.android.security.AuditLogger
import ai.openclaw.android.skill.builtin.*
import android.content.Context
import android.util.Log
import okhttp3.OkHttpClient

class SkillManager(private val context: Context) {
    private val TAG = "SkillManager"
    private val loadedSkills: MutableMap<String, Skill> = mutableMapOf()
    private val httpClient = OkHttpClient()

    /**
     * 用户审批偏好（ALWAYS_APPROVE / ALWAYS_DENY 持久化）。
     * 由宿主（GatewayManager）初始化后注入；null 时审查仍生效，只是不持久化偏好。
     */
    @Volatile
    var preferenceManager: UserPreferenceManager? = null
    
    fun loadBuiltinSkills(context: Context) {
        Log.i(TAG, "Loading built-in skills...")
        
        // Register all built-in skills
        registerSkill(WeatherSkill())
        registerSkill(MultiSearchSkill())
        registerSkill(TranslateSkill())
        registerSkill(ReminderSkill(context))
        registerSkill(CalendarSkill(context))
        registerSkill(LocationSkill(context))
        registerSkill(ContactSkill(context))
        registerSkill(SMSSkill(context))
        registerSkill(NotificationSkill(context))
        registerSkill(AppLauncherSkill())
        registerSkill(SettingsSkill())
        registerSkill(FileSkill(context))
        registerSkill(ScriptSkill())

        // Node 能力 Skills (Phase 1)
        registerSkill(ScreenSkill(context))
        registerSkill(DeviceSkill(context))

        // Node 能力 Skills (Phase 2)
        registerSkill(CameraSkill(context))
        registerSkill(FileXferSkill(context))

        // Node 能力 Skills (Phase 3)
        registerSkill(ShellSkill(context))
        registerSkill(NotifySkill(context))

        Log.i(TAG, "SkillManager initialized with ${loadedSkills.size} skills")
    }
    
    fun loadBuiltinSkills() {
        // Backward compatible overload - requires context for some skills
        Log.w(TAG, "loadBuiltinSkills() called without context - skills requiring context will not be loaded")
    }
    
    fun registerSkill(skill: Skill) {
        try {
            skill.initialize(createSkillContext())
            loadedSkills[skill.id] = skill
            Log.i(TAG, "Loaded skill: ${skill.name} v${skill.version}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load skill ${skill.id}: ${e.message}")
        }
    }
    
    fun getAllTools(): List<ToolDefinition> {
        return loadedSkills.flatMap { (skillId, skill) ->
            skill.tools.map { tool ->
                ToolDefinition(
                    name = "${skillId}_${tool.name}",
                    description = tool.description,
                    parameters = tool.parameters
                )
            }
        }
    }
    
    /**
     * 统一执行入口（方案 3 安全层）—— 内置与动态技能的同一条路：
     * 解析 skill/tool → 权限门（[Skill.requiredPermissions] 唯一真源）→
     * [SecurityReview] 风险审查 → 执行 / 需审批 / 拒绝。
     *
     * 云端（AgentSession）与本地（GatewayManager.executeLocalTool）路径都经过这里，
     * 审查自动覆盖两条路径（A4）；区别只在调用方提供的回调（审批卡 UI / 确认流）。
     *
     * @param requestApproval 审批回调：SecurityReview 判定 ASK_USER 时挂起等待用户决策。
     *   null（无审批通道，如后台触发器/sync 调用）→ 返回 [ToolExecutionOutcome.NeedsApproval]，绝不静默放行。
     * @param requestPermissions 权限回调：权限缺失时调用（云端弹系统授权框）。
     *   null → 缺权限直接拒绝。
     */
    suspend fun executeTool(
        fullName: String,
        params: Map<String, Any>,
        requestApproval: ToolApprovalRequester? = null,
        requestPermissions: SkillPermissionRequester? = null
    ): ToolExecutionOutcome {
        val (skillId, toolName) = parseToolName(fullName, loadedSkills.keys)
        val skill = loadedSkills[skillId]

        if (skill == null) {
            return ToolExecutionOutcome.Done(SkillResult(false, "", "Skill not found: $skillId"))
        }

        val tool = skill.tools.find { it.name == toolName }
        if (tool == null) {
            return ToolExecutionOutcome.Done(SkillResult(false, "", "Tool not found: $toolName in skill $skillId"))
        }

        // ---- 权限门（统一行为，A4：消灭本地路径静默失败）----
        val missing = missingPermissions(skill)
        if (missing.isNotEmpty()) {
            val granted = requestPermissions?.invoke(skillId, skill.name, missing) ?: false
            if (!granted || missingPermissions(skill).isNotEmpty()) {
                return ToolExecutionOutcome.Denied(
                    "需要权限: ${missing.joinToString(", ")}。请在设置中授权后重试。"
                )
            }
        }

        // ---- 安全审查（风险分级）----
        val toolId = fullName // namespaced: skillId_toolName
        val preference = preferenceManager?.getPreference(toolId)
        val policy = SecurityReview.reviewTool(toolId, tool.riskLevel, preference)

        return when (policy) {
            ToolSecurityPolicy.AUTO_EXECUTE -> executeWithAudit(toolId, tool, params)

            ToolSecurityPolicy.DENY ->
                ToolExecutionOutcome.Denied("此操作已被用户拒绝。如需恢复，请在对话中重新发起并确认。")

            ToolSecurityPolicy.ASK_USER -> {
                val requester = requestApproval
                    // 无审批通道（后台触发器/sync 调用）→ 不执行，交调用方文本化
                    ?: return ToolExecutionOutcome.NeedsApproval(toolId, tool.description, tool.riskLevel)
                val decision = requester(toolId, tool.description, tool.riskLevel)
                when (decision) {
                    null -> ToolExecutionOutcome.Denied("用户取消了操作")
                    ApprovalDecision.ALWAYS_APPROVE -> {
                        // DANGEROUS 不持久化白名单：审查规则下一次仍会询问
                        if (tool.riskLevel != ToolRiskLevel.DANGEROUS) {
                            preferenceManager?.setPreference(toolId, ApprovalDecision.ALWAYS_APPROVE)
                        }
                        executeWithAudit(toolId, tool, params)
                    }
                    ApprovalDecision.ALWAYS_DENY -> {
                        preferenceManager?.setPreference(toolId, ApprovalDecision.ALWAYS_DENY)
                        ToolExecutionOutcome.Denied("用户已拒绝此操作")
                    }
                    ApprovalDecision.ASK_EVERY_TIME -> executeWithAudit(toolId, tool, params)
                }
            }
        }
    }

    /** DANGEROUS 工具执行前后各写一条审计（SHA-256 哈希链），其余直接执行 */
    private suspend fun executeWithAudit(
        toolId: String,
        tool: SkillTool,
        params: Map<String, Any>
    ): ToolExecutionOutcome {
        if (tool.riskLevel == ToolRiskLevel.DANGEROUS) {
            AuditLogger.log("dangerous_tool_start", 0L, "tool=$toolId")
        }
        val result = try {
            tool.execute(params)
        } catch (e: Exception) {
            Log.e(TAG, "Tool $toolId threw: ${e.message}")
            SkillResult(false, "", "工具执行异常: ${e.message}")
        }
        if (tool.riskLevel == ToolRiskLevel.DANGEROUS) {
            AuditLogger.log(
                "dangerous_tool_end", 0L,
                "tool=$toolId success=${result.success} output=${result.output.take(50)}"
            )
        }
        return ToolExecutionOutcome.Done(result)
    }

    /**
     * 检查技能所需权限（读取 [Skill.requiredPermissions]）
     * @return Pair<Boolean, String> - 第一个元素表示是否有权限，第二个元素是缺少的权限信息
     */
    fun checkSkillPermissions(skillId: String): Pair<Boolean, String> {
        val skill = loadedSkills[skillId]
        val permissions = skill?.requiredPermissions ?: return Pair(true, "")

        val missing = missingPermissions(skill)
        return if (missing.isEmpty()) Pair(true, "") else Pair(false, missing.joinToString(", "))
    }

    private fun missingPermissions(skill: Skill): List<String> {
        return skill.requiredPermissions.filter { permission ->
            androidx.core.content.ContextCompat.checkSelfPermission(context, permission) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * 获取技能声明的权限列表（来自 [Skill.requiredPermissions]，无声明返回 null）
     */
    fun getSkillRequiredPermissions(skillId: String): Array<String>? {
        val skill = loadedSkills[skillId] ?: return null
        return skill.requiredPermissions.takeIf { it.isNotEmpty() }?.toTypedArray()
    }
    
    private fun parseToolName(fullName: String, knownSkillIds: Set<String> = emptySet()): Pair<String, String> {
        // Try to match known skill IDs (longest match first) to handle IDs containing underscores
        for (skillId in knownSkillIds.sortedByDescending { it.length }) {
            if (fullName.startsWith("${skillId}_")) {
                return Pair(skillId, fullName.removePrefix("${skillId}_"))
            }
        }
        // Fallback: split on first underscore
        val parts = fullName.split("_", limit = 2)
        return if (parts.size == 2) {
            Pair(parts[0], parts[1])
        } else {
            Pair("", fullName)
        }
    }
    
    private fun createSkillContext(): SkillContext {
        return object : SkillContext {
            override val applicationContext: Context = context
            override val httpClient: OkHttpClient = this@SkillManager.httpClient
            override fun log(message: String) {
                Log.d(TAG, message)
            }
        }
    }
    
    fun getLoadedSkills(): Map<String, Skill> = loadedSkills.toMap()
    
    fun getSkillCount(): Int = loadedSkills.size

    /**
     * 注销已加载的技能
     */
    fun unregisterSkill(skillId: String) {
        val skill = loadedSkills.remove(skillId)
        skill?.cleanup()
        Log.i(TAG, "Unregistered skill: $skillId")
    }
}

data class ToolDefinition(
    val name: String,
    val description: String,
    val parameters: Map<String, SkillParam>
)