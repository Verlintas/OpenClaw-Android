package ai.openclaw.android.domain.memory

import android.util.Log
import ai.openclaw.android.data.model.MessageEntity
import ai.openclaw.android.data.model.MemoryEntity
import ai.openclaw.android.data.model.MemoryType
import ai.openclaw.android.model.LocalLLMClient
import ai.openclaw.android.model.Message
import ai.openclaw.android.util.MemoryExtractionPrompts
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.JsonElement

class LlmMemoryExtractor(private val llmClient: LocalLLMClient) : MemoryExtractorInterface {

    private val TAG = "LlmMemoryExtractor"

    override suspend fun extractFromConversation(
        messages: List<MessageEntity>
    ): Result<List<MemoryEntity>> = runCatching {
        if (messages.isEmpty()) return@runCatching emptyList()

        val conversation = messages.takeLast(10).joinToString("\n") {
            "${it.role}: ${it.content}"
        }

        // P3-2 决策门：先问一句「这段对话值不值得长期记忆」（prefill + 1 步 decode，~0.3s）。
        // 判定为「不值得」就直接跳过全量抽取 —— 既省一次生成，也避免走 chat() 旁路
        // 把主会话的 KV-cache 作废（chat() 因引擎单会话槽位必须先 closeCachedConversation）。
        // 决策本身不可用（引擎未加载/解析失败）时按「值得」处理，保持原行为。
        if (!worthRemembering(conversation)) {
            Log.i(TAG, "记忆门判定：本轮无可记忆内容，跳过抽取")
            return@runCatching emptyList()
        }

        val prompt = "${MemoryExtractionPrompts.SYSTEM_PROMPT}\n\n对话：\n$conversation"
        
        val response = llmClient.chat(
            listOf(Message(role = "user", content = prompt))
        ).getOrThrow()
        
        parseMemories(response.content ?: "")
    }
    
    /**
     * 决策门：这段对话是否含值得长期记忆的信息。
     * true = 值得（继续抽取）；false = 不值得（跳过）。
     *
     * 保守原则：决策不可用时返回 true（继续原路径），不引入新的漏记风险。
     */
    private suspend fun worthRemembering(conversation: String): Boolean {
        return try {
            val result = llmClient.decisionRunner.decide(
                ai.openclaw.android.agent.decision.DecisionRequest(
                    id = "memory_gate",
                    state = conversation.take(1200),
                    question = "这段对话里有没有值得长期记住的用户信息（偏好、事实、承诺、计划）？",
                    options = listOf(
                        "没有，纯闲聊或一次性问答，无需记忆",
                        "有，包含用户的偏好/事实/承诺/计划"
                    ),
                    stakes = ai.openclaw.android.agent.decision.DecisionStakes.LOW,
                )
            )
            // parsed=0 → 不值得；parsed=1 → 值得；无效 → 值得（保守）
            result.chosenIndex != 0
        } catch (e: Exception) {
            Log.w(TAG, "记忆决策门失败，按「值得」继续抽取: ${e.message}")
            true
        }
    }

    override suspend fun extractFromUserInput(
        content: String,
        type: MemoryType?
    ): Result<MemoryEntity> = runCatching {
        val memoryType = type ?: classifyType(content)
        val priority = if (content.contains("重要") || content.contains("必须")) 5 else 3
        
        MemoryEntity(
            content = content,
            memoryType = memoryType,
            priority = priority,
            source = "manual",
            tags = extractTags(content),
            createdAt = System.currentTimeMillis(),
            lastAccessedAt = System.currentTimeMillis()
        )
    }
    
    private fun parseMemories(json: String): List<MemoryEntity> {
        return try {
            // 提取 JSON 部分
            val jsonStart = json.indexOf("{")
            val jsonEnd = json.lastIndexOf("}") + 1
            if (jsonStart < 0 || jsonEnd <= jsonStart) return emptyList()
            
            val cleanJson = json.substring(jsonStart, jsonEnd)
            val jsonObject = Json.parseToJsonElement(cleanJson).jsonObject
            val memoriesArray = jsonObject["memories"]?.jsonArray ?: return emptyList()
            
            memoriesArray.map { memoryElement ->
                val memoryObj = memoryElement.jsonObject
                MemoryEntity(
                    content = memoryObj["content"]?.jsonPrimitive?.content ?: "",
                    memoryType = MemoryType.valueOf(memoryObj["type"]?.jsonPrimitive?.content ?: "FACT"),
                    priority = memoryObj["priority"]?.jsonPrimitive?.content?.toIntOrNull() ?: 3,
                    source = "auto",
                    tags = memoryObj["tags"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList(),
                    createdAt = System.currentTimeMillis(),
                    lastAccessedAt = System.currentTimeMillis()
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
    
    private fun classifyType(content: String): MemoryType {
        return when {
            content.contains("喜欢") || content.contains("偏好") -> MemoryType.PREFERENCE
            content.contains("明天") || content.contains("记得") -> MemoryType.TASK
            content.contains("决定") || content.contains("选择") -> MemoryType.DECISION
            content.contains("项目") || content.contains("路径") -> MemoryType.PROJECT
            else -> MemoryType.FACT
        }
    }
    
    private fun extractTags(content: String): List<String> {
        val tags = mutableListOf<String>()
        if (content.contains("项目")) tags.add("项目")
        if (content.contains("工作")) tags.add("工作")
        if (content.contains("个人")) tags.add("个人")
        return tags
    }
}