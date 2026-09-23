package ai.openclaw.android.agent

import ai.openclaw.android.model.Tool

/**
 * 端侧（on-device）运行档位。
 *
 * 背景：真机实测 Gemma 4 E2B 的窗口只有 **8192**，而云端那份完整 system prompt
 * （[AgentSession] 里的 `BASE_SYSTEM_PROMPT`，含一整套 A2UI 协议规范 + 7 个示例 JSON）
 * 实测 **9245 token**，单个就超过窗口。再加上 60+ 个工具的 schema（2597 token 且被丢弃 34 个），
 * 对话历史只剩 512 token —— 每轮都丢历史 → KV-cache 前缀失配 → 每轮全量 prefill（实测 5~12 秒）。
 *
 * 因此端侧必须走一个「精简档」：短 system prompt + 少量工具；云端继续用完整版。
 *
 * 两个约束要一起看：
 * 1. **prompt 与工具必须同时精简**。只砍 prompt 不砍工具，工具 schema 照样吃掉三分之一窗口。
 * 2. **精简 prompt 里必须显式禁止 A2UI**。A2UI 协议规范是那段 prompt 的主体，
 *    丢掉规范却不说「不要用」，模型会照着训练习惯吐一堆残缺的 `[A2UI]...[/A2UI]`，
 *    客户端解析不出有效界面。
 */
object OnDeviceProfile {

    /**
     * 端侧精简 system prompt。目标 < 1500 token（完整版是 9245）。
     *
     * 只保留：工具调用纪律、语言一致性、输出格式（纯文本）、工具轮次上限。
     * 刻意不提 A2UI —— 提了反而会勾起模型输出它；这里直接规定「不要输出」。
     */
    const val SYSTEM_PROMPT = """You are an AI assistant running locally on an Android device. You can call tools to get real data.

## Rules
1. For real data (weather, calendar, reminders, notifications, on-device content) you MUST call a tool. Never invent facts.
2. Reply in the same language as the user.
3. Reply in PLAIN TEXT only. Do NOT emit A2UI blocks, JSON, or Markdown tables — in local mode they are not rendered as UI and will show up as garbage.
4. Call at most ONE tool per turn, then read the result before deciding whether to call another. At most 3 tool rounds total.
5. Simple greetings and chitchat need no tool calls.
6. When calling a tool, output only the tool call. Do not explain it beforehand.

## Output length
Local inference is slow. Keep answers short: 1-3 sentences unless the user asks for detail.
"""

    /**
     * 端侧工具白名单（完整名，格式 `<skillId>_<toolName>`；无障碍工具用的是裸名）。
     *
     * 选入标准：schema 小、离线或本地即可完成、值班率高。
     * 排除标准（都会造成明显代价）：
     * - **能产生通知/短信**：`notification_send_notification`、`notify_reply`、`sms_send_sms`、
     *   `notification_clear_notifications` 等 —— 它们会闭环到 `SmartNotificationListener`
     *   再触发规则引擎，是「规则自激循环」的一条通路（P0-1 已加冷却，这里从源头掐掉）。
     * - **危险或高权限**：`shell_exec`、`script_execute_script`、`file_*`、`file_xfer_*`、
     *   `camera_*`、`screen_*`、`settings_*`。
     * - **schema 巨大**：`dynamic_skill_generator_generate_skill`（整个 skill JSON 定义）、
     *   `trigger_rule_*`（7 个工具，且同样能闭环到规则引擎）。
     * - **依赖云端**：`search_search`、`location_*`。
     *
     * 想扩容就往这个集合里加，别改成「按前缀匹配」—— 那样 `device_*` 一次就放进来 8 个。
     */
    val TOOL_WHITELIST: Set<String> = setOf(
        // 只读 / 低风险、值班率最高的本机能力
        "weather_get_weather",
        "reminder_set_reminder",
        "reminder_list_reminders",
        "calendar_list_events",
        "calendar_add_event",
        "notification_list_notifications",
        "translate_translate",
        // 无障碍：留 3 个够串起「看屏幕 → 点/输入」
        "read_screen",
        "click",
        "input_text"
    )

    /**
     * 按白名单过滤工具。返回结果 + 被丢弃的工具名（便于日志核对，避免「少工具了却不知道为什么」）。
     */
    fun filterTools(tools: List<Tool>): Pair<List<Tool>, List<String>> {
        val kept = tools.filter { it.function.name in TOOL_WHITELIST }
        val keptNames = kept.map { it.function.name }.toSet()
        val dropped = tools.map { it.function.name }.filter { it !in keptNames }
        return kept to dropped
    }
}
