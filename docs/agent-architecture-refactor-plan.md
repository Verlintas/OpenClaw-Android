# Agent 架构三项收敛方案

> 目标：解决评审发现的「三处双份实现 + 一处安全断点」。
> 代码位置基于当前 master，行号可能随提交漂移，以符号名为准。

---

## 0. 前置事实核对（方案依据）

| # | 事实 | 位置 |
|---|------|------|
| F1 | `MAX_TOOL_ROUNDS = 50` | `AgentSession.kt:114` |
| F2 | `BASE_SYSTEM_PROMPT` 硬编码 280 行，且被 :952/:953/:1145 实际使用 | `AgentSession.kt:116-395` |
| F3 | 可变 `history` 与 `AgentState.history` 双份，靠 :601-602 手工回写 | `AgentSession.kt:398 / 554 / 702 / 601` |
| F4 | `toolExecutionMutex` 只包住单次工具执行，不保护 history | `AgentSession.kt:401 / 887 / 943` |
| F5 | 反思只在 streaming 路径，`handleMessage` 无 | `AgentSession.kt:760`（对比 :557 循环） |
| F6 | `SecurityReview` 全项目仅一处调用 | `DynamicSkill.kt:158` |
| F7 | `SkillTool` 接口无风险/幂等字段，`DynamicToolDef` 才有 | `SkillTool.kt:3-9` |
| F8 | 权限白名单两份且不一致 | `SkillManager.kt:130-153` vs `PermissionManager.kt:174-182` |
| F9 | GatewayManager 同时持有两套多 Agent 系统 | `GatewayManager.kt:89 / 100` |
| F10 | 两套 `AgentConfig` 的 `tools` 默认值相反 | YAML `emptyList()` vs JSON `listOf("all")` |

### 新发现（评审时未提及）

**N1 — 权限白名单两份互相矛盾**
`SkillManager.getRequiredPermissionsForSkill` 覆盖 `calendar/location/contact/sms/camera`；
`PermissionManager.getPermissionsForSkill` 覆盖 `location/contact/sms/calendar/calllog/storage`。
差异：`camera` 只在前者，`calllog`/`storage` 只在后者。
`executeToolCall`（`AgentSession.kt:902`）查前者、`:907` 请求后者 —— 同一技能两步拿到不同结果，
`calllog`/`storage` 类技能会走到 `:907` 拿到 `emptyArray()`，然后请求一组空权限。

**N2 — 新建的 agent 实际没有任何工具**
`AgentRegistry.createAgent`（`:111-117`）写出的默认 `config.yaml` 是 `tools: []`，
而 `config.AgentConfig.tools` 默认 `emptyList()`，`AgentSession.setToolsWithSkills`（`:464-471`）
对 `emptyList()` 的过滤结果是**零个技能工具**。
JSON 那套默认 `listOf("all")` 反而是全量。结论：通过对话创建的 agent 既路由不到、又没有工具。

**N3 — `RoutingConfig.targetAgent` 是死字段**
`config.AgentConfig.routing.targetAgent` 有定义，`AgentRouter` 从不读取（只用
`configManager.getKeywordIndex()`）。

---

## 方案 1：合并两套多 Agent 系统

### 1.1 现状

| | `agent/AgentRegistry` | `domain/agent/AgentSessionManager` |
|---|---|---|
| 配置源 | `files/agents/<id>/config.yaml` + `SOUL.md` | `assets/agents.json` |
| 解析 | snakeyaml | kotlinx.serialization |
| 配置类型 | `config.AgentConfig` | `data.model.AgentConfig` |
| Session 缓存 | `mutableMap`，无淘汰 | LRU，`maxCachedSessions=3` |
| keywords | `RoutingConfig.keywords`（未被读取） | 顶层 `keywords` → `keywordIndex` |
| isDefault | 无 | 有 |
| tools 默认 | `emptyList()`（= 无工具） | `listOf("all")` |
| 模型客户端 | 工厂注入 | `createModelClient()` 含 LOCAL 共享实例复用 |

`GatewayManager.sendMessage`（:155-167）走 JSON 套；`sendMessageToAgent`（:172）、
`listAgents`（:179）、`AgentManagementSkill` 四个工具走 YAML 套。

### 1.2 目标

单一 `AgentRuntime`：配置源唯一、session 缓存唯一、创建与路由读同一份数据。

### 1.3 选型：保留 JSON 套，淘汰 YAML 套

理由：
1. 字段最完整（`keywords` 顶层 + `isDefault` + `reflectionStrategy` + `tools` 默认 `all`）
2. `createModelClient()` 的 provider 解析与 LOCAL 共享实例复用是真实价值，不能丢
3. `AgentRouter` 依赖 `getKeywordIndex()`
4. 已有 `AgentConfigManagerTest` / `AgentSessionManagerTest` / `AgentRouterTest`
5. 去掉 snakeyaml 依赖与 YAML 手写拼接（`createAgent` 用字符串模板写 YAML，注入风险）

### 1.4 结构设计

```
AgentRuntime(context, skillManager, accessibilityBridge, permissionManager, sharedLocalLLMClient)
├── configStore: AgentConfigStore          // 唯一配置源
│   ├── builtin: assets/agents.json        // 只读，随包发布
│   ├── user:    filesDir/agents/*.json    // 运行时创建/覆盖
│   └── all(): List<AgentConfig>           // builtin + user，同名 id 后者覆盖
├── sessions: SessionCache                 // LRU(3)，唯一
├── route(text): String                    // 委托 AgentRouter
├── session(agentId): AgentSession         // getOrCreate，含 fallback 语义
├── reconfigureModel(config)               // 统一 evict，取代 GatewayManager:278 特判
└── createAgent / deleteAgent              // 取代 AgentRegistry 同名方法
```

保留 YAML 套的两项能力：
- **SOUL.md**：新建 agent 时把内容写进 `systemPrompt` 字段，不再维护独立文件
- **动态创建/删除**：`AgentManagementSkill` 改依赖 `AgentConfigStore`

### 1.5 改动清单

| 文件 | 改动 |
|---|---|
| `domain/agent/AgentConfigStore.kt` | **新增**。两级 merge、持久化到 `filesDir/agents/`、`isDefault` 不可删 |
| `agent/AgentRuntime.kt` | **新增**。持有 configStore + SessionCache + AgentRouter |
| `config/AgentConfig.kt` | **删除**（含死字段 `RoutingConfig.targetAgent`） |
| `agent/AgentRegistry.kt` | **删除**。迁移期可保留为 deprecated 一次性 converter |
| `domain/agent/AgentSessionManager.kt` | 删除 `toConfigAgent()`（:42-56），session 构造收进 AgentRuntime |
| `agent/AgentSession.kt` | `setAgentConfig` 改收 `data.model.AgentConfig` |
| `GatewayManager.kt` | 删 :89 / :100 两个字段 → 单个 `agentRuntime`；:155-167 / :172 / :179 / :278 改调 runtime |
| `skill/builtin/AgentManagementSkill.kt` | 4 个工具（`ListAgentsTool`/`CreateAgentTool`/`DeleteAgentTool`/`GetAgentConfigTool`）依赖改 `AgentConfigStore` |
| `build.gradle.kts` | 移除 snakeyaml 依赖（若无其他使用方） |
| `AgentRegistryTest.kt` | 重写为 `AgentConfigStoreTest`（含 N2：新建 agent 工具非空） |

### 1.6 迁移与风险

- 启动时检测 `files/agents/*/config.yaml`，无对应 json 则转换写入（yaml 保留不删，降级用）
- `AgentRegistry.getSession` 的「未知 id 回落默认且共享 history」（:59-81，production bug #3 的修复）
  必须原样搬进 `AgentRuntime.session()`，不要重写丢掉
- 风险点：`AgentManagementSkill` 是 LLM 可调用工具，改注入后要确认 `dynamicSkillManager`
  注册路径（`GatewayManager:244-247`）仍拿到新实例

### 1.7 验收

1. 对话说「创建一个名为 tester 的 agent」→ 再 `@tester 现在几点` → 命中且不报 "agent not found"
2. 新建 agent 能调用工具（不再 `tools=[]`）
3. `AgentRouterTest` / `AgentSessionManagerTest` / `AgentConfigManagerTest` 通过
4. `listAgents()` 结果与路由可选 id 集合一致

---

## 方案 2：AgentSession 单一状态源 + 并发保护

### 2.1 现状

- `history: MutableList<Message>`（:398）与 `AgentState.history` 并行
- 入口处快照 `AgentState(history = history.toList())`（:554 / :702）
- 出口处回写 `history.clear(); history.addAll(state.history)`（:601-602）
- streaming 路径里还有直接 `history.add(...)`（:697 / :878）
- `clearHistory()`（:1031）
- 三个入口（UI / 飞书 / 定时触发）共享同一 session 实例，无并发保护

已由两处 `[FIX]` 注释证实此设计产生过线上 bug。

### 2.2 目标

删掉可变 history，只留不可变 `AgentState`；并发由显式机制保证。

### 2.3 设计

**A. 状态单一真源**

```kotlin
private val stateMutex = Mutex()
private var currentState: AgentState = AgentState()

// 入口
val start = stateMutex.withLock { currentState }
    .copy(history = currentState.history + userMsg, round = 0)
// ...循环内全程用局部变量 state...
// 出口
stateMutex.withLock { currentState = trimIfNeeded(state) }
```

**B. 跨入口串行化用 actor**

`Mutex` 只能保证状态读写原子，防不住「飞书消息和 UI 消息交错推进同一会话」。
在 `AgentRuntime` 给每个 agentId 配一个 `Channel<ChatRequest>` + 单消费者协程：

```kotlin
class AgentActor(session: AgentSession) {
    private val mailbox = Channel<ChatRequest>(Channel.UNLIMITED)
    init { scope.launch { for (req in mailbox) session.handleMessageStream(...).collect { req.emit(it) } } }
    suspend fun submit(req: ChatRequest) { mailbox.send(req) }
}
```

消息自然排队，无需在每个入口加锁。

**C. `trimHistoryByTokens` 改纯函数**

```kotlin
private fun trim(history: List<Message>, maxTokens: Int): List<Message>
```
返回新 list，删掉原地 `history.subList(0, trimStart).clear()`（:1016）。
原子块跳过逻辑（:995-1012）原样保留 —— 那是修 API 2013 的正确做法。

### 2.4 顺带修的三项

| 项 | 现状 | 改法 |
|---|---|---|
| token 低估 | `estimateTokens`（:1023）只算 `msg.content`，漏 `toolCalls.arguments`、system prompt、memory context | 计入 toolCalls JSON 序列化长度；system/memory 单独估算后加到预算 |
| 轮数失控 | `MAX_TOOL_ROUNDS = 50`，无预算无循环检测 | 降到 8；维护最近 5 次 `(toolName, argsHash)`，同组合重复 ≥3 次强制终止并回「已重复调用同一工具，请换策略」 |
| 权限等待无超时 | `withContext(Main) { requestPermission(...) }`（:910-912）同步等用户点击 | 包 `withTimeoutOrNull(60_000)`，超时返回「授权超时」让 LLM 继续而非永久挂起 |

### 2.5 改动清单

| 位置 | 动作 |
|---|---|
| :398 `history` 字段 | 删除 |
| :548 / :697 user msg add | 改为构造初始 state |
| :601-602 回写 | 改为 `stateMutex.withLock` 单次赋值 |
| :873-883 `executeToolsAndAppend` 里 `history.add` | 改为返回新 state（对齐 :656-678 的 `executeToolsStep` 范式） |
| :970 `buildMessages()` legacy | 删除 |
| :980-1018 `trimHistoryByTokens` | 改纯函数 |
| :1023 `estimateTokens` | 计入 toolCalls |
| :1031 `clearHistory()` | 改 `stateMutex.withLock { currentState = AgentState() }` |
| :114 `MAX_TOOL_ROUNDS` | 50 → 8 |
| :910 权限请求 | 加超时 |
| `AgentRuntime` | 新增 actor 层 |

### 2.6 验收

1. 单测：两个协程并发 `handleMessage` ×20 轮，断言最终 history 长度 = 预期、无 orphan tool 消息
   （orphan = `role=="tool"` 且 `toolCallId` 在上一 assistant 的 `toolCalls` 中找不到）
2. 单测：`trim` 不破坏 assistant+tool 配对块
3. `AgentSessionTest`(androidTest) / `AgentSessionRefreshTest` 通过
4. 手工：飞书推消息的同时 UI 发消息，两条回复不串台

---

## 方案 3：安全策略上提到统一执行层

### 3.1 现状

- `SecurityReview.reviewTool()` 只在 `DynamicTool.execute()`（:158）调用
- `SkillManager.executeTool()`（:73-94）只查运行时权限，不查安全策略
- 结果：**LLM 现场生成的 JS 要过用户确认；预置的 `send_sms` / `shell.exec` / `file_xfer` / `camera` 直接放行**
- `SkillTool` 接口无风险字段，`DynamicToolDef` 有 `isIdempotent`

### 3.2 设计

**A. 风险等级声明**

```kotlin
enum class ToolRiskLevel { READ, WRITE, DANGEROUS }

interface SkillTool {
    val name: String
    val description: String
    val parameters: Map<String, SkillParam>
    val riskLevel: ToolRiskLevel get() = ToolRiskLevel.WRITE   // 默认保守
    suspend fun execute(params: Map<String, Any>): SkillResult
}
```

分级：
- `READ` — `get_weather` / `search` / `translate` / `read_screen` / `get_location` / 所有 `list_*`
- `WRITE` — `set_reminder` / `add_event` / `input_text` / `click` / `notify`
- `DANGEROUS` — `shell_exec` / `sms_send_sms` / `contact_call_contact` / `file_delete` / `filexfer_*` / `settings_toggle_*`

`DynamicTool` 用 `def.isIdempotent` 映射：`true → READ`，`false → WRITE`（保持现有行为）。

**渐进接入**：默认 `WRITE`，先把明确只读的标 `READ`（避免一次性全弹窗），
`DANGEROUS` 清单先只上 6 个高危工具。

**B. 统一执行入口**

审查上提到 `SkillManager.executeTool()`，内置与动态技能走同一条路：

```kotlin
sealed interface ToolExecutionOutcome {
    data class Done(val result: SkillResult) : ToolExecutionOutcome
    data class NeedsApproval(val toolId: String, val description: String,
                             val risk: ToolRiskLevel) : ToolExecutionOutcome
    data class Denied(val reason: String) : ToolExecutionOutcome
}
```

流程：解析 skill/tool → 统一权限检查 → `SecurityReview` → 执行 / 挂起 / 拒绝。

**C. 审批回调通道**

- `GatewayContract` 加 `suspend fun requestToolApproval(toolId, description, risk): ApprovalDecision`
- `ChatViewModel` 实现（复用现有 `UserPreferenceManager` 持久化 ALWAYS_APPROVE / ALWAYS_DENY）
- `AgentSession.executeToolCall`（:886）遇 `NeedsApproval` → `emit(SessionEvent.ToolApprovalRequest)`
  → UI 弹确认卡 → 用户决策回传 → 继续或返回「用户已拒绝此操作」
- `DynamicTool` 现有的 `onUserConfirmation` 回调可直接上提复用

**D. 统一权限白名单（修 N1）**

```kotlin
interface Skill {
    ...
    val requiredPermissions: List<String> get() = emptyList()
}
```
Skill 自声明，`SkillManager` 注册时收集；`PermissionManager` 只负责检查与请求。
删除 `SkillManager.getRequiredPermissionsForSkill`（:130-153）与
`PermissionManager.getPermissionsForSkill`（:174-182）两份硬编码，合并为一处。

**E. 审查规则**

```kotlin
fun reviewTool(toolName: String, risk: ToolRiskLevel,
               preference: UserApprovalPreference?): ToolSecurityPolicy =
    when (risk) {
        READ -> AUTO_EXECUTE
        WRITE -> when (preference?.decision) {
            ALWAYS_APPROVE -> AUTO_EXECUTE
            ALWAYS_DENY    -> DENY
            else           -> ASK_USER
        }
        DANGEROUS -> ASK_USER          // 即使 ALWAYS_APPROVE 也每次问
    }
```

DANGEROUS 执行前后各写一条 `AuditLogger`（已有 SHA-256 哈希链）。

### 3.3 改动清单

| 文件 | 改动 |
|---|---|
| `skill/SkillTool.kt` | 新增 `ToolRiskLevel` + `riskLevel` 默认属性 |
| `skill/ToolSecurityPolicy.kt` | `SecurityReview.reviewTool` 签名改为收 `ToolRiskLevel` |
| `skill/SkillManager.kt` | `executeTool` 返回 `ToolExecutionOutcome`；接入 `SecurityReview`；删硬编码权限表 |
| `skill/Skill.kt` | `Skill` 新增 `requiredPermissions` |
| `skill/builtin/*.kt` | 19 个技能标注 `riskLevel` + `requiredPermissions`（分批） |
| `skill/DynamicSkill.kt` | `DynamicTool` 的 `riskLevel` 由 `isIdempotent` 映射；审查逻辑上移后可移除本地分支 |
| `agent/AgentSession.kt` | `executeToolCall`（:886）处理 `NeedsApproval`；新增 `SessionEvent.ToolApprovalRequest` |
| `GatewayContract.kt` / `ChatViewModel.kt` | 新增 `requestToolApproval` 通道 |
| `permission/PermissionManager.kt` | 删 `getPermissionsForSkill` 硬编码，改查 Skill 声明 |
| `ui/` | 工具确认卡 UI |

### 3.4 风险

- `SkillResult` → `ToolExecutionOutcome` 是破坏性变更，调用点：`AgentSession:921`、
  `DynamicSkillManager`、`ScriptSkill`、多个测试
- 审批挂起期间 LLM 流式输出已中断，UI 需要「等待确认」态，不能表现为卡死
- 19 个技能逐个标注是体力活，建议按 READ → DANGEROUS → 其余 WRITE 的顺序分三批

### 3.5 验收

1. 单测：`SecurityReviewTest` 扩展覆盖 READ/WRITE/DANGEROUS × 4 种偏好组合
2. 单测：`sms_send_sms` 在无偏好时返回 `NeedsApproval` 而非执行
3. 单测：统一权限表对 `camera` / `calllog` / `storage` 均返回非空（修 N1）
4. 手工：让 LLM 发短信 → 弹确认卡 → 点拒绝 → LLM 收到「用户已拒绝此操作」并继续对话
5. 手工：查询天气不弹窗（READ 直通）

---

## 4. 执行顺序与依赖

```
方案 1（单一 AgentRuntime）
   │  独立，可先做；消除 N2「创建后路由不到」
   ▼
方案 2（单一状态源 + actor）
   │  依赖方案 1 的 AgentRuntime（actor 挂在 runtime 上）
   ▼
方案 3（统一安全层）
       依赖方案 2 的 SessionEvent 扩展点
```

方案 1 和 2 建议同一次 PR 完成（都在动 session 生命周期），方案 3 单独一个 PR。

每步收尾都跑：
```
./gradlew :app:testDebugUnitTest
./gradlew :app:compileDebugAndroidTestKotlin
./gradlew :app:assembleDebug
```
