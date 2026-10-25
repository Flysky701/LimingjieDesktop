# EDGE_CASES.md —— 边界情况登记表

**规则**：每一条都必须在「证据」列填上以下之一，才算完成：测试（`类名#方法名`）、实机清单项（`L1-xx` / `L2-xx` / `L3-xx`，见 ACCEPTANCE.md）、CI 步骤名，或 `N/A：理由`。
「验证」列给出预期的验证方式：**T** = 单元或集成测试，**L** = 实机，**CI** = 流水线步骤，**D** = 只需写进文档。
执行中发现的新边界情况，按同样格式追加到对应分组的末尾，编号顺延。

## A. ADB 与设备连接

| ID | 场景 | 期望行为 | 验证 | 证据 |
|---|---|---|---|---|
| A-01 | 找不到 adb（没有模拟器自带的、PATH 中没有、也未指定） | 设备页显示「未找到 adb」，提供「指定路径」「下载 platform-tools」「查看说明」；不崩溃 | T | ProcessAdbExecutorTest#missingAndLaunchFailuresAreTyped；UI 展示未在该测试覆盖 |
| A-02 | adb 路径包含中文或空格 | 正常执行 | T | ProcessAdbExecutorTest#argvSupportsChineseSpacesAndShellQuotes |
| A-03 | adb server 版本冲突 | 停止重试，给出 R-12 的提示 | T（fixture） | ProcessAdbExecutorTest#daemonStartsOnlyOnceAndVersionConflictNeverRestarts、repeatedDaemonRestartIsConflictEvenWhenCommandSucceeds |
| A-04 | adb server 未启动或被其他程序杀掉 | `adb start-server` 一次（FB-06）；仍失败则显示原因 | T | ProcessAdbExecutorTest#daemonStartsOnlyOnceAndVersionConflictNeverRestarts |
| A-05 | 设备 `unauthorized` | 提示在模拟器或设备上允许 USB 调试 | T（fixture） | ProcessAdbExecutorTest#allKnownFailurePhrasesAreClassified（合成 stderr） |
| A-06 | 设备 `offline` | 进入 Lost → Reconnecting（FB-01） | T | ProcessAdbExecutorTest#allKnownFailurePhrasesAreClassified；实际 offline 时间线待 L1-11 |
| A-07 | 同时连接了多台设备 | 由用户选择；所有命令都带 `-s` | T | ProcessAdbExecutorTest#argvSupportsChineseSpacesAndShellQuotes；AdbDeviceServiceTest#discoveryDeduplicatesStableDeviceAndPrefersTcp |
| A-08 | 同一设备以两种地址出现 | 按 serialno + boot_id 去重，优先 TCP 地址 | T | AdbDeviceServiceTest#discoveryDeduplicatesStableDeviceAndPrefersTcp |
| A-09 | 模拟器还在开机（`sys.boot_completed` ≠ 1） | 每 2 s 复查，最长 120 s，期间显示「模拟器正在启动」；超时 Failed | T | AdbDeviceServiceTest#disconnectCancelsBootPollingImmediately；120 秒耗尽未验证，L1-01 |
| A-10 | Android 8 以下 | Failed：「不支持 Android 8 以下的模拟器」 | T | AdbDeviceServiceTest#unsupportedAndroidFailsAndSleepIsVisibleWithoutWakeCommand |
| A-11 | 任务运行中模拟器被关闭 | Lost；任务按上游停止路径结束并说明原因；重连 12 次后 Failed；**任务不续跑** | T（FakeAdb 时间线） | AdbDeviceServiceTest#failedTouchRestartLosesConnectionAndReconnectDoesNotSendActions；DesktopGraphIntegrationTest#realGraphRecognizesUpstreamFixturePausesStopsAndReleasesResources；12 次耗尽待 L1-11 |
| A-12 | 模拟器重启（boot_id 变化） | 视为新会话：重新推送 MaaTouch、重新测速、刷新设备画像 | T | 未验证：L1-11（真实重启/boot_id 变化） |
| A-13 | adb 命令卡住 | 超时后杀进程树，返回 `Timeout` | T | ProcessAdbExecutorTest#timeoutKillsParentAndDescendant |
| A-14 | 输出非常大（数 MB 的 dumpsys） | 不死锁，完整读取 | T | ProcessAdbExecutorTest#drains64MbStdoutAnd8MbStderrConcurrentlyWithoutChangingBinary |
| A-15 | 输出包含中文 | UTF-8 正确解码 | T | ProcessAdbExecutorTest#argvSupportsChineseSpacesAndShellQuotes |
| A-16 | MuMu 多开，多个实例同时运行 | 列出全部实例和端口，由用户选择 | T（fixture）+ L | ScreenAndParserTest#discoveryAndPackageParsers（合成）；真实多开待 L1-01 |
| A-17 | 雷电 9 的本地 ADB 连接未开启 | 检测不到时提示开启的位置 | L | 未验证：L1-01（未发现雷电安装） |
| A-18 | 其他模拟器、WSA、真机 | 通过「自定义地址」实验性支持；文档写明不在官方支持范围 | D | 未验证：仅自定义地址入口已实现，其他模拟器支持说明待发行文档核对 |

## B. 截图

| ID | 场景 | 期望行为 | 验证 | 证据 |
|---|---|---|---|---|
| B-01 | Raw 头部 12 或 16 字节 | 都能正确解析 | T | ScreenAndParserTest#rawAllHeadersAndFormats |
| B-02 | 未知 `format` | 测速阶段淘汰 Raw，使用 PNG | T | ScreenAndParserTest#benchmarkRejectsAnyFailedModeAndUsesSeparateChannel |
| B-03 | 数据被截断、PNG 损坏或为空 | 丢弃本帧，计入连续失败；连续 5 次失败或 10 s 内没有成功帧 → Lost | T | ScreenAndParserTest#rawRejectsTruncationFormatAndInvalidDimensions、pngMatchesArgb；AdbDeviceServiceTest#fiveFailedCapturesNotifyLostBeforeReconnectAndNeverResumeTasks（5 次分支）；10 秒分支待 L1-03 |
| B-04 | 截图 p95 > 600 ms | 环境检查黄色警告，附调整建议 | T | L1-MuMu.md：真实 PNG p95=626 ms 时警告出现（settings.log） |
| B-05 | 持续全黑帧 | 照常投递；超过 10 s 显示警告，不停止任务 | T | 未验证：L1-13 |
| B-06 | 开始时是竖屏（桌面或游戏未横屏） | 上游稳定门阻止开始，并给出上游的提示 | T（上游测试 + 桥接集成测试） | MediaProjectionCaptureServiceTest#stable portrait metrics never consume the projection token（上游门）；桌面竖屏桥接待 L1-06 |
| B-07 | 运行中发生旋转 | 由上游旋转门处理 | T（上游测试） | MediaProjectionCaptureServiceTest#a transient portrait rotation suspends frames and resumes after settling |
| B-08 | 横屏但不是 1920×1080 | 警告，不阻断 | T | L1-MuMu.md：1280×720 正常截图/触控且警告不阻断；DesktopGraphIntegrationTest#realGraphRecognizesUpstreamFixturePausesStopsAndReleasesResources |
| B-09 | 消费者处理很慢 | 截图线程不积压，下一轮在处理完成后立即开始 | T | 未验证：L1-03（慢消费者节奏） |
| B-10 | 没有注册消费者 | 帧被回收（上游 `dispatch` 语义） | T | DesktopAdaptersTest#captureUsesOwnedThreadAndStopsAfterFiveFailures（桌面帧桥接）；无消费者分支待补 |
| B-11 | 色彩空间字段不为 0 | 忽略，按像素格式解码 | T | ScreenAndParserTest#rawAllHeadersAndFormats |
| B-12 | 长时间运行（≥ 1 h dryRun） | 内存和句柄稳定；记录 Bitmap 创建数和回收数 | L（L2-soak） | 未验证：L2-05 |

## C. 触控与输入

| ID | 场景 | 期望行为 | 验证 | 证据 |
|---|---|---|---|---|
| C-01 | 坐标为负 | `Rejected("动作坐标无效")` | T | AdbDeviceServiceTest#liveForegroundOverridesCacheAndUnknownRejectsTouch（NaN/负坐标） |
| C-02 | 坐标超出屏幕 | 不额外校验（与上游一致），在 DECISIONS 记录 | N/A 或 T（透传） | AdbDeviceServiceTest#liveForegroundOverridesCacheAndUnknownRejectsTouch（超屏坐标透传） |
| C-03 | 滑动时长为 0、5001，或 duration + hold > 5000 | `Rejected("滑动时长超出范围")` | T | AdbDeviceServiceTest#liveForegroundOverridesCacheAndUnknownRejectsTouch（0/5001/溢出）；MaaTouchTest#exactGestureBytesAndHoldWithoutDrift（总时长越界） |
| C-04 | hold 为负 | 按 0 处理（上游 `coerceAtLeast(0)`） | T | MaaTouchTest#exactGestureBytesAndHoldWithoutDrift |
| C-05 | 游戏不在前台 | `Rejected(GAME_NOT_FOREGROUND_REASON)` | T | AdbDeviceServiceTest#liveForegroundOverridesCacheAndUnknownRejectsTouch；DesktopGraphIntegrationTest#realGraphRecognizesUpstreamFixturePausesStopsAndReleasesResources |
| C-06 | 前台查询结果为 Unknown | 同 C-05 | T | AdbDeviceServiceTest#liveForegroundOverridesCacheAndUnknownRejectsTouch |
| C-07 | MaaTouch 进程意外退出 | `Rejected("触控服务已断开")`，连接转为 Lost | T | MaaTouchTest#persistentActorSerializesGesturesAndDetectsProcessExit；AdbDeviceServiceTest#failedTouchRestartLosesConnectionAndReconnectDoesNotSendActions |
| C-08 | MaaTouch 启动后 5 s 内没有输出头部 | 启动失败，环境检查标红并显示原因 | T | 未验证：L1-05（握手超时分支） |
| C-09 | 设备上有残留的 MaaTouch 进程 | 启动前清理 | T | MaaTouchTest#persistentActorSerializesGesturesAndDetectsProcessExit 经过清理路径；设备残留进程效果待 L1-05 |
| C-10 | 并发提交多个动作 | actor 串行执行，逐个完成 | T | MaaTouchTest#persistentActorSerializesGesturesAndDetectsProcessExit |
| C-11 | 兼容模式下 hold > 0 的滑动 | `Rejected("兼容模式不支持按住拖拽，请在设置中切换到 MaaTouch")` | T | AdbDeviceServiceTest#compatibilityIsExplicitAndRefusesHold；L1-MuMu.md 真实兼容模式拒绝 hold |
| C-12 | 触控坐标系与帧尺寸既不相等也不互为转置 | `Rejected("触控坐标系与截图尺寸不一致")`，提示重新连接 | T | AdbDeviceServiceTest#liveForegroundOverridesCacheAndUnknownRejectsTouch |
| C-13 | 模拟器设置为竖屏 1080×1920，游戏横屏 | 头部与帧互为转置，允许；坐标不缩放 | T + L | MaaTouchTest#headerAndPackagedAsset；L1-MuMu.md：wm 720×1280、真实帧1280×720，Tap/Swipe/hold 实测正确 |
| C-14 | dryRun 会话 | 上游 `SessionBoundActionExecutor` 返回 DryRun，动作不会到达后端 | T（上游测试） | DesktopGraphIntegrationTest#realGraphRecognizesUpstreamFixturePausesStopsAndReleasesResources |
| C-15 | 用户同时用鼠标操作模拟器 | 不做防护（与上游一致），README 提醒 | D | 未验证：发行 README 提醒待核对；实机测试不同时手动操作 |

## D. 应用控制、前台检测、UI 节点树

| ID | 场景 | 期望行为 | 验证 | 证据 |
|---|---|---|---|---|
| D-01 | 当前账号对应的客户端未安装 | 上游 `NotInstalled` 结果和文案 | T | 未验证：上游 GameClientResolverTest 可复用，具体用例由共享验收映射 |
| D-02 | 安装了多个渠道客户端 | 上游 `Ambiguous` 处理 | T | 未验证：上游 GameClientResolverTest 可复用，具体用例由共享验收映射 |
| D-03 | `resolve-activity` 输出异常或没有启动入口 | 返回失败及原因 | T（fixture） | ScreenAndParserTest#discoveryAndPackageParsers（正常入口）；异常输出分支待 L1-08 |
| D-04 | `am start` 退出码为 0 但输出包含 `Error:` | 判为失败 | T（fixture） | AdbDeviceServiceTest#applicationLaunchDetectsExitZeroErrorAndForceStopPolls |
| D-05 | `force-stop` 后 3 s 内进程仍在 | 返回失败及原因 | T | AdbDeviceServiceTest#applicationLaunchDetectsExitZeroErrorAndForceStopPolls（进程退出轮询）；3 秒耗尽待 L1-08 |
| D-06 | `mCurrentFocus=null` | 改用 `mFocusedApp` | T（fixture） | ScreenAndParserTest#foregroundFixturesAreLiveAndFailClosed（Android9/12合成）；actualMumu12SettingsDumpPreservesTheInspectedNodes（实采设置前台） |
| D-07 | 输入法、状态栏、系统对话框在前台 | 判为非游戏 | T（fixture） | ScreenAndParserTest#foregroundFixturesAreLiveAndFailClosed（Android 9/12 合成） |
| D-08 | 渠道 SDK 应用（如华为帐号）在前台 | 返回该应用的包名；`ChannelPromptScreen` 按规则中的包处理 | T（fixture）+ L3 | ScreenAndParserTest#foregroundFixturesAreLiveAndFailClosed（合成包名解析）；真实渠道页待 L3 |
| D-09 | uiautomator 无法获取空闲状态 | 重试 3 次（FB-03），仍失败返回空列表，并显示警告 | T | AdbDeviceServiceTest#uiAutomatorRetriesThreeTimesThenWarnsAndCanRecover；L1-MuMu.md 真实间歇空树告警，恢复后20次dump成功 |
| D-10 | uiautomator XML 中含 DTD 或外部实体 | 解析被拒绝 | T | ScreenAndParserTest#nodesPreserveTextBoundsAndRejectExternalEntities |
| D-11 | 节点文字包含 emoji、换行或特殊字符 | 原样保留 | T | ScreenAndParserTest#nodesPreserveTextBoundsAndRejectExternalEntities、actualMumu12SettingsDumpPreservesTheInspectedNodes（中文实采）；emoji/换行未验证 |
| D-12 | 点击时节点已消失或位置变化 | `GONE` | T | 未验证：L3 渠道弹窗点击 |
| D-13 | 点击时匹配到多个节点 | `GONE`（上游 `singleOrNull` 语义） | T | 未验证：L3 渠道弹窗多节点 |
| D-14 | 拉取 APK 失败（空间不足等） | 显示错误；设备画像不可用，阻止登录 | T | 未验证：L2-01（APK 拉取失败分支） |
| D-15 | 游戏更新（versionCode 或 lastUpdateTime 变化） | 重新读取签名 | T | DeviceIdentityReaderTest#realApkSignatureUsesCertificateDerCachesUpdateKeyAndDeletesPulledApk |
| D-16 | 分包安装（base.apk + splits） | 只读取 base.apk 的签名 | T（fixture） | DeviceIdentityReaderTest#realApkSignatureUsesCertificateDerCachesUpdateKeyAndDeletesPulledApk |
| D-17 | 模拟器屏幕休眠 | 禁止开始任务，提示唤醒 | T（fixture） | AdbDeviceServiceTest#unsupportedAndroidFailsAndSleepIsVisibleWithoutWakeCommand（识别休眠不唤醒）；启动门待 L1-05 |
| D-18 | 游戏在运行中崩溃或被系统杀掉 | 前台变化，后续动作被拒绝，由上游会话逻辑处理 | L2 | 未验证：L2-02，前台拒绝逻辑已有 C-05 证据 |

## E. OCR

| ID | 场景 | 期望行为 | 验证 | 证据 |
|---|---|---|---|---|
| E-01 | 模型加载失败 | 所有请求回调 `null`；环境检查红色阻断黎明界任务 | T | `OnnxOcrEngineTest#initialization failure completes queued requests and does not retry`；UI阻断由装配验证 |
| E-02 | 缺少 VC++ 运行库 | 预加载 JRE 中的 DLL；仍失败时给出安装说明 | T（self-test）+ D | `OnnxOcrEngineTest#native inference failure completes current and future submissions`；Windows真实模型加载测试通过；发行环境见 L3-08 |
| E-03 | 提交后调用方立即回收 Bitmap | 结果不受影响 | T | `DesktopAdaptersTest#ocrBridgeCopiesPixelsAndReturnsOnSwingMain`，主分支共享模块验证通过 |
| E-04 | 队列满 | 回调 `null` 并记警告（FB-13） | T | `OnnxOcrEngineTest#loading queues up to 64 requests and rejects only excess request` |
| E-05 | 空白或纯色图 | 返回 `""` | T | `OnnxOcrEngineTest#warmUp loads real packaged models and recognizes a blank image as empty`，覆盖普通与紧凑纯白图 |
| E-06 | ROI 很小（短边 < 32 px） | 先放大再检测 | T | `OcrPipelineTest#detection enlarges small crops and bounds large images` |
| E-07 | 模型仍在加载时有请求 | 排队等待 | T | `OnnxOcrEngineTest#loading queues up to 64 requests and rejects only excess request` |
| E-08 | 回调线程 | 在 Main 上回调 | T | `DesktopAdaptersTest#ocrBridgeCopiesPixelsAndReturnsOnSwingMain`，主分支共享模块验证通过；引擎本身按契约不指定UI线程 |
| E-09 | 繁体、异体字、全角符号 | 不做归一化（除非有评估集证据） | T（评估集） | `OcrPipelineTest#join sorts rows and columns without character normalization`；完整实采覆盖未完成，见 L2-04 |
| E-10 | 多行文字 | 用 `\n` 拼接，顺序正确 | T | `OcrPipelineTest#join sorts rows and columns without character normalization`；真实事件样本见 `ocr/evaluation/final-v5.json` |
| E-11 | 检测器遗漏孤立数字 | 有界紧凑ROI先整图识别，仍失败才追加一次黑边det+rec；不补字、不降阈值 | T | FB-14；真实1920和低清地图计数、5个无文字ROI回归通过；系列长列检测仍有缺口，见D-46与诊断报告 |

## F. 持久化、文件与 Windows 环境

| ID | 场景 | 期望行为 | 验证 | 证据 |
|---|---|---|---|---|
| F-01 | 数据目录不可写（例如放在 Program Files 下） | 阻断提示并退出，不换位置 | T | PlatformTest#resolvesDataDirectoryPrecedenceAndRejectsInvalidPath；不可写分支由DataDirectories.ensureWritable处理，权限拒绝场景待L1-16 |
| F-02 | 同一数据目录启动第二个实例 | 提示后退出 | T | PlatformTest#sameDirectoryLockExcludesSecondInstanceAndCanReopen |
| F-03 | 原子写时文件被杀毒软件占用 | 重试（FB-04）；仍失败返回错误 | T | AtomicFiles.move有界重试；真实杀软占用未诱发，L1-16 |
| F-04 | 断电或强制结束后遗留 tmp 文件 | 启动时清理 | T | PlatformTest#tempCleanupLeavesNormalFiles |
| F-05 | prefs JSON 损坏 | 改名为 `.corrupt`，使用默认值并提示（FB-07） | T | ShimTest#prefsTypesPersistenceAndCorruptionBackup；DesktopStorageTest#settingsPersistAndCorruptionIsPreserved |
| F-06 | Room 数据库损坏 | 备份到 `corrupt-<时间>/`，新建空库并提示 | T | DesktopStorageTest#corruptRoomDatabaseIsBackedUpBeforeRecreation |
| F-07 | 磁盘已满 | `commit()` 返回 `false`；上游的 `check` 消息显示到 UI（例如「策略保存失败，请检查存储空间」） | T | DesktopContext.commit保留false错误结果；未填满真实磁盘，L1-16与策略保存错误反馈 |
| F-08 | 数据目录被复制到另一台电脑或另一个用户 | DPAPI 解密失败，提示重新填写密码（FB-12） | T | PlatformTest#dpapiProtectsCurrentUserAndRejectsWrongEntropyOrTampering（本机实际DPAPI）；跨电脑待L3-08 |
| F-09 | 安装国服数据库时文件被占用 | 暂存，下次启动时安装（FB-08） | T | DesktopCnDatabaseTest#lockedInstallIsPendingUntilNextStartupAndKeepsMetadataHonest |
| F-10 | 数据目录路径过长（> 150 字符） | 启动时警告：可能超出 Windows 路径长度限制 | T | Main启动时长度>150提示；长路径实机待L1-16 |
| F-11 | Windows 用户名包含中文（`%LOCALAPPDATA%` 含中文） | 正常工作 | CI（self-test） | DesktopSelfTest data-dir/room/ocr；中文与空格发行验证见evidence/packaging |
| F-12 | 系统睡眠或锁屏 | 任务运行时阻止睡眠；锁屏后模拟器是否继续渲染，在 L1 中记录 | T + L1 | PlatformTest#powerStateAlwaysUsesSameThreadAndClearsOnClose、powerStateClearsAfterNativeFailure；L1-13/15待实机 |
| F-13 | 系统时间跳变 | 计时使用单调时钟（`SystemClock` 垫片）；时间戳使用墙钟（与上游一致） | T | ShimTest#monotonicClockAndProcess；未修改系统时钟 |
| F-14 | 英文版 Windows / 非中文区域设置 | UTF-8 和字体正常 | CI（self-test） | DesktopSelfTest fonts和JVM UTF-8参数；非中文Windows待L3-08 |
| F-15 | 监听回环端口时出现防火墙提示 | 只绑定 127.0.0.1；在 L1 中记录是否出现提示 | L1 | 回环服务仅127.0.0.1；L1-14记录实际防火墙表现 |

## G. 协议、网络与验证码

| ID | 场景 | 期望行为 | 验证 | 证据 |
|---|---|---|---|---|
| G-01 | 没有任何设备画像快照 | 阻止登录并提示先连接模拟器 | T | DesktopBindings设备快照前置与账号页提示；无快照登录UI见G4记录 |
| G-02 | 当前未连接，使用上次设备的快照 | 账号页标明来源和时间 | T | DesktopStorageTest#snapshotPersistsAndDeviceKeyCannotEscapeDirectory；UI_PARITY设备画像行 |
| G-03 | `android_id` 为空 | 使用 installId（FB-11） | T | AdbDeviceServiceTest#identityDoesNotPullUnrecognizedPackagesAndNormalizesNullAndroidId；FB-11 |
| G-04 | B 服包没有安装在当前模拟器上 | 与上游语义一致的错误 | T | 共享GameClientResolver测试；L2-01/L3-01待实机 |
| G-05 | 上游增删画像字段 | 漂移检查失败 | T（CI） | :shared:checkUpstreamDrift及checkSubstituteSignatures |
| G-06 | 系统设置了静态代理 | 跟随系统代理 | T | PlatformTest#proxySwitchAffectsPreviouslyCapturedSelector |
| G-07 | 系统使用 PAC 自动配置 | 不支持；写入 KNOWN_LIMITATIONS 和 README | D | KNOWN_LIMITATIONS KL-06；README-desktop常见问题 |
| G-08 | 切换到「直连」 | 新请求不再走代理 | T | PlatformTest#proxySwitchAffectsPreviouslyCapturedSelector |
| G-09 | 无法打开系统浏览器 | 对话框显示可复制的链接（FB-05） | T | BrowserLauncher→验证码可复制链接；系统浏览器失败分支待L3-01 |
| G-10 | 验证超过 10 分钟未完成 | `onError("验证已超时，请重新发起登录")` | T | LoopbackCaptchaHostTest#timeoutExpiresAndMalformedPageFailsBeforeRegistration |
| G-11 | 用户关闭了浏览器标签但没有完成验证 | 对话框保持，可重新打开或取消 | T（UI） | GeetestCaptchaDialog保留重开/复制/取消；真实浏览器标签操作待L3-01 |
| G-12 | 其他网页尝试请求回环服务 | 被令牌、Host、Origin 校验拒绝 | T | LoopbackCaptchaHostTest#rejectsWrongTokenHostOriginAndOversizedBody |
| G-13 | Geetest 拒绝 127.0.0.1 来源 | 启用 R-02 备选方案（需要证据） | L3 | L3-01；未取得被拒证据，未启用KCEF |
| G-14 | 网络超时或 DNS 失败 | 上游结果类型和文案 | T（上游测试） | 共享上游协议测试；真实网络账号请求待L3-01/02 |
| G-15 | 网络中有 HTTPS 中间人代理（企业环境） | 使用 JRE 自带根证书，握手失败时显示原因；写入 KNOWN_LIMITATIONS | D | KNOWN_LIMITATIONS KL-07；README-desktop TLS说明 |

## H. 任务生命周期与界面

| ID | 场景 | 期望行为 | 验证 | 证据 |
|---|---|---|---|---|
| H-01 | 任务运行中关闭窗口 | 弹出三选一对话框 | T（UI） | Main三选一接线；任务运行时托盘实机路径见L1-16 |
| H-02 | 退出时任务 10 s 内没有停止 | 记录警告，继续退出流程 | T | DesktopAppGraph.shutdown withTimeoutOrNull(10000)及finally释放；超时强制场景未独立注入，L1-16 |
| H-03 | 任务运行中设备断开 | 任务停止并说明原因 | T（FakeDevice） | DesktopAdaptersTest#captureUsesOwnedThreadAndStopsAfterFiveFailures；DesktopGraphIntegrationTest |
| H-04 | 连续点击暂停 / 继续 / 停止 | 命令执行期间按钮禁用 | T（UI） | 底栏/托盘pending与stopping互斥；Graph暂停继续见集成测试；托盘连点待L1-16 |
| H-05 | 系统不支持托盘 | 关闭行为中不提供「最小化到托盘」 | T | Main按SystemTray.isSupported改为最小化窗口；本机支持托盘，缺托盘环境未验证 |
| H-06 | 未捕获异常 | 写崩溃日志，显示对话框 | T | evidence/ui/07-opening-crash-before-fix.png；开发期间重写运行classpath触发，固定classpath重启后已复测 |
| H-07 | 后台协程异常 | 停止任务，UI 显示原因 | T | graph的CoroutineExceptionHandler记录错误并停止；对应异常注入未独立验证，L2-05 |
| H-08 | 记住的窗口位置在已拔掉的显示器上 | 重置到主屏居中 | T | Main检查保存位置是否与现有屏幕相交并居中；未实际拔显示器，L1-16 |
| H-09 | 高 DPI（150% / 200%） | 正常缩放 | L1（截图） | G4窗口证据；125%/200%组合待L1-17 |
| H-10 | 首次启动没有同意风险告知 | 无法进入主界面 | T（UI） | evidence/ui/01-first-run.png；SetupWizard风险告知确认后进入 |
| H-11 | 诊断面板端口被占用 | 在 8765–8775 中顺延（FB-10） | T | DesktopAppGraph.debugPort探测；DesktopGraphIntegrationTest与FB-10 |
| H-12 | 同时启动两个需要截图的任务 | 上游会话管理器互斥（`CaptureFrameBus` 返回 Busy 等），UI 显示上游消息 | T | 共享CaptureFrameBus/会话测试；Graph快速启停集成与taskStarting/taskStopping |
| H-13 | 长时间运行（8 h） | 内存、线程、句柄稳定 | L2（soak） | L2-05：1h必测与可选8h，当前无模拟器 |
| H-14 | 界面空白（显卡驱动问题） | README 说明 `SKIKO_RENDER_API=SOFTWARE` | D | README-desktop：SKIKO_RENDER_API=SOFTWARE |
| H-15 | 重设计页面后典型操作 | 原功能、保存 / 取消与确认语义不变；主要操作可达 | 复用 G4 的典型流程记录 | evidence/ui与registers/UI_PARITY.md；只复用G4操作记录 |

## I. 打包与运行环境

| ID | 场景 | 期望行为 | 验证 | 证据 |
|---|---|---|---|---|
| I-01 | 解压到包含中文和空格的路径 | self-test 通过 | CI | evidence/packaging/self-test.json；云端Windows job未运行 |
| I-02 | SmartScreen 拦截 | README 说明处理方法和 SHA-256 校验 | D | README-desktop校验和SmartScreen说明；L3-09待真实下载环境 |
| I-03 | 杀毒软件误报或隔离文件 | README 说明；self-test 校验 MaaTouch 的 SHA-256 | D + CI | DesktopSelfTest maatouch；README-desktop误报说明 |
| I-04 | JRE 缺少模块 | `includeAllModules`；self-test 的 TLS 检查 | CI | app/build.gradle.kts includeAllModules；DesktopSelfTest tls |
| I-05 | 系统区域设置不是 UTF-8 | JVM 参数指定 UTF-8 | CI | app JVM UTF-8参数；中文路径本地self-test；非UTF-8区域待L3-08 |
| I-06 | Windows 10 较旧版本 | 支持 Windows 10 1809 及以上，写入 README | D | README-desktop系统要求 |
| I-07 | ARM64 Windows | 不支持，写入 KNOWN_LIMITATIONS | D | KNOWN_LIMITATIONS KL-01；仅x64发行 |
| I-08 | Windows 上 git 把文本转成 CRLF | CI 和开发说明中设置 `core.autocrlf=false`、`core.eol=lf` | CI | desktop-ci.yml Windows checkout前设置LF；本地Git同设置 |
| I-09 | ONNX Runtime 原生库解压到包含中文的临时目录 | 加载成功 | CI（self-test） | evidence/packaging/self-test.json；运行时临时目录亦指定中文空格路径 |

## J. 上游同步

| ID | 场景 | 期望行为 | 验证 | 证据 |
|---|---|---|---|---|
| J-01 | 上游新增与 Android 耦合的文件 | 扫描发现，漂移检查失败并列出文件 | CI | :shared:checkUpstreamDrift |
| J-02 | 上游修改了有替身的公共签名 | 签名检查失败 | CI | :shared:checkSubstituteSignatures |
| J-03 | 上游 Room schema 版本升级 | 漂移检查失败（替身需要同步迁移） | CI | :shared:checkUpstreamDrift；RoomCompatibilityTest版本/identityHash |
| J-04 | 派生改写的目标文本变化 | 命中次数校验失败 | CI | :shared:prepareUpstreamMain派生替换命中数断言 |
| J-05 | 上游删除了排除清单中的文件 | `prepareUpstreamMain` 失败 | CI | :shared:prepareUpstreamMain排除清单存在性断言 |
| J-06 | 上游新增的测试依赖 Android | 测试编译失败 → 登记排除及理由 | CI | :shared:compileTestKotlin；upstream-test-excludes.txt只排除Android对象 |
| J-07 | 上游修改协议字段或常量 | 漂移检查失败 | CI | :shared:checkUpstreamDrift协议文件内容锁定 |
| J-08 | 上游在导航中接入会战助手 | 按 UPSTREAM_SYNC 清单更新 UI_PARITY 并接入 | D | registers/UPSTREAM_SYNC.md；当前会战不接入与上游一致 |
| J-09 | 上游页面内部功能变化 | 只核对受影响页面并同步，简记到 UI_PARITY；不重复验证无关功能 | 复用相关检查 | registers/UPSTREAM_SYNC.md、UI_PARITY.md；后续同步只检查受影响页面 |
