# 个人中心 - 重构设计

> 日期: 2026-04-23
> 状态: 开发中
> 替换: 通知页面 (Tab 1)

---

## 定位

汇总用户所有可能关注的信息（通知、日程、短信等）的集散地。**不按来源分类，按重要程度统一排序**，点击可跳转到对应应用。

## 核心需求

### 1. 多源数据聚合
| 来源 | 实现方式 | 权限 |
|------|----------|------|
| 通知 | `SmartNotificationListener` (已有) | 通知监听 (已有) |
| 日程 | `CalendarContract` ContentProvider | `READ_CALENDAR` (新增) |
| 短信 | `Telephony.Sms` ContentProvider | `READ_SMS` (新增) |
| 扩展 | 未来可接入邮件、待办等 | |

### 2. 统一数据模型

```kotlin
data class CenterItem(
    val id: String,
    val source: ItemSource,         // 来源: Notification / Calendar / Sms
    val sourceApp: String,          // "微信" / "系统日历" / "短信"
    val icon: ItemSourceIcon,       // 来源图标
    val importance: Float,          // 0.0 ~ 1.0 重要程度
    val title: String,
    val body: String,
    val timestamp: Long,            // 事件时间
    val createdAt: Long,            // 记录创建时间
    val isRead: Boolean,
    val openIntent: PendingIntent?, // 点击跳转
    val dedupKey: String,           // 去重键
    val relatedIds: List<String>,   // 关联的其他来源ID（合并展示用）
)
```

### 3. 重要程度计算

```
importance = baseScore × recencyWeight

baseScore:
  - 通知紧急(电话/微信语音/短信验证码)     → 0.95
  - 短信(银行/快递/验证码)                 → 0.85
  - 日程(今天内开始)                       → 0.80
  - 通知重要(工作邮件/日历)                → 0.70
  - 短信(普通)                             → 0.50
  - 日程(本周内)                           → 0.60
  - 通知一般                               → 0.40
  - 日程(未来)                             → 0.30

recencyWeight: 1.0 (1小时内) → 0.5 (24小时) → 0.3 (3天) → 0.1 (7天+)
```

### 4. 跨源去重 ⭐

**问题**: 同一件事可能从多个来源收到。例如：
- 日程"产品评审" + 微信通知"提醒：产品评审" + 短信提醒
- 短信验证码 + 该App的推送通知

**去重策略**:

```kotlin
// 去重键生成规则
fun generateDedupKey(item: CenterItem): String {
    // 提取关键特征：时间窗口 + 关键词指纹
    val timeBucket = item.timestamp / (30 * 60 * 1000L) // 30分钟窗口
    val keywords = extractKeywords(item.title + item.body) // 提取2-4个关键词
    val fingerprint = md5(keywords.sorted().joinToString(":"))
    return "${timeBucket}:${fingerprint}"
}
```

**合并规则**:
- 相同 dedupKey → 合并为一条
- 保留 importance 最高的源作为主展示
- 在 UI 上标记 "关联 3 条信息" 可展开查看其他来源

### 5. 实时刷新 ⭐

```
进入个人中心 → 立即拉取全量数据
之后:
  - 通知: ContentObserver 监听 (SmartNotificationListener 的 StateFlow 实时推送)
  - 日程: ContentObserver 监听 CalendarContract
  - 短信: ContentObserver 监听 Sms Inbox
  - 全量刷新: 每 60 秒兜底刷新一次 (防漏)
```

**架构**:
```
ViewModel
  ├── StateFlow<List<CenterItem>>  ← UI 订阅
  │
  ├── NotificationSource (已有 StateFlow)
  ├── CalendarSource (ContentObserver + 首次拉取)
  └── SmsSource (ContentObserver + 首次拉取)
  │
  └── Merger + Deduplicator → sorted by importance desc
```

### 6. UI 布局

```
┌───────────────────────────────────┐
│  🦞 个人中心            🔄 刷新    │
├───────────────────────────────────┤
│  ┌─────┐ ┌─────┐ ┌─────┐         │
│  │通知12│ │日程3 │ │短信5 │  统计  │
│  └─────┘ └─────┘ └─────┘         │
├───────────────────────────────────┤
│ 🔴 09:30  [微信]          0.92    │
│     张三：今晚聚餐吗？             │
│     🔗 关联 1 条通知               │
├───────────────────────────────────┤
│ 🟡 10:00  [日程]          0.80    │
│     产品评审会议 - 14:00           │
├───────────────────────────────────┤
│ 🟢 08:15  [短信]          0.75    │
│     【银行】验证码 482916          │
└───────────────────────────────────┘
       ↓ 按 importance 降序
```

### 7. 点击跳转

每条 item 携带 `openIntent: PendingIntent`：
- 通知 → 打开原始通知对应的 PendingIntent
- 日程 → `Intent(CalendarContract)` 打开日历 App 对应事件
- 短信 → `Intent(Telephony.Sms)` 打开短信 App 对应对话

---

## 文件变更

| 操作 | 文件 | 说明 |
|------|------|------|
| 新建 | `personalcenter/PersonalCenterScreen.kt` | UI 页面 |
| 新建 | `personalcenter/PersonalCenterViewModel.kt` | 数据聚合 + 去重 + 排序 |
| 新建 | `personalcenter/models/CenterItem.kt` | 统一数据模型 |
| 新建 | `personalcenter/models/ItemSource.kt` | 来源枚举 + 图标 |
| 新建 | `personalcenter/sources/NotificationSource.kt` | 通知数据源 (包装现有) |
| 新建 | `personalcenter/sources/CalendarSource.kt` | 日历数据源 |
| 新建 | `personalcenter/sources/SmsSource.kt` | 短信数据源 |
| 新建 | `personalcenter/DeduplicationEngine.kt` | 去重引擎 |
| 新建 | `personalcenter/ImportanceCalculator.kt` | 重要度计算 |
| 修改 | `MainActivity.kt` | Tab 1 替换 |
| 修改 | `AndroidManifest.xml` | 新增权限声明 |

---

## 后续修订（2026-09-20）

本节记录原始设计在落地后暴露的问题与修正，供后续维护参考。均在荣耀 DNP-AN00 真机验证。

### 1. 四个数据源必须「订阅即拉取」

原架构图里 `NotificationSource` 标注为「已有 StateFlow」，落地后实现成纯被动的
`flow { SmartNotificationListener.notifications.collect { ... } }` —— 只镜像内存 StateFlow，不主动查询系统。
结果它是四源中**唯一无法自愈**的：权限补授后重新进页面，SMS / 日历 / 通话（都是
`callbackFlow + trySend(fetch())`）能恢复，通知不能。

修正：`NotificationSource.observe()` 同样改为 `callbackFlow`，订阅时先
`SmartNotificationListener.refreshFromSystem()`。

### 2. 权限状态不能从数据源 Flow 的 `.catch` 推导

设计里没写清这点，落地时写成了：

```kotlin
calendarSource.observe().catch { e -> _calendarPermissionGranted.value = false; emit(emptyList()) }
```

但 `CalendarSource` / `SmsSource` 内部已把 `SecurityException` 吞掉并返回 `emptyList()`，
异常不会传播 → `.catch` 永不触发 → 这两个 flag 一直是初值 `true` → **未授权状态在 UI 上不可达**，
对应的去授权入口成了死代码。

修正：用 `ContextCompat.checkSelfPermission()` 真实检测（见
`PersonalCenterViewModel.checkCalendarAndSmsPermissionStatus()`），并在 init / `onScreenResumed` /
`refresh()` / 60s 轮询里都刷新一次。

### 3. 通知监听权限是「特殊权限」，且有两个独立状态

- 没有运行时申请 API，只能跳设置页：`ACTION_NOTIFICATION_LISTENER_SETTINGS`
  （ROM 无接收方时降级 `ACTION_APPLICATION_DETAILS_SETTINGS`）。
- **权限已授予 ≠ 服务已绑定**。`isNotificationListenerEnabled()` 读的是
  `Settings.Secure.enabled_notification_listeners`，而服务绑定由系统决定。荣耀的 `iaware` 会拦截
  绑定（`Service starting has been prevented by iaware or trustsbase`），此时权限为真但服务没起来。
  必须同时看 `SmartNotificationListener.isConnected`，两者的与才是「通知可用」。
- iaware 拦截**不是永久失败**：实测 force-stop 后重启，前 3 次 bind 被拦、第 4 次（约 10s 后）成功。

### 4. UI 需显式暴露「不可用」状态

原设计只给了统计数字和列表，没有权限态。补：

- 统计项未授权时 tint 转橙 + 显示 `!`，点击走去授权而非展开分类
- 列表为空时给出具体原因（未授权 / 服务未运行）+ 去授权按钮，而不是笼统的「暂无内容」
| 保留 | `notification/*` | 原有通知监听服务保留 |

## 权限变更

```xml
<!-- 已有 -->
<uses-permission android:name="android.permission.BIND_NOTIFICATION_LISTENER_SERVICE" />

<!-- 新增 -->
<uses-permission android:name="android.permission.READ_CALENDAR" />
<uses-permission android:name="android.permission.READ_SMS" />
```
