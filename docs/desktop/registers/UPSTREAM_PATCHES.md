# UPSTREAM_PATCHES.md —— 对 `android/` 的修改登记（只有总指挥可以修改）

**规则**（AGENTS.md §2）：只允许 (a) 纯搬移、(b) 带默认值的注入缝。每条都要填完整，「Android 行为影响」必须是「无」。
验证：本地有 Android SDK 时在 `android/` 下执行 `./gradlew lintDebug testDebugUnitTest assembleDebug`；没有时以 CI 的 `android-verify` job 为准，填 run 链接。
计划中的补丁不一定都需要；M1 扫描后不需要的标「不需要」并写明原因。新增补丁从 P-08 顺延。

| ID | 文件 | 类别 | 内容 | 新文件 | 理由 | Android 行为影响 | Android 验证 | commit | 状态 |
|---|---|---|---|---|---|---|---|---|---|
| P-01 | `automation/capture/MediaProjectionCaptureService.kt` | 纯搬移 | `CaptureDisplayMetrics`、`CaptureDisplayStabilityGate`、`CaptureRotationPolicy`、`CaptureRotationDecision`、`CaptureTransientRotationGate`、`rgba8888ToArgbPixels`、`CaptureFrameScratch`、`CaptureStateRegistry`（以 M1 扫描为准） | 同包 `CaptureRuntime.kt` | 桌面截图桥接复用上游纯逻辑 | 无 | | | 计划 |
| P-02 | `automation/accessibility/LandosolAccessibilityService.kt` | 纯搬移 | `GAME_NOT_FOREGROUND_REASON` | 同包 `AccessibilityContracts.kt` | 桌面动作后端返回同一拒绝原因 | 无 | | | 计划 |
| P-03 | `labyrinth/vision/AndroidLabyrinthRoleRewardNameResolver.kt` | 纯搬移 | `object AndroidChineseRoleNameOcr` | 同包 `AndroidChineseRoleNameOcr.kt` | 让 OCR 单例可以被同名替身替换，而 resolver 本身保留 | 无 | | | 计划 |
| P-04 | `protocol/bilibili/AndroidGameProtocolProfileFactory.kt` | 纯搬移 | `GameProtocolProfile` | 同包 `GameProtocolProfile.kt` | 数据类被保留代码使用，工厂本身被替身替换 | 无 | | | 计划 |
| P-05 | `automation/overlay/AndroidAutomationNotificationHost.kt` | 纯搬移 | `AutomationNotificationContent`、`notificationContent()`、`automationNotificationActionMatches` | 同包 `AutomationNotificationContent.kt` | 托盘和任务栏复用通知文案 | 无 | | | 计划 |
| P-06 | `labyrinth/LabyrinthRerollService.kt` | 纯搬移 | `labyrinthRerollStatusText` | 同包 `LabyrinthRerollStatusText.kt` | `LabyrinthScreen` 依赖它 | 无 | | | 计划 |
| P-07 | `labyrinth/debug/LabyrinthDebugDashboardServer.kt` | 注入缝 | 构造参数 `processLogSource: () -> ByteArray`，默认值为原 logcat 采集（提成文件内顶层 `private fun`） | — | 日志包的 `logs/logcat.txt` 改用桌面进程日志 | 无（默认值等于原行为） | | | 计划 |

## 检查清单（每条补丁）

- [ ] 只移动了声明及其 KDoc；名称、签名、可见性、注解没有变化
- [ ] 原文件删除了对应声明，import 已调整，没有其他改动（`git diff` 只包含这些行）
- [ ] 没有格式化无关代码
- [ ] Android 编译 + 单测通过（链接或日志）
- [ ] 桌面 `upstream-excludes.txt` 和分类表已同步
