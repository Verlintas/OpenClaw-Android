package ai.openclaw.android.agent

import android.util.Log
import ai.openclaw.android.LogManager
import ai.openclaw.android.util.CrashRecord
import com.tencent.bugly.crashreport.CrashReport
import ai.openclaw.android.data.model.AgentConfig
import ai.openclaw.android.data.model.MessageRole
import ai.openclaw.android.domain.AgentResponse
import ai.openclaw.android.domain.DeviceCapabilities
import ai.openclaw.android.domain.ReflectionConfig
import ai.openclaw.android.domain.ReflectionResult
import ai.openclaw.android.domain.ReflectionRole
import ai.openclaw.android.domain.ReflectionStrategy
import ai.openclaw.android.domain.ReflectionUtils
import kotlinx.coroutines.withTimeoutOrNull
import ai.openclaw.android.domain.ResponseRouter
import ai.openclaw.android.domain.session.HybridSessionManager
import ai.openclaw.android.model.*
import ai.openclaw.android.permission.PermissionManager
import ai.openclaw.android.skill.ApprovalDecision
import ai.openclaw.android.skill.SkillManager
import ai.openclaw.android.skill.SkillParam
import ai.openclaw.android.skill.ToolExecutionOutcome
import ai.openclaw.android.skill.ToolRiskLevel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * AgentSession - Manages conversation context and model interactions
 *
 * Supports both synchronous (chat) and streaming (chatStream) modes.
 * Uses native function calling instead of text-based [TOOL_CALL] parsing.
 */
class AgentSession(
    private val modelClient: ModelClient,
    private val skillManager: SkillManager,
    private val permissionManager: PermissionManager? = null,
    private val maxContextTokens: Int = 8000
) {
    // Agent-specific fields (mutable backing, exposed via factory constructor)
    private var _agentConfig: AgentConfig? = null
    // Tool prefixes to allow (e.g. ["weather", "script"]), null = all tools allowed
    private var _allowedToolPrefixes: List<String>? = null
    // Reflection config for multi-round self-improvement
    private var _reflectionConfig: ReflectionConfig? = null

    // Device capabilities for response routing
    private var deviceCapabilities: DeviceCapabilities? = null
    private var responseRouter: ResponseRouter? = null

    /**
     * 是否运行在「端侧档位」（本地模型）。
     *
     * 端侧窗口实测只有 8192，而完整 system prompt 是 9245 token —— 必须换成
     * [OnDeviceProfile.SYSTEM_PROMPT] 精简版，否则每轮都要截断，历史前缀随之变化，
     * KV-cache 复用失效、每轮全量 prefill。工具也要同步收敛到白名单
     * （[OnDeviceProfile.TOOL_WHITELIST]），光砍 prompt 不砍工具没用。
     *
     * 由 [ai.openclaw.android.domain.agent.AgentSessionManager] 在识别出 modelClient 是
     * LocalLLMClient 后调用，必须早于 `setToolsWithSkills()`。
     */
    @Volatile
    var onDeviceMode: Boolean = false
        private set

    fun setOnDeviceMode(enabled: Boolean) {
        onDeviceMode = enabled
        Log.i(TAG, "On-device profile ${if (enabled) "ENABLED" else "disabled"}")
    }

    /**
     * Set device capabilities for response routing.
     * Call this after initialization to enable LLM format decisions.
     */
    fun setDeviceCapabilities(capabilities: DeviceCapabilities) {
        deviceCapabilities = capabilities
        responseRouter = ResponseRouter(capabilities)
        Log.d(TAG, "Device capabilities set: profile=${capabilities.profile}")
    }

    /**
     * Get the response router (if device capabilities are set).
     * Returns null if capabilities haven't been configured yet.
     */
    fun getResponseRouter(): ResponseRouter? = responseRouter

    /**
     * Factory constructor — creates an AgentSession with agent-specific config.
     * Supports tool filtering and custom system prompt prepending.
     */
    constructor(
        modelClient: ModelClient,
        skillManager: SkillManager,
        agentConfig: AgentConfig,
        permissionManager: PermissionManager? = null,
        maxContextTokens: Int = 8000
    ) : this(modelClient, skillManager, permissionManager, maxContextTokens) {
        // Tool filtering: null means all tools allowed, otherwise store prefixes
        _allowedToolPrefixes = if (agentConfig.tools.contains("all")) null else {
            agentConfig.tools
        }
        // [FIX] Mirror the agentConfig to the legacy `agentConfig` field so
        // getMaxContextTokens() (which reads from that field) sees the same
        // value the factory constructor just assigned to _agentConfig.
        // Previously the two fields were set independently, causing trim to
        // never trigger in production because the read field stayed null.
        _agentConfig = agentConfig
        this.agentConfig = agentConfig
        // Auto-select reflection strategy based on agent config
        _reflectionConfig = ReflectionConfig.defaultFor(parseReflectionStrategy(agentConfig.reflectionStrategy))
    }

    /**
     * Set reflection config for multi-round self-improvement.
     * Call this to override the auto-selected strategy.
     */
    fun setReflectionConfig(config: ReflectionConfig) {
        _reflectionConfig = config
        Log.d(TAG, "Reflection config set: strategy=${config.strategy}, timeout=${config.timeoutMs}ms")
    }

    /**
     * Set reflection strategy (shorthand).
     */
    fun setReflectionStrategy(strategy: ReflectionStrategy) {
        _reflectionConfig = ReflectionConfig.defaultFor(strategy)
        Log.d(TAG, "Reflection strategy set: $strategy")
    }
    companion object {
        private const val TAG = "AgentSession"
        private const val MAX_TOOL_ROUNDS = 15

        /** 运行时权限弹窗等待上限（超时按未授权处理，防止会话永久挂起） */
        private const val PERMISSION_REQUEST_TIMEOUT_MS = 60_000L
        /**
         * 工具审批等待上限（A3 修订：审批不可达时挂起等待用户回来决策，而非自动批准/秒拒）。
         * 10 分钟内未决策 → 视为取消；DANGEROUS 工具绝不因超时放行。
         */
        private const val APPROVAL_REQUEST_TIMEOUT_MS = 10 * 60_000L
        /** trim 预算下限，避免系统 prompt 过长时把预算压成负数 */
        private const val MIN_TRIM_BUDGET = 1000

        /** Parse reflection strategy name (case-insensitive) from JSON config; null/unknown → NONE */
        private fun parseReflectionStrategy(raw: String?): ReflectionStrategy =
            raw?.let { runCatching { ReflectionStrategy.valueOf(it.uppercase()) }.getOrNull() }
                ?: ReflectionStrategy.NONE

        private const val BASE_SYSTEM_PROMPT = """You are an AI assistant on an Android device with tool access.

## Rules
1. Call tools to get REAL data — never invent facts.
2. Format results using A2UI for rich display. This device renders A2UI natively — do not use Markdown tables or formatting, they will not render as rich UI.
3. Respond in the same language as the user.
4. Simple greetings need no tools or A2UI.

## Device Context
- **Screen**: A2UI cards render at **full screen width** (no margins). Use `padding` for inner spacing.
- **Container width**: ~360-420dp (typical phone). Design for 360dp minimum.
- **Theme**: Dark mode. Use dark backgrounds (`#1a1a2e`, `#0a0a1a`) with light text.

## A2UI Protocol (v0.9)
When you need rich UI output, use the A2UI standard protocol wrapped in [A2UI]...[/A2UI].

### Message Structure
Each A2UI response is a JSON object containing one or more operations:
- `createSurface`: Initialize a new UI surface with `{"surfaceId": "...", "catalogId": "..."}`
- `updateComponents`: Render components with `{"surfaceId": "...", "components": [...]}`
- `updateDataModel`: Update data bindings with `{"surfaceId": "...", "path": "...", "value": ...}`
- `deleteSurface`: Remove a surface with `{"surfaceId": "..."}`

Always include both `createSurface` and `updateComponents` in the same response for new surfaces.

### Component Format (v0.9)
Each component is a flat JSON object with these common fields:
- `id`: Unique component identifier (required)
- `component`: Component type name (required)

**Component values use plain strings, NOT wrapper objects:**
- ✅ Correct: `"text": "Hello"`
- ❌ Wrong: `"text": {"literalString": "Hello"}`
- ✅ Correct: `"children": ["child1", "child2"]`
- ❌ Wrong: `"children": {"explicitList": ["child1", "child2"]}`

### Available Components and Their Fields

**Layout:**
- `Row`: children, justify (start/center/end/spaceBetween), align (start/center/end)
- `Column`: children, justify (start/center/end/spaceBetween), align (start/center/end)
- `List`: children (array of component ids, OR template object with `path` and `componentId`), direction (vertical/horizontal)
  - **Template mode (recommended for data)**: `{"path":"${'$'}items","componentId":"item_template"}` — data comes from dataModel at `${'$'}items`, each item rendered with the template component
  - **Array mode**: `["item1","item2","item3"]` — explicit list of child component ids

**Display:**
- `Text`: text (string), variant (h1/h2/h3/h4/h5/title/subtitle/body/caption/label)
- `Image`: url (string), fit (contain/cover/fill/none/scale-down), variant (icon/avatar/smallFeature/mediumFeature/largeFeature/header)
- `Icon`: name (string, e.g. "Star", "Check", "Close", "Info", "Warning")
- `Divider`: axis (horizontal/vertical)

**Interactive:**
- `Button`: child (component id), action ({"event": {"name": "..."}}), variant (primary/borderless/text)
- `TextField`: label, value (data binding path), placeholder, variant (shortText/longText/number/obscured), action
- `CheckBox`: label, value (data binding path), action
- `Slider`: value (data binding path), minValue, maxValue, step, label
- `DateTimeInput`: label, value, enableDate (bool), enableTime (bool)
- `ChoicePicker`: options ([{"label":"...","value":"..."}]), selections (data binding), variant (mutuallyExclusive/multipleSelection), maxAllowedSelections (number), label

**Container:**
- `Card`: child (component id) or children, **variant** (top/middle/bottom for fused card groups, or omit for standalone)
- `Modal`: trigger (component id), content (component id)
- `Tabs`: tabs ([{"title":"...","child":"component_id"}])
- `Accordion`: children (array of component ids, each with label and child)

**Custom (app-specific):**
- `StockCard`, `CandlestickChart`, `LineChart`, `GaugeChart`, `HeatmapChart`, `RadarChart`, `Video`, `AudioPlayer`, `Surface`, `Spacer`, `ProgressBar`, `Switch`, `Dropdown`
- `BubbleChart`, `MiniGauge`, `MultipleChoice`, `InteractiveLineChart`, `StreamingLineChart`

### Design Guidelines — Make It Look Premium

**Layout structure matters more than text:**
- Wrap content in a `Card` container — it adds elevation and rounded corners
- Use `Column` with multiple sections (header, body, footer) instead of stacking everything
- Use `Row` with `justify: "spaceBetween"` for label-value pairs (saves vertical space, looks like a data table)
- Use `Divider` between sections for visual separation
- **Fused card groups**: When showing multiple related cards vertically, use `variant: "top"` for first card, `"middle"` for middle cards, `"bottom"` for last card — this removes inner corners for a seamless look

**Visual hierarchy through variants:**
- `h1` — hero value only (one per card, e.g. "21°C")
- `h3` — section titles (city name, category labels)
- `body` — normal content
- `caption` — metadata, secondary info

**Visual styling — make it beautiful:**
Every component supports these visual fields:
- `backgroundColor`: Hex color string, e.g. `"#667eea"` or `"#80FF6B6B"` (with 50% alpha)
- `textColor`: Hex color for text, e.g. `"#FFFFFF"`
- `gradient`: Array of 2+ hex colors for gradient background, e.g. `["#667eea", "#764ba2"]`
- `cornerRadius`: Integer dp, e.g. `16` for rounded corners
- `padding`: Integer dp for inner spacing, e.g. `16`
- `shadow`: Integer dp for elevation/shadow, e.g. `8`
- `blur`: Integer dp for glassmorphism/blur effect, e.g. `20`

**Color palette tips:**
- Modern gradients: `["#667eea", "#764ba2"]` (purple), `["#f093fb", "#f5576c"]` (pink), `["#4facfe", "#00f2fe"]` (blue)
- Dark cards: `backgroundColor: "#1a1a2e"` with `textColor: "#ffffff"`
- Glass effect: `backgroundColor: "#80ffffff"` + `blur: 20` + `cornerRadius: 16`
- Semi-transparent overlays: `backgroundColor: "#cc000000"` (80% black)

**Decorative touches:**
- Add an `Icon` next to the title or in a corner (weather emoji, checkmark, etc.)
- Use `Row` to put icon + text side by side
- Put a small action `Button` at the bottom (borderless style)

**Example: Premium Weather Card with Visual Styling**
[A2UI]
{"version":"v0.9","createSurface":{"surfaceId":"weather_premium","catalogId":"app_catalog"},"updateComponents":{"surfaceId":"weather_premium","components":[
  {"id":"root","component":"Card","child":"content","gradient":["#667eea","#764ba2"],"cornerRadius":20,"shadow":12,"padding":20},
  {"id":"content","component":"Column","children":["header","divider1","details","divider2","footer"]},
  {"id":"header","component":"Row","children":["city","icon"],"justify":"spaceBetween","align":"center"},
  {"id":"city","component":"Text","text":"西安","variant":"h3","textColor":"#FFFFFF"},
  {"id":"icon","component":"Text","text":"☁️","variant":"h1"},
  {"id":"divider1","component":"Divider","axis":"horizontal"},
  {"id":"details","component":"Column","children":["row1","row2","row3"]},
  {"id":"row1","component":"Row","children":["lbl_temp","val_temp"],"justify":"spaceBetween"},
  {"id":"lbl_temp","component":"Text","text":"温度","variant":"caption","textColor":"#E0E0E0"},
  {"id":"val_temp","component":"Text","text":"21°C","variant":"body","textColor":"#FFFFFF"},
  {"id":"row2","component":"Row","children":["lbl_hum","val_hum"],"justify":"spaceBetween"},
  {"id":"lbl_hum","component":"Text","text":"湿度","variant":"caption","textColor":"#E0E0E0"},
  {"id":"val_hum","component":"Text","text":"45%","variant":"body","textColor":"#FFFFFF"},
  {"id":"row3","component":"Row","children":["lbl_wind","val_wind"],"justify":"spaceBetween"},
  {"id":"lbl_wind","component":"Text","text":"风向","variant":"caption","textColor":"#E0E0E0"},
  {"id":"val_wind","component":"Text","text":"南风 3级","variant":"body","textColor":"#FFFFFF"},
  {"id":"divider2","component":"Divider","axis":"horizontal"},
  {"id":"footer","component":"Text","text":"多云 · 空气质量 良","variant":"caption","textColor":"#B0B0B0"}
]}}
[/A2UI]

**Example: 7-Day Forecast using List with Data Binding**
When you have multi-row tabular data (e.g. 7-day weather, stock list, search results), use `List` with template mode instead of markdown tables.
[A2UI]
{"version":"v0.9","createSurface":{"surfaceId":"weather_7d","catalogId":"app_catalog"},"updateDataModel":{"surfaceId":"weather_7d","value":{"weather":[{"date":"周一","condition":"小雨","high":"25°C","low":"16°C"},{"date":"周二","condition":"多云","high":"27°C","low":"18°C"},{"date":"周三","condition":"晴","high":"30°C","low":"20°C"},{"date":"周四","condition":"晴","high":"31°C","low":"21°C"},{"date":"周五","condition":"多云","high":"28°C","low":"19°C"},{"date":"周六","condition":"阴","high":"26°C","low":"17°C"},{"date":"周日","condition":"小雨","high":"24°C","low":"15°C"}]}},"updateComponents":{"surfaceId":"weather_7d","components":[
  {"id":"root","component":"Card","child":"content","gradient":["#667eea","#764ba2"],"cornerRadius":20,"shadow":12,"padding":16},
  {"id":"content","component":"Column","children":["title","divider","forecast_list","footer"]},
  {"id":"title","component":"Text","text":"西安 · 7日天气预报","variant":"h3","textColor":"#FFFFFF"},
  {"id":"divider","component":"Divider","axis":"horizontal"},
  {"id":"forecast_list","component":"List","children":{"path":"${'$'}weather","componentId":"day_row"},"direction":"vertical"},
  {"id":"day_row","component":"Row","children":["day_date","day_condition","day_temp"],"justify":"spaceBetween","align":"center"},
  {"id":"day_date","component":"Text","text":"${'$'}date","variant":"body","textColor":"#E0E0E0"},
  {"id":"day_condition","component":"Text","text":"${'$'}condition","variant":"body","textColor":"#FFFFFF"},
  {"id":"day_temp","component":"Text","text":"${'$'}high","variant":"caption","textColor":"#B0B0B0"},
  {"id":"footer","component":"Text","text":"数据来自 Open-Meteo","variant":"caption","textColor":"#888888"}
]}}
[/A2UI]

**Example: Tabs Navigation**
Use Tabs for multi-page content (e.g., stock overview + chart + news).
[A2UI]
{"version":"v0.9","createSurface":{"surfaceId":"tabs_demo","catalogId":"app_catalog"},"updateComponents":{"surfaceId":"tabs_demo","components":[
  {"id":"root","component":"Tabs","tabs":[{"title":"概览","child":"tab_overview"},{"title":"图表","child":"tab_chart"},{"title":"详情","child":"tab_detail"}]},
  {"id":"tab_overview","component":"Column","children":["ov_title","ov_value","ov_change"]},
  {"id":"ov_title","component":"Text","text":"上证指数","variant":"h3"},
  {"id":"ov_value","component":"Text","text":"3,285.67","variant":"h1"},
  {"id":"ov_change","component":"Text","text":"+1.23%","variant":"body","textColor":"#4CAF50"},
  {"id":"tab_chart","component":"LineChart","text":"3200,3220,3180,3250,3285"},
  {"id":"tab_detail","component":"Column","children":["dt_volume","dt_turnover"]},
  {"id":"dt_volume","component":"Text","text":"成交量: 3.2亿手","variant":"body"},
  {"id":"dt_turnover","component":"Text","text":"成交额: 4,521亿","variant":"body"}
]}}
[/A2UI]

**Example: Accordion (Collapsible Sections)**
Use Accordion for expandable FAQ, settings, or grouped information.
[A2UI]
{"version":"v0.9","createSurface":{"surfaceId":"accordion_demo","catalogId":"app_catalog"},"updateComponents":{"surfaceId":"accordion_demo","components":[
  {"id":"root","component":"Column","children":["title","accordion","footer"]},
  {"id":"title","component":"Text","text":"常见问题","variant":"h3"},
  {"id":"accordion","component":"Accordion","children":["section1","section2","section3"]},
  {"id":"section1","component":"Column","children":["s1_label","s1_content"],"label":"如何添加设备？","child":"s1_content"},
  {"id":"s1_label","component":"Text","text":"如何添加设备？","variant":"body"},
  {"id":"s1_content","component":"Text","text":"进入设置 → 设备管理 → 添加新设备","variant":"caption"},
  {"id":"section2","component":"Column","children":["s2_label","s2_content"],"label":"如何重置密码？","child":"s2_content"},
  {"id":"s2_label","component":"Text","text":"如何重置密码？","variant":"body"},
  {"id":"s2_content","component":"Text","text":"登录账户 → 安全设置 → 重置密码","variant":"caption"},
  {"id":"section3","component":"Column","children":["s3_label","s3_content"],"label":"如何导出数据？","child":"s3_content"},
  {"id":"s3_label","component":"Text","text":"如何导出数据？","variant":"body"},
  {"id":"s3_content","component":"Text","text":"设置 → 数据管理 → 导出为 CSV","variant":"caption"},
  {"id":"footer","component":"Text","text":"更多帮助请联系客服","variant":"caption"}
]}}
[/A2UI]

**Example: Form (TextField + Button + Validation)**
Use TextField + Button for user input (search, login, settings).
[A2UI]
{"version":"v0.9","createSurface":{"surfaceId":"form_demo","catalogId":"app_catalog"},"updateDataModel":{"surfaceId":"form_demo","value":{"searchInput":""}},"updateComponents":{"surfaceId":"form_demo","components":[
  {"id":"root","component":"Card","child":"content","cornerRadius":16,"padding":20},
  {"id":"content","component":"Column","children":["title","search_field","search_btn"]},
  {"id":"title","component":"Text","text":"搜索股票","variant":"h3"},
  {"id":"search_field","component":"TextField","label":"输入股票代码或名称","value":"${'$'}searchInput","placeholder":"例如: 000001 或 平安银行","variant":"shortText","action":{"event":{"name":"onInput"}}},
  {"id":"search_btn","component":"Button","child":"btn_text","variant":"primary","action":{"event":{"name":"onSearch"}}},
  {"id":"btn_text","component":"Text","text":"搜索","variant":"body","textColor":"#FFFFFF"}
]}}
[/A2UI]

**Example: MiniGauge (Simple Metric)**
Use MiniGauge for single percentage values (CPU usage, battery, progress).
[A2UI]
{"version":"v0.9","createSurface":{"surfaceId":"gauge_demo","catalogId":"app_catalog"},"updateComponents":{"surfaceId":"gauge_demo","components":[
  {"id":"root","component":"Column","children":["title","cpu_gauge","mem_gauge"]},
  {"id":"title","component":"Text","text":"系统资源","variant":"h3"},
  {"id":"cpu_gauge","component":"MiniGauge","text":"75","variant":"100","usageHint":"#FF9800"},
  {"id":"mem_gauge","component":"MiniGauge","text":"62","variant":"100","usageHint":"#2196F3"}
]}}
[/A2UI]

**Example: Charts (LineChart, GaugeChart, CandlestickChart)**
Use chart components for financial/data visualization. Data format: comma-separated values or JSON.
[A2UI]
{"version":"v0.9","createSurface":{"surfaceId":"charts_demo","catalogId":"app_catalog"},"updateComponents":{"surfaceId":"charts_demo","components":[
  {"id":"root","component":"Card","child":"content","cornerRadius":16,"padding":16},
  {"id":"content","component":"Column","children":["title","line_chart","divider","gauge_row","candle_title","candle_chart"]},
  {"id":"title","component":"Text","text":"股票走势","variant":"h3"},
  {"id":"line_chart","component":"LineChart","text":"3200,3220,3180,3250,3285,3270,3300"},
  {"id":"divider","component":"Divider","axis":"horizontal"},
  {"id":"gauge_row","component":"Row","children":["bull_gauge","bear_gauge"],"justify":"spaceAround"},
  {"id":"bull_gauge","component":"GaugeChart","text":"75","variant":"100"},
  {"id":"bear_gauge","component":"GaugeChart","text":"25","variant":"100"},
  {"id":"candle_title","component":"Text","text":"K线图","variant":"body"},
  {"id":"candle_chart","component":"CandlestickChart","text":"open:3200,high:3250,low:3180,close:3230|open:3230,high:3280,low:3210,close:3270|open:3270,high:3310,low:3260,close:3300"}
]}}
[/A2UI]

### Display Decision Guide

This device renders A2UI natively. Markdown is NOT rendered as rich UI — markdown tables, bold, code blocks will appear as plain text.

**Good A2UI Patterns:**
- Weather forecasts → Card + List with data binding
- Search results → Card list with title + url
- Stock prices → GaugeChart / LineChart
- User profile → Card with avatar + fields
- Forms / input → TextField + Button + ChoicePicker
- Multi-row data → List with template mode + updateDataModel

**When Plain Text is OK:**
- Simple greetings, short answers, code snippets — plain text is fine, no need for A2UI

**Bad Patterns:**
- ❌ Outputting both A2UI card AND markdown table for the same data (duplicates information)
- ❌ Using markdown tables thinking they will render as rich UI (they won't)

### Critical Rules
1. **NEVER invent version numbers** — only use `"v0.8"`, `"v0.9"`, or `"v0.10"`. Prefer `"v0.9"`.
2. **NEVER invent component names** — only use components listed above.
3. **NEVER invent field names** — each component only accepts the fields listed above.
4. **String values are plain strings** — no `{"literalString": ...}` wrapper.
5. **Children arrays are plain arrays** — no `{"explicitList": ...}` wrapper.
6. **Buttons use `child` reference** — don't nest Text inside Button directly.
7. **Actions use `event` wrapper** — `{"event": {"name": "action_name"}}`.

### Legacy Card Format (fallback only)
If A2UI protocol is too complex, use the simpler legacy format:
[A2UI]{"type":"weather","data":{"title":"西安 · 天气","city":"西安","condition":"晴","temperature":"20°C"}}[/A2UI]
Supported types: weather, translation, search_result, reminder, location, info.

## Dynamic Skills
You can create new skills dynamically using the `dynamic_skill_generator_generate_skill` tool.
When a user asks you to create a game, utility, or new capability, use this tool.
The tool accepts a single `skillJson` parameter with the skill definition.
When asked to create a new capability, use `dynamic_skill_generator_generate_skill` with a complete JSON definition.
The skill definition must include: id, name, description, version, instructions, script, tools[]
Each tool must have: name, description, parameters, entryPoint, idempotent

Example:
{
  "id": "joke_generator",
  "name": "笑话生成",
  "description": "生成随机笑话",
  "version": "1.0.0",
  "instructions": "当用户想要听笑话时使用",
  "script": "const jokes = ['笑话1', '笑话2']; function get_joke() { return JSON.stringify({joke: jokes[Math.floor(Math.random()*jokes.length)]}); }",
  "tools": [{
    "name": "get_joke",
    "description": "获取一个随机笑话",
    "parameters": {},
    "entryPoint": "get_joke",
    "idempotent": true
  }]
}"""
    }

    // ==================== 单一状态源 ====================
    // 会话的唯一真源：不可变 AgentState。所有入口（UI 流式 / 飞书同步 / 触发器）
    // 的完整一轮对话都在 stateMutex 内推进（入口快照 → 循环 → 出口写回），
    // 取消/异常经 finally + NonCancellable 写回已达状态，不回滚。
    private val stateMutex = Mutex()
    @Volatile private var currentState: AgentState = AgentState()

    private var tools: List<Tool> = emptyList()
    private var toolExecutor: (suspend (ToolCall) -> String)? = null
    private val toolExecutionMutex = Mutex()
    private var accessibilityTools: List<Tool> = emptyList()

    // ==================== Tool Approval（方案 3 统一安全层） ====================

    /** 进行中的审批请求：requestId → 等待用户决策的 deferred（synchronized 保护，临界区内无挂起） */
    private val pendingToolApprovals = LinkedHashMap<String, CompletableDeferred<ApprovalDecision?>>()

    /**
     * UI 层响应用户审批决策（确认卡按钮 → ChatViewModel → GatewayContract → 此处）。
     * requestId 不属于本会话时为 no-op。
     */
    fun respondToToolApproval(requestId: String, decision: ApprovalDecision?) {
        val deferred = synchronized(pendingToolApprovals) { pendingToolApprovals.remove(requestId) }
        deferred?.complete(decision)
    }

    /**
     * 发出 [SessionEvent.ToolApprovalRequest] 并挂起等待用户决策。
     * 超时 / 无响应 → null（视为取消，绝不放行 —— A3 修订语义）。
     */
    private suspend fun requestToolApproval(
        toolId: String,
        description: String,
        risk: ToolRiskLevel,
        emitEvent: suspend (SessionEvent) -> Unit
    ): ApprovalDecision? {
        val requestId = java.util.UUID.randomUUID().toString()
        val deferred = CompletableDeferred<ApprovalDecision?>()
        synchronized(pendingToolApprovals) { pendingToolApprovals[requestId] = deferred }
        try {
            emitEvent(SessionEvent.ToolApprovalRequest(requestId, toolId, description, risk))
            return withTimeoutOrNull(APPROVAL_REQUEST_TIMEOUT_MS) { deferred.await() }
        } finally {
            synchronized(pendingToolApprovals) { pendingToolApprovals.remove(requestId) }
        }
    }

    /**
     * 运行时权限请求（系统弹窗，限时防挂起）。无 PermissionManager 时直接失败。
     */
    private suspend fun requestSkillPermissions(
        skillId: String,
        skillName: String,
        missing: List<String>
    ): Boolean {
        val permMgr = permissionManager ?: return false
        val displayName = PermissionManager.getSkillDisplayName(skillId)
            .takeIf { it != skillId } ?: skillName
        return withTimeoutOrNull(PERMISSION_REQUEST_TIMEOUT_MS) {
            withContext(Dispatchers.Main) {
                permMgr.requestPermission(missing.toTypedArray(), skillId, displayName)
            }
        } ?: false
    }

    // System prompt — loaded from external file, not hardcoded
    private var systemPrompt: String = ""

    /**
     * Set system prompt (called by GatewayManager after loading from file)
     */
    fun setSystemPrompt(prompt: String) {
        systemPrompt = prompt
        Log.d(TAG, "System prompt set (${prompt.length} chars)")
    }

    // Agent config (optional, set via factory constructor or setter)
    private var agentConfig: AgentConfig? = null

    /**
     * Set agent config (optional)
     */
    fun setAgentConfig(config: AgentConfig) {
        agentConfig = config
        Log.d(TAG, "AgentConfig set: ${config.id}, maxTokens=${config.maxContextTokens}")
    }

    /**
     * Get effective max context tokens (from config or default).
     *
     * Treats `maxContextTokens <= 0` on the agent config as "not set" and
     * falls back to the session default (8k) so an unset value doesn't
     * silently turn trim into a no-op (0 means "trim every round", which is
     * even worse than never trimming).
     */
    fun getMaxContextTokens(): Int {
        val fromConfig = agentConfig?.maxContextTokens ?: 0
        return if (fromConfig > 0) fromConfig else maxContextTokens
    }

    // Memory & persistence hooks (set via setters)
    private var memoryContextProvider: (suspend () -> String?)? = null
    private var sessionManager: HybridSessionManager? = null
    private var memoryContextText: String? = null

    // ==================== Tool Setup ====================

    fun setTools(tools: List<Tool>, executor: suspend (ToolCall) -> String) {
        this.tools = tools
        this.toolExecutor = executor
    }

    fun setToolsWithSkills(accessTools: List<Tool>, executor: suspend (ToolCall) -> String) {
        this.accessibilityTools = accessTools
        val allSkillTools = skillManager.getAllTools().map { toolDef ->
            Tool(
                type = "function",
                function = ToolFunction(
                    name = toolDef.name,
                    description = toolDef.description,
                    parameters = convertSkillParams(toolDef.parameters)
                )
            )
        }
        // Apply tool filtering based on allowed prefixes
        val prefixes = _allowedToolPrefixes
        val skillTools = if (prefixes == null) {
            allSkillTools
        } else {
            allSkillTools.filter { tool ->
                prefixes.any { prefix -> tool.function.name.startsWith("${prefix}_") }
            }
        }
        // 端侧档位：先把 60+ 个工具收敛到白名单。
        // 这一步必须在 accessTools + skillTools 合并之后做 —— 无障碍工具和技能工具各占一块预算。
        val allTools = accessTools + skillTools
        val (finalTools, dropped) = if (onDeviceMode) {
            OnDeviceProfile.filterTools(allTools)
        } else {
            allTools to emptyList()
        }

        this.tools = finalTools
        this.toolExecutor = executor
        if (onDeviceMode) {
            Log.i(
                TAG,
                "LOCAL profile: ${finalTools.size}/${allTools.size} tools kept " +
                    "(dropped ${dropped.size}): ${finalTools.map { it.function.name }}"
            )
        } else {
            Log.d(TAG, "Loaded ${accessTools.size} accessibility + ${skillTools.size} skill = ${this.tools.size} tools")
        }
    }

    /**
     * 刷新工具列表（当动态技能注册后调用）
     * 重新从 SkillManager 获取最新工具列表，保留已有的 accessibility tools
     */
    fun refreshTools() {
        val currentExecutor = this.toolExecutor
        if (currentExecutor == null) {
            Log.w(TAG, "Cannot refresh tools: toolExecutor is null")
            return
        }
        val allSkillTools = skillManager.getAllTools().map { toolDef ->
            Tool(
                type = "function",
                function = ToolFunction(
                    name = toolDef.name,
                    description = toolDef.description,
                    parameters = convertSkillParams(toolDef.parameters)
                )
            )
        }
        // Apply tool filtering based on allowed prefixes
        val prefixes = _allowedToolPrefixes
        val skillTools = if (prefixes == null) {
            allSkillTools
        } else {
            allSkillTools.filter { tool ->
                prefixes.any { prefix -> tool.function.name.startsWith("${prefix}_") }
            }
        }
        val allTools = accessibilityTools + skillTools
        // 与 setToolsWithSkills 保持一致：端侧档位在这里也要收敛到白名单，
        // 否则动态技能注册一次就把刚砍掉的工具全请回来。
        val finalTools = if (onDeviceMode) {
            OnDeviceProfile.filterTools(allTools).first
        } else {
            allTools
        }
        setTools(finalTools, currentExecutor)
        Log.d(TAG, "Tools refreshed: ${finalTools.size} kept of ${allTools.size} (${skillTools.size} skill tools)")
    }

    // ==================== Memory & Persistence Setup ====================

    fun setMemoryContextProvider(provider: suspend () -> String?) {
        this.memoryContextProvider = provider
    }

    fun setSessionManager(manager: HybridSessionManager) {
        this.sessionManager = manager
    }

    private suspend fun refreshMemoryContext() {
        memoryContextText = try {
            memoryContextProvider?.invoke()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to refresh memory context", e)
            null
        }
    }

    private suspend fun persistMessage(role: String, content: String) {
        if (content.isBlank()) return
        val sessionMgr = sessionManager ?: return
        try {
            val messageRole = when (role) {
                "user" -> MessageRole.USER
                "assistant" -> MessageRole.ASSISTANT
                else -> return // skip system/tool messages
            }
            sessionMgr.addMessage(messageRole, content)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to persist message", e)
        }
    }

    // ==================== Synchronous API (backward compat) ====================

    suspend fun handleMessage(userMessage: String, images: List<ImageContent>? = null): String {
        refreshMemoryContext()
        persistMessage("user", userMessage)
        val activeTools = tools.takeIf { it.isNotEmpty() }
        val detector = ToolLoopDetector()
        var state: AgentState? = null
        var committed = false
        var error: String? = null
        var content = ""

        try {
            stateMutex.withLock {
                // 入口快照：以 currentState 为基底追加用户消息，整轮在锁内推进
                var s = currentState.copy(
                    history = currentState.history + Message(role = "user", content = userMessage, images = images),
                    round = 0
                )
                state = s

                for (r in 1..MAX_TOOL_ROUNDS) {
                    // Step 1: Call LLM (includes building messages)
                    val callResult = callLLMStep(s, activeTools)
                    if (callResult.first != null) {
                        error = callResult.first!!
                        break
                    }
                    s = callResult.second
                    state = s // 同步快照：LLM 轮完成后取消，写回点即此
                    Log.d(TAG, "[State] Round $r → ${s.dump()}")

                    // Step 2: Check if final answer
                    if (!s.needsToolExecution) {
                        break
                    }

                    // Step 3: Execute tools (with loop detection)
                    val (next, loopMsg) = executeToolsWithLoopGuard(s, detector)
                    s = next
                    state = s // 同步快照：工具轮完成后取消，写回点即此
                    Log.d(TAG, "[State] After tools → ${s.dump()}")
                    if (loopMsg != null) break
                }

                if (error == null && !s.isFinalAnswer) {
                    // Force final response if max rounds exceeded
                    Log.w(TAG, "[State] Max rounds exceeded, forcing final response")
                    // 【Bugly 埋点】
                    CrashRecord.logAgentSessionError("max_rounds_exceeded", s.dump(), null)
                    val messages = buildMessagesInternal(s.history)
                    val result = modelClient.chat(messages, null)
                    val forced = result.getOrDefault(ModelResponse()).content ?: "操作完成"
                    s = s.copy(
                        history = s.history + Message(role = "assistant", content = forced),
                        finalContent = forced,
                        currentToolCalls = null,
                        round = s.round + 1
                    )
                    state = s
                }

                content = s.finalContent ?: ""
                currentState = commitTrimmed(s)
                committed = true
            }
        } finally {
            val s = state
            if (!committed && s != null) {
                // 取消/异常路径：保留已达状态（含用户消息与已完成轮次），
                // 修复孤儿 tool 块后写回，不回滚 —— 避免"消失的用户消息"。
                withContext(NonCancellable) {
                    stateMutex.withLock {
                        currentState = commitTrimmed(repairTrailingToolBlock(s.history).let { s.copy(history = it) })
                    }
                }
                Log.w(TAG, "[State] Turn interrupted, partial state committed (history=${s.history.size})")
            }
        }

        if (error != null) {
            Log.e(TAG, "[State] ERROR → $error")
            return error
        }
        persistMessage("assistant", content)
        return content
    }

    // ==================== State Machine Steps ====================

    /** Call LLM with current state's tools */
    private suspend fun callLLMStep(state: AgentState, activeTools: List<Tool>?): Pair<String?, AgentState> {
        val messages = buildMessagesFromState(state, true)
        val result = modelClient.chat(messages, activeTools)

        if (result.isFailure) {
            val exception = result.exceptionOrNull()
            val errorMsg = "抱歉，模型调用失败: ${exception?.message}"
            Log.e(TAG, "[State] ${state.dump()} → Model call failed", exception)

            // 【Bugly 埋点】记录非致命异常 + 上下文
            CrashReport.postCatchedException(
                exception ?: Exception("Model call failed with null exception")
            )
            CrashRecord.logAgentSessionError("model_call_failed", state.dump(), exception?.message)

            return errorMsg to state
        }

        val response = result.getOrThrow()
        val toolCalls = response.toolCalls

        return if (toolCalls.isNullOrEmpty()) {
            // Final answer
            val content = response.content ?: ""
            val newHistory = state.history + Message(role = "assistant", content = content)
            null to state.copy(
                history = newHistory,
                currentToolCalls = null,
                finalContent = content
            )
        } else {
            // Need tool execution
            val newHistory = state.history + Message(
                role = "assistant",
                content = "",
                toolCalls = toolCalls
            )
            null to state.copy(
                history = newHistory,
                currentToolCalls = toolCalls
            )
        }
    }

    /**
     * Execute pending tool calls and add results to history.
     *
     * 带循环检测：同 (toolName, argsHash) 在窗口内重复达到阈值时终止本轮，
     * 并为剩余未执行的 toolCall 补合成 tool 结果 —— 保证每个 toolCallId
     * 都有配对的 tool 消息，避免下一轮请求被 API 以 2013 拒绝。
     *
     * @return (newState, loopMessage?) — loopMessage 非空表示因循环终止
     */
    private suspend fun executeToolsWithLoopGuard(
        state: AgentState,
        detector: ToolLoopDetector,
        onBefore: suspend (String) -> Unit = {},
        onResult: suspend (String, String) -> Unit = { _, _ -> },
        emitEvent: (suspend (SessionEvent) -> Unit)? = null
    ): Pair<AgentState, String?> {
        val toolCalls = state.currentToolCalls ?: return state to null
        var history = state.history

        for ((index, toolCall) in toolCalls.withIndex()) {
            val toolName = toolCall.function.name
            onBefore(toolName)
            val looped = detector.recordAndCheck(toolName, toolCall.function.arguments)

            val result = if (looped) {
                "已终止：同一工具以相同参数重复调用达到上限"
            } else {
                Log.d(TAG, "[Tool] Executing $toolName, args: ${toolCall.function.arguments}")
                val r = executeToolCall(toolCall, emitEvent)
                Log.d(TAG, "[Tool] $toolName → ${r.take(100)}")
                r
            }
            onResult(toolName, result)
            history += Message(role = "tool", content = result, toolCallId = toolCall.id)

            if (looped) {
                // 剩余未执行的调用补合成结果（孤儿防护）
                for (remaining in toolCalls.drop(index + 1)) {
                    history += Message(
                        role = "tool",
                        content = "已跳过：工具调用循环检测触发",
                        toolCallId = remaining.id
                    )
                }
                val message = "已重复调用同一工具（$toolName），请换一种策略完成任务。"
                Log.w(TAG, "[State] Tool loop detected on $toolName, terminating turn")
                CrashRecord.logAgentSessionError("tool_loop_detected", "tool=$toolName", null)
                return state.copy(
                    history = history + Message(role = "assistant", content = message),
                    currentToolCalls = null,
                    finalContent = message
                ) to message
            }
        }

        return state.copy(
            history = history,
            currentToolCalls = null
        ) to null
    }

    /** Build messages list from AgentState for LLM call */
    private fun buildMessagesFromState(state: AgentState, includeSystemPrompt: Boolean): List<Message> {
        return if (includeSystemPrompt) {
            buildMessagesInternal(state.history)
        } else {
            state.history
        }
    }

    // ==================== Streaming API ====================

    /**
     * Streaming variant — emits tokens and tool events in real-time.
     * The flow completes with a [SessionEvent.Complete] containing the full text.
     *
     * 整轮在 stateMutex 内推进；取消/异常经 finally + NonCancellable 写回
     * 已达状态（修复孤儿 tool 块），不回滚。
     */
    fun handleMessageStream(userMessage: String, images: List<ImageContent>? = null): Flow<SessionEvent> = channelFlow {
        // channelFlow 而非 flow：工具执行在 executeToolCall 的 withContext(IO) 内，
        // 审批事件（ToolApprovalRequest）会从 IO 协程回调 send —— 冷流 emit 跨协程
        // 会违反 Flow 不变式，channelFlow 的 send 跨协程安全。
        refreshMemoryContext()
        persistMessage("user", userMessage)
        val activeTools = tools.takeIf { it.isNotEmpty() }
        val detector = ToolLoopDetector()
        var state: AgentState? = null
        var committed = false

        try {
            stateMutex.withLock {
                var s = currentState.copy(
                    history = currentState.history + Message(role = "user", content = userMessage, images = images),
                    round = 0
                )
                state = s
                var errorText: String? = null
                var pendingComplete: String? = null

                for (r in 1..MAX_TOOL_ROUNDS) {
                    s = s.copy(round = r)
                    Log.d(TAG, "[State] Round $r start → ${s.dump()}")

                    val messages = buildMessagesFromState(s, true)
                    val fullText = StringBuilder()
                    var completeResponse: ModelResponse? = null

                    modelClient.chatStream(messages, activeTools).collect { event ->
                        when (event) {
                            is ChatEvent.Token -> {
                                fullText.append(event.text)
                                send(SessionEvent.Token(event.text))
                            }
                            is ChatEvent.Complete -> completeResponse = event.response
                            is ChatEvent.Error -> {
                                send(SessionEvent.Error(event.message))
                                errorText = event.message
                            }
                            is ChatEvent.ToolCallRequested -> {}
                        }
                    }
                    if (errorText != null) break

                    val response = completeResponse
                    if (response == null) {
                        val text = fullText.toString()
                        if (text.isNotEmpty()) {
                            s = s.copy(
                                history = s.history + Message(role = "assistant", content = text),
                                currentToolCalls = null,
                                finalContent = text
                            )
                            state = s // 同步快照
                            pendingComplete = text
                        } else {
                            errorText = "No response from model"
                        }
                        break
                    }

                    val toolCalls = response.toolCalls
                    if (toolCalls.isNullOrEmpty()) {
                        // Final text response — apply reflection if configured
                        var content = response.content ?: fullText.toString()
                        s = s.copy(
                            history = s.history + Message(role = "assistant", content = content),
                            currentToolCalls = null,
                            finalContent = content
                        )
                        state = s // 同步快照（reflection 前的最终答案已入史）

                        Log.d(TAG, "[State] Final answer → ${s.dump()}")

                        content = applyReflection(s, content) { event -> send(event) }
                        s = s.copy(
                            history = s.history.dropLast(1) + Message(role = "assistant", content = content),
                            finalContent = content,
                            reflectionApplied = true
                        )
                        state = s // 同步快照（reflection 后）
                        pendingComplete = content
                        break
                    }

                    s = s.copy(
                        history = s.history + Message(role = "assistant", content = "", toolCalls = toolCalls),
                        currentToolCalls = toolCalls
                    )
                    state = s // 同步快照：工具执行前（最长挂起点，取消高发区）
                    Log.d(TAG, "[State] Tool calls → ${s.dump()}")

                    val (next, loopMsg) = executeToolsWithLoopGuard(
                        s, detector,
                        onBefore = { name -> send(SessionEvent.ToolExecuting(name)) },
                        onResult = { name, result -> send(SessionEvent.ToolResult(name, result)) },
                        emitEvent = { event -> send(event) }
                    )
                    s = next
                    state = s // 同步快照：工具轮完成后
                    Log.d(TAG, "[State] Tools done → ${s.dump()}")
                    if (loopMsg != null) {
                        pendingComplete = loopMsg
                        break
                    }
                }

                if (pendingComplete != null) {
                    persistMessage("assistant", pendingComplete)
                    send(SessionEvent.Complete(pendingComplete))
                } else if (errorText != null) {
                    send(SessionEvent.Error(errorText!!))
                } else {
                    send(SessionEvent.Error("Exceeded max tool rounds. Last state: ${s.dump()}"))
                }
                currentState = commitTrimmed(s)
                committed = true
            }
        } finally {
            val s = state
            if (!committed && s != null) {
                withContext(NonCancellable) {
                    stateMutex.withLock {
                        currentState = commitTrimmed(s.copy(history = repairTrailingToolBlock(s.history)))
                    }
                }
                Log.w(TAG, "[State] Stream interrupted, partial state committed (history=${s.history.size})")
            }
        }
    }.flowOn(Dispatchers.Default)

    /** Apply reflection to final content, emit events via callback */
    private suspend fun applyReflection(
        state: AgentState,
        content: String,
        emitEvent: suspend (SessionEvent) -> Unit
    ): String {
        val reflectionConfig = _reflectionConfig
        val lastUserMessage = state.history.lastOrNull { it.role == "user" }?.content ?: ""

        if (reflectionConfig == null || reflectionConfig.strategy == ReflectionStrategy.NONE || content.isBlank()) {
            return content
        }

        Log.d(TAG, "Applying reflection: ${reflectionConfig.strategy}")
        LogManager.shared.log("INFO", TAG, "[反思] 开始: strategy=${reflectionConfig.strategy}")
        emitEvent(SessionEvent.ReflectionStart("reflection"))

        val reflectionResult = runReflectionWithProtection(
            originalContent = content,
            userMessage = lastUserMessage,
            config = reflectionConfig
        )

        if (reflectionResult.changed) {
            val refined = reflectionResult.refinedContent
            Log.d(TAG, "Reflection applied: changeRate=${String.format("%.2f", reflectionResult.changeRate)}, rounds=${reflectionResult.roundsCompleted}")
            LogManager.shared.log("INFO", TAG, "[反思] 已应用: 变化率=${String.format("%.1f", reflectionResult.changeRate * 100)}%, A2UI=${reflectionResult.a2uiPreserved}")
            emitEvent(SessionEvent.ReflectionComplete("reflection"))
            return refined
        } else {
            Log.d(TAG, "Reflection unchanged: keeping original answer")
            LogManager.shared.log("INFO", TAG, "[反思] 无变化，保留原答案")
            emitEvent(SessionEvent.ReflectionComplete("reflection"))
            return content
        }
    }

    // ==================== Tool Execution ====================

    /**
     * 执行单个工具调用。
     *
     * Skill 工具走 [SkillManager.executeTool] 统一安全层（权限门 + 风险分级审查 + 审批）；
     * [emitEvent] 非空（stream 路径）时审批请求经 [SessionEvent.ToolApprovalRequest] 冒泡到 UI，
     * 为 null（sync 路径，无事件通道）时需要审批的工具直接返回引导文本，不挂起等待。
     */
    private suspend fun executeToolCall(
        toolCall: ToolCall,
        emitEvent: (suspend (SessionEvent) -> Unit)? = null
    ): String {
        toolExecutionMutex.lock()
        return try {
            withContext(Dispatchers.IO) {
                val toolName = toolCall.function.name

                // Check if this is an accessibility tool first
                val isAccessibilityTool = accessibilityTools.any { it.function.name == toolName }
                if (!isAccessibilityTool && toolName.contains("_") && toolName.split("_").size >= 2) {
                    // Skill tool — 统一安全层入口
                    val params = parseToolCallParams(toolCall)

                    val outcome = skillManager.executeTool(
                        toolName, params,
                        requestApproval = emitEvent?.let { emit ->
                            { toolId, description, risk ->
                                requestToolApproval(toolId, description, risk, emit)
                            }
                        },
                        requestPermissions = ::requestSkillPermissions
                    )

                    when (outcome) {
                        is ToolExecutionOutcome.Done -> {
                            val r = outcome.result
                            if (r.success) {
                                Log.d(TAG, "Tool $toolName success: ${r.output}")
                                r.output
                            } else {
                                Log.e(TAG, "Tool $toolName failed: ${r.error}")
                                // 【Bugly 埋点】
                                CrashRecord.logAgentSessionError("tool_failed", "tool=$toolName", r.error)
                                r.error ?: "Skill error"
                            }
                        }

                        is ToolExecutionOutcome.Denied -> {
                            Log.w(TAG, "Tool $toolName denied: ${outcome.reason}")
                            outcome.reason
                        }

                        is ToolExecutionOutcome.NeedsApproval -> {
                            // 仅 sync 路径（无事件通道）到达：返回引导文本，LLM 据此告知用户
                            "工具 ${outcome.toolId} 需要用户确认后才能执行，当前入口无法弹出确认，请直接在应用对话中发起。"
                        }
                    }
                } else {
                    // Accessibility tool
                    Log.d(TAG, "Executing accessibility tool: $toolName")
                    // Fix: Add null check before invoking toolExecutor
                    if (toolExecutor != null) {
                        toolExecutor!!.invoke(toolCall)
                    } else {
                        "Tool executor not set"
                    }
                }
            }
        } finally {
            toolExecutionMutex.unlock()
        }
    }

    // ==================== History Management ====================

    private fun buildMessagesInternal(currentHistory: List<Message>): List<Message> =
        buildSystemMessages() + currentHistory

    /** 进入每轮消息列表头部的系统块：system prompt + 记忆上下文 */
    private fun buildSystemMessages(): List<Message> {
        // 端侧走精简档：完整版 9245 token > E2B 窗口 8192，等于每轮必截断
        val builtinPrompt = if (onDeviceMode) {
            OnDeviceProfile.SYSTEM_PROMPT
        } else {
            BASE_SYSTEM_PROMPT
        }

        val basePrompt = _agentConfig?.systemPrompt?.takeIf { it.isNotBlank() }
            ?.let { customPrompt -> "$customPrompt\n\n---\n$builtinPrompt" }
            ?: builtinPrompt

        val systemPrompt = deviceCapabilities?.let { caps ->
            "${caps.toPromptSection()}\n\n---\n$basePrompt"
        } ?: basePrompt

        return buildList {
            add(Message(role = "system", content = systemPrompt))
            memoryContextText?.let { context ->
                add(Message(role = "system", content = "用户的重要记忆：\n$context"))
            }
        }
    }

    /**
     * 提交状态：按 token 预算裁剪后返回新状态。
     * 预算 = maxContextTokens − 系统块（system prompt + 记忆）估算，下限 [MIN_TRIM_BUDGET]。
     */
    private fun commitTrimmed(state: AgentState): AgentState {
        val budget = (getMaxContextTokens() - estimateTokens(buildSystemMessages()))
            .coerceAtLeast(MIN_TRIM_BUDGET)
        return state.copy(history = trimHistory(state.history, budget))
    }

    /**
     * Token-aware history trimming（纯函数，不改入参）。
     * Estimates ~1.3 tokens per CJK character, ~0.25 tokens per ASCII character.
     *
     * Treats assistant(tool_calls) + N×tool(tool_call_id) as atomic blocks so we
     * never leave orphan tool messages that would cause the API to reject the
     * next request with "tool result's tool id(...) not found" (2013).
     */
    internal fun trimHistory(history: List<Message>, maxTokens: Int): List<Message> {
        if (estimateTokens(history) <= maxTokens || history.size <= 2) {
            return history
        }
        Log.d(TAG, "[trim] triggered: estimatedTokens=${estimateTokens(history)} > maxTokens=$maxTokens (history.size=${history.size})")

        // 跳过 tool-call 配对块作为原子单位: assistant(tool_calls) + N×tool(tool_call_id)
        // 防止留下 orphan tool 消息导致 API 报 "tool result's tool id not found"
        var trimStart = 0
        while (trimStart < history.size - 2 &&
               estimateTokens(history.subList(trimStart, history.size)) > maxTokens) {
            val msg = history[trimStart]
            if (msg.role == "assistant" && !msg.toolCalls.isNullOrEmpty()) {
                // 跳过整个 assistant + tools 配对块
                val toolIds = msg.toolCalls.orEmpty().map { it.id }.toSet()
                var next = trimStart + 1
                while (next < history.size &&
                       history[next].role == "tool" &&
                       history[next].toolCallId in toolIds) {
                    next++
                }
                trimStart = next
            } else {
                trimStart++
            }
        }

        return if (trimStart > 0) {
            Log.d(TAG, "[trim] removing first $trimStart messages (history.size: ${history.size} → ${history.size - trimStart})")
            history.drop(trimStart)
        } else {
            history
        }
    }

    /**
     * 修复取消遗留的尾部孤儿 tool 块：若最后一条 assistant(tool_calls) 之后
     * 存在未执行的 toolCall（中途取消/异常），为其补合成 tool 结果，
     * 保证下一轮请求的消息配对完整（防 API 2013）。
     */
    internal fun repairTrailingToolBlock(history: List<Message>): List<Message> {
        val lastCallIdx = history.indexOfLast { it.role == "assistant" && !it.toolCalls.isNullOrEmpty() }
        if (lastCallIdx == -1) return history
        val toolCalls = history[lastCallIdx].toolCalls.orEmpty()
        // 只修复尾部块：该 assistant 消息之后应当只有 tool 消息
        val tail = history.subList(lastCallIdx + 1, history.size)
        if (tail.any { it.role != "tool" }) return history
        val executed = tail.mapNotNull { it.toolCallId }.toSet()
        val missing = toolCalls.filter { it.id !in executed }
        if (missing.isEmpty()) return history
        Log.w(TAG, "[repair] synthesizing ${missing.size} missing tool result(s) after interrupted turn")
        return history + missing.map {
            Message(role = "tool", content = "用户取消了此操作（生成中断）", toolCallId = it.id)
        }
    }

    /**
     * Estimate token count: CJK ~1.3 tokens/char, ASCII ~4 chars/token.
     * 同时计入 toolCalls 的函数名与参数 —— 工具调用轮次的消息 content 为空但
     * arguments 可能非常大，只算 content 会显著低估真实上下文占用。
     */
    internal fun estimateTokens(messages: List<Message>): Int {
        return messages.sumOf { msg ->
            estimateTextTokens(msg.content) +
                (msg.toolCalls?.sumOf { tc ->
                    estimateTextTokens(tc.function.name) + estimateTextTokens(tc.function.arguments)
                } ?: 0)
        }
    }

    private fun estimateTextTokens(text: String): Int {
        val cjkCount = text.count { it.code > 0x7F }
        val asciiCount = text.length - cjkCount
        return (cjkCount * 1.3 + asciiCount * 0.25).toInt()
    }

    fun clearHistory() {
        // 非挂起 API，无法等待 stateMutex（进行中的一轮可能持锁数十秒）。
        // currentState 为 @Volatile，直接原子替换。若一轮对话恰好并发收尾，
        // 其写回会覆盖本次清空 —— 与旧实现 (history.clear()) 的竞争窗口一致，
        // 实际调用点（新建会话）都在无进行中对话时触发。
        currentState = AgentState()
    }

    /** 无锁快照（currentState 为 @Volatile） */
    fun getHistory(): List<Message> = currentState.history

    // ==================== Helpers ====================

    private fun convertSkillParams(params: Map<String, SkillParam>): ToolParameters {
        val properties = mutableMapOf<String, ToolProperty>()
        val required = mutableListOf<String>()
        for ((name, param) in params) {
            properties[name] = ToolProperty(type = param.type, description = param.description)
            if (param.required) required.add(name)
        }
        return ToolParameters(type = "object", properties = properties, required = required)
    }

    private fun parseToolCallParams(toolCall: ToolCall): Map<String, Any> {
        return try {
            JSONObject(toolCall.function.arguments).let { json ->
                val map = mutableMapOf<String, Any>()
                for (key in json.keys()) map[key] = json.get(key)
                map
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse tool params: ${e.message}")
            // 【Bugly 埋点】
            CrashReport.postCatchedException(e)
            emptyMap()
        }
    }

    // ==================== Reflection (Protected) ====================

    /**
     * Run reflection with safety guards:
     * - Timeout protection
     * - Empty content rejection (never overwrite good answer with empty string)
     * - A2UI format preservation check
     * - Early termination if change rate < threshold
     */
    private suspend fun runReflectionWithProtection(
        originalContent: String,
        userMessage: String,
        config: ReflectionConfig
    ): ReflectionResult {
        return try {
            val checkpoint = System.currentTimeMillis()
            val reflectionPrompt = ReflectionRole.CHECKER.buildPrompt(userMessage, originalContent)
            val reflectionMessages = buildLightReflectionMessages(reflectionPrompt)

            val fullText = StringBuilder()
            var completeResponse: ModelResponse? = null

            // Run with timeout using kotlinx.coroutines
            withTimeoutOrNull(config.timeoutMs) {
                modelClient.chatStream(reflectionMessages, null).collect { event ->
                    when (event) {
                        is ChatEvent.Token -> fullText.append(event.text)
                        is ChatEvent.Complete -> completeResponse = event.response
                        is ChatEvent.Error -> return@collect
                        else -> {}
                    }
                }
            }

            val refinedContent = completeResponse?.content ?: fullText.toString()

            // Guard 1: reject empty content
            if (refinedContent.isBlank()) {
                Log.w(TAG, "[反思] 返回空内容，保留原答案")
                return ReflectionResult.unchanged(originalContent)
            }

            // Guard 2: A2UI format preservation
            val a2uiPreserved = if (config.protectA2UI) {
                ReflectionUtils.isA2UIPreserved(originalContent, refinedContent)
            } else true

            if (!a2uiPreserved) {
                Log.w(TAG, "[反思] A2UI 格式被破坏，保留原答案")
                return ReflectionResult.unchanged(originalContent)
            }

            // Guard 3: early termination if change < threshold
            val changeRate = ReflectionUtils.changeRate(originalContent, refinedContent)
            if (changeRate < config.minChangeRate) {
                Log.d(TAG, "[反思] 变化率 ${String.format("%.1f", changeRate * 100)}% < 阈值，早停")
                return ReflectionResult.unchanged(originalContent)
            }

            val elapsed = System.currentTimeMillis() - checkpoint
            Log.d(TAG, "[反思] 完成: ${String.format("%.1f", changeRate * 100)}% 变化, 耗时 ${elapsed}ms")

            ReflectionResult(
                refinedContent = refinedContent,
                changed = true,
                changeRate = changeRate,
                roundsCompleted = 1,
                a2uiPreserved = a2uiPreserved
            )
        } catch (e: Exception) {
            Log.e(TAG, "[反思] 异常: ${e.message}", e)
            ReflectionResult.unchanged(originalContent)
        }
    }

    /**
     * Build lightweight reflection messages: only send the original answer + reflection prompt.
     * Don't include full conversation history to save tokens.
     */
    private fun buildLightReflectionMessages(reflectionPrompt: String): List<Message> {
        val basePrompt = _agentConfig?.systemPrompt?.takeIf { it.isNotBlank() }
            ?: BASE_SYSTEM_PROMPT

        return listOf(
            Message(role = "system", content = basePrompt),
            Message(role = "user", content = reflectionPrompt)
        )
    }
}

/**
 * 工具调用循环检测：记录最近 [WINDOW] 次 (toolName, argsHash)，
 * 同组合出现 ≥ [THRESHOLD] 次判定为循环，强制终止本轮。
 * （窗口/阈值与 AgentSession.MAX_TOOL_ROUNDS 同源设计，取值见文档）
 */
private class ToolLoopDetector {
    private val recent = ArrayDeque<Pair<String, Int>>()

    fun recordAndCheck(toolName: String, args: String): Boolean {
        val key = toolName to args.hashCode()
        recent.addLast(key)
        if (recent.size > WINDOW) recent.removeFirst()
        return recent.count { it == key } >= THRESHOLD
    }

    private companion object {
        const val WINDOW = 5
        const val THRESHOLD = 3
    }
}

// ==================== AgentState (immutable, for debugging & logging) ====================

/**
 * AgentState — immutable snapshot of the agent's conversation state.
 * Each tool-calling round produces a new state via copy().
 * 
 * Benefits:
 * - Full state dump on error for quick debugging
 * - No mutable variable sprawl
 * - Easy to trace round-by-round in logs
 */
data class AgentState(
    val history: List<Message> = emptyList(),
    val currentToolCalls: List<ToolCall>? = null,
    val round: Int = 0,
    val a2uiResponse: String? = null,
    val reflectionApplied: Boolean = false,
    val finalContent: String? = null
) {
    val isFinalAnswer: Boolean get() = currentToolCalls == null && finalContent != null
    val needsToolExecution: Boolean get() = !currentToolCalls.isNullOrEmpty()

    /** Full state dump for debugging — call when errors occur */
    fun dump(): String = buildString {
        append("AgentState(")
        append("round=$round, ")
        append("historySize=${history.size}, ")
        append("toolCalls=${currentToolCalls?.map { it.function.name } ?: "null"}, ")
        append("a2ui=${a2uiResponse != null}, ")
        append("reflectionApplied=$reflectionApplied, ")
        append("finalContent=${finalContent?.take(30)}, ")
        append("isFinalAnswer=$isFinalAnswer")
        append(")")
    }
}

/** Tool execution result (internal use, different from SessionEvent.ToolResult) */
data class AgentToolResult(val name: String, val result: String)

// ==================== Session Events (for streaming) ====================

sealed class SessionEvent {
    data class Token(val text: String) : SessionEvent()
    data class ToolExecuting(val name: String) : SessionEvent()
    data class ToolResult(val name: String, val result: String) : SessionEvent()
    data class Complete(val fullText: String) : SessionEvent()
    data class Error(val message: String) : SessionEvent()
    /** Reflection phase started */
    data class ReflectionStart(val role: String) : SessionEvent()
    /** Reflection phase completed */
    data class ReflectionComplete(val role: String) : SessionEvent()
    /**
     * 工具执行需要用户审批（方案 3 统一安全层）。UI 弹确认卡，
     * 用户决策经 [AgentSession.respondToToolApproval] 回传；超时未决策视为取消。
     */
    data class ToolApprovalRequest(
        val requestId: String,
        val toolId: String,
        val description: String,
        val risk: ToolRiskLevel
    ) : SessionEvent()
}
