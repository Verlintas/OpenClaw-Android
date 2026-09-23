# 端侧决策模式（Decision Mode）方案设计

> 2026-09-22。基于《jev-decision-model-integration-2026-09-22.md》的分析与 LiteRT-LM 0.10.0 API 实测调研，
> 给出可落地的工程方案。目标：**把项目里五处"伪装成 LLM 生成的判断"改造成 prefill+1 步 decode 的决策调用，
> 端侧单次判断从数秒级降到数百毫秒级**。

---

## 0. M0 真机验证结果（2026-09-22，荣耀 DNP-AN00 / GPU backend / 窗口 8192）

探针入口：`adb shell am broadcast -a ai.openclaw.android.TEST_DECISION_PROBE`（输出 tag `LocalLLMClient`）。

| 项 | 实测 | 结论 |
|---|---|---|
| 槽位关系 | Conversation 开启时 `createSession()` → `FAILED_PRECONDITION: A session already exists. Only one session is supported at a time.` | **验证 B：Session 与 Conversation 共享唯一槽位** → 决策前必须释放主会话（已由 `onBeforeSession` 钩子实现），代价是主会话 KV-cache 作废 |
| 主会话受影响吗 | 决策失败/成功前后 `Conversation.isAlive=true`，二次 `sendMessage` OK（111ms） | 决策不会损坏生成会话对象本身（只是要重建 prefill） |
| 决策延迟 | 单条 `prefill=50ms + decode=214ms`；批量 header 50ms，逐条 `prefill≈50ms + decode≈220ms` | **≈0.27s/条**，与预估 0.3–0.6s 一致；**decode 占 80%，且不能被批量摊薄** |
| 决策后恢复 | 释放 Session 后重建 Conversation 成功（268ms） | 生成路径可正常恢复 |
| **准确性（首跑）** | 5 条样本：招行支出→C✅、微信闲聊→B✅、京东快递→C✅、头条热点→B✅、**淘宝红包→C❌**；**从未输出过 A（无价值）** | 存在明显选项偏置与中文校准问题 → 印证知识库"校准是上手第一件事"，阈值/决策表必须自标后迭代 |

**对设计的两处修正**：
1. 批次上限从 20 改为 **10**（0.27s/条，20 条 ≈5.4s 会顶到 SmartFilter 15s 外层超时）；个人中心加 12s 总截止，超时条目按 0.5 保留（宁可多显示不误杀）。
2. 单条 decode 固定 ~220ms 说明 **批量摊不掉 decode 开销**，后续若嫌慢只能减条数或降模型/后端，不能再指望"共享前缀"进一步加速。

## 1. 可行性调研结论（已验证的 API 事实）

对 `com.google.ai.edge.litertlm:litertlm-android:0.10.0` 的 AAR 做了 javap 反汇编，关键事实：

| 事实 | 依据 | 影响 |
|---|---|---|
| **公开 API 不暴露 logits / token 分布** | 全部 64 个类中无 Logits/TokenScore/Probability 类型；`Session.runDecode()` 返回 `String` | SemIf「截 logits 读分布」路线在 0.10.0 不可行 |
| **`Engine.createSession(SessionConfig)` 提供低层 Session** | `Session.runPrefill(List<InputData>)` + `Session.runDecode()` | ✅ 决策模式的正确载体：prefill 决策提示 → decode 一步拿答案 token |
| **SamplerConfig 可配置采样参数** | `SessionConfig(SamplerConfig)` | 决策会话设 `temperature=0, topK=1` → decode 即确定性 argmax |
| **Conversation API 只有 sendMessage/Async** | 返回 `Message`（文本+toolCalls），无分数 | 高层 API 做不了决策，必须用 Session |
| **引擎单会话槽位** | 实测 `FAILED_PRECONDITION: A session already exists` | Session 与 Conversation 是否共享槽位**未知，真机第一优先验证**（见 §6） |

**结论：走「prefill + 1 步 decode」路线**（SemIf 的降级版）。没有概率分布，置信度改用**选项置换一致性**近似（见 §4.3）。

---

## 2. 总体架构

```
┌─────────────────────────────────────────────────────────┐
│ 接入层（五处改造点，见 §5）                                │
│  SmartFilter  │  LlmMemoryExtractor  │  ActionExecutor   │
│  AgentSession(路由)  │  ScreenSkill(GUI选点, 后置)         │
└──────────────┬──────────────────────────────────────────┘
               │ DecisionRequest{state, question, options, stakes}
┌──────────────▼──────────────────────────────────────────┐
│ OnDeviceDecisionRunner（新组件，唯一持有决策 Session）      │
│  · decide(): prefill(state+question) → 1×runDecode()     │
│  · decideBatch(): 共享 state 前缀，逐条增量 prefill        │
│  · 选项字母协议 + 解析 + 未知输出升级                      │
│  · 置换一致性置信度（stakes=HIGH 时双跑）                  │
└──────────────┬──────────────────────────────────────────┘
┌──────────────▼──────────────────────────────────────────┐
│ LocalLLMClient（扩展现有类）                              │
│  · decisionMutex（与 sessionMutex 协调，见 §6）            │
│  · 复用已初始化的 Engine（决策不新建引擎、不重载模型）        │
│  · 引擎未加载 → 返回 DecisionUnavailable，调用方走各自降级   │
└─────────────────────────────────────────────────────────┘
```

新增文件：
- `agent/decision/DecisionProtocols.kt` —— 请求/结果数据类 + 场景决策表（prompt 模板、选项集、stakes）
- `agent/decision/OnDeviceDecisionRunner.kt` —— 决策执行器
- 修改：`LocalLLMClient.kt`（暴露 `decisionRunner`）、`SmartFilter.kt` 评估器实现、`ActionExecutor.kt`、`PersonalCenterViewModel.kt`、`LlmMemoryExtractor.kt`

---

## 3. 决策协议设计

### 3.1 选项字母协议

每个决策 = `state`（非结构化输入）+ 一个 `question` + 固定 `options`。提示模板（全英文模板 + 中文输入，Gemma 对英文指令遵循更好）：

```
You are a decision engine. Read the input and answer with EXACTLY ONE letter.
A = <option A description>
B = <option B description>
C = <option C description>
Do not explain. Answer with a single letter only.

Input:
<state>

Answer:
```

- `runDecode()` 一步输出的首字符即答案字母。
- **解析容错**：取返回文本的首个 `[A-<maxLetter>]` 字符；无效输出（空/其他字）→ `DecisionResult.invalid` → 调用方走升级路径。
- 选项数 ≤ 8（远低于 Jev 的 255 上限；字母协议下单 token 可表达 A–Z+）。

### 3.2 数据类

```kotlin
enum class Stakes { LOW, HIGH }   // HIGH 触发置换一致性双跑

data class DecisionRequest(
    val id: String,               // 调用方追踪用
    val state: String,            // <500 token，硬上限
    val question: String,
    val options: List<String>,    // 顺序敏感，置换一致性要打乱重跑
    val stakes: Stakes = Stakes.LOW,
)

data class DecisionResult(
    val chosenIndex: Int?,        // null = 无效输出/引擎不可用
    val confidence: Double,       // 置换一致时 1.0 / 不一致 0.0 / 单跑 0.7（见 §4.3）
    val latencyMs: Long,
)
```

### 4. OnDeviceDecisionRunner 核心逻辑

### 4.1 单条 decide()

```kotlin
suspend fun decide(req: DecisionRequest): DecisionResult = decisionMutex.withLock {
    withContext(Dispatchers.IO) {
        val session = engine.createSession(SessionConfig(SamplerConfig(temperature = 0.0, topK = 1)))
        session.use {
            it.runPrefill(listOf(InputData.Text(renderPrompt(req))))
            val out = it.runDecode()          // 一步，argmax
            parseLetter(out, req.options.size)
        }
    }
}
```

延迟预算（荣耀 DNP-AN00，CPU 档实测推算）：prefill ~200–400 token 提示 + state ≈ 200–500ms；1 步 decode ≈ 50–100ms；**单次决策 ≈ 0.3–0.6s**。对比现状：SmartFilter 全量生成 10s+ 超时、规则 AgentQuery 3–10s。

### 4.2 批量 decideBatch()（通知过滤用）

利用知识库验证的「共享 state 编码」思想，手动实现 KV 复用：

```
session.runPrefill(SYSTEM + 使用说明)          // 一次
for each item:
    session.runPrefill(item 文本 + "Answer:")   // 增量，只 prefill 本条
    session.runDecode()                         // 1 步 → 字母
```

- 20 条通知 ≈ 每条增量 ~60 token prefill + 1 decode ≈ **总耗时 2–3s**（现状 15s 超时档）。
- 批次结束关闭 Session；跨批次不复用（通知批次间 state 无公共前缀收益）。
- **注意**：批次内选项序列相同（同一决策表），只有输入变——这正是增量 prefill 有效的前提。

### 4.3 置换一致性置信度（无 logits 下的近似）

- `stakes = LOW`（通知分类、记忆门）：单跑，confidence 固定 0.7，结果直接消费（判错代价低，且有缓存与人工可见性兜底）。
- `stakes = HIGH`（规则唤醒门、意图路由）：跑两次，第二次选项顺序置换（A↔C 等）：
  - 两次一致 → confidence 1.0，按阈值分流；
  - 不一致 → confidence 0.0 → **升级路径**（保持现有行为：规则门直接放行 AgentQuery / 路由上云）。
  - 成本 = 2 次决策 ≈ 1s，仍远低于一次完整生成。

### 4.4 阈值分流（对齐知识库建议，阈值自标后修订）

| confidence | 行为 |
|---|---|
| ≥ 0.7（HIGH 场景：双跑一致） | 自动执行决策结果 |
| 0.5–0.7 | 执行但打标（日志/Debug 面板可见） |
| < 0.5 或 invalid | 升级：走原全量生成路径或确定性降级 |

---

## 6. 会话槽位与并发策略（关键风险区）

**问题**：引擎同一时刻只允许一个会话（Conversation 实测 FAILED_PRECONDITION）。Session 是否与 Conversation 共用槽位**未验证**。两种情况：

- **验证 A：Session 与 Conversation 槽位独立**（乐观）→ 决策与主聊天完全并行，零互相干扰。
- **验证 B：共享槽位**（保守假设，设计按此兜底）：
  1. `decisionMutex` 与 `sessionMutex` 用**同一个锁对象**，决策进锁后若 `cachedConversation != null` 先 `closeCachedConversation()`（与 `chat()` 旁路同款策略）；
  2. **活跃聊天 defer**：`AgentSession` 暴露 `isBusy`（正在流式生成中）；决策入口遇到 busy 时入队延迟到空闲（通知过滤晚 2s 无感，聊天被打断有感）；
  3. 决策完成后不清主会话缓存——由下一次 `canReuseConversation()` 判定前缀是否还有效（Session 的 prefill 若写入了独立上下文则不影响；验证 B 下 close 掉的重放即可）。

**真机验证步骤**（实施第一步）：
```
初始化引擎 → createConversation(A) → createSession(B) → runPrefill/runDecode(B)
→ 观察 A.isAlive() 与下一次 A.sendMessage 是否报错
```

---

## 5. 五处接入点方案

### P3-1 SmartFilter 决策化（第一优先，收益最大）

**现状**：`PersonalCenterViewModel.evaluateWithLLM()` 用 `contract.sendMessage()` 走完整会话生成 JSON，端侧 15s 超时（`withTimeout(15_000L)`），超时降级关键词。

**改造**：
- `SmartFilter.llmEvaluator` 的实现换成 `OnDeviceDecisionRunner.decideBatch()`；
- 决策表：单条三分类 `A=无价值(营销/签到/推广) / B=一般(社交/新闻/更新) / C=高价值(验证码/银行/日程/快递/工作)` → 映射 value：A→0.1 / B→0.5 / C→0.9（沿用现有 `>= 0.3f` 保留线，不动 SmartFilter 主体）；
- state = `app包名 | 标题 | 正文前 100 字`（现 batchText 格式去掉 id 前缀，改逐条）；
- 降级链不变：决策不可用 → 关键词模式（已存在）；超时 15s → **3s**（决策批量正常 2–3s 内完成）；
- **云端模式不动**：`setGatewayContract` 注入的云端评估器保留，LOCAL 档才切决策器。

**验收**：个人中心通知列表刷新无 10s+ 卡顿；logcat 出现 `DecisionBatch: N items in Xms`；过滤结果与关键词模式对比无大规模误杀。

### P3-2 记忆抽取前置门

**现状**：`LlmMemoryExtractor`（`domain/memory/MemoryExtractor.kt`）每轮对话全量生成；且经 `chat()` 旁路会作废主会话 KV-cache。

**改造**：抽取前先跑一条两选项决策 `A=不含值得长期记忆的信息 / B=含`（state=最近 2–4 轮对话压缩文本，<300 token）。
- A（或双跑不一致）→ 跳过抽取，**不进 `chat()`**——顺带消掉一次 KV-cache 作废；
- B → 走现有 `LlmMemoryExtractor` 全量抽取不变。
- 预期：大部分闲聊轮次省掉一次生成 + 一次主会话重建。

### P3-3 规则唤醒门（ActionExecutor）

**现状**：规则命中 →（120s 全局闸门 + 单规则冷却）→ `executeAgentQuery` 完整生成。频率闸门管不住「低价值事件命中高价值规则」。

**改造**：闸门通过后、生成前插入决策：
```
question: "Does this event justify waking the AI agent to generate a response?"
options:  A=No(纯系统通知/营销/用户无感知)  B=Yes(用户需要 AI 处理或回复)
stakes:   HIGH（双跑一致性）
```
- 不一致 → 放行（保守，维持现状行为，宁可多生成不可漏处理）；
- state = 事件源 + 标题 + 摘要（<200 token）。
- 与 120s 闸门互补：闸门管频率，决策管语义必要性。

### P3-4 端侧意图路由（LOCAL 档主聊天）

**现状**：LOCAL 档所有消息全量进入 8192 窗口生成。

**改造**（`AgentSession` 或 `AgentSessionManager` 分流层）：
```
options: A=闲聊寒暄(短回复即可)  B=需要调用工具  C=复杂任务(多步/长文)
```
- A → 跳过工具注入直接短生成（省 tools schema prefill ~1000+ token）；
- B → 正常流程；C → 提示用户可切云端（UI 现有档位机制），本地仍兜底执行。
- stakes=HIGH，双跑。**第三期再做**——改主链路风险最高，前四项验证决策模式稳定性后再上。

### P3-5 GUI 自动化选点（后置）

依赖记忆里三个已定位的无障碍/MediaProjection 隐患修复（`MyAccessibilityService:504` MediaProjection 类型缺失、touch exploration flag、主线程 CountDownLatch 自锁）。修复后：`read_screen` 输出编号候选 → `decide()` 选编号（选项=候选数，≤8 超出分页）。本设计只预留决策表，不展开。

---

## 7. 校准与评测计划（知识库强调的"第一件事"）

1. **数据标注**：从真机导出 200–500 条真实通知（标题+正文+来源），人工标 A/B/C 三档（用现有 CenterItem 导出或 logcat 抓取）。
2. **对照跑分**：同一批数据跑 ① 关键词模式 ② 决策模式（INT4 端侧）。产出混淆矩阵 + 分档一致率。
3. **阈值校准**：由于拿不到 logits，校准对象是**置换一致性有效率**——记录双跑一致率随场景的分布；一致率 < 80% 的决策表要拆问（知识库结论：拆窄问 62.6%→95%）。
4. **中文专项**：标注集必须以中文通知为主（知识库明确中文校准退化）；若三分类一致率低，降级为两分类（有价值/无价值）。
5. 指标入库：`DecisionMetrics`（次数、一致率、平均延迟、invalid 率）进 `LogManager`，Debug 面板可见。

---

## 8. 实施顺序与里程碑

| 阶段 | 内容 | 验收 | 状态 |
|---|---|---|---|
| **M0 槽位验证**（半天） | 真机验证 Session/Conversation 槽位关系 + runPrefill/runDecode 延迟实测 | 确定走并行还是互斥路径；拿到单次决策实测延迟 | ✅ 完成（见 §0） |
| **M1 Runner 落地**（1 天） | `DecisionProtocols` + `OnDeviceDecisionRunner` + `LocalLLMClient` 接线，编译通过 | 单元冒烟：mock 引擎跑通 decide/decideBatch/置换 | ✅ 完成（编译通过，探针可跑） |
| **M2 SmartFilter 决策化**（1 天） | P3-1 全链路 + 批次上限 10 + 12s 总截止 | 真机：通知列表刷新耗时；标注集一致率报告 | ✅ 已通过真机验证（`DecisionBatch: 1 条 in 744ms`） |
| **M3 记忆门 + 规则门**（1 天） | P3-2 + P3-3 | 真机：闲聊轮记忆抽取跳过率；规则误唤醒下降；无 FAILED_PRECONDITION 回归 | 🟡 P3-2 已完成；P3-3 未开始 |
| **M4 意图路由**（0.5 天，可选） | P3-4 | LOCAL 档闲聊首 token 延迟下降 | ⬜ 未开始 |
| **校准迭代**（持续） | §7 计划，阈值与决策表迭代（M0 已暴露选项偏置） | 一致率 ≥ 80% 后再考虑扩选项/扩场景 | ⬜ 未开始 |

### 真机复验阻塞项（2026-09-22 更新）

**挂起 90 分钟问题已解**：监听服务被系统挂起后（`Scheduling restart of crashed service ... in 5409999ms`），
用 adb 去授权再授权 `enabled_notification_listeners` 可强制立即重绑（`dumpsys notification`
的 `Live notification listeners` 段出现 `INotificationListener$Stub$Proxy` 即真绑上），
随后服务开始正常过滤真实通知。

**新发现的系统性阻塞 —— 间歇性 SIGSEGV（优先级应高于决策模式）**：
同签名 native crash 出现两次（23:27:43 与 23:48:19）：

```
F libc: Fatal signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x0
backtrace: #00-#03 liblitertlm_jni.so（PC 偏移两次完全一致）
```

- 两次都在「模型加载完成后第一次 chat 之后 ~10s」；同流程 23:29 那次却正常生成（4093ms）→ **间歇性**。
- 其中 23:27 那次发生在个人中心打开之前（决策代码不可能参与）→ 与决策模式无因果关系，
  属既有 P2 遗留项「`release()` 加锁 / SIGSEGV」，需作为独立高优先项处理（当前它会随机杀死
  应用进程，连带监听服务被挂起、验证链路全断）。
- 崩溃后监听服务会再次被挂起（这次是 1000ms 重启，属正常），需再按上面的 adb 套路确认绑定。

**P3-1 端到端验证状态：✅ 已通过（2026-09-23 00:49）**

真机观察点全部命中：

```
D/PersonalCenterVM: Raw items: 13 (notif=11, cal=2, sms=0, call=0)
I/DecisionRunner  : DecisionBatch: 1 条 in 744ms，无效 0 条
D/PersonalCenterVM: After SmartFilter: 12 → After dedup: 12 → Final items: 12
```

- 决策模式在真实通知流上跑通，`OnDeviceDecisionRunner.decideBatch()` 端到端执行，无 10s+ 卡顿。
- 单次含 Session 建立 744ms（M0 纯推理约 0.27s/条，差额是 Session 创建 + 首条 prefill）。
- 注意 `SmartFilter` 带 1 小时 TTL 结果缓存：**重复通知不会重复送 LLM**，验证时必须投
  *全新标题+正文* 的通知，否则 `pending` 为空、`DecisionBatch` 不出现。

### 顺带定位并修复的三个真 bug（2026-09-23，均已真机验证）

### 决策模式校准结果（2026-09-23 01:28，21 条标注集）

**变更前置**：`decideBatch` 已从「共享会话 + 每条增量 prefill」改为**每条独立会话**。原因：

> 校准首跑时，同一 Session 内连续 prefill 到**第 12 条左右模型开始失控** —— 不再输出单个字母，
> 而是凭记忆续写后面的 `Item 12:` `Item 13:` …（内容失真），那次 decode 还拖到 **20 秒**。
> 随后整个校准协程挂死、报告永远不落地。而生产 P3-1 的批次上限正好是 **10 条**，
> 落在崩坏阈值边缘，这是必须修的生产隐患。

每条重建会话的代价只有重复 prefill 一次 header（约 50ms），decode 仍占单条耗时 80%，实测总量几乎无损。
同时加了「模型续写」检测：单次输出 > 16 字符即判无效（正常只该吐 `" A"` 这种 1 token）。

改后真机跑满 21 条 ×2 轮的校准结果：

| 指标 | 数值 | 说明 |
|---|---|---|
| 一致率 | **81.0%**（原序与置换序同为 81.0%） | ≥80% 达标线 |
| 可解析率 | 21/21 | 无「模型不吐字母」的情况 |
| 位置稳定性 | 17/21 = **81.0%** | 拿不到 logits 时最接近置信度的代理指标，>70% 可用 |
| 延迟 | 371ms/条 原序、314ms/条 置换序 | 对比 M0 纯推理 0.27s/条，多出的是会话重建 |

**但混淆矩阵暴露了明确的失效方向**（这才是校准的价值，一致率本身会骗人）：

```
          预测无  预测一般  预测高
期望无        3        2        2     ← 7 条里判错 4 条，2 条判成了「高价值」
期望一般      0        7        0
期望高        0        0        7
```

- 「一般」「高」两档近乎完美，错误**全部集中在「无价值」档**；
- 典型错判：淘宝限时秒杀、唯品会优惠券 → 判成「高价值」；拼多多砍价、WiFi 万能钥匙 → 判成「一般价值」；
- 生产后果很直接：SmartFilter 只有 0.1 分（无价值）才被过滤，被判成 0.5/0.9 的广告**全部留在列表里**，
  即当前的决策模式过滤基本不生效 —— 这与 M0 探针首跑观察到的「从不输出 A」是同一个偏置的延续。

**下一步校准方向**（按性价比排序）：
1. 把三档改成**二分类**（「值得保留 / 应当过滤」）—— 选项越少偏置越小，且生产只关心这一个决定，
   三档里的「一般 vs 高」区分对过滤毫无用处，纯属给模型增加难度；
2. 或在「无价值」选项描述里直接写死判定标准并给 1~2 个示例（few-shot），例如
   「注意：限时秒杀、优惠券、砍价、清理提醒一律属于本档」；
3. 之后再考虑把 DecisionStakes 提到 HIGH 做双跑校验（代价是延迟翻倍）。

复跑命令：`adb shell am broadcast -a ai.openclaw.android.TEST_DECISION_CALIB`（tag `DecisionCalib`）。

### 校准 v2：按方案 2 优化「无价值」选项（2026-09-23 07:12，真机）

**变更**（用户拍板保留三档、不降二分类，按上面第 2 条方案优化）：
- 判据 + few-shot 直接写进「无价值」选项描述：限时秒杀/打折促销/优惠券红包/砍价助力/签到打卡/
  游戏活动/清理加速/标题党资讯「一律归入本档，不因文案里的『限时』『专属』『免费』而改变」，附 3 个示例；
- QUESTION 加提示「营销推广一律按最低档处理」；
- 决策表抽成共享单源 `NotificationValueDecision`（生产 `PersonalCenterViewModel` 与校准
  `DecisionCalibration` 直接引用同一份 OPTIONS/QUESTION/VALUES，杜绝两处漂移）。

结果（同一 21 条标注集 ×2 轮）：

| 指标 | v1 | v2 原序（=生产顺序） | v2 置换序（长描述位于中间位） |
|---|---|---|---|
| 一致率 | 81.0% | **76.2%**（16/21）↓ | **90.5%**（19/21）↑ |
| 期望「无」行 | 3 对，2→一般，2→高 | 2 对，1→一般，**4→高** | **6 对**（仅京东新品首发→高） |
| 预测分布 | 无3 / 一般9 / 高9 | 无2 / 一般8 / **高11**（偏向末位） | **完美 7/7/7** |
| 位置稳定性 | 81.0% | 76.2%（16/21） | — |
| 延迟 | 371ms/条 | 497ms/条（长描述 prefill 变长） | 469ms/条 |

逐条变化：淘宝秒杀原序修复（高→无）；梦幻西游签到、京东新品首发原序新错成「高」；
置换轮里拼多多/唯品会/WiFi 全部修复。新失误：**未接来电在置换轮被判「无」**（原序正确）——
这是唯一涉及「高价值被过滤」方向的错判，比广告没滤掉更危险，后续迭代要盯住。

**关键发现：判据内容有效，但选项位置决定它是否被读到。**
- 同一份长描述放在选项**第一位**（原序）时，模型明显不读它：分布向末位「高」漂移（11/21）；
- 放在**中间位**（置换序实际顺序为 高/无/一般）时完全生效：6/7 命中 + 分布 7/7/7；
- v1 两轮同分（81/81），排除「第二轮热身效应」——两轮差距就是选项顺序本身。

**结论：v2 不应直接上线（生产走原序，76.2% 低于 v1 的 81%）。**

**v3 方案**（仍属方案 2 范畴：判据保留在选项描述里，只调位置）：
把 `NotificationValueDecision.OPTIONS` 重排为置换轮验证过的顺序 **[高, 无, 一般]**，
`VALUES` 同步为 `[0.9, 0.1, 0.5]`。注意三处：
1. `DecisionCalibration.SAMPLES` 的 `expected` 是硬编码索引，必须同步换算；
2. `PersonalCenterViewModel` 按 `chosenIndex` 索引对齐取 `VALUES`，随对象引用自动生效；
3. `permute/unpermuteIndex` 是纯轮转，与基础顺序无关，HIGH 双跑不受影响。

前置步骤：设备重连后**先零成本复跑一次 v2 校准（n=2，已装机无需重建，直接重播广播）**，
确认「原序差 / 置换序好」的差距稳定；若复跑翻盘则回退考虑把判据挪进 QUESTION（选项保持短描述）。

**待办：n=2 复核 + v3 重排实验（设备离线时记录）→ 已于当晚 22:15 完成，见下节。**

### 校准 v3：选项重排为 [高, 无, 一般]（2026-09-23 22:15，真机）

**① n=2 复核（22:11，机上是 v2，零成本重播广播）**：
v2 首跑与复跑**逐条 100% 一致**（temperature=0/topK=1 的确定性引擎无随机性）：
原序 76.2% / 置换序 90.5%、分布 2-8-11 与 7-7-7、混淆矩阵期望无行 2/1/4、21 条预测全部相同。
「原序差 / 置换序好」的差距**确凿而非单次波动** → v3 重排实验放行。

**② v3 变更**：`NotificationValueDecision.OPTIONS` 重排为 **[高, 无, 一般]**，`VALUES` 同步为 `[0.9, 0.1, 0.5]`；
`DecisionCalibration` 侧同步换算：`LEVEL_NAMES` 改为 `[高, 无, 一般]`、21 条标注改用
`TIER_HIGH/TIER_NONE/TIER_NORMAL` 常量（避免魔法数字错位）、混淆矩阵表头改为动态生成。
已核查无副作用：`PersonalCenterViewModel` 按索引对齐自动生效；`permute/unpermuteIndex` 纯轮转；
`MemoryExtractor` 记忆门用的是本地二选项表（`chosenIndex != 0`），与共享表无关。

**③ v3 结果**（同一 21 条标注集 ×2 轮）：

| 指标 | v1 | v2 原序 | **v3 原序（=生产顺序）** | v3 置换序 |
|---|---|---|---|---|
| 一致率 | 81.0% | 76.2% | **90.5%**（19/21） | 76.2% |
| 期望「无」行 | 3/7 | 2/7 | **6/7**（仅京东新品首发→高） | 5/7 |
| 期望「一般」行 | 7/7 | 7/7 | **7/7** | 4/7 |
| 期望「高」行 | 7/7 | 7/7 | 6/7（**未接来电→无**） | 6/7 |
| 预测分布 | 3/9/9 | 2/8/11 | **高7 / 无7 / 一般7 完美** | 高11/无6/一般4 |
| 位置稳定性 | 81.0% | 76.2% | 76.2%（16/21） | — |
| 延迟 | 371ms/条 | 497ms/条 | 491ms/条 | 468ms/条 |

- **生产路径拿到的就是 v2 置换序验证过的配置**：v3 原序与 v2 置换序逐条完全一致（确定性引擎的同序印证）；
- 逐条修复：拼多多砍价、梦幻西游签到、WiFi 万能钥匙、唯品会优惠券 —— v2 原序全错，v3 原序全对；
- 广告过滤从此真正生效（期望无行 2/7 → 6/7），这是三版本迭代的核心收益。

**④ 位置偏置全景（4 个数据点，同一份长描述换位置）**：

| 长描述位置 | 对应轮次 | 预测分布 | 结论 |
|---|---|---|---|
| A（首位） | v2 原序 | 无2/一般8/高11 | 偏移最严重，偏向末位 |
| B（中间） | v2 置换序、v3 原序 | **7/7/7** | **唯一可用配置** |
| C（末位） | v3 置换序 | 高11/无6/一般4 | 偏向中间位 |

→ **带硬判据的长选项必须放在中间位（B）**，放首尾都会被模型的选项偏置绕过。v3 已把生产顺序固定为此配置。

**⑤ 遗留的两个单点问题**：
1. **电话未接来电（高→无）** —— v2 原序判对、v3 原序判错。这是 21 条里唯一「高价值被过滤」方向的错判，
   生产后果是未接来电会被 SmartFilter 滤掉（比广告没滤掉更危险）。高价值描述里「来电与未接来电」排在第 4 位；
2. **京东新品首发（无→高）** —— 四轮全错，「新品首发预约」营销话术形似商品通知，属边界样本。

**结论：v3 达到上线标准（一致率 90.5% ≥ 80% 达标线、分布完美 7/7/7、位置稳定性 76.2% > 70%）。**

### 校准 v4：修复「未接来电」单点（2026-09-23 22:34，真机，n=2 已确认）

**变更（一处、一行）**：v3 的唯一「高价值被过滤」错判是「电话 | 未接来电」被判成无价值
（生产后果：用户可能看不到漏接来电）。只把高价值选项里的「来电与未接来电」从第 4 位提到首位，
**其余措辞一字未动**，这样分数变化可干净归因到这一处改动。

**v4 结果**（同一 21 条标注集 ×2 轮；22:34 与 22:36 两次复跑逐条完全一致）：

| 指标 | v1 | v2 | v3 | **v4（当前版本）** |
|---|---|---|---|---|
| 生产路径一致率（原序） | 81.0% | 76.2% | 90.5% | **95.2%**（20/21） |
| 置换序一致率 | 81.0% | 90.5% | 76.2% | **85.7%**（18/21） |
| 期望「无」行 | 3/7 | 2/7 | 6/7 | **6/7**（仅京东新品首发→高） |
| 期望「一般」行 | 7/7 | 7/7 | 7/7 | **7/7** |
| 期望「高」行 | 7/7 | 7/7 | 6/7 | **7/7**（未接来电修复） |
| 预测分布（原序） | 3/9/9 | 2/8/11 | 7/7/7 | 高8/无6/一般7 |
| 位置稳定性 | 81.0% | 76.2% | 76.2% | **90.5%**（19/21，历版最高） |
| 延迟 | 371ms/条 | 497ms/条 | 491ms/条 | **481ms/条** |

```
混淆矩阵（行=期望，列=原序预测）      预测高  预测无  预测一般
期望高                                   7      0      0     ← 未接来电已修复
期望无                                   1      6      0     ← 仅京东新品首发
期望一般                                 0      0      7
```

**结论：v4 为上线版本。**
- 生产路径 95.2%（≥80% 达标线），三个高档全和不被过滤：**未接来电、验证码、银行变动、快递、
  日历、钉钉、12306 全部 7/7**；
- 位置稳定性从 76.2% 跳到 90.5% —— 这项提升说明不只是「多判对一条」，而是整张表的稳定性提升；
- 唯一残留错判「京东新品首发预约」在四个版本 × 两轮共 8 次判读中**全部判成高价值**，
  属「营销话术形似商品通知」的边界样本，可接受；若后续要修，需在该样本上单独验证是否引入新偏移。

**决策表迭代的完整教训（值得后续所有决策表复用）**：
1. **一致率会骗人**：v1 81% 但混淆矩阵显示广告从不被过滤；真正的指标是混淆矩阵 + 预测分布；
2. **确定性引擎下校准是一次性验证**：temperature=0/topK=1 下 n=2 逐条 100% 一致，
   所以「单次表现好」不是运气，但也要警惕它只对这 21 条负责 —— **标注集要持续扩充**；
3. **长判据必须放中间位**：第一批 → 末位偏移严重；中间位 → 分布完美；末位 → 中间位偏移；
4. **最小干预**：v4 只挪动一个词组，就把「高价值被过滤」这类最危险的错判清零。

下一步：**P3-1 生产端到端复验**（投全新通知，验证广告真被过滤、高价值全保留）—— 已完成，见下。

### P3-1 生产端到端复验：v4 决策表真实生效（2026-09-23 22:54，真机）

**前置：监听服务需在 ROM 设置页重新授权。** 装机后 `PCDiag` 显示
`listenerEnabled=true connected=false`，日志确证 `Service starting has been prevented by iaware or trustsbase`。
用 adb 反复翻转 `enabled_notification_listeners`（含 force-stop 后重启、连续 4 轮）**全部被 iaware 拦下**；
最终走 **ROM 自己的「通知使用权」页**（`am start -a android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS`
→ 点 OpenClaw 开关关闭 → 确认「停用」→ 再点开 → 确认「允许」）即绑定成功：
`PCDiag [1] listenerEnabled=true connected=true`、`stateFlow=7 getActiveNotifications=78`。
**再次印证：荣耀上 adb 直写 secure 设置不可靠，ROM 设置页 + 确认弹窗才是稳定路径。**

**验证方法**：投 3 条全新通知（避开 SmartFilter 1 小时 TTL 缓存），标题+正文均为新文本：
- 广告（期望被过滤）：`淘宝 | 限时秒杀专场，全场低至一折，今日最后一天，立即抢购`
- 高价值（期望保留）：`顺丰速运 | 您的快递已到达公司前台代收点，取件码 6612，请及时领取`
- 一般（期望保留）：`腾讯视频 | 您关注的热播剧更新了第十二集，点击观看`

**结果（日志 + 截图双重确认）**：
```
Raw items: 10 (notif=7, ...)  → After SmartFilter: 9   → 投递 3 条后
Raw items: 11 (notif=8, cal=3, sms=0, call=0) → After SmartFilter: 11 → Final items: 11
DecisionRunner: DecisionBatch: 1 条 in 480ms，无效 0 条
```
- **通知源只有 8 条**（投 3 条、去重/过滤后净增 1 条量级）→ **广告那条没有进入列表**，
  截图中「通知」分组里出现 `顺丰速运 / 取件码`（22:54），**没有「淘宝 / 限时秒杀」**；
- 单条决策 `751ms`（含建会话）→ `480ms`，与校准侧 481ms/条 吻合；
- 页面无空白、无 NPE，`Raw items → Final items` 每周期稳定输出。

**至此 P3-1（决策化 SmartFilter）在生产路径闭环：广告被过滤、高价值被保留、无崩溃。**
四个数据源链路健康（notif=8, cal=3, sms=0, call=0，后两者是设备本身无数据）。

0. **合并流被一次异常永久打死（个人中心常年空列表的真凶，非通知监听问题）**：
   `startMerging` 的 `combine` transform 里任一步抛异常，都会终止整个 `collect`，页面从此永远空白。
   真机实测一次 NPE（`classifyPrioritySafely` → `collectionSizeOrDefault(<this>)` 收到 null 集合，
   ART 内联后栈帧落在调用方、难以定位）就把「通知」页打成空列表。三处修复：
   - `classifyPrioritySafely` 内层主体改为 `classifyPriorityInternal`，外层 `catch (Throwable)` 兜底返回原列表；
   - `PriorityClassifier.classifyBatch` 的 `results` 显式声明为可空并判空（Kotlin 对声明非空的
     接口返回值不插运行时检查，空引用会以 NPE 形式在调用方炸出来）；
   - 合并流加 `.retryWhen`（最多 5 次、间隔 1s），单轮失败自动重新订阅四个源。
   修复后冷启动真机复验：**NPE 未复现**，页面稳定每 ~20s 输出一次 `Final items: 12`。
   该 NPE 只在「首次进入个人中心时 `setGatewayContract()` 尚未注入 `llmClassifier`、走纯规则兜底路径」
   的窗口出现一次，根因未彻底坐实，但已被上述三层防御完全兜住。

   诊断入口：`adb shell am broadcast -a ai.openclaw.android.TEST_PC_DIAG`（tag `PCDiag`），
   输出「权限 / 服务是否真绑定 / StateFlow 条数 / getActiveNotifications 条数 / 四个源是否发射」，
   一次广播即可判断个人中心没数据是卡在监听、还是卡在某个源。

### 顺带定位并修复的两个真 bug（2026-09-23 00:20，均已真机验证）

1. **个人中心「推理洪流」（SIGSEGV 的诱因）**：`startMerging` Step 4 的
   `PriorityClassifier.classifyBatch()` 每个合并周期（~5s）都起一次完整生成（端侧 7–10s/次），
   且超时分支还会**再调一次** → 上一轮没跑完下一轮又起，native 引擎被持续压满。
   修复（`classifyPrioritySafely`）：① 条目 id 集合未变 → 复用上次结果；② 距上次 LLM 调用
   <60s → 规则兜底；③ 超时/异常只走规则兜底，不再重试。
   验证：45s 观察窗口内 `Generated response/Stream complete` 从「连续不断」变为 0 次（洪流止住）。
2. **`chat()` 缺少畸形工具调用兜底 → 记忆抽取 100% 失败**：模型吐
   `call:weather_get_weather{location}`（缺参数值），LiteRT 抛 `INVALID_ARGUMENT: Failed to parse
   tool calls`；`chatStream` 有 `parseToolCallsFromError` 兜底，`chat()`（记忆抽取旁路）没有。
   修复：新增 `runOneShotConversation()` + 命中解析错误时追加「禁止工具调用」约束重试一次。
   ⚠️ 坑：**重试必须在上一个会话 `close` 之后**再 `createConversation`（引擎单会话槽位），
   第一版写在同一个 `use{}` 块里，直接命中 `FAILED_PRECONDITION`。
   验证：启动时 `Chat failed` 消失，改为 `Generated response in 3.6–6.0s` 三次成功。

**真机顺带确认 P1 生效**：`AgentSession: LOCAL profile: 10/68 tools kept (dropped 58)`，窗口 8192。

**全局不变量**（每阶段回归）：
- 云端模式行为零变化（决策组件只在 `modelClient is LocalLLMClient` 时启用）；
- 决策不可用（引擎未加载/超时/invalid）必须静默降级到现有路径，**不产生新故障面**；
- P0 熔断（120s 闸门、单规则冷却、EventBus 去重）保持不动，决策门叠加在其上。

## 9. 开放问题

1. Session/Conversation 槽位关系（M0 验证）；
2. `runPrefill` 是否支持 systemInstruction 语义（模板里把指令拼进文本即可，但需确认没有隐式 system 覆盖）；
3. SamplerConfig `temperature=0` 在 GPU/NPU backend 下是否严格 argmax（若非，改用 topK=1）；
4. 批量决策中 decode 输出可能带 BOM/空白——解析层用 `trim().firstOrNull()` 容错；
5. 决策 Session 的 `maxNumTokens` 是否继承引擎配置（批量 20 条 × 60 token 增量 + 400 提示 ≈ 1600 token，4096 窗口内安全）。
