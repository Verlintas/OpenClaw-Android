package ai.openclaw.android.skill

import ai.openclaw.script.ScriptOrchestrator
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

/**
 * 动态技能 — 由 LLM 生成、JS 脚本实现的 Skill
 *
 * 通过 fromJson() 从 LLM 返回的 JSON 创建，脚本由 ScriptOrchestrator 执行。
 * 安全审查（风险分级 + 用户审批）已上提到 [SkillManager.executeTool] 统一入口，
 * 本类只负责脚本执行与使用时间戳更新。
 *
 * @param onUsed 工具被调用时的回调（用于更新 lastUsedAt）
 */
class DynamicSkill(
    override val id: String,
    override val name: String,
    override val description: String,
    override val version: String,
    override val instructions: String,
    val script: String,
    toolDefs: List<DynamicToolDef>,
    private val orchestrator: ScriptOrchestrator,
    private val onUsed: (() -> Unit)? = null
) : Skill {

    override val tools: List<SkillTool> = toolDefs.map { def ->
        DynamicTool(id, def, script, orchestrator, onUsed)
    }

    override fun initialize(context: SkillContext) {
        // No-op — JS engine is managed by ScriptOrchestrator
    }

    override fun cleanup() {
        // No-op — ScriptOrchestrator lifecycle is external
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /**
         * 从 JSON 字符串创建 DynamicSkill
         */
        fun fromJson(
            jsonStr: String,
            orchestrator: ScriptOrchestrator,
            onUsed: (() -> Unit)? = null
        ): DynamicSkill {
            val element = json.parseToJsonElement(jsonStr).jsonObject
            val id = element["id"]?.jsonPrimitive?.content
                ?: throw IllegalArgumentException("Missing 'id'")
            val name = element["name"]?.jsonPrimitive?.content
                ?: throw IllegalArgumentException("Missing 'name'")
            val description = element["description"]?.jsonPrimitive?.content
                ?: throw IllegalArgumentException("Missing 'description'")
            val version = element["version"]?.jsonPrimitive?.content ?: "1.0.0"
            val instructions = element["instructions"]?.jsonPrimitive?.content ?: ""
            val script = element["script"]?.jsonPrimitive?.content
                ?: throw IllegalArgumentException("Missing 'script'")
            val toolsArray = element["tools"]?.jsonArray
                ?: throw IllegalArgumentException("Missing 'tools'")

            val toolDefs = toolsArray.map { toolElement ->
                val toolObj = toolElement.jsonObject
                val toolName = toolObj["name"]?.jsonPrimitive?.content
                    ?: throw IllegalArgumentException("Tool missing 'name'")
                val toolDesc = toolObj["description"]?.jsonPrimitive?.content ?: ""
                val entryPoint = toolObj["entryPoint"]?.jsonPrimitive?.content ?: toolName
                val isIdempotent = toolObj["idempotent"]?.jsonPrimitive?.booleanOrNull ?: false

                val paramsObj = toolObj["parameters"]?.jsonObject ?: JsonObject(emptyMap())
                val parameters = paramsObj.mapValues { (_, v) ->
                    val pObj = v.jsonObject
                    SkillParam(
                        type = pObj["type"]?.jsonPrimitive?.content ?: "string",
                        description = pObj["description"]?.jsonPrimitive?.content ?: "",
                        required = pObj["required"]?.jsonPrimitive?.booleanOrNull ?: false,
                        default = pObj["default"]?.jsonPrimitive?.content
                    )
                }

                DynamicToolDef(
                    name = toolName,
                    description = toolDesc,
                    parameters = parameters,
                    entryPoint = entryPoint,
                    isIdempotent = isIdempotent
                )
            }

            return DynamicSkill(
                id, name, description, version, instructions, script, toolDefs, orchestrator,
                onUsed
            )
        }
    }
}

/**
 * 工具定义（从 JSON 解析）
 */
data class DynamicToolDef(
    val name: String,
    val description: String,
    val parameters: Map<String, SkillParam>,
    val entryPoint: String,
    val isIdempotent: Boolean = false
)

/**
 * 动态工具实现 — 将 SkillTool 调用路由到 ScriptOrchestrator
 *
 * 安全审查已上提到 [SkillManager.executeTool]；风险等级由 `def.isIdempotent` 映射：
 * 幂等（纯计算/读取）→ [ToolRiskLevel.READ]，否则 [ToolRiskLevel.WRITE]。
 *
 * @param skillId 所属技能 ID
 * @param onUsed 工具被调用时的回调（用于更新 lastUsedAt）
 */
class DynamicTool(
    private val skillId: String,
    private val def: DynamicToolDef,
    private val script: String,
    private val orchestrator: ScriptOrchestrator,
    private val onUsed: (() -> Unit)? = null
) : SkillTool {

    /**
     * 兼容旧版构造函数
     */
    constructor(
        def: DynamicToolDef,
        script: String,
        orchestrator: ScriptOrchestrator
    ) : this("", def, script, orchestrator, null)

    override val name: String = def.name
    override val description: String = def.description
    override val parameters: Map<String, SkillParam> = def.parameters

    /** 幂等（纯读取/计算）映射 READ，保持与旧审查行为一致 */
    override val riskLevel: ToolRiskLevel =
        if (def.isIdempotent) ToolRiskLevel.READ else ToolRiskLevel.WRITE

    override suspend fun execute(params: Map<String, Any>): SkillResult {
        onUsed?.invoke()
        return executeScript(params)
    }

    private suspend fun executeScript(params: Map<String, Any>): SkillResult {
        val paramsJson = buildJsonObject {
            params.forEach { (key, value) ->
                when (value) {
                    is String -> put(key, value)
                    is Number -> put(key, value)
                    is Boolean -> put(key, value)
                    else -> put(key, value.toString())
                }
            }
        }.toString()

        val callScript = buildString {
            appendLine(script)
            appendLine()
            appendLine("${def.entryPoint}($paramsJson)")
        }

        val result = orchestrator.execute(callScript, listOf("fs", "http"))
        return if (result.success) {
            SkillResult(success = true, output = result.output)
        } else {
            SkillResult(success = false, output = "", error = result.error ?: "Script execution failed")
        }
    }
}
