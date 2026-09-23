# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased] — 2026-09-21 端侧模型：上下文超限 + 运行卡顿

### 修复 · 端侧上下文很容易超限

根因（实测）：系统提示约 4800 token，而工具 schema 有 60+ 个（19 技能 + 12 无障碍）从未计入预算；
同时 E4B 的预算是 `system 8000 + 对话 14000 = 22000`，**已经超过窗口 16384 本身**，且完全没有给生成预留。

- 新增 `planContext()`：按 `窗口 − 输出预留(768)` 统一分配，顺序为 工具(≤35%) → 系统提示(≤45%) → 对话历史
- 工具 schema 首次计入预算，装不下的按序丢弃并在日志打印丢弃数量（宁可少工具也不撑爆窗口）
- 系统提示超预算时截断为「头部 60% + 尾部 25%」，避免丢掉结尾的输出格式约定（原先只有 E2B 才截断，且只留头部）
- `AgentSessionManager` 在 LOCAL 下把 `maxContextTokens` 对齐到模型窗口 ×0.6（原先沿用云端默认 8000）
- 每轮打印预算分布日志：`窗口 / 系统 / 工具 / 对话`

### 优化 · 端侧推理期间整机卡顿

- **会话复用（主要收益）**：原先每轮 `createConversation()` 并把整段历史作为 `initialMessages` 重放一次，
  等于每轮全量 prefill（端侧最贵的操作）。改为复用同一个 `Conversation` 复用 KV cache，只发新增消息；
  仅在历史被裁剪、换会话、system/tools 变化时重建。生成失败时丢弃缓存重建重试一次
- **限制推理线程**：`Backend.CPU(numOfThreads)`（核数 − 2，收敛到 2..6）。此前 native 默认按核心数开满，
  prefill/decode 期间 UI 与其他应用抢不到时间片
- **UI 节流**：`ChatViewModel` 把流式 token 按 80ms 合并后再推给 UI，避免每个 token 触发一次全列表重组

### 修复 · 上下文窗口按硬编码值申请，实际可能超过模型包容量

Gemma 4 E2B/E4B 官方卡标称 128K，但那是模型架构能力；`.litertlm` 包的物理 KV-cache 由导出时的
`cache_length` 决定，公开版本（含 litert-community 官方包）普遍是 **4096**。此前直接写死申请
`8192 / 16384`，一旦超过包内容量就会初始化失败或在 prefill 时炸 —— 这才是「上下文很容易超限」的根因。

- `tryInitEngine()` 按「首选值 → 4096 → 2048」逐级降级初始化，实际生效值记入 `effectiveMaxNumTokens`
- `getContextWindowTokens()` 返回实际生效窗口，不再硬编码 8192/16384；`planContext()` 的预算随之对齐
- 输出预留改为 `min(768, 窗口/4)`，避免 4K 小窗口下预留吃掉近 1/5 的可用上下文
- 初始化成功日志附带实际窗口 token 数，可直接用 logcat 判断手里的模型包是几 K

### 新增 API

- `LocalLLMClient.getContextWindowTokens()` —— 返回引擎实际分配到的上下文窗口（非模型宣传值），供上层对齐 trim 预算
- `LocalLLMClient.resetConversation()` —— 丢弃复用会话

---

## [Unreleased] — 2026-09-20 个人中心 / 通知修复

均在荣耀 DNP-AN00（MagicOS）真机验证通过。

### 修复 · 通知列表为空且无任何权限提示

- `NotificationSource` 改为 `callbackFlow` **订阅即拉取**，对齐 SMS / 日历 / 通话三源的自愈能力 —— 此前权限补授后重新进页面也无法恢复，只有通知源做不到
- 新增 `SmartNotificationListener.isNotificationListenerEnabled()`，按 `pkg/Component` 精确解析 `Settings.Secure.enabled_notification_listeners`
- 通知状态拆成两个独立信号：`isNotificationListenerEnabled()`（权限是否授予）与 `isConnected` StateFlow（服务是否真被系统绑定）。国产 ROM 上存在「权限为真但服务没起来」，只看前者会误判
- `PersonalCenterViewModel` 暴露 `notificationPermissionGranted` / `notificationServiceConnected`；`refresh()` 与 60s 轮询由**空转**改为真正重新判定权限 + 触发拉取
- 页面 `ON_RESUME` 重新判定权限，用户从设置页返回立即生效
- 统计行通知项补未授权标记（橙色 `!`）与去授权入口，跳 `ACTION_NOTIFICATION_LISTENER_SETTINGS`；ROM 无接收方时降级到应用详情页
- 空列表改为具体提示「尚未开启通知使用权」/「通知服务未运行」+ 去授权按钮，替代笼统的「暂无内容」
- `loadActiveNotifications()` 重试耗尽不再用 `emptyList()` 覆盖已有数据（清空交给 `onNotificationRemoved` 逐条处理）
- `fetchActiveNotificationsFromSystem()` 加并发守卫，避免状态栏为空时叠出多条重试链

### 修复 · 统计行横向溢出（UI-AUDIT P0）

- `HorizontalDivider` 在 `Row` 中因内部 `fillMaxWidth()` 吃掉全部剩余宽度，把后三个统计项压成 0 宽 → 改用 `VerticalDivider`。现四项全部可见（🔔33 \| 📅3 \| 💬0 \| 📞0）

### 修复 · 日程/短信「去授权」跳错页面

- 误跳 `ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION`（**所有文件访问权限**页）→ 改为应用详情页 `ACTION_APPLICATION_DETAILS_SETTINGS`
- 顺带修复：日历 / 短信权限 flag 因数据源内部吞掉 `SecurityException`（`CalendarSource` / `SmsSource`）而**永远是 `true`**，导致未授权状态在 UI 上不可达、去授权入口成了死代码 → 改用 `ContextCompat.checkSelfPermission()` 真实检测

### 优化 · 首屏加载提速

- `onListenerConnected()` 去掉硬编码 `delay(5000)`，改为立即拉取（空结果的重试链本就能覆盖系统同步延迟）。实测：连接 → `getActiveNotifications()` 间隔由 5000ms 降至 1ms

### 已知未做

- 通知仍**无持久化**，仅存于 `SmartNotificationListener` 的内存 StateFlow，进程重启即丢失历史

## [1.0.0] - 2026-05-30

### 首个正式版本

#### Trigger 触发器系统
- ✅ NotificationReply — 支持系统 RemoteInput + 自动回复
- ✅ CustomScript — ScriptOrchestrator 动态脚本执行
- ✅ ActionExecutor — 4 种动作（SkillCall / AgentQuery / NotificationReply / CustomScript）
- ✅ EventBus — 事件总线 + 过滤器（Package / Keyword / Category）+ 去重 + 冷却
- ✅ CronScheduler — cron 表达式解析 + 定时调度
- ✅ 63 个单元测试全部通过

#### Node 端能力（Phase 1-3）
- ✅ ScreenSkill — 截屏三级 fallback（MediaProjection → PixelCopy → ViewTree）+ 坐标点击 + 增强读屏 + 滚动查找
- ✅ DeviceSkill — 设备信息 + 状态（电池/存储/网络/内存）+ 健康评分 + 运行应用
- ✅ CameraSkill — 拍照（前置/后置）+ 录像 + 最新相册
- ✅ FileXferSkill — 文件读写 + pull/push + 目录列表 + 沙箱安全
- ✅ ShellSkill — 白名单命令执行 + 黑名单双保险
- ✅ NotifySkill — 通知列表 + 操作 + 回复
- ✅ 全部 6 个 Skill 在 HMA-AL00 (API 29) 真机验证通过

#### 架构改进
- ✅ Plugin SDK + PluginManager（Phase 1）
- ✅ Gateway 重构 — 会话管理迁移至 GatewayManager，ChatViewModel 零参构造
- ✅ ParentChildResolver 拓扑排序组件注册
- ✅ AGP 8.2 / Kotlin 2.3.0 兼容性修复

#### CI/CD
- ✅ GitHub Actions：自动测试 + lint + Debug APK 构建
- ✅ Release 构建：打 tag 自动签名发布 APK
- ✅ versionCode 自动递增（基于 git commit 数）

---
