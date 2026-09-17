# Current Status — 2026-09-17 — 废弃 API 清理收官 & lint 修复

> 距上一份状态文档(2026-07-03)2.5 个月,距最后一次提交(71d7711, 07-05)同。本文补记 7 月主线 + 本次清理批。

## ✅ 本次成果(随本批提交)

| 内容 | 变更 |
|------|------|
| Compose/Material3 废弃 API 清理收官 + lintDebug 修复 | 19 文件, +77/-349(净删 272 行) |

清理项(全部为等价替换或死代码删除,无业务逻辑变更):

- **Icons.AutoMirrored 全量迁移**:ChatScreen / ItemSource / A2UICards / SessionListDrawer / TriggerScreen / ModelDownloadScreen / MainActivity / PluginScreen / ComponentRegistry(全文件 13 处:ICON_MAP 11 项 + 股票卡片内联 TrendingUp/Down 2 处)/ A2UIComprehensiveDemo。RTL 场景下方向类图标行为修正
- **Divider → HorizontalDivider**:PersonalCenterScreen / ModelDownloadScreen / ComponentRegistry ×2
- **剪贴板迁移**:废弃的 `LocalClipboardManager` 全仓库清零,统一为 `ClipboardManager.setPrimaryClip(ClipData.newPlainText(...))`(MainActivity/ChatScreen/SettingsScreen;含 MainActivity 半截迁移收尾)
  - 决策记录:07-03 待办原计划用 `LocalClipboard`(suspend 版);实际采用系统 ClipboardManager 方案——同步调用、无协程样板,同样消除废弃 API
- **LinearProgressIndicator** 迁移 lambda progress API(AnimatedComponents)
- **SystemPromptLoader → AgentPromptLoader**(GatewayManager 最后一个调用点,废弃 shim 清零)
- **AppDatabase** 仅加 `@Suppress("DEPRECATION")`,**schema version=8 与迁移链未动,无数据风险**
- **AndroidTTSEngine**:移除 3 个错误 `@Deprecated`(onStart/onDone 在 SDK 中并未废弃);`onError` 用 `@Suppress("OVERRIDE_DEPRECATION")`——K2 下 `DEPRECATION` 与 `OVERRIDING_DEPRECATED_MEMBER` 均压不住该诊断,实测有效
- **死代码删除**:整个 `ui/MessageBubble.kt`(`EnhancedMessageBubble` 零引用,三源集 grep 确认;架构评审 A2UI-Architecture-Review.md 本就建议删除);3 处未使用 `val scope = rememberCoroutineScope()`;6 个残留 import

---

## 📅 2026-07-03 文档之后落地的主线(补记)

| Commit | 日期 | 内容 |
|--------|------|------|
| `08d8922` | 06-04 | MultiSearchSkillTest 适配 v2 卡片格式 + TriggerScreen 实验性布局 API @OptIn(F-07 修复) |
| `6961955` | 06-04 | 6 个 skill 适配 A2UI v2 卡片格式(AppLauncher/File/Settings/SMS/Device/Notification)+ typeTitle 映射 |
| `66014c6` | 06-04 | T005 预取数据层:WeatherSkill cache-first 30min TTL、PrefetchWorker、CachedDataEntity/DAO、**AppDatabase v7→v8** |
| `91b1754` | 07-04 | TriggerScreen 集成为 MainActivity 第 4 个 tab(F-11) |
| `495e54f` / `137e606` | 07-05 | feature/dev 合入 dev → master |
| `71d7711` | 07-05 | trigger v1/v2 存储统一进 Room trigger_rules 表 |

## 📋 07-03 待办兑现状态

- [x] F-07 release-blocker(TriggerScreen FlowRow 缺 @OptIn)— `08d8922` 已修(写文档时 feature 分支已修但文档滞后)
- [x] Icons.AutoMirrored 替换 — 本批完成(当时只列了 Chat,实际全量清扫)
- [x] LocalClipboardManager 替换 — 本批完成(方案变更,见上文决策记录)
- [ ] **>20 轮真机压测 Fix B trim 路径** — 仍未做(07-03 遗留)
- [ ] **统一 history 列表与 state(删 mutable history list)** — 仍未做(07-03 遗留,文档自评"bug 温床")

---

## 🐛 工具链问题:lint 崩溃(compose runtime 1.9.0 × AGP 8.7.3)

### 现象与根因

`gradlew lintDebug` 崩溃 `IncompatibleClassChangeError`。根因:`androidx.compose.runtime:runtime` **1.9.0**(传递依赖,解析时覆盖了 1.7.8)自带 lint.jar,其中多个 detector 与 AGP 8.7.3 的 lint 31.7.3 二进制不兼容。

### 实证过程(逐个 detector 剥洋葱)

1. 只 disable `RememberInComposition`(原有配置)→ 仍崩,路径经 `FrequentlyChangingValueDetector` 调共享 helper
2. + disable `FrequentlyChangingValue` → android_compose 通过;app 换崩在 `AutoboxingStateCreationDetector`
3. app 再 + disable `AutoboxingStateCreation` → **`:app:lintDebug` 与 `:android_compose:lintDebug` 双双通过**(app 需 3 条,android_compose 需 2 条)

### 教训

判断 lint issue id 是否存在,**必须解包实际解析到的依赖 AAR**(查 `debugRuntimeClasspath`),不能只看 AGP 自带 lint 注册表——传递依赖会把高版本 lint.jar 带进来。本批曾据"注册表中无此 id"误判两条 disable 为 no-op 并删除,被独立对抗审查(解包 runtime AAR 找到两个 Detector class)推翻后恢复。

---

## 📊 验证记录

- **CLAUDE.md 三项必检全绿**:`:app:testDebugUnitTest`、`:android_compose:testDebugUnitTest`、`:app:compileDebugAndroidTestKotlin`、`:android_compose:compileDebugUnitTestKotlin`、`assembleDebug`
- **两模块强制全量重编译**(`--rerun-tasks`)后,本次清理目标的废弃 API 警告**清零**;剩余废弃警告均在未触碰子系统(OkHttpFeishuClient / DeviceSkill / NotifySkill / CameraSkill / SettingsSkill / SMSSkill / Theme / A2UICards)
- **lintDebug 双模块通过**(见上)
- 流程:workflow 多 agent 自检(意图梳理 + 分簇审查 + 对抗验证 + 完整性批评)→ 修复 → 独立 agent 对抗复审 → lint A/B 实验
- 注:`./gradlew` 在本机直接跑会挂 `GradleWrapperMain` 类加载错误,需用 CLAUDE.md 里的 `java -cp gradle/wrapper/gradle-wrapper.jar` 回退命令

---

## 📋 后续待办

- [ ] **>20 轮真机压测 trim 路径**(07-03 遗留,构造大 history 场景)
- [ ] **统一 history/state 双轨制**(07-03 遗留)
- [ ] 其余子系统废弃 API 清理(需单独评估,含敏感 API):DeviceSkill wake lock / NetworkInfo 系、NotifySkill PRIORITY 系、OkHttpFeishuClient `RequestBody.create`、CameraSkill `createCaptureSession`、Theme `statusBarColor`、A2UICards `outlinedButtonBorder`
- [ ] lint 崩溃根因修复:升级 AGP(>8.7.3)或审视 compose runtime 1.9.0 引入来源,然后移除三条 disable
- [ ] personalcenter/ 模块(PersonalCenterScreen、ItemSource)无架构文档,CLAUDE.md 未提及
- [ ] CLAUDE.md 架构段落同步 AppDatabase v8(CachedDataEntity/DAO、T005 预取层)

---

## 📁 相关文件

| 文件 | 改动 |
|------|------|
| `ui/MessageBubble.kt` | **删除**(-283 行,EnhancedMessageBubble 死代码) |
| `android_compose/.../ComponentRegistry.kt` | AutoMirrored ×13(ICON_MAP 11 项 + 内联 TrendingUp/Down)+ Divider ×2 + import |
| `app/build.gradle.kts` | lint disable 补全(+AutoboxingStateCreation) |
| `android_compose/build.gradle.kts` | lint disable 补全(+FrequentlyChangingValue) |
| `ChatScreen.kt` / `MainActivity.kt` / `SettingsScreen.kt` | 剪贴板统一 + AutoMirrored + 死代码清理 |
| 其余 10 文件 | AutoMirrored / HorizontalDivider / lambda API / @Suppress / shim 迁移 |
