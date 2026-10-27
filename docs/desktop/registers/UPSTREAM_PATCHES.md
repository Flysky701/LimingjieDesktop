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
| P-12 | `account/AccountRepository.kt` | 注入缝 | 可选 `onGameUidUpdated` 挂起回调，默认 `null`；认证 UID 本地更新前回传唯一数据拥有者 | — | 桌面运行实例只缓存账号元数据，UID 更新须写回全局服务 | 默认无；Android 未提供回调，仍执行原数据库更新 | 本轮仅桌面定向验证，见下文 | 本次共享数据提交 | 已实施 |
| P-13 | `labyrinth/AndroidLabyrinthStrategySettingsStore.kt` | 注入缝 | `transient = false`；仅桌面运行实例选择内存模式，保留原编码校验及状态流 | — | 权威全局策略只保存一份，运行实例持有启动时的固定版本 | 默认无；Android 仍读取和提交原 SharedPreferences | 本轮仅桌面定向验证，见下文 | 本次共享数据提交 | 已实施 |
| P-14 | `labyrinth/LabyrinthController.kt` | 只读属性 | 增加从原 `chrome` 读取工作/验证码状态的 `isBusy` | — | 任务启动与结束时，UI 投影可能晚一轮更新；桌面账号锁据同步状态释放 | 无；不改状态转换、业务条件或原 Android 调用 | 本轮仅桌面定向验证，最终同步状态加固待相关重跑 | 本次共享数据提交 | 已实施 |

## 2026-10-26 共享数据与运行标签适配

以上三处补丁保持原 Android 默认行为。桌面主数据角色独占账号凭据、策略与账号配置；设备 worker 使用内存凭据、内存 SDK 会话及只读策略快照，正常登录后回传服务器认证 UID。`isBusy` 仅消除桌面锁释放对异步 UI 投影的依赖，没有新增必须手动读取开局的门槛。

总指挥已统一执行阶段 1 的 16 项定向用例（真实 Graph 离线集成、GraphService、单设备 Supervisor、原 UI）及阶段 2 的 17 项定向用例（共享数据、运行页 UI、并发 Supervisor、worker 生命周期、设备身份），均通过。之后收尾补充的同步 busy 状态及权威账号配置保存顺序，由总指挥再重跑受影响的 GraphService、SharedDataIntegration 和真实 Graph 离线集成；本提交未自行运行 Gradle、Android 全量或实机验证。测试只使用临时目录、示例凭据和假设备，不接触 D5 稳定包、运行中的 D6 工作区或原账号数据。

## 2026-10-27 兼容路线坐标修正

`labyrinth/LabyrinthFallbackTaps.kt` 的备用点改为复用 `CoordinateMapper` 的居中 FIT 映射，修正非 16:9 画面下独立缩放 X/Y 造成的偏移。1920×1080 七个参考点、页面规则、策略和触发条件不变。该项属于明确的坐标行为修正，不归类为纯搬移或默认无变化的注入缝。

既有定向验证 11 项通过，包含原尺寸点位、宽高比、居中留边和已有按钮样本；记录见 [分辨率与 Android 版本兼容调查](../reports/COMPATIBILITY-RESOLUTION-ANDROID.md)。本次整理将原有未提交修复纳入 desktop.8 兼容基础版，没有重跑 Android 全量构建，也不宣称所有分辨率的游戏流程已验证。桌面自动视觉执行的 1920×1080 前置检查继续保留。

## 检查清单（每条补丁）

- [ ] 只移动了声明及其 KDoc；名称、签名、可见性、注解没有变化
- [ ] 原文件删除了对应声明，import 已调整，没有其他改动（`git diff` 只包含这些行）
- [ ] 没有格式化无关代码
- [x] Android lintDebug、testDebugUnitTest、assembleDebug 全通过；见 evidence/android/result.json。1,247 项中 1,201 通过、46 个缺素材跳过、0 失败。
- [ ] 桌面 `upstream-excludes.txt` 和分类表已同步
