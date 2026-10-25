# UPSTREAM_PATCHES.md —— 对 `android/` 的修改登记（只有总指挥可以修改）

**规则**：只允许纯搬移或带原行为默认值的注入缝。下表为已实施内容；Android 端验证结果以本节后续记录为准。
验证：本地有 Android SDK 时在 `android/` 下执行 `./gradlew lintDebug testDebugUnitTest assembleDebug`；没有时以 CI 的 `android-verify` job 为准，填 run 链接。
计划中的补丁不一定都需要；M1 扫描后不需要的标「不需要」并写明原因。新增补丁从 P-08 顺延。

| ID | 文件 | 类别 | 内容 | 新文件 | 理由 | Android 行为影响 | Android 验证 | commit | 状态 |
|---|---|---|---|---|---|---|---|---|---|
| P-01 | `automation/capture/MediaProjectionCaptureService.kt` | 纯搬移 | 显示稳定、旋转、像素转换、临时缓冲及捕获状态声明 | 同包 `CaptureRuntime.kt` | 桌面截图桥接复用上游纯逻辑 | 无 | Android 全检查通过 | 321a3f1 | 已实施 |
| P-02 | `automation/accessibility/LandosolAccessibilityService.kt` | 纯搬移 | `GAME_NOT_FOREGROUND_REASON` | 同包 `AccessibilityContracts.kt` | 桌面动作后端返回同一拒绝原因 | 无 | Android 全检查通过 | 321a3f1 | 已实施 |
| P-03 | `labyrinth/vision/AndroidLabyrinthRoleRewardNameResolver.kt` | 纯搬移 | `internal object AndroidChineseRoleNameOcr` | 同包 `AndroidChineseRoleNameOcr.kt` | 单例替身，resolver 保留 | 无 | Android 全检查通过 | 321a3f1 | 已实施 |
| P-04 | `protocol/bilibili/AndroidGameProtocolProfileFactory.kt` | 纯搬移 | `GameProtocolProfile` | 同包 `GameProtocolProfile.kt` | 数据类保留、工厂替换 | 无 | Android 全检查通过 | 321a3f1 | 已实施 |
| P-05 | `automation/overlay/AndroidAutomationNotificationHost.kt` | 纯搬移 | `AutomationNotificationContent`、`notificationContent()`、`automationNotificationActionMatches` | 同包 `AutomationNotificationContent.kt` | 桌面经公开桥接复用通知文案 | 无 | Android 全检查通过 | 321a3f1 | 已实施 |
| P-06 | `labyrinth/LabyrinthRerollService.kt` | 纯搬移 | `labyrinthRerollStatusText` | 同包 `LabyrinthRerollStatusText.kt` | 桌面任务状态复用 | 无 | Android 全检查通过 | 321a3f1 | 已实施 |
| P-07 | `labyrinth/debug/LabyrinthDebugDashboardServer.kt` | 注入缝 | `processLogSource` 默认原采集函数；采集函数移动到 companion 内 | — | 桌面日志替换 logcat 来源 | 无 | Android 全检查通过 | 321a3f1 | 已实施 |
| P-08 | `permissions/PermissionStatusReader.kt` | 纯搬移 | `PermissionCenterState` | 同包 `PermissionCenterState.kt` | 保留权限数据类及纯测试编译 | 无 | Android 全检查通过 | 321a3f1 | 已实施 |
| P-09 | `automation/overlay/AutomationOverlayService.kt` | 纯搬移 | `wrapAutomationOverlayLabel` 与私有省略号常量 | 同包 `AutomationOverlayLabelLayout.kt` | 保留原排版测试 | 无 | Android 全检查通过 | 321a3f1 | 已实施 |
| P-10 | `LabyrinthController`、`LabyrinthEntryRecognitionSession`、`GameSessionResetWorkflow` | 注入缝 | 原有 scope/actionScope 改为构造参数，默认表达式与原成员相同 | — | 应用退出能取消归属协程 | 无 | Android 全检查通过；共享相关测试通过 | 9573ffc | 已实施 |
| P-11 | `labyrinth/debug/LabyrinthDebugDashboardServer.kt` | 注入缝 | `serverSocketFactory = ::ServerSocket`、`allowAddressReuse = true` | — | 桌面独占端口并持有关闭句柄 | 无 | Android 全检查通过；桌面诊断测试通过 | 9573ffc | 已实施 |

## 检查清单（每条补丁）

- [ ] 只移动了声明及其 KDoc；名称、签名、可见性、注解没有变化
- [ ] 原文件删除了对应声明，import 已调整，没有其他改动（`git diff` 只包含这些行）
- [ ] 没有格式化无关代码
- [x] Android lintDebug、testDebugUnitTest、assembleDebug 全通过；见 evidence/android/result.json。1,247 项中 1,201 通过、46 个缺素材跳过、0 失败。
- [ ] 桌面 `upstream-excludes.txt` 和分类表已同步
