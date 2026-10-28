# SPEC-10 设备层 `:device`（WS-B）

**职责**：定位 adb、连接模拟器、执行命令、截图、触控、应用控制、前台检测、UI 节点树、设备信息读取。
**不依赖** `:shared`；对上游类型的适配（`AutomationActionBackend`、`ChannelPromptScreen`、`CaptureFrameBus` 等）放在 `:shared` 的桥接层或 `:app` 装配层，由 WS-H 接线。公共 API 在 M1 冻结到 `desktop/CONTRACTS.md`，本文给出必须满足的语义。

## 1. adb 进程执行（所有设备 I/O 的唯一出口）

`AdbExecutor`：

- 一律用 argv 列表启动（`ProcessBuilder(listOf(adb, "-s", serial, …))`），**不经过 `cmd.exe`**，不拼接命令字符串。设备端 shell 命令中的参数由本层统一做 POSIX 单引号转义。
- stdout 按字节读取（截图是二进制），stderr 单独读取；两路必须**并发排空**（各自线程或异步读取），否则输出大时会死锁（上游 `ProcessAdbShellCommandRunner` 的问题）。
- 每条命令必须带超时（按命令类别给默认值，写入 CONTRACTS.md）。超时后先杀进程树（`process.descendants()` 再 `destroyForcibly()`），再返回 `Timeout`。
- 文本输出按 UTF-8 解码，`\r\n` 统一为 `\n`（只对文本，二进制不动）。
- 结果类型化（sealed）：`Success(exitCode, stdout, stderr)`，或 `Failure(kind, detail)`，`kind` 至少包括：`AdbMissing`、`LaunchFailed`、`Timeout`、`DeviceOffline`、`Unauthorized`、`DeviceNotFound`、`ServerVersionConflict`、`ConnectionRefused`、`NonZeroExit`。错误识别基于 stderr 的已知文案（`device offline`、`unauthorized`、`device '…' not found`、`adb server version (…) doesn't match this client (…)`、`cannot connect to`、`failed to connect`、`no devices/emulators found`、`error: closed`、`protocol fault` 等）。**每种文案都要有 fixture 测试。**
- 并发：普通命令最多 4 个并行，截图单独一条通道，不与普通命令争抢。
- 给上游用的 `ShellCommandRunner` 适配器（如果装配层用到上游的 `AdbForceStopTerminator` / `AdbRelauncher`）：只接受 `argv[0] == "adb"` 的命令，替换为解析出的 adb 路径并插入 `-s <serial>`，其他一律 `require` 失败。

## 2. adb 定位（D-18）

按顺序取第一个可用的，结果和来源都显示在设备页：

1. 正在运行的模拟器自带的 adb（通过 `ProcessHandle.allProcesses()` 找模拟器进程的可执行文件路径，或读注册表安装目录）：
   - MuMu 12：`<安装目录>\shell\adb.exe`，新版本可能在 `<安装目录>\nx_main\adb.exe`。
   - 雷电 9：`<InstallDir>\adb.exe`（`HKCU\Software\leidian\ldplayer9` 的 `InstallDir`）。
2. `PATH` 中的 `adb.exe`。
3. 用户在设置中指定的路径。
4. 用户点击「下载 platform-tools」：从 Google 官方地址下载到 `<data>/tools/platform-tools/`，校验压缩包内含 `adb.exe`、`AdbWinApi.dll`、`AdbWinUsbApi.dll`，并记录 `adb version` 的输出。**发行包不内置 adb。**

候选路径的存在性检查用 `adb version` 验证可执行。所有路径都要支持中文和空格。

**adb server 冲突**：检测到 `ServerVersionConflict`，或 10 秒内 server 被反复重启 2 次以上时，停止重试，提示：「检测到多个不同版本的 adb 在争抢（常见原因：同时打开了其他助手或手机管家）。请在设置中改用模拟器自带的 adb，或关闭其他工具后重试。」（R-12）。`adb start-server` 最多主动执行一次（FB-06）。

## 3. 设备发现与连接

**发现**（设备页的「自动检测」按钮，以及启动时执行一次）：

1. `adb devices -l` 解析出已连接的设备。
2. 如果模拟器管理命令可用，用它枚举正在运行的实例和端口：MuMu 12 的 `MuMuManager.exe info -v all`；雷电 9 的 `ldconsole.exe list2`。输出格式以 L1 实测 fixture 为准。
3. 否则对候选端口执行 `adb connect`（每个 3 s 超时）：MuMu 12 为 `127.0.0.1:(16384 + 32·i)`，旧版为 `127.0.0.1:7555`；雷电 9 为 `emulator-(5554 + 2·i)` / `127.0.0.1:(5555 + 2·i)`。i 取 0..7，只对检测到正在运行的那种模拟器尝试。
4. 用户可以手动输入地址（`host:port`）。

同一台设备可能同时以 `emulator-5554` 和 `127.0.0.1:5555` 出现：按 `ro.serialno` + `/proc/sys/kernel/random/boot_id` 去重，优先显示 TCP 地址。

**连接状态机**（`StateFlow` 暴露给 UI）：

```text
Disconnected → Connecting → Verifying → Ready
                                 ↘ Failed(原因)
Ready → Lost → Reconnecting(第 n 次) → Ready | Failed
```

- Verifying：`getprop sys.boot_completed` 必须为 `1`（不为 `1` 时每 2 s 复查一次，最长 120 s，期间 UI 显示「模拟器启动中」；超时 → Failed「模拟器未完成启动」）；`ro.build.version.sdk` < 26（Android 8）时直接 Failed（「不支持 Android 8 以下的模拟器」）；读取 `wm size`、`ro.product.cpu.abilist`。
- Lost 的判定（满足任一）：连续 5 次截图失败；或 10 s 内没有任何成功帧（仅在截图需求方存在时计时）；或触控进程退出且重启 1 次仍失败；或 `adb get-state` 不再是 `device`。Reconnecting 每 5 s 一次，最多 12 次（FB-01）。**正在运行的任务不会续跑**：Lost 发生时任务立即按上游的停止路径结束，并在 UI 说明原因。
- 用户主动断开、切换设备时，先停止任务，再释放截图和触控。

## 4. 截图

`ScreenCapturer` 只负责「取一帧并解码成 ARGB 像素」；节奏、旋转门和总线投递在 `:shared` 的 `DesktopCaptureService` 桥接中（复用 P-01 搬出的上游纯类）。

**Raw 模式**：`adb exec-out screencap`。头部为 little-endian `u32`：`width`、`height`、`format`，Android 9 及以上通常还有一个 `u32`（色彩空间）。头长度由 `总长度 - width × height × 4` 推出，只接受 12 或 16，否则判为格式错误。`format` 只接受 1（RGBA_8888）、2（RGBX_8888，alpha 置 255）、5（BGRA_8888）；其他格式在测速阶段直接淘汰 Raw 模式。像素转换优先复用上游 `rgba8888ToArgbPixels`（通过桥接），签名不合适时自写并以 fixture 对照测试。
**PNG 模式**：`adb exec-out screencap -p`，用 `BitmapFactory` 垫片的同一路径解码。

**测速选模式（FB-02）**：连接 Ready 后各取 5 帧，选 p50 更低且解码成功的模式；只在连接时决定一次，运行中不切换。结果和耗时写入日志和设备页。p95 > 600 ms 时在环境检查中给出警告（R-04），并提示可尝试的模拟器设置（渲染模式、关闭高帧率）。

**节奏**：一个专用截图线程循环：截图 → 解码 → 投递 → 等到距离本轮开始满 300 ms（与上游 `FRAME_INTERVAL_MILLIS = 300` 一致）。截图本身超过 300 ms 时，下一轮立即开始，不积压。`CapturedFrame.timestampMillis = System.currentTimeMillis()`（与上游一致），取截图命令返回的时刻。投递后帧的所有权归消费者（上游约定由消费者 `recycle`）。

**旋转与尺寸**：启动时用上游 `CaptureDisplayStabilityGate`（要求横屏且连续 3 次稳定）；运行中用 `CaptureTransientRotationGate` / `CaptureRotationPolicy` 处理旋转，判定逻辑完全复用上游。帧尺寸为 1920×1080 时正常运行；其他横屏尺寸允许运行，但环境检查显示警告：「上游只在 1920×1080 下验证过」。

**黑帧**：照常投递（与 Android 端 MediaProjection 行为一致，上游识别逻辑自行处理过场黑屏）。连续全黑超过 10 s 时，UI 显示警告「截图持续全黑：模拟器窗口可能被最小化，或渲染模式不兼容」，不停止任务。

## 5. 触控（MaaTouch，D-04）

**部署**：每次连接 Ready 后，`adb push` MaaTouch 到 `/data/local/tmp/maatouch`（二进制随发行包附带，来源和 SHA-256 见 SPEC-60）。启动前先清理残留进程（`pkill -f com.shxyke.MaaTouch`，退出码 1 表示没有残留，属于正常情况）。
**启动**：`adb -s S shell CLASSPATH=/data/local/tmp/maatouch app_process / com.shxyke.MaaTouch.App`，保持 stdin 管道常开。读取头部 `^ <contacts> <W> <H> <pressure>` 和 `$ <pid>`，5 s 内读不到即启动失败。
**坐标**：MaaTouch 按注入时的显示坐标处理，内部不做旋转变换；`ScreenPoint` 是帧坐标，四舍五入成整数后直接发送，不缩放。每次动作前比较最新帧尺寸与头部的 `W×H`：既不相等也不互为转置时拒绝，返回 `Rejected("触控坐标系与截图尺寸不一致")`，并在 UI 提示重新连接。
**动作语义**（与上游无障碍后端逐条对齐，常量从上游读取或在漂移检查里核对）：

| 动作 | 校验（按顺序） | 发送 | 完成时机 |
|---|---|---|---|
| 公共 | 坐标 ≥ 0，否则 `Rejected("动作坐标无效")`；前台包 = 期望包，否则 `Rejected(GAME_NOT_FOREGROUND_REASON)`（§7）；触控进程存活，否则 `Rejected("触控服务未连接")` | | |
| Tap | | `d 0 x y 1`、`c`、`w 60`、`u 0`、`c` 一次写入 | 写入后等待 60 ms + 宽限，再返回 `Completed` |
| Swipe | `durationMillis ∈ 1..5000` 且 `duration + max(hold, 0) ≤ 5000`，否则 `Rejected("滑动时长超出范围")` | `d` 起点；按约 8 ms 一步线性插值的 `m` 与 `w`，最后一步精确落在终点；hold > 0 时 `w hold`（原地不动）；`u 0`；`c` | 写入后等待 duration + hold + 宽限 |
| Back | | `k 4 o`、`c` | 写入后立即返回 |

- 时间控制用设备端的 `w`，避免 PC→设备的管道抖动影响手势形状。上游在 hold 期间漂移 1 px 只是因为 Android 不允许零长度笔画；桌面原地按住即可，在 DECISIONS 登记。
- **单写者**：所有写入经过一个 actor（Channel），同一时刻只有一个手势在执行。
- 触控进程意外退出：当前和后续动作返回 `Rejected("触控服务已断开")`，连接状态转为 Lost（§3）。应用退出时关闭 stdin 并杀掉设备端进程。
- **兼容模式**（只能由用户在设置里手动选择，不自动切换）：Tap → `input tap`；hold = 0 的 Swipe → `input swipe x1 y1 x2 y2 duration`；hold > 0 的 Swipe → `Rejected("兼容模式不支持按住拖拽，请在设置中切换到 MaaTouch")`；Back → `input keyevent 4`。设置页说明兼容模式会导致黎明界地图拖拽失败。

## 6. 应用控制

| 能力 | 命令 | 解析要点 |
|---|---|---|
| 已安装包 | `pm list packages` | `package:` 前缀；与 `GameServer` 的包名求交集，结果交给上游 `GameClientResolver.resolve` |
| 启动组件 | `cmd package resolve-activity --brief -c android.intent.category.LAUNCHER <pkg>` | 取最后一行 `pkg/.Activity`；解析失败视为未安装或无入口 |
| 启动 | `am start -n <component>` | `am` 出错时退出码也可能是 0，**必须解析输出中的 `Error:` / `Exception`** |
| 强制停止 | `am force-stop <pkg>`，然后轮询 `pidof <pkg>` 直到为空（≤ 3 s） | 超时返回失败及原因 |
| 返回桌面 | MaaTouch `k 3 o` | 兼容模式用 `input keyevent 3` |
| 版本 | `dumpsys package <pkg>` | `versionName=`、`versionCode=`（取 `minSdk` 之前的数字）、`lastUpdateTime=` |
| 签名 MD5 | `pm path <pkg>` → `adb pull` base.apk 到 `<data>/cache/apk/` → `apksig` 读取签名证书 | 与上游一致：取 `apkContentsSigners` 的第一个证书（v3 / v2 当前签名者；只有 v1 时用 v1），对证书 DER 编码做 MD5，小写十六进制。按 `(pkg, versionCode, lastUpdateTime)` 缓存，命中则不再 pull；pull 完成后删除 APK |

装配层（WS-H）对上游 `GameClientHardCloser` 的接线：`foregroundPackage` = 前台监视器的缓存值；`pressHome` = 返回桌面；`killBackgroundProcesses` = `am force-stop`；`canKillOtherApps = true`。`force-stop` 比 `killBackgroundProcesses` 更彻底，在 DECISIONS 记录，L3 验证渠道账号不会因此被登出。

## 7. 前台检测（D-27）

- 解析 `dumpsys window` 的 `mCurrentFocus=Window{… u0 <pkg>/<activity>}`；值为 `null` 时取 `mFocusedApp`。都无法解析时结果为 `Unknown`。Android 9 和 Android 12 的输出都要有 fixture（包括 `mCurrentFocus=null`、输入法、状态栏、系统对话框、渠道 SDK 应用在前台等情况）。
- **动作路径实时查询**：每次 Tap / Swipe / Back 前查询一次；`Unknown` 也按不在前台处理（返回 `Rejected(GAME_NOT_FOREGROUND_REASON)`）。L1 测量这条命令的 p95 延迟，写入 PROGRESS；如果 p95 > 150 ms，可以改用常驻 `adb shell` 会话执行查询（需登记到 DECISIONS），不允许改成读缓存。
- **非挂起回调**（上游 `() -> String?` 形态，例如 `GameClientHardCloser.foregroundPackage`、`ChannelPromptScreen.foregroundPackage()`）：读取前台监视器的缓存。监视器在连接 Ready 期间每 500 ms 轮询一次；缓存超过 2 s 未更新时返回 `null`。

## 8. UI 节点树（渠道 SDK 登录页）

`UiAutomatorChannelPromptScreen` 实现上游 `ChannelPromptScreen`：

- `windows()`：`uiautomator dump /data/local/tmp/ui.xml` 后 `cat` 读回（不用 `/dev/tty`，不用 `/sdcard`，避免权限和存储问题），解析成上游的 `ChannelPromptWindow` / `ChannelPromptNode`（`text`、`content-desc`、`clickable`、`bounds="[l,t][r,b]"`、子节点）。按 `package` 属性分窗口。节点数和深度上限与上游 `AccessibilityChannelPromptScreen` 的常量一致。
- XML 解析禁用 DTD 和外部实体。
- 输出 `ERROR: could not get idle state` 时，最多重试 3 次，每次间隔 500 ms（FB-03）；仍然失败则返回空列表（与上游服务不可用时的行为一致），同时记录警告并在 UI 显示「无法读取渠道登录界面」。
- `click(match)`：重新 dump，按包名、目标文字和 bounds 找到**唯一**的活节点（与上游 `findLiveNode` 规则一致），再按 `clickTargetHops` 用上游 `resolveChannelPromptClickOwner` 找点击节点，对其 bounds 中心发送 Tap。找不到返回 `GONE`，触控被拒返回 `REFUSED`。这个点击同样经过 §5 的前台校验（期望包 = match 的包名）。
- 线程：`windows()` 在调用线程上同步执行。WS-H 在 THREADING.md 中确认调用方不在 `Dispatchers.Main`；如果在，改为后台轮询快照（最长 1.5 s 旧）并登记决策。

## 9. 设备信息（给 SPEC-40 的设备画像快照）

连接 Ready 后读取并写入 `DeviceIdentitySnapshot`：`ro.product.brand`、`ro.product.model`、`ro.build.version.release`、`ro.build.version.sdk`、`ro.product.cpu.abilist`、`settings get secure android_id`（空或 `null` 时记为空，由 SPEC-40 回落到 installId，FB-11）、`wm size`（有 Override 用 Override，否则用 Physical）、各已安装游戏包的 versionName / versionCode / 签名 MD5。快照带上设备键（serialno + boot_id 之外的稳定标识：serialno + android_id），持久化由 WS-D 提供。

## 10. 屏幕状态

`dumpsys power` 的 `mWakefulness`：非 `Awake` 时禁止开始任务，提示「模拟器屏幕已休眠，请先在模拟器中唤醒」。不自动发送唤醒键。

## 11. 测试

- `FakeAdb`：按 argv 匹配返回 fixture（stdout 字节、stderr、退出码、延迟、超时），用于本模块所有单测。
- fixture 目录 `desktop/device/src/test/resources/fixtures/<android9|android12>/…`，来源写在同目录 `SOURCES.md`（实机采集时间、模拟器版本）。无法实机采集时，按 AOSP 源码格式手写并标注「合成」，L1 时替换为实采。
- 必测：raw 头 12/16 字节和三种格式；截断的数据；PNG 解码；超时杀进程；大输出不死锁（≥ 64 MB 假输出）；全部错误文案；MaaTouch 头部解析、手势序列逐字节比对、进程退出；前台解析全部 fixture；uiautomator 重试与 XML 外部实体被拒；`am start` 退出码 0 但输出含 Error；中文和空格路径。
