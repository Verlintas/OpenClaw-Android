# Agent 架构评审（2026-09-23）

> 评审对象：`app/src/main/java/ai/openclaw/android` 下的 Agent 运行时。
> 参照物：`docs/agent-architecture-refactor-plan.md`（三项收敛方案）与
> `docs/agent-architecture-refactor-plan-review.md`（A1–A8 审计）。
> 结论：**骨架合理，方案 2/3 落地质量高；但收敛只完成约 70%，剩余问题是「两套并存」的残留与三条入口的半收敛。**

---

## 0. 一句话结论

不是"架构不合理"，而是**一次正确方向的重构做了一半**：核心状态机与安全层已经收敛得很好，
但宿主层（`GatewayManager`）里那个为兼容而留的单个 `agentSession` 没删干净，
导致多 Agent 系统实际上只有一个 agent 被完整管理；飞书与触发器两条入口也只是"接上了"而非"收敛了"。

---

## 1. 已做对的部分（本次评审确认属实）

| # | 项 | 证据 |
|---|---|---|
| 1 | 会话状态单一真源：`currentState: AgentState` + `stateMutex`，整轮在锁内推进 | `agent/AgentSession.kt:444-445` |
| 2 | 取消语义正确：`finally` + `NonCancellable` 写回已达状态，不回滚（审计 A5 已修） | `AgentSession.kt:736-748`、`:1003-1013` |
| 3 | 孤儿 tool 消息修复：`repairTrailingToolBlock` 补合成结果，防 API 2013 | `AgentSession.kt:1215-1229` |
| 4 | 轮数 50→15 + 循环检测 `ToolLoopDetector`(窗口 5 / 阈值 3)（审计 A6 采纳） | `AgentSession.kt:141`、`:1384-1398` |
| 5 | 审批超时语义已反转：10 分钟未决策 = 取消，DANGEROUS 绝不因超时放行（A3 硬性要求） | `AgentSession.kt:145-149` |
| 6 | Actor 串行化落地：`Channel.UNLIMITED` mailbox + 单消费者，飞书已接入 | `domain/agent/AgentSessionManager.kt:57-81`、`:274-327` |
| 7 | 统一安全层：`SkillManager.executeTool` 是唯一工具入口，云端/端侧/后台三条路径全覆盖（A4） | `skill/SkillManager.kt:94-156` |
| 8 | 权限唯一真源：`Skill.requiredPermissions`，两份硬编码白名单已删（N1 已修） | `skill/Skill.kt:15`、`permission/PermissionManager.kt:164` |
| 9 | 死代码已清理：`AgentRegistry` / `AgentManagementSkill` / `config.AgentConfig` 已不存在（A1） | 源码树已无此三文件 |
| 10 | 端侧档位设计扎实：`OnDeviceProfile` 同时精简 prompt 与工具白名单，并有实测数据支撑 | `agent/OnDeviceProfile.kt:1-20` |
| 11 | 端侧决策旁路：prefill + 1 步 decode 做分类决策，`0.27s/条`，与生成路径解耦 | `agent/decision/OnDeviceDecisionRunner.kt:23-31` |
| 12 | 并发状态有单测覆盖：`AgentSessionStateTest` 覆盖多协程并发 `handleMessage` | `app/src/test/.../agent/AgentSessionStateTest.kt` |

---

## 2. 问题清单（按严重度）

### P0-1 `GatewayManager` 仍残留第二套会话引用，`agentSession` 与 `agentSessionManager` 并存

现象：

- `GatewayManager.kt:600-613` 先 `AgentSession(...)` 建一个会话，`:622-625` 立刻被
  `agentSession = manager.getOrCreate(defaultAgentId)` 覆盖 —— **每次启动白建一个**。
- `reconfigureModel()` `:275-288` 同样白建一个，`:293-300` 再覆盖 —— **每次换模型白建一个**。
- 所有"全局单点"语义仍只看这一个引用：
  `isReady()`（`:168`）、`clearHistory()`（`:361-363`）、`sendMessage` 的 fallback（`:188`）、
  `ActionExecutor.agentSessionFactory`（`:644`）。
- 审计报告 A8-1 明确要求删除的 "Rebuild AgentSession for backward compatibility" 段落未删。

后果（不止是浪费）：

1. `clearHistory()` 只清默认 agent，coder / security 的历史清不掉，用户"新建会话"后切到子 agent 仍带旧上下文。
2. `reconfigureModel()` 换模型时只 `evict(defaultAgentId)`，coder / security 的缓存会话仍持有旧 `ModelClient`。
3. fallback 路径 `agentSession?.handleMessageStream(...)` 完全绕过 Actor，无串行保护。

建议：删除 `agentSession` 字段与其所有重建代码；`isReady/clearHistory/fallback/agentSessionFactory`
一律改为经 `agentSessionManager`；`evict` 改为全量 `cleanup()` 或遍历 `getActiveAgentIds()`。

### P0-2 `AgentSession.setSystemPrompt()` 是死写入，文件版 system prompt 被静默丢弃

- `AgentSession.kt:506` 声明 `private var systemPrompt`，`:511` 写入。
- 但 `buildSystemMessages()`（`:1133-1155`）只读 `_agentConfig?.systemPrompt` 与内置常量，
  **从不读这个字段**。
- `GatewayManager.kt:279-280` 的 `AgentPromptLoader.load(service)` 结果因此被丢弃。

当前真正生效的 system prompt 来自 `assets/agents.json` 每个 agent 的 `systemPrompt` 字段。
也就是说存在两套 prompt 来源，其中一套完全死掉。建议二选一：删掉 `AgentPromptLoader` +
`setSystemPrompt`（连带已 deprecated 的 `SystemPromptLoader`），或让文件版落到 `_agentConfig.systemPrompt`。

### P1-1 动态技能注册后只有默认会话刷新工具列表

`GatewayManager.kt:578-580` 与 `:250-252`：

```kotlin
dynamicSkillManager.setToolsChangedListener { agentSession?.refreshTools() }
```

只刷一个会话。`AgentSessionManager` 缓存的 coder / security 会话不刷新，
LLM 现场生成的新技能对子 agent 不可见。应改为遍历 `getActiveAgentIds()` 逐个 `refreshTools()`。

### P1-2 多 Agent 能力对外不可见，且不可运行时增删

- `GatewayContract.getAvailableAgents()`（`:27`）全项目无调用者 → UI 看不到 agent 列表，
  也没有 `@` 提示，用户不知道有 coder / security 存在。
- `AgentConfigManager` 只有 `loadFromAssets()`，无 create/save，配置只读。
- 路由仅 `@mention` + 关键词（Phase 2 的 LLM 路由未做）。关键词匹配是
  `lowerMessage.contains(keyword)`（`AgentRouter.kt:79-98`）的朴素子串包含：
  `security` 的关键词 `key` 会让"键盘""keyboard"之类输入被路由到安全 agent；
  `coder` 同时含 `bug`/`debug` 也存在互相遮蔽。中文短词误命中风险更高。

### P1-3 飞书与触发器两条入口只有"接上"，没有"收敛"

- 飞书：`GatewayManager.kt:765` `sessionManager.streamMessage(...).collect { /* PR-3 接回发 */ }`
  —— 流式事件全被丢弃，飞书侧收不到任何回复。审计 A2 的一半。
- 触发器：`ActionExecutor.kt:200` 直接 `session.handleMessage(prompt)`，**绕过 Actor**，
  且写进用户主会话历史（`:141` TODO 已自认）。
- sync 路径（`handleMessage`）仍然没有反思（F5 遗留），只有 streaming 路径有（`:959`）。

### P1-4 审批挂起期间整轮锁被持有

`requestToolApproval` 在 `executeToolCall` 内、而后者在 `stateMutex.withLock` 内
（`AgentSession.kt:896`、`:1062`）。用户不决策的 10 分钟里整个会话（含飞书/触发器）被阻塞。
Actor 本身也串行，所以行为可接受，但属于"锁"与"等用户"耦合，建议在文档中写明，
并确认 UI 有明确的等待态（`ChatScreen.kt:839` 有 `pendingToolApproval`，已具备）。

### P2 其他

| 项 | 说明 |
|---|---|
| 并发容器无保护 | `AgentSessionManager.getOrCreate`（`:90-156`）直接改 `sessionCache` / `accessOrder`，无锁；`evict` / `respondToToolApproval` 只对 `actors` 加了 `synchronized`。UI 主线程与 IO 线程都会调用，存在重复创建与 `ConcurrentModificationException` 风险。建议统一用一个 `Mutex` 或 `synchronized(lock)` 包住三个容器。 |
| 审批事件缓冲边界 | `_toolApprovalRequests` 用 `MutableSharedFlow(extraBufferCapacity = 8)`，无订阅者时 `tryEmit` 仍成功；超过 8 条后 `tryEmit` 失败即判为取消。极端并发下会静默拒绝，建议改为 `Channel.UNLIMITED` 或记日志。 |
| 端侧工具白名单维护成本 | `OnDeviceProfile.TOOL_WHITELIST` 是 12 个硬编码全名，新增技能不会自动纳入。注释说明了理由（避免前缀匹配放进整组），合理，但建议加一条单测：白名单里的名字必须存在于 `SkillManager.getAllTools()`，改名时能被测出来。 |
| 死代码 | `SystemPromptLoader`（已 deprecated）+ `AgentPromptLoader` 随 P0-2 一并处理。 |

---

## 3. 建议的修复顺序

```
PR-A  删 backward-compat agentSession（P0-1）+ 清 AgentPromptLoader（P0-2）
      风险低、纯删除，先做；顺带把 clearHistory / reconfigure 的 evict 语义补全
PR-B  动态技能刷新覆盖全部缓存会话（P1-1）
      + AgentSessionManager 三个容器加锁（P2）
PR-C  触发器接入 Actor 独立会话（P1-3）+ 飞书回发流式事件（P1-3）
      + sync 路径补反思（F5）
PR-D  路由增强：关键词改分词/边界匹配，或接 LLM 路由（P1-2）
      + UI 暴露 agent 列表与 @ 提示（P1-2）
```

每步收尾：

```
./gradlew :app:testDebugUnitTest
./gradlew :app:compileDebugAndroidTestKotlin
./gradlew :app:assembleDebug
```

> 本机构建约定见项目记忆：走 PowerShell，`JAVA_HOME` 指到 JBR 21，先 `gradlew --stop`。

---

## 4. 与既有方案文档的对照

| 审计项 | 状态 |
|---|---|
| A1 删 YAML 套死代码（AgentRegistry / AgentManagementSkill / config.AgentConfig） | ✅ 已完成 |
| A2 飞书/触发器接入统一入口 | ⚠️ 飞书接入但回发未做（TODO PR-3）；触发器未接入 |
| A3 审批超时语义反转为"取消" | ✅ 已完成（10 min，DANGEROUS 不放行） |
| A4 统一权限行为、审查覆盖本地路径 | ✅ 已完成 |
| A5 Flow 取消时状态写回 | ✅ 已完成（`finally` + `NonCancellable`） |
| A6 轮数分步降 + 循环检测 | ✅ 已完成（50→15 + 检测器） |
| A7 绿色基线 | ⚠️ 未见基线留档，建议重构前补一次全量 `testDebugUnitTest` |
| A8-1 删 backward-compat 重建段落 | ❌ 未做（P0-1） |
| N1 权限白名单两份矛盾 | ✅ 已完成（`Skill.requiredPermissions`） |
| N2 新建 agent 无工具 | ⬜ 已按审计结论降级为"未来新功能"，当前配置只读 assets，无创建入口 |
| N3 `RoutingConfig.targetAgent` 死字段 | ✅ 随 `config.AgentConfig` 一并删除 |
