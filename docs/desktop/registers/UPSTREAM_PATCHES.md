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
| P-15 | `labyrinth/LabyrinthEntryRecognitionSession.kt` | 流程拆分与行为修正 | 特别装备流程抽取；编组停滞判断改读业务进展与动作派发，不读展示文字和日志标签 | `LabyrinthSpecialEquipmentFlow.kt`、`LabyrinthBattleTeamProgress.kt` | 明确流程边界，并防止反复刷新提示重置停滞计时 | 有：无动作、无业务进展持续达到原超时上限时会停止 | 本轮不运行 Android 测试；桌面构建结果见本节记录 | 本分支 | 已实施 |
| P-16 | `labyrinth/LabyrinthEntryRecognitionSession.kt` | 纯搬移 | 战斗重试、节点导航及会话弹窗分类规则按业务归属移至同包文件 | `LabyrinthBattleRetryPolicy.kt`、`LabyrinthNodeExecutionRules.kt`、`LabyrinthSessionBlockRules.kt` | 减少业务规则与会话控制的混杂，保持现有调用入口 | 未有意改变；阈值、条件和返回值沿用原逻辑 | 本轮不运行 Android 测试；桌面构建结果见本节记录 | 本分支 | 已实施 |

## 2026-10-27 逻辑拆解版：剩余业务流程

后续内部批次 P-19：`LabyrinthOrphanMoveRecoveryWorkflow.kt` 收拢无待办移动弹窗恢复的五项私有状态及完整处理流程，删除原会话/转换端口的对应字段接线。路线只读查询、目标恢复、取消回执、预算恢复和入口重置通过明确端口交接；原三个私有入口保留。动作顺序、阈值与原重置边界不作行为修正，P-15 的既有行为修正仍独立记录。仅做局部源码对照和桌面编译，不运行或修改测试、不操作游戏；本批具体结果见拆解记录。

| 编号 | 修改位置 | 类型 | 目的及内容 | 行为边界 | 构建范围 |
| --- | --- | --- | --- | --- | --- |
| P-17 | `labyrinth/LabyrinthEntryRecognitionSession.kt`、`labyrinth/execution/`、`LabyrinthWorkflowBindings.kt` 及分域规则/模型文件 | 业务拆解与兼容接线 | 战斗、角色扫描、EX、奖励、地图、商店、事件、结算、入口恢复、持久化和展示拆为 20 个执行模块；按域处理换页/新局重置与动作回执，门面保留原字段和入口 | 沿用原顺序、阈值、默认值、动作互斥及游戏画面确认条件；没有新增有意的游戏策略变化 | 桌面编译与便携包；不运行或修改测试，不操作游戏 |
| P-18 | `LabyrinthFrameTrace.kt`、`LabyrinthWorkflowDiagnostics.kt`、`debug/LabyrinthDebugDashboardServer.kt` | 诊断增强 | 输出 `workflowOwner`、`workflowPhase`，帮助定位处理帧的业务模块和阶段 | 只写诊断，不用于选择动作或判断业务进展 | 同上 |

本轮延续 `codex/logic-decomposition`，版本为 `1.0.18+desktop.7.logic.2`。独立基线工作树固定在 `308368d`；源码对照确认搬出的 103 个基线规则/模型声明保留原文（仅忽略空白与包内可见性变化）。两个原私有嵌套模型改为包内模型，并新增页面动作建议模型。业务执行类不持有具体会话实例，接线集中在兼容适配文件中。

完整模块清单、定位入口及构建边界见 [LOGIC_DECOMPOSITION.md](LOGIC_DECOMPOSITION.md)。本轮不修改兼容工作区，也不纳入其中尚未提交的修改。静态对照和构建不代替动态行为验证。

## 2026-10-27 逻辑拆解版：编组与特别装备

本分支按用户授权从移植兼容工作转向业务逻辑拆解。P-15 不属于最初移植阶段的“纯搬移或默认不变的注入缝”限制；其中停滞判断是明确的行为修正。

特别装备的流程、动作准入结果和进展投影独立；原会话保留状态字段与会话动作执行，先通过接口委托，避免同时改变跨流程动作锁、节流和状态生命周期。流程仍使用共享上游源码编译，不复制第二套业务实现。

节点地图手势、节点切换保护与定位规则，战斗重试与会话阻断分类分别迁入同包文件。旧函数签名及可见性保留；执行流程仍由原会话调用。此项是规则归类，尚不等于相关业务流程已全部拆开。

基线曾出现：编组页每帧刷新提示文字，即使业务状态和动作没有进展，原计时也会重新开始。现改为比较编组业务状态及动作派发序号，不以消息或诊断标签变化表示进展。原来的 120 秒阈值保留；是否发生实际点击与游戏是否确认仍分别处理。

用户要求不再进行测试、电脑操控和改动已有测试。已有测试文件保持原样；此后的构建仅用于确认编译和打包，不等于业务闭环或实机验证。当前拆解尚未覆盖完整的战斗、奖励和地图链路。

最终桌面便携包构建完成：`1.0.18+desktop.7.logic.1`。使用项目原有 `:app:packagePortableZip`，排除测试任务；构建在逻辑拆解工作树内完成。节点、战斗重试和会话弹窗规则与独立基线工作树的原文对照一致。现有兼容版的未提交修改未纳入此版本。

## 2026-10-26 共享数据与运行标签适配

以上三处补丁保持原 Android 默认行为。桌面主数据角色独占账号凭据、策略与账号配置；设备 worker 使用内存凭据、内存 SDK 会话及只读策略快照，正常登录后回传服务器认证 UID。`isBusy` 仅消除桌面锁释放对异步 UI 投影的依赖，没有新增必须手动读取开局的门槛。

总指挥已统一执行阶段 1 的 16 项定向用例（真实 Graph 离线集成、GraphService、单设备 Supervisor、原 UI）及阶段 2 的 17 项定向用例（共享数据、运行页 UI、并发 Supervisor、worker 生命周期、设备身份），均通过。之后收尾补充的同步 busy 状态及权威账号配置保存顺序，由总指挥再重跑受影响的 GraphService、SharedDataIntegration 和真实 Graph 离线集成；本提交未自行运行 Gradle、Android 全量或实机验证。测试只使用临时目录、示例凭据和假设备，不接触 D5 稳定包、运行中的 D6 工作区或原账号数据。

## 检查清单（每条补丁）

- [ ] 只移动了声明及其 KDoc；名称、签名、可见性、注解没有变化
- [ ] 原文件删除了对应声明，import 已调整，没有其他改动（`git diff` 只包含这些行）
- [ ] 没有格式化无关代码
- [x] Android lintDebug、testDebugUnitTest、assembleDebug 全通过；见 evidence/android/result.json。1,247 项中 1,201 通过、46 个缺素材跳过、0 失败。
- [ ] 桌面 `upstream-excludes.txt` 和分类表已同步
