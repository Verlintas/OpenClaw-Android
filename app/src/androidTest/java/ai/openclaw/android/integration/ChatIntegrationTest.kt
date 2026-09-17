package ai.openclaw.android.integration

import ai.openclaw.android.ChatMessage
import ai.openclaw.android.GatewayContract
import ai.openclaw.android.agent.SessionEvent
import ai.openclaw.android.gateway.MockGateway
import ai.openclaw.android.gateway.MockScenario
import ai.openclaw.android.viewmodel.ChatViewModel
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Chat 集成测试
 *
 * 验证端到端消息渲染流程：
 * 1. MockGateway 返回响应
 * 2. ChatViewModel 处理响应
 * 3. StateFlow 更新
 * 4. 验证完整链路
 *
 * 使用 MockK 模拟 ViewModel 的复杂依赖，聚焦于网关→ViewModel→StateFlow 的消息流。
 * 使用 TestDispatcher 控制协程时序。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatIntegrationTest {

    private val testDispatcher: TestDispatcher = StandardTestDispatcher()
    private lateinit var viewModel: ChatViewModel
    private lateinit var mockGateway: MockGateway

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

        viewModel = ChatViewModel()

        // Set up MockGateway
        mockGateway = MockGateway(MockScenario.PlainText)
        mockGateway.responseDelayMs = 0
        viewModel.setMessageGateway(mockGateway)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ==================== 正常文本回复流程 ====================

    @Test
    fun `plain_text_reply_flow_user_message_and_AI_response_added_to_messages`() = runTest {
        mockGateway.setScenario(MockScenario.PlainText)
        mockGateway.responseDelayMs = 0

        viewModel.sendMessage("你好")
        testDispatcher.scheduler.advanceUntilIdle()

        val messages = viewModel.messages.first()
        assertEquals(2, messages.size)
        assertEquals("user", messages[0].role)
        assertEquals("你好", messages[0].content)
        assertEquals("assistant", messages[1].role)
        // The assistant message should contain the complete response
        assertTrue(messages[1].content.contains("OpenClaw"))
    }

    @Test
    fun `isLoading_becomes_true_then_false_during_text_reply`() = runTest {
        mockGateway.setScenario(MockScenario.PlainText)
        mockGateway.responseDelayMs = 50

        assertFalse(viewModel.isLoading.value)

        viewModel.sendMessage("测试")
        // isLoading should be true immediately
        assertTrue(viewModel.isLoading.value)

        testDispatcher.scheduler.advanceUntilIdle()

        // After completion, isLoading should be false
        assertFalse(viewModel.isLoading.value)
    }

    // ==================== A2UI 卡片渲染流程 ====================

    @Test
    fun `weather_card_flow_A2UI_markup_in_response`() = runTest {
        mockGateway.setScenario(MockScenario.WeatherCard)
        mockGateway.responseDelayMs = 0

        viewModel.sendMessage("西安天气")
        testDispatcher.scheduler.advanceUntilIdle()

        val messages = viewModel.messages.first()
        assertEquals(2, messages.size)
        val aiMessage = messages[1]
        assertEquals("assistant", aiMessage.role)
        // Response should contain A2UI weather card markup
        assertTrue(aiMessage.content.contains("[A2UI]"))
        assertTrue(aiMessage.content.contains("天气"))
    }

    @Test
    fun `search_card_flow_A2UI_search_results`() = runTest {
        mockGateway.setScenario(MockScenario.SearchCard)
        mockGateway.responseDelayMs = 0

        viewModel.sendMessage("搜索 OpenClaw")
        testDispatcher.scheduler.advanceUntilIdle()

        val messages = viewModel.messages.first()
        val aiMessage = messages[1]
        assertTrue(aiMessage.content.contains("[A2UI]"))
        assertTrue(aiMessage.content.contains("搜索结果"))
    }

    @Test
    fun `error_card_flow_A2UI_error_card`() = runTest {
        mockGateway.setScenario(MockScenario.ErrorCard)
        mockGateway.responseDelayMs = 0

        viewModel.sendMessage("触发错误")
        testDispatcher.scheduler.advanceUntilIdle()

        val messages = viewModel.messages.first()
        val aiMessage = messages[1]
        assertTrue(aiMessage.content.contains("[A2UI]"))
        assertTrue(aiMessage.content.contains("操作失败"))
    }

    // ==================== 工具调用流程 ====================

    @Test
    fun `tool_calling_flow_includes_tool_execution_markers`() = runTest {
        mockGateway.setScenario(MockScenario.MixedContent)
        mockGateway.responseDelayMs = 0

        viewModel.sendMessage("搜索信息")
        testDispatcher.scheduler.advanceUntilIdle()

        val messages = viewModel.messages.first()
        val aiMessage = messages[1]
        // Tool calling should include tool execution markers
        assertTrue(aiMessage.content.contains("调用工具"))
        assertTrue(aiMessage.content.contains("search_web"))
        // Should also have A2UI content
        assertTrue(aiMessage.content.contains("[A2UI]"))
    }

    // ==================== 错误处理流程 ====================

    @Test
    fun `error_flow_gateway_error_handled_gracefully`() = runTest {
        mockGateway.setScenario(MockScenario.Error)
        mockGateway.responseDelayMs = 0

        viewModel.sendMessage("触发网络错误")
        testDispatcher.scheduler.advanceUntilIdle()

        val messages = viewModel.messages.first()
        assertEquals(2, messages.size)
        val aiMessage = messages[1]
        // Should contain error message
        assertTrue(aiMessage.content.contains("错误"))
        // isLoading should be false after error
        assertFalse(viewModel.isLoading.value)
    }

    @Test
    fun `timeout_flow_tokens_then_error`() = runTest {
        mockGateway.setScenario(MockScenario.Timeout)
        mockGateway.responseDelayMs = 0

        viewModel.sendMessage("超时测试")
        testDispatcher.scheduler.advanceUntilIdle()

        val messages = viewModel.messages.first()
        val aiMessage = messages[1]
        // Should have accumulated tokens before error
        assertTrue(aiMessage.content.isNotEmpty())
        // Should have error handling
        assertTrue(aiMessage.content.contains("超时") || aiMessage.content.contains("错误"))
        assertFalse(viewModel.isLoading.value)
    }

    // ==================== JSONL A2UI 流程 ====================

    @Test
    fun `JSONL_A2UI_flow_JSONL_format_response`() = runTest {
        mockGateway.setScenario(MockScenario.JsonlA2UI)
        mockGateway.responseDelayMs = 0

        viewModel.sendMessage("JSONL 测试")
        testDispatcher.scheduler.advanceUntilIdle()

        val messages = viewModel.messages.first()
        val aiMessage = messages[1]
        assertTrue(aiMessage.content.contains("[A2UI]"))
        assertTrue(aiMessage.content.contains("v0.10"))
        assertTrue(aiMessage.content.contains("JSONL"))
    }

    // ==================== 测试模式切换流程 ====================

    @Test
    fun `switch_from_plain_text_to_error_scenario_at_runtime`() = runTest {
        // Start with plain text
        mockGateway.setScenario(MockScenario.PlainText)
        mockGateway.responseDelayMs = 0

        viewModel.sendMessage("第一句")
        testDispatcher.scheduler.advanceUntilIdle()

        val messagesAfter1 = viewModel.messages.first()
        assertEquals(2, messagesAfter1.size)

        // Switch to error scenario
        mockGateway.setScenario(MockScenario.Error)

        viewModel.sendMessage("第二句")
        testDispatcher.scheduler.advanceUntilIdle()

        val messagesAfter2 = viewModel.messages.first()
        assertEquals(4, messagesAfter2.size)
        // Last message should be an error
        assertTrue(messagesAfter2[3].content.contains("错误"))
    }

    // ==================== Multiple messages flow ====================

    @Test
    fun `multiple_sequential_messages_accumulate_correctly`() = runTest {
        mockGateway.setScenario(MockScenario.PlainText)
        mockGateway.responseDelayMs = 0

        viewModel.sendMessage("消息1")
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.sendMessage("消息2")
        testDispatcher.scheduler.advanceUntilIdle()

        val messages = viewModel.messages.first()
        assertEquals(4, messages.size)
        // user1, ai1, user2, ai2
        assertEquals("user", messages[0].role)
        assertEquals("assistant", messages[1].role)
        assertEquals("user", messages[2].role)
        assertEquals("assistant", messages[3].role)
    }

    // ==================== Clear history ====================

    @Test
    fun `clearHistory_resets_messages_to_empty`() = runTest {
        mockGateway.setScenario(MockScenario.PlainText)
        mockGateway.responseDelayMs = 0

        viewModel.sendMessage("消息")
        testDispatcher.scheduler.advanceUntilIdle()

        val messagesBefore = viewModel.messages.first()
        assertEquals(2, messagesBefore.size)

        viewModel.clearHistory()

        val messagesAfter = viewModel.messages.first()
        assertEquals(0, messagesAfter.size)
    }

    // ==================== Empty message handling ====================

    @Test
    fun `sending_empty_text_still_adds_user_message`() = runTest {
        mockGateway.setScenario(MockScenario.PlainText)
        mockGateway.responseDelayMs = 0

        viewModel.sendMessage("")
        testDispatcher.scheduler.advanceUntilIdle()

        val messages = viewModel.messages.first()
        // Empty message should still be added
        assertEquals(2, messages.size)
        assertEquals("", messages[0].content)
    }

    // ==================== isReady check ====================

    @Test
    fun `MockGateway_isReady_returns_true_allows_message_sending`() = runTest {
        mockGateway.setScenario(MockScenario.PlainText)
        mockGateway.responseDelayMs = 0

        assertTrue(mockGateway.isReady())

        viewModel.sendMessage("测试")
        testDispatcher.scheduler.advanceUntilIdle()

        val messages = viewModel.messages.first()
        assertEquals(2, messages.size)
    }
}
