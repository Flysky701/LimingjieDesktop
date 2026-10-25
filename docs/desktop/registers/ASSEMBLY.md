# ASSEMBLY.md —— `LandosolToolboxApplication` → `DesktopAppGraph` 逐段对照（G3-10）

**规则**：上游装配根的每个成员、每个 `onCreate` 步骤都要有一行。状态为 `已对齐` / `不适用 + 理由` / `未开始`。G3 要求全部为前两种。
桌面装配必须保持上游的**构造参数、回调语义、作用域划分和惰性初始化顺序**；只替换 Android 能力。行号基于 `b809b196`，M1 复核。
被排除的上游文件里的胶水逻辑（例如 `hardCloseChannelClientBeforeReroll`）要在 `DesktopAppGraph` 中按原样重写：同样的分支、同样的日志 tag 和文案；只有提到 Android 专属概念的文案（「无障碍服务」）改为桌面对应概念（「设备或触控服务」），并在「差异」列写明。

## 1. `onCreate`（`:80-111`）

| 步骤 | 桌面对应 | 差异 | 状态 | 测试 |
|---|---|---|---|---|
| `Log.i(APP_LOG_TAG, "黎明界助手启动：${AppVersion.display}")` 作为进程第一条日志 | `Main.kt` 中日志器初始化后的第一条日志，内容相同（`AppVersion` 来自上游） | 无 | 未开始 | |
| `logGameClient()` | 同一函数逻辑；设备未连接时客户端解析结果为不可用 | 无 | 未开始 | |
| `CaptureRotationPolicy.toleratesTransientRotation = { ChannelSdkTraits.toleratesTransientRotation(selectedGameServer) }` | 相同 | 无 | 未开始 | |
| `accountServerScope` 监听 `observeSelectedServer()`，更新 `selectedServer` 并记日志 | 相同 | 无 | 未开始 | |
| 立即创建 `labyrinthDebugDashboard`（启动诊断面板） | 相同；端口按 D-35 探测后传入 | 端口可能不是 8765（FB-10） | 未开始 | |
| `databaseUpdateScope` 执行 `labyrinthCnDatabaseRepository.updateIfNeeded()`，按结果记日志 | 相同；仓库为 `DesktopLabyrinthCnDatabaseRepository` | 先完成 FB-08 的暂存安装 | 未开始 | |
| 桌面新增：单实例锁、数据目录、`DesktopBindings.install`、崩溃处理器、`instance.lock` 后的 tmp 清理 | `Main.kt`，在以上所有步骤之前 | 新增 | 未开始 | |

## 2. 成员

| 上游成员（行） | 上游用到的 Android 能力 | 桌面对应 | 差异 | 状态 | 测试 |
|---|---|---|---|---|---|
| `databaseUpdateScope`、`accountServerScope`（`:77-78`，`SupervisorJob + IO`） | — | 相同 | 无 | 未开始 | |
| `automationSessionManager`（`:130`） | — | 相同 | 无 | 未开始 | |
| `automationOverlayCoordinator`（`:131`） | `AndroidAutomationNotificationHost(this)` | `AutomationOverlayCoordinator(<任务栏 + 托盘宿主>)`（WS-F） | 宿主替换 | 未开始 | |
| `selectedServer`（`:136`，`@Volatile`） | — | 相同 | 无 | 未开始 | |
| `installedGamePackages`（`:142`，首次取用时查询一次） | `PackageManager` | 每次设备连接 Ready 时通过 `pm list packages` 查询一次，并缓存到断开为止 | 上游在进程生命周期内只查一次；桌面可能切换模拟器，所以按连接查询。任务运行中连接变化 → 任务已按 FB-01 停止，不会读到混合状态（DECISIONS 记一条） | 未开始 | |
| `gameClientResolution` / `selectedGameServer` / `gamePackageName`（`:148-168`） | — | 相同（上游 `GameClientResolver.resolve`） | 无 | 未开始 | |
| `channelLoginPromptAssistant(logTag)`（`:159`） | `AccessibilityChannelPromptScreen()` | `UiAutomatorChannelPromptScreen`（WS-B） | 屏幕实现替换 | 未开始 | |
| `gameClientVersionName`（`:171`） | `getPackageInfo(...).versionName` | `dumpsys package` 的 `versionName`（按连接缓存，SPEC-10 §6） | 来源替换 | 未开始 | |
| `actionTargetPackageName`（`:182`，解析失败返回 `NO_GAME_CLIENT_SENTINEL`） | — | 相同；**必须**把同一个 provider 传给桌面动作后端，保证解析失败时动作稳定走 `Rejected` | 无 | 未开始 | |
| `accessibilityActionBackend`（`:185`） | `AndroidAccessibilityActionBackend { actionTargetPackageName }` | `DeviceActionBackend { actionTargetPackageName }`（WS-B） | 后端替换 | 未开始 | |
| `automationActionExecutor`（`:188`） | — | `SessionBoundActionExecutor(automationSessionManager, <桌面后端>)` | 无 | 未开始 | |
| `clanBattleRecognitionSession`（`:191`） | — | 相同（会战助手无 UI，但对象照常可构造，保持惰性） | 无 | 未开始 | |
| `labyrinthRoleDecisionData`（`:194`） | `AndroidLabyrinthRoleDecisionDataLoader(this)` | 相同（Context 垫片） | 无 | 未开始 | |
| `labyrinthStrategySettings`、`loadLabyrinthRoleRatings()`（`:197-212`） | Context | 相同 | 无 | 未开始 | |
| `labyrinthCnDatabaseRepository`（`:213`） | `AndroidLabyrinthCnDatabaseRepository(this)` | `DesktopLabyrinthCnDatabaseRepository`（WS-D） | 实现替换 | 未开始 | |
| `labyrinthDebugDashboard`（`:216`） | `cacheDir`、`LabyrinthRuntimeDiagnostics(this)` | `frameArchiveDirectory = <data>/cache/labyrinth-frame-archive`；诊断替身（WS-F）；`port`（D-35）；`processLogSource`（P-07） | 端口、日志来源 | 未开始 | |
| `labyrinthEntryRecognitionSession`（`:224-343`） | 帧处理器 `AndroidLabyrinthEntryFrameProcessor.create(this, …)` 等 | 构造参数逐个对照，结果附在本行下方的子表中 | 待逐项 | 未开始 | |
| `gameLaunchIntent(target)`（`:344`） | `getLaunchIntentForPackage` + `startActivity` | `cmd package resolve-activity` + `am start -n`（SPEC-10 §6） | 来源替换 | 未开始 | |
| `database`、`labyrinthRunStateStore`、`labyrinthRouteStore`（`:348-350`） | Room Android | `AppDatabase` 替身（WS-D） | 无 | 未开始 | |
| `credentialStore`、`sessionStore`（`:351-352`） | Keystore | 上游类原样 + `AndroidKeystoreEncryptedStore` 替身（WS-D） | 无 | 未开始 | |
| `gameSessionRegistry`（`:353`） | — | 相同 | 无 | 未开始 | |
| `bilibiliSdkGateway`、`bilibiliSdkLoginCoordinator`、`bilibiliNativeLoginCoordinator`（`:354-367`） | `BilibiliSdkGatewayFactory.create(this)` | 相同；画像工厂替身（WS-E） | 画像来源 | 未开始 | |
| `gameGateways` / `gameGatewayFor(server)`（`:368-388`），含「未安装…客户端」的 `check` | Context | 相同；`installedGamePackages` 为按连接的结果 | 见上 | 未开始 | |
| `accountRepository`（`:389`） | — | 相同 | 无 | 未开始 | |
| `labyrinthController`（`:392-402`） | `AndroidLabyrinthRerollSettingsStore(this)`；`launchForeground = LabyrinthRerollService.start(this)` | 设置存储原样（prefs 垫片）；`launchForeground` = 桌面任务宿主启动（WS-H `tasks/`） | 前台服务替换 | 未开始 | |
| `gameClientHardCloseLedger`、`gameClientHardCloser`（`:403-425`） | `LandosolAccessibilityService::foregroundPackage`、`GLOBAL_ACTION_HOME`、`killBackgroundProcesses`、`canKillOtherApps = SDK_INT <= LAST_SDK_THAT_KILLS_OTHER_APPS` | 前台缓存（D-27）、MaaTouch `k 3 o`、`am force-stop`（D-29）、`canKillOtherApps = true` | 见 D-29 | 未开始 | |
| `hardCloseChannelClientBeforeReroll`（`:426`） | `LandosolAccessibilityService.isConnected()` | 「设备 Ready 且触控已连接」；未满足时文案改为「…刷开局前需要先大退游戏，但设备或触控服务未连接」 | 文案中的概念替换 | 未开始 | |
| `coldStartChannelClientAfterReroll`、`consumeGameClientHardClosed`（`:448-463`） | `startActivity` | `am start -n`；日志相同 | 来源替换 | 未开始 | |
| `validateLabyrinthExecutionForAccount`（`:464`） | — | 相同 | 无 | 未开始 | |
| `gameSessionResetWorkflow`（`:480-568`） | `foregroundPackage`、`available = isConnected`、`Relauncher`（launch intent）、`AndroidLabyrinthEntryFrameProcessor.create(this, …)` | 前台缓存；`available` = 设备 Ready 且触控已连接；`Relauncher` 用 `am start -n` | 见各项 | 未开始 | |
| `labyrinthBatchCheckpointStore`（`:569`） | `AndroidLabyrinthBatchCheckpointStore(this)` | 原样（prefs 垫片） | 无 | 未开始 | |
| `labyrinthBatchController`（`:577-655`） | 内部匿名实现调用上面各项 | 原样移植匿名实现 | 无 | 未开始 | |
| `labyrinthAutoRunWorkflow`（`:656-686`） | — | 相同 | 无 | 未开始 | |
| `autoRunScope`（`SupervisorJob + Default`）、`autoRunJob`、`startLabyrinthAutoRun`、`stopLabyrinthAutoRun`（`:687-768`） | — | 相同 | 无 | 未开始 | |

### 2.1 `labyrinthEntryRecognitionSession` 构造参数（M1/M3 填写）

| 参数 | 上游取值 | 桌面取值 | 状态 |
|---|---|---|---|
| | | | |

## 3. 桌面新增（上游没有）

| 成员 | 作用 | 归属 | 状态 |
|---|---|---|---|
| 设备连接管理（`:device` + 状态机） | 发现、连接、重连 | B / H | 未开始 |
| 前台监视器 | D-27 | B | 未开始 |
| OCR 引擎生命周期 | 后台加载与预热 | C / H | 未开始 |
| 防睡眠控制器 | 条件见 UI_PARITY §2 | D / H | 未开始 |
| 回环验证码服务 | D-10 | E | 未开始 |
| 退出流程 | G3-8 中列出的资源全部释放 | H | 未开始 |
