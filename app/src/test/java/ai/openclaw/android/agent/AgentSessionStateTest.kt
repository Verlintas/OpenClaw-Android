package ai.openclaw.android.agent

import ai.openclaw.android.model.ChatEvent
import ai.openclaw.android.model.Choice
import ai.openclaw.android.model.Message
import ai.openclaw.android.model.ModelClient
import ai.openclaw.android.model.ModelResponse
import ai.openclaw.android.model.ResponseMessage
import ai.openclaw.android.model.ToolCall
import ai.openclaw.android.model.ToolCallFunction
import ai.openclaw.android.permission.PermissionManager
import ai.openclaw.android.skill.SkillManager
import ai.openclaw.android.skill.SkillResult
import android.util.Log
import io.mockk.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * PR-1: AgentSession 单一状态源 + 取消语义测试。
 *
 * 覆盖：并发轮次串行化（无丢失）、trim 原子块、孤儿 tool 修复、
 * estimateTokens 计入 toolCalls、工具循环检测、流式中断后的部分状态提交。
 */
class AgentSessionStateTest {

    /**
     * 手写 fake：chat 返回 Result<ModelResponse>，MockK 对 suspend + Result
     * 返回类型的 stub 存在双层包装问题（Result.success 里再装 Result），
     * 手写实现零歧义。
     */
    private class FakeModelClient : ModelClient {
        var chatResponder: (List<Message>) -> Result<ModelResponse> =
            { Result.success(ModelResponse()) }
        var streamResponder: (List<Message>) -> ModelResponse? = { null }

        override fun configure(
            provider: ai.openclaw.android.model.ModelProvider,
            apiKey: String,
            model: String,
            baseUrl: String
        ) { /* no-op */ }

        override suspend fun chat(
            messages: List<Message>,
            tools: List<ai.openclaw.android.model.Tool>?
        ): Result<ModelResponse> = chatResponder(messages)

        override fun chatStream(
            messages: List<Message>,
            tools: List<ai.openclaw.android.model.Tool>?
        ): Flow<ChatEvent> = flow {
            val response = streamResponder(messages)
            if (response != null) emit(ChatEvent.Complete(response))
        }
    }

    private lateinit var fakeModelClient: FakeModelClient
    private lateinit var mockSkillManager: SkillManager

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.i(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0

        fakeModelClient = FakeModelClient()
        mockSkillManager = mockk(relaxed = true)

        // Skill 路径通用桩
        every { mockSkillManager.getLoadedSkills() } returns mapOf("weather" to mockk(relaxed = true))
        every { mockSkillManager.checkSkillPermissions(any()) } returns Pair(true, "")
        coEvery { mockSkillManager.executeTool(any(), any()) } returns SkillResult(success = true, output = "ok")
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    // ==================== Helpers ====================

    private fun textResponse(content: String): ModelResponse = ModelResponse(
        choices = listOf(Choice(message = ResponseMessage(role = "assistant", content = content)))
    )

    private fun toolCallResponse(vararg calls: ToolCall): ModelResponse = ModelResponse(
        choices = listOf(Choice(message = ResponseMessage(role = "assistant", toolCalls = calls.toList())))
    )

    private fun call(id: String, name: String = "weather_get", args: String = """{"city":"北京"}"""): ToolCall =
        ToolCall(id = id, function = ToolCallFunction(name = name, arguments = args))

    private fun createSession(): AgentSession = AgentSession(
        modelClient = fakeModelClient,
        skillManager = mockSkillManager,
        permissionManager = mockk(relaxed = true)
    )

    // ==================== 并发：整轮持锁 ====================

    @Test
    fun `concurrent sync turns serialize without losing messages`() = runBlocking(Dispatchers.Default) {
        fakeModelClient.chatResponder = { Result.success(textResponse("ok")) }

        val session = createSession()
        val jobs = (1..2).flatMap { i ->
            (1..10).map { j ->
                launch { session.handleMessage("m$i-$j") }
            }
        }
        jobs.forEach { it.join() }

        val history = session.getHistory()
        assertEquals("20 turns × (user + assistant) = 40 messages", 40, history.size)

        // 每一轮 user→assistant 交替，无交错
        history.chunked(2).forEachIndexed { turn, pair ->
            assertEquals("turn $turn first message must be user", "user", pair[0].role)
            assertEquals("turn $turn second message must be assistant", "assistant", pair[1].role)
        }

        // 无消息丢失：20 个不同的用户消息全部在场
        val users = history.filter { it.role == "user" }.map { it.content }.toSet()
        assertEquals(20, users.size)
        (1..2).forEach { i -> (1..10).forEach { j -> assertTrue(users.contains("m$i-$j")) } }
    }

    // ==================== trim：原子块 ====================

    @Test
    fun `trimHistory keeps tool call pairs atomic`() {
        val session = createSession()
        val big = "字".repeat(200) // ~260 tokens per message

        val history = (1..8).flatMap {
            listOf(
                Message(role = "user", content = big),
                Message(role = "assistant", content = "ok")
            )
        } + listOf(
            Message(role = "user", content = "hi"),
            Message(role = "assistant", content = "", toolCalls = listOf(call("c1"), call("c2"))),
            Message(role = "tool", content = "r1", toolCallId = "c1"),
            Message(role = "tool", content = "r2", toolCallId = "c2")
        )
        assertEquals(20, history.size)

        val trimmed = session.trimHistory(history, maxTokens = 1000)

        assertTrue("trim must remove something", trimmed.size < history.size)
        assertNotEquals("result must not start with an orphan tool message", "tool", trimmed.first().role)

        // 遍历验证：每个 tool 消息都归属于其前方的 assistant(tool_calls) 块
        var i = 0
        while (i < trimmed.size) {
            val msg = trimmed[i]
            if (msg.role == "assistant" && !msg.toolCalls.isNullOrEmpty()) {
                val ids = msg.toolCalls.map { it.id }.toSet()
                var j = i + 1
                while (j < trimmed.size && trimmed[j].role == "tool") {
                    assertTrue("tool ${trimmed[j].toolCallId} must belong to preceding assistant block", ids.contains(trimmed[j].toolCallId))
                    j++
                }
                i = j
            } else {
                if (msg.role == "tool") {
                    fail("orphan tool message at index $i (toolCallId=${msg.toolCallId})")
                }
                i++
            }
        }

        // 尾部的完整 tool-call 块不被拆散
        assertEquals("c1", trimmed[trimmed.size - 2].toolCallId)
        assertEquals("c2", trimmed[trimmed.size - 1].toolCallId)
    }

    @Test
    fun `trimHistory returns input unchanged when under budget`() {
        val session = createSession()
        val history = listOf(
            Message(role = "user", content = "hi"),
            Message(role = "assistant", content = "hello")
        )
        assertSame(history, session.trimHistory(history, maxTokens = 8000))
    }

    // ==================== repair：孤儿 tool 修复 ====================

    @Test
    fun `repairTrailingToolBlock synthesizes missing tool results`() {
        val session = createSession()
        val history = listOf(
            Message(role = "user", content = "hi"),
            Message(role = "assistant", content = "", toolCalls = listOf(call("c1"), call("c2"), call("c3"))),
            Message(role = "tool", content = "r1", toolCallId = "c1") // c2/c3 未执行（中途取消）
        )

        val repaired = session.repairTrailingToolBlock(history)

        assertEquals(5, repaired.size)
        assertEquals(listOf("c2", "c3"), repaired.takeLast(2).map { it.toolCallId })
        repaired.takeLast(2).forEach {
            assertEquals("tool", it.role)
            assertTrue(it.content.contains("取消"))
        }
    }

    @Test
    fun `repairTrailingToolBlock is a no-op for complete history`() {
        val session = createSession()
        val history = listOf(
            Message(role = "user", content = "hi"),
            Message(role = "assistant", content = "done")
        )
        assertSame(history, session.repairTrailingToolBlock(history))
    }

    // ==================== estimateTokens：计入 toolCalls ====================

    @Test
    fun `estimateTokens counts toolCall name and arguments`() {
        val session = createSession()
        val longArgs = """{"city":"${"北".repeat(100)}"}""" // ~100 CJK chars in args

        val withToolCalls = listOf(
            Message(role = "assistant", content = "", toolCalls = listOf(call("c1", args = longArgs)))
        )
        val empty = listOf(Message(role = "assistant", content = ""))

        assertEquals(0, session.estimateTokens(empty))
        assertTrue(
            "toolCall arguments must contribute tokens (>100 expected)",
            session.estimateTokens(withToolCalls) > 100
        )
    }

    // ==================== 工具循环检测 ====================

    @Test
    fun `repeated identical tool calls terminate the turn`() = runBlocking {
        // LLM 永远返回同一个 toolCall（同 id/同参数）
        fakeModelClient.chatResponder = {
            Result.success(toolCallResponse(call("loop1", args = """{"city":"北京"}""")))
        }

        val session = createSession()
        val reply = session.handleMessage("查天气")

        assertTrue("reply should mention the loop termination: $reply", reply.contains("重复调用"))
        // 第 3 次同调用触发阈值（≥3），实际只执行前 2 次
        coVerify(exactly = 2) { mockSkillManager.executeTool(any(), any()) }

        // 状态落盘：循环终止消息在历史中，且无孤儿 tool 消息
        val history = session.getHistory()
        assertEquals("user", history.first().role)
        assertTrue(history.last().role == "assistant" && history.last().content.contains("重复调用"))
    }

    // ==================== 取消：部分状态提交 ====================

    @Test
    fun `cancelled stream commits partial state without orphan tool messages`() = runBlocking(Dispatchers.Default) {
        // LLM 返回 2 个 toolCall，工具执行挂起模拟长任务
        fakeModelClient.streamResponder = { toolCallResponse(call("c1"), call("c2")) }
        coEvery { mockSkillManager.executeTool(any(), any()) } coAnswers { awaitCancellation() }

        val session = createSession()
        val toolExecuting = CompletableDeferred<Unit>()
        val job = launch {
            session.handleMessageStream("查天气").collect { event ->
                if (event is SessionEvent.ToolExecuting) toolExecuting.complete(Unit)
            }
        }

        toolExecuting.await()
        job.cancelAndJoin()

        // finally + NonCancellable 写回：user 消息不丢，孤儿 toolCall 补合成结果
        val history = session.getHistory()
        assertEquals(
            listOf("user", "assistant", "tool", "tool"),
            history.map { it.role }
        )
        assertEquals(listOf("c1", "c2"), history.takeLast(2).map { it.toolCallId })
        history.takeLast(2).forEach { assertTrue(it.content.contains("取消")) }
    }

    @Test
    fun `stream turn completes and commits normally`() = runBlocking(Dispatchers.Default) {
        fakeModelClient.streamResponder = { textResponse("你好") }

        val session = createSession()
        val events = mutableListOf<SessionEvent>()
        session.handleMessageStream("hi").collect { events.add(it) }

        assertTrue(events.last() is SessionEvent.Complete)
        assertEquals("你好", (events.last() as SessionEvent.Complete).fullText)

        val history = session.getHistory()
        assertEquals(listOf("user", "assistant"), history.map { it.role })
        assertEquals("你好", history.last().content)
    }

    // ==================== 并发：sync + streaming 混合入口 ====================

    @Test
    fun `mixed sync and streaming turns do not interleave`() = runBlocking(Dispatchers.Default) {
        fakeModelClient.chatResponder = { Result.success(textResponse("sync-reply")) }
        fakeModelClient.streamResponder = { textResponse("stream-reply") }

        val session = createSession()
        val jobs = listOf(
            launch { session.handleMessage("sync-1") },
            launch { session.handleMessageStream("stream-1").collect { } },
            launch { session.handleMessage("sync-2") }
        )
        jobs.forEach { it.join() }

        // 3 轮 × 2 条，严格 user→assistant 交替
        val history = session.getHistory()
        assertEquals(6, history.size)
        history.chunked(2).forEachIndexed { turn, pair ->
            assertEquals("user", pair[0].role)
            assertEquals("assistant", pair[1].role)
        }
        val replies = setOf("sync-reply", "stream-reply")
        history.filter { it.role == "assistant" }.forEach { assertTrue(replies.contains(it.content)) }
    }
}
