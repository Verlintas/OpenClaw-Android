# 端侧模型真机诊断报告

**设备**：荣耀 DNP-AN00（MagicOS） · **模型**：`gemma-4-E2B-it.litertlm`（2.54 GB）
**版本**：debug · **时间**：2026-09-21 23:1x

---

## 结论速览

| 问题 | 结论 |
|---|---|
| E2B 实际上下文窗口 | **8192 tokens**（本机包接受 8192，非 4096） |
| 上下文为何容易超限 | 系统提示 **9245 token** 占了窗口 113%，工具占 2597，对话只剩 **512** |
| 手机为何非常卡 | **规则引擎自激循环**，无人操作下每 5–7 秒跑一轮本地 LLM，app 持续占 **84% CPU** |
| 一次性崩溃 | `chat()` → native `sendMessage` 的 **SIGSEGV**（fault addr 0x188） |

---

## 一、上下文窗口：实测 8192

运行日志（`I/W LocalLLMClient`）：

```
系统提示 9245 tokens 超过预算 3340，已截断
Truncated 3 messages (budget: 512 tokens)
上下文超预算已收敛: 窗口=8192 系统=4393 工具=2597(丢弃34个) 对话=328/512(丢弃3条)
```

`maxNumTokens = 8192` 被这个包接受，初始化降级链路第 1 档就成功 ——
**本机包不是 4096**，我上一轮基于社区模型卡的推测在本机不成立。

> 结论：窗口按 8192 算。降级机制保留（防的是别的包），但预算基数要用实测值。

## 二、上下文实际分布：严重失衡

| 组成 | 实际 | 预算 | 结果 |
|---|---|---|---|
| 系统提示 `BASE_SYSTEM_PROMPT` | **9245** | 3340 | 每轮都被截断成「头 60% + 尾 25%」 |
| 工具 schema（60+ 个） | 2597 | 35% | **丢弃 34 个** |
| 对话历史 | — | **512** | **每轮都丢历史** |

**连锁反应**：对话预算 512 → 历史每轮变化 → KV-cache 前缀不匹配 → 会话复用失效 →
**每轮全量 prefill**（昨天为治卡顿做的会话复用，在这里被预算挤成了摆设）。

单轮耗时实测：`11879 / 7276 / 7035 / 5418 / 6574 / 7211 / 7323 ms`

## 三、卡顿真凶：规则引擎自激循环

`dumpsys cpuinfo`（循环期间 23:16:41–23:17:28）：

```
Load: 15.69 / 15.6 / 15.98      99% awake
  84% 9812/ai.openclaw.android: 58% user + 26% kernel     ← 第 2 名仅 18%
Mem: 11263M total, 11059M used,  204M free
Swap: 12287M total, 5241M used                            ← swap 吃掉 5.2 GB
```

app RES **1.7 GB**。日志实锤循环（**全程无任何用户操作，冷启动后自动开始**）：

```
23:16:51.837  LocalLLMClient: Chat failed  (tool call 解析失败)
23:16:51.854  EventBus: Rule dd1182a1-… executed: success=true
23:16:51.854  EventBus: Executing rule: 工作时段免打扰 (1ee1463d-…)
23:16:51.852  LocalLLMClient: 上下文超预算已收敛 窗口=8192 对话=265/512
…  每 5–7 秒重复，无终止
```

**闭环链路**（已逐环排除其他可能）：

1. 事件发布 → 匹配到多条规则（`通用天气` / `工作时段免打扰` / `dd1182a1`）
2. 这些规则的 action 都是 **`AgentQuery`**
   —— `AgentSession.handleMessage` 的唯一非 UI 调用点就是 `ActionExecutor.executeAgentQuery:150`
3. 每轮跑完整 LLM 推理 → 生成 tool call（`reminder_set_reminder`、`weather_get_weather`）
4. 工具执行产生新通知/事件 → `SmartNotificationListener.onNotificationPosted:247`
   捕获并 `publish`（**未过滤自身包名**）→ 回到第 1 步

**事件源判定**：`CronWorker` 被排除（最小间隔 15 min，日志无 CronWorker）；
`triggerRuleManually` 被排除（无对应工具调用日志）。只剩 `onNotificationPosted`。

## 四、崩溃

```
Fatal signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x188
  #24 nativeSendMessage (liblitertlm_jni.so)
  #50 LocalLLMClient$chat$result$1$1.invokeSuspend
  #90 AgentSession.callLLMStep
  #95 AgentSession.handleMessage
```

指针偏移 0x188 通常是「对象已被释放/未初始化」。
疑点：`LocalLLMClient.release()` **不加任何锁**就 `engine.close()`，与在途的
`chat()`/`chatStream()` 存在竞态（`sessionMutex` 只保护了读路径，没保护销毁路径）。

另有非崩溃类错误：
`LiteRtLmJniException: Failed to parse tool calls from response` ——
E2B 输出的 `<|tool_call>call:reminder_set_reminder{…}` 格式 LiteRT 解析不了，
`chat()` 会走 catch 返回 failure（用户侧表现为「模型没反应」）。

## 五、待确认疑点

「触发器」UI **稳定显示 0 个规则 / 0 条日志**，但 EventBus 明确在执行规则。
疑似 v1 `EventBus` 与 v2 UI 数据源不一致（`MainActivity:223` 注释提到两套触发系统并存），
**用户可以看不到、也关不掉这些规则**。

数据库 `openclaw_database` 是 SQLCipher 加密的（文件头非 `SQLite format 3`），无法离线核验。

---

## 建议修复顺序

| 优先级 | 项 | 说明 |
|---|---|---|
| **P0** | 循环熔断 | 规则触发的 agent 调用不得再触发规则（递归深度保护，或在 `AgentSession` 加 `triggeredByRule` 标志并屏蔽 `trigger_rule_*` 工具） |
| **P0** | 规则可见可控 | 修 UI 数据源，或在启动时清掉这些幽灵规则 |
| **P1** | 端侧精简系统提示 | 9245 → 目标 < 2000 token（云端继续用完整版） |
| **P1** | 端侧工具白名单 | 60+ → 10 个以内，替代现在的「按顺序丢」 |
| **P2** | 对话预算 | 512 → ≥1500，否则 KV-cache 复用永远失效 |
| **P2** | ~~通知自过滤 + 线程安全~~ | ✅ 已做（见下） |
| **P2** | `release()` 加锁 | 消除与在途推理的竞态（SIGSEGV） |

---

# 六、P0 修复实施与真机验证（2026-09-21 深夜）

## 6.1 已合入的改动

| # | 文件 | 改动 | 目的 |
|---|---|---|---|
| 1 | `trigger/EventBus.kt` | `cooldowns` / `dedupCache` 由 `mutableMapOf` 改 `ConcurrentHashMap`；新增 `MIN_COOLDOWN_MS = 60_000`；`executeRule` 防抖改为 `putIfAbsent` 原子占位；`dedupCache` 加 `DEDUP_MAX_SIZE = 200` + `evictDedupExpired()` | 原 `mutableMapOf` 在通知回调/Cron/UI 多协程并发写时会丢条目 → 防抖形同虚设。这是「每 5~7 秒跑一轮 LLM」的直接原因之一 |
| 2 | `notification/SmartNotificationListener.kt` | `onNotificationPosted` 开头丢弃 `sbn.packageName == packageName` 的通知 | 切断「自己发的通知 → 自己匹配到规则 → 再推理」这条自激回路 |
| 3 | `trigger/ActionExecutor.kt` | `executeAgentQuery` 加 `Semaphore(1, true)` + 全局闸门 `AGENT_QUERY_MIN_INTERVAL_MS = 120_000`（`AtomicLong` 记录上次执行时刻） | 并发时直接跳过（不排队）；单规则冷却只能限制同一条规则，一个事件常同时命中 3~4 条规则轮番点火，全局闸门把占空比压到 ~5% |
| 4 | `ui/trigger/TriggerScreen.kt` | 进入页面时主动调 `viewModel.loadRules()` + `loadRecentLogs()` | **P0-2 根因**：`TriggerScreen` 只 `collect` 了 `toastMessage`，从未触发首次加载，`rules`/`recentLogs` 永远是初值 `emptyList()` → UI 恒显示「0 个规则 / 0 条日志」 |
| 5 | `ui/components/StatusIndicator.kt` | 呼吸点动画由 `rememberInfiniteTransition`（vsync，60fps 驱动整棵子树重组）改为自管循环：`delay(1000/PULSE_FPS)`（20fps）+ `Modifier.alpha` | 见 6.4 |

## 6.2 编译 / 安装坑（复现备忘）

```powershell
Set-Location E:\Android\OpenClaw-Android
$env:JAVA_HOME="E:\Program Files\Android\Android Studio\jbr"   # 必须 JBR 21
.\gradlew.bat --stop                                          # 先停遗留 daemon
.\gradlew.bat :app:assembleDebug --console=plain
```
- `journal-1.lock 拒绝访问` → `--stop` 后重试即可。
- 类体内写 `private const val` 会报 `Const 'val' is only allowed on top level…`；本类已有 companion object，**不能再建第二个**（`Only one companion object is allowed`），改普通 `private val` 即可。

## 6.3 P0-2 验证结果：幽灵规则已可见可控

真机点进「触发器」页，系统概览从 `0 / 0 / 0` 变为：

```
规则总数 8    已启用 8    最近日志 50
```

即库里一直有 8 条启用规则的，只是 UI 没拉数据。**根因不是 v1/v2 数据源不一致**
（`TriggerViewModel.kt:100-116` 与 `EventBus.kt:157` 都读同一个 `ruleDao`，且 Manifest 里
没有 `android:process`，单进程），而是 UI 缺一次首屏加载。

## 6.4 卡顿归因：聊天页常驻 34% 单核

P0-1 熔断后（无通知事件时规则不点火），真机复测发现**还有第二个耗电源**：

| 页面 | 空闲 CPU（单核 %） |
|---|---|
| 聊天（tab 0） | **34.4** |
| 触发器（tab 3） | **0.0** |

线程级采样（`top -H`，60s 增量）：主线程 ~30%、RenderThread ~8%、JIT ~3%。
日志里刷满 `jit_compiled:[Failed] double java.lang.Math.sqrt(double)`，约 **90 条/秒**
（≈ 1.5 条/帧 @60fps）—— 每帧重组时画圆/圆角矩形走到的路径。

`ChatScreen` 里唯一的无限动画是 `ChatScreen.kt:469` 的
`StatusIndicator(state = ConnectionState.ONLINE)`（标题栏那个呼吸绿点）。
改 20fps 后：

| 版本 | 聊天页 CPU | 主线程增量 CPU |
|---|---|---|
| `rememberInfiniteTransition`（60fps） | 34.4% | 30% |
| 自管 20fps 循环 | 13.3% → **6.6%** | **6.6%** |

主线程开销与动画帧率**严格成正比**（60→20fps，30%→6.6%，≈3×），说明这笔开销确实来自该动画。
`Math.sqrt` 刷屏同时消失。

> ⚠️ 待人工确认：`screencap`（PNG 与 raw 两种方式）在 500ms 间隔下抓到的画面几乎完全一致，
> 无法用截图证明 20fps 版本仍在「呼吸」。需要肉眼确认呼吸效果是否保留；若看起来变静止，
> 把 `StatusIndicator.kt` 回退到 `rememberInfiniteTransition` 版本即可（代价是恢复 34% CPU）。

**结论**：聊天页（默认 tab，长期驻留）单是这一个装饰性呼吸点就常驻吃掉约 1/3 个核心，
是「什么都没干手机也发热/掉电」的独立成因，与端侧推理无关。

## 6.5 顺带发现的问题

1. **`MainActivity` 泄露广播接收器**：日志稳定报
   `IntentReceiverLeaked: ai.openclaw.android.MainActivity$onCreate$testReceiver$1`，
   来源 `MainActivity.kt:210` 注册的 `testReceiver` 没有配套 `unregisterReceiver()`。
   每次 Activity 重建（配置变更/`handleRelaunchActivity`）就多泄漏一个。
2. `docs` 里在 6.4 提到的 `StatusIndicator` 之外，`ShimmerEffect.kt` / `ScanLineOverlay.kt` /
   `TypingIndicator.kt` / `ui/components/TypingCursor.kt` 都是**只声明、无人调用**的死代码，
   其中三个各带一个 `rememberInfiniteTransition`。
3. `release()` 与在途推理的竞态（SIGSEGV）仍未修，仍在 P2。

## 6.6 尚未做的

| 优先级 | 项 | 状态 |
|---|---|---|
| P1 | 端侧精简系统提示 | ✅ 已做（见 7.1） |
| P1 | 端侧工具白名单 | ✅ 已做（见 7.2） |
| P2 | 对话预算 512 → ≥1500 | ✅ 已做（见 7.3，顺带修了一个越界 bug） |
| P2 | `release()` 加锁 | 未开始 |
| P2 | `MainActivity` 泄露 `testReceiver` | 未开始 |

---

# 七、P1 / P2 实施（2026-09-22 凌晨）

## 7.1 端侧精简系统提示

**新文件 `agent/OnDeviceProfile.kt`**：端侧档位的两份配置集中在一个 object 里。

- `SYSTEM_PROMPT`：约 1300 字符（估算 < 600 token，完整版 9245）。只保留 6 条规则：
  必须用工具拿真实数据、语言一致、**纯文本输出（明确禁止 A2UI/JSON/Markdown 表格）**、
  单轮最多 1 个工具且总共 ≤3 轮、问候不调工具、回复要短。
- 刻意**不**写 A2UI 协议细节 —— 写了反而勾起模型输出它；这里直接禁止。

`AgentSession` 新增 `onDeviceMode` + `setOnDeviceMode()`，`buildSystemMessages()` 按档位选 prompt。
Agent 的自定义 systemPrompt（`_agentConfig.systemPrompt`）仍会拼在精简版后面，云端行为不变。

> ⚠️ A2UI 这条是双刃剑：端侧回复从富卡片退化成纯文本。这是**有意的取舍** ——
> 要保留 A2UI 就得把整套协议规范塞回 prompt，窗口立刻爆。

## 7.2 端侧工具白名单

`OnDeviceProfile.TOOL_WHITELIST`：**60+ → 10 个**。

| 保留 | 理由 |
|---|---|
| `weather_get_weather` | 端侧最常问，schema 小 |
| `reminder_set_reminder` / `reminder_list_reminders` | 纯本机，E2B 实测就会主动调它 |
| `calendar_list_events` / `calendar_add_event` | 纯本机、高频 |
| `notification_list_notifications` | 只读，是本应用的核心场景 |
| `translate_translate` | schema 小、常用 |
| 无障碍 `read_screen` / `click` / `input_text` | 3 个就够串起「看屏幕 → 点/输入」 |

排除标准（写进了代码注释，防止后人无意放开）：
- **能产生通知/短信**（`notification_send_notification`、`notify_reply`、`sms_send_sms` 等）
  —— 会闭环回 `SmartNotificationListener` 再触发规则引擎，是自激循环的一条通路，从源头掐掉；
- **危险/高权限**（`shell_exec`、`script_execute_script`、`file_*`、`camera_*`、`settings_*`）；
- **schema 巨大**（`dynamic_skill_generator_generate_skill`、7 个 `trigger_rule_*`）；
- **依赖云端**（`search_search`、`location_*`）。

`setToolsWithSkills()` / `refreshTools()` 在端侧档位都走 `OnDeviceProfile.filterTools()`，
并打印 `LOCAL profile: N/M tools kept`。**过滤放在 accessTools 与 skillTools 合并之后**，
两块预算一起管；`refreshTools()` 也过滤，否则动态技能一注册就把砍掉的工具全请回来。

接入点（3 处都要，漏一处就会拿到完整版）：
- `AgentSessionManager.getOrCreate()` —— 多 agent 主路径；
- `GatewayManager` 两处 backward-compat 构造（`:594` 初始化、`:270` 重配后重建）。

## 7.3 对话预算

`LocalLLMClient.MIN_CONVERSATION_TOKENS` **512 → 1500**。

512 装不下几轮对话 → 每轮丢历史 → 送入引擎的文本前缀每轮都在变 →
**KV-cache 复用失效 → 每轮全量 prefill**（端侧实测 5~12 秒/轮）。这是「上下文超限」和「慢」的共同要害。

顺带修了一个**潜在越界**：`coerceAtLeast(MIN)` 在 system/tools 极端膨胀时会把预算顶到
`usable` 之上，反而撑爆窗口。现在补了 `.coerceAtMost(usable)`。

## 7.4 真机首验：预算公式生效，但抓到一个会话槽位冲突

装机后发消息，日志（`LocalLLMClient`）：

```
上下文预算: 窗口=8192 输出预留=768 系统=0 工具=0(0个) 对话=0/7424
Chat failed: LiteRtLmJniException: Failed to create conversation:
  FAILED_PRECONDITION: A session already exists. Only one session is supported at a time.
  at Engine.createConversation → LocalLLMClient.chat(LocalLLMClient.kt:416)
  ← MemoryManager.extractAndStore → LlmMemoryExtractor.extractFromConversation
  ← HybridSessionManager.triggerDelayedExtraction
```

两个结论：
1. **预算公式正确**：`usable = 8192 − 768 = 7424`，与代码一致。
2. **`cachedConversation`（上一轮为修「每轮全量 prefill」加的会话复用）引入了回归**：
   LiteRT 引擎同一时刻只允许**一个** conversation，而记忆抽取旁路 `chat()` 每次都
   `createConversation` —— 主会话缓存占着槽位，旁路必炸（记忆抽取 100% 失败）。

**修复**（`LocalLLMClient.chat()`）：进 `sessionMutex` 后先 `closeCachedConversation()`
再 `createConversation`。代价是主会话 KV-cache 作废、下一轮 `chatStream` 重建（多一次
全量 prefill）—— 比记忆抽取永远失败强。代码里留了 TODO：等引擎支持多 conversation 后让旁路复用主会话。

## 7.5 未完成 / 待办

| 项 | 说明 |
|---|---|
| **真机复验** | 改完后 adb 断开（`no devices/emulators found`），**新 APK 尚未装机**。需要重连后：① 发消息看 `LOCAL profile: 10/71 tools kept` 和 `系统≈XXX 工具≈YYY 对话=…`；② 确认记忆抽取不再报 FAILED_PRECONDITION |
| 主聊天走的是云端 | 发 `hello` 得到的回复没走本地（日志里只有记忆抽取旁路调了 LocalLLMClient）。设置页选了「本地模型」但可能没点 Save Configuration，或 Gateway 没重启。要验证端侧档位得先把主聊天切到 LOCAL |
| 端侧呼吸点 20fps 的视觉效果 | 待肉眼确认（截图抓不到小幅动画） |
