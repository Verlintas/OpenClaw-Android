# 设计方案审计报告

> 审计对象：`docs/agent-architecture-refactor-plan.md`（三项收敛方案）
> 审计方式：对方案全部前提假设做代码级复核，重点是方案未覆盖的入口与安全语义。
> 结论：**有条件通过** —— 方案框架成立，但方案 1 的影响面假设错误（应从「合并」降级为「删除死代码」），方案 3 有一个必须反转的安全语义。

---

## A1【高·推翻方案 1 迁移设计】YAML 套是彻底死代码，不是"第二套并行系统"

方案 1 的前提是"两套系统都在运行"。复核证据链：

| 断言 | 证据 |
|---|---|
| `AgentRegistry` 从未被实例化 | 全项目 `AgentRegistry(` 构造零处；`GatewayManager` 仅 :89 字段声明、:467 `agentRegistry = null`，无任何非 null 赋值 |
| `sendMessageToAgent` 零调用者 | 全项目仅 :172 定义处 |
| `GatewayManager.listAgents()` 零调用者 | 全项目仅 :179-180 定义处 |
| `AgentManagementSkill` 零实例化、零注册 | `AgentManagementSkill(` 仅 :6 定义；19 技能注册列表（`SkillManager:17-41`）与 `GatewayManager:246/:573/:656` 均无它 |

推论：`agentRegistry` 恒为 null → `sendMessageToAgent` 恒走 `"AgentRegistry not ready"` → `listAgents()` 恒空 → **评审报告里"对话创建的 agent 路由不到"这个 N2 功能断裂，实际是"该功能整体不存在"，而非"存在但坏了"**。

### 对方案 1 的修订：从「合并」降级为「纯删除」

原方案的这些工作全部**取消**（为死代码做迁移没有意义）：
- ~~两级 merge 配置源~~（只需保留现有 `AgentConfigManager` 读 assets）
- ~~config.yaml 一次性迁移~~（无存量数据：`AgentManagementSkill` 从未注册 → 没有任何对话创建过 agent；`files/agents/` 下最多只有 assets 拷贝的 main）
- ~~SOUL.md 收敛为 systemPrompt~~（`assets/agents/main/SOUL.md` 是死资源，`AgentPromptLoader.loadForAgent` 唯一调用点在死代码里）
- ~~AgentManagementSkill 改造~~（删除）

修订后 PR-0 清单（半天工作量，风险极低）：
1. 删 `agent/AgentRegistry.kt`、`config/AgentConfig.kt`（含死字段 `RoutingConfig.targetAgent`）、`skill/builtin/AgentManagementSkill.kt`
2. 删 `GatewayManager` :89 字段、:172-174 `sendMessageToAgent`、:179-180 `listAgents`
3. 删 `build.gradle.kts` 的 snakeyaml（唯一使用者是 AgentRegistry）；删 `assets/agents/` 死资源
4. 删 `AgentRegistryTest`（测的是死代码，不是"重写"）
5. 同名陷阱：`data/model/AgentConfig.kt` 里的 `@Serializable data class AgentRegistry` 是 JSON 容器，与被删的 `agent/AgentRegistry` 同名但无关，**保留**
6. 原方案 1.6 里"必须原样搬进 AgentRuntime 的 fallback 语义"随之作废——那是给死代码写的

「对话创建 agent」降级为**未来新功能**（届时走 JSON 套加 `AgentConfigStore`），不进本次改造范围。

---

## A2【高·方案 1 遗漏第三条消息路径】飞书走 agentSession 兼容路径

`GatewayManager:747`：

```kotlin
agentSession?.handleMessage(message.content)   // fire-and-forget，launch 不 collect
```

三条路径现状：

| 入口 | 调用 | 路由 | 流式 | 反思 |
|---|---|---|---|---|
| UI | `sendMessage` → JSON 套 | 有 | 有 | 有 |
| 飞书 | `agentSession.handleMessage` | 无 | 无 | 无 |
| 触发器 `ActionExecutor` | （未逐一验证，方案需补查） | ? | ? | ? |

后果：UI 与飞书的对话是**两个独立 AgentSession**——用户在飞书说的话，UI 会话的 LLM 看不到（仅靠 Memory 注入间接感知）。方案 1 的改动清单只覆盖 `sendMessage`/`sendMessageToAgent`/`listAgents`，漏了 :747。

修订：
- AgentRuntime 落地时飞书入口改走 `runtime.route(text) + runtime.session(id)`，否则合并后仍是"三入口两份历史"
- :747 的 fire-and-forget 顺手修：改为收集流式事件回发飞书，错误进 `CrashRecord` 而非静默丢弃
- 增加待办：核查 `trigger/ActionExecutor` 的 LLM 调用入口，若也持有独立 AgentSession，一并收敛

---

## A3【高·方案 3 安全语义必须反转】现有审批超时是 auto-approve

`GatewayManager:793-797`（`createDynamicSkillManager` 的确认回调）：

```kotlin
withTimeout(30_000L) { deferred.await() }
catch (TimeoutCancellationException) {
    Log.w(TAG, "Confirmation timed out for $toolId, auto-approving")
    ApprovalDecision.ALWAYS_APPROVE      // ← 超时 = 自动批准
}
```

且 :786-789：确认请求 emit 失败时同样 auto-approve。

这意味着**今天就存在**一个安全漏洞：用户锁屏/切走/确认卡未渲染时，LLM 生成的脚本 30 秒后被静默批准执行。

方案 3 的致命问题：原文说"DynamicTool 现有的 `onUserConfirmation` 回调可直接上提复用"——若原样复用这套语义，等于把 auto-approve **扩散到内置高危工具**，方案 3 会让安全变差而不是变好。

修订（写入方案 3 的硬性要求）：
1. 超时默认改为「视为取消」（返回 null / DENY）；`DANGEROUS` 一律超时即拒绝，无例外
2. 审批请求不可达时（后台/锁屏）应**挂起等待**而非自动批准，用户下次打开再决策；挂起上限建议 10 分钟
3. emit 失败分支同步改为拒绝
4. 新增验收：锁屏状态下让 LLM 调 `sms_send_sms` → 解锁后必须看到待确认卡，而不是短信已发出

---

## A4【中·方案 3 覆盖面证实 + 一处行为不一致】本地模型路径

- 好消息（方案 3 选型被证实）：本地模型工具执行 `GatewayManager.executeLocalTool`（:813-845）同样调 `sm.executeTool` —— 审查放在 `SkillManager` 层可自动覆盖云端+本地两条路径，无需额外工作
- 行为不一致：缺权限时，云端路径（`AgentSession:902-918`）会弹授权 UI，本地路径被 `SkillManager:88-91` 静默返回失败（只查不请求）。方案 3 统一入口后需定统一行为：建议统一为「权限缺失 → 发授权请求事件」，消灭静默失败

---

## A5【中·方案 2 技术缺口】Flow 取消时状态丢失

方案 2 的出口 `stateMutex.withLock { currentState = state }` 假设循环正常走完。但 `handleMessageStream` 是 Flow——用户离开页面/取消生成时，collect 侧协程被 cancel，**取消点之后的写回不执行**，半轮对话状态直接丢；且可能留下半截 tool 消息（原子块 trim 也救不回孤儿 tool_call）。

修订：
- `flow { try { ... } finally { ... } }` 或 `onCompletion`：无论正常/异常/取消，都把已达状态写回（`finally` 里不能调用挂起的 `withLock`？可以，但要用 `withContext(NonCancellable)` 包住）
- 取消语义定为「保留已执行轮次 + 追加取消标记」，不回滚
- 新增验收：生成中途按返回键退出再进入，会话历史完整、无孤儿 tool 消息

---

## A6【中·方案 2 行为变更风险】50→8 轮砍得太急

多步任务（搜索→读结果→再搜索→汇总）真实可能超过 8 轮，直接砍会引发"任务做不完"回归。

修订：先 50→15 + 循环检测（同 `(toolName, argsHash)` 连续 3 次即断），利用现有 `CrashRecord.logAgentSessionError("max_rounds_exceeded", ...)` 埋点（:587）观察两周触发分布，再定终值。数据驱动，不拍脑袋。

---

## A7【低·执行遗漏】绿色基线

三个方案都没写"动刀前先建立基线"。本机构建有已知约束（PowerShell 工具、JBR 21、先 `gradlew --stop`）。要求：PR-0 之前先跑全量 `testDebugUnitTest` 确认当前全绿并留档，否则重构后无法归因。

另：`AgentSessionFactoryTest` / `AgentSessionRefreshTest` 依赖 `setAgentConfig(config.AgentConfig)`，PR-0 删类型后这两个测试要同步调整——方案 1 改动清单漏列。

---

## A8【低·待确认项】

1. `GatewayManager:256-276` 那段 "Rebuild AgentSession for backward compatibility" 在 AgentRuntime 落地后应整体删除，方案 1 改动清单漏了这段
2. PluginManager 注册的是 Plugin 而非 Skill，未见桥接进 `SkillManager` 的证据。若插件工具将来要暴露给 LLM，需先过 `registerSkill`——方案 3 的审查层在 SkillManager 层已天然就位。不阻塞，列为观察项
3. 方案 3 `SkillTool.riskLevel` 默认 WRITE + `DynamicTool` 的 `isIdempotent→READ` 映射：复核无回归风险，维持

---

## 修订后执行顺序

```
PR-0  绿色基线 + 删除死代码（A1）          ← 半天，风险极低，先做
PR-1  方案 2：状态单一真源 + actor
      + A5 取消语义 + A6 分步降轮数
PR-2  方案 3：统一审查层
      + A3 反转超时语义（硬性）
      + A4 统一权限行为
PR-3  飞书/触发器入口接入 AgentRuntime（A2）
```

原方案的「方案 1+2 同 PR」建议撤销：方案 1 已瘦身为纯删除，单独成 PR-0 更安全；actor 与状态重构留在 PR-1。

## 方案中经复核维持原样的部分

- MAX_TOOL_ROUNDS=50、双份 history、三处行号引用（:114/:398/:601-602 等）全部属实
- 保留 JSON 套的选型依据成立（字段、`createModelClient`、测试）
- trimHistoryByTokens 原子块逻辑保留、estimateTokens 低估、权限请求无超时——均属实
- N1（权限白名单两份矛盾）、N3（targetAgent 死字段）属实；N2 按上表修订结论处理
