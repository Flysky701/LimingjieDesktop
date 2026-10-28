# PLAN.md —— 黎明界助手 Windows 桌面版（MAA 形态）移植总方案

## 1. 目标与非目标

**产品原则（2026-10-25 用户确认，D-37）**：业务能力和行为保持一致，界面按桌面使用方式重新设计。以 MAA 的操作组织方式为参考，形成完整、清晰、统一的黎明界桌面助手。允许调整、美化功能页面；设备、识别、协议、存储和打包的既定技术路线保留。

**目标（P0/P1）**
- 交付 Windows 10/11 x64 便携版：zip 解压后双击 `LimingjieAssistant.exe` 启动，内置 JRE，不要求用户另装 Java。
- 形态为 MAA 式独立桌面助手：账号库、黎明界、策略设置、开局编组、角色评分、日志与任务控制均在 Windows 窗口内使用，无需在模拟器中安装或操作安卓版助手。
- 助手逻辑在 PC 上运行，通过 ADB 连接模拟器，负责截图、识别、触控和刷开局网络任务；模拟器仍用于运行游戏，底层触控组件由助手管理。本方案不是完全脱离模拟器的协议版。
- 桌面 UI 保留原有功能与行为，可重新设计布局和视觉。采用左侧导航、主要工作区、任务日志与持续可见的任务控制；根据窗口宽度重排内容，不将手机页面简单拉伸作为最终交付。
- 与上游 1.0.18 功能对齐：账号库与原生登录（B 服 SDK 账密 + 人工 Geetest；渠道服 uid/access_key）、刷开局（含“刷到出”、首轮复用）、黎明界画面自动化、队伍编组、遗物/商店/事件决策、策略设置、个人角色评分、任务暂停/继续/停止、诊断面板与日志包、国服数据库更新。
- 会战助手：上游 `ClanBattleScreen` 未接入导航（`AppScreen` 只有「黎明界」「账号库」「设置」），桌面版同样**不接入 UI**；其代码照常编译、上游单测照常运行。上游接入后按 `UPSTREAM_SYNC.md` 跟进。
- 官方支持环境沿用上游已验证的组合：MuMu 模拟器 12（B 服）和雷电模拟器 9（小米 / 华为 / vivo 渠道服），1920×1080 横屏。其他模拟器通过「自定义 ADB 地址」实验性支持。
- 可持续跟进上游：业务层**共享上游源码**，不复制业务实现。合并后重新构建并检查漂移；重设计的桌面页面还需按 `UI_PARITY.md` 核对上游字段、操作和状态变化，不能假定界面自动同步。

**非目标**
- 不重写识别或决策逻辑；不提供验证码绕过；不追求比上游更“聪明”。界面重排、美化和桌面交互适配属于本次 P0/P1 范围，不视为新增业务功能。
- 不发行非 Windows 版本（开发期允许在 Linux/macOS 上跑测试和调试）。
- v1 不做：自动更新、定时任务、多设备同时运行、手机实机官方支持、MuMu/雷电原生截图扩展。这些都在 P2 清单里。

### 1.1 用户使用路径与交付预期

1. 准备并打开模拟器和游戏；双击 `LimingjieAssistant.exe`，按向导连接设备。
2. 在桌面账号库选择账号，在黎明界页面配置任务，通过桌面页面设置策略、编组和角色评分。
3. 在桌面启动任务，查看当前账号、模式、进度、状态与异常原因；画面任务提供原有的暂停 / 继续 / 停止，刷开局只提供停止。
4. 需要验证码时在浏览器中手动完成；结束、失败或中断时在桌面获得反馈，必要时导出诊断包。
5. 交付 Windows 便携 zip、使用说明和验证报告。原型、已接通的桌面功能、真实游戏验证分开报告；仅能启动 EXE 或通过离线验证不能表述为完整游戏流程已验证。

### 1.2 界面调整边界

允许改变导航呈现、字段分组、列表与详情布局、控件和视觉；同一功能可移动到更合适的桌面页面，但须记录去向且保持可达。保留配置含义、默认值、合法范围、校验、保存 / 取消、危险操作确认、任务互斥与暂停 / 停止语义。复用上游状态和动作，UI 不另建业务规则。MAA 是操作组织参考，不要求复制其图标、品牌资源、配色或任务机制。

## 2. 事实基线（commit b809b196；M0 需重新测量）

| 项 | 数值 / 事实 |
|---|---|
| 主代码 | 189 个 .kt 文件，56,590 行。其中 126 个文件没有任何 `android.*` / `androidx.*` / ML Kit import |
| 与 Android 耦合 | 63 个文件，其中 10 个是 `ui/`。很多文件只依赖 `android.util.Log` 或 `android.graphics.Bitmap` |
| 核心会话 | `labyrinth/LabyrinthEntryRecognitionSession.kt` 共 9,720 行，唯一的 Android 依赖是 `android.util.Log` |
| 测试 | 163 个测试文件，34,504 行，只有 1 个 import 了 Android。上游已有回放工具 `LabyrinthReplayHarness` |
| 资源 | `android/app/src/main/assets` 约 40 MB（模板、图标、角色/遗物/事件数据） |
| 端口抽象 | 已有：`AutomationActionBackend`、`CaptureFrameBus`/`CapturedFrame`、`ChannelPromptScreen`、`ShellCommandRunner`、`GameClientTerminator`/`Relauncher`、`LabyrinthRunStateStore`、`LabyrinthRouteStore`、`LabyrinthRerollSettingsStore`、`CredentialStore` 等 |
| 截图节奏 | Android 端每 300 ms 一帧（`FRAME_INTERVAL_MILLIS = 300`），稳定判定按帧数计数（多为 2–5 帧） |
| 手势契约 | 点击 60 ms；滑动 `durationMillis ∈ 1..5000` 且 `duration + hold ≤ 5000`，否则 `Rejected("滑动时长超出范围")`；每次动作前校验前台包名，不一致返回 `Rejected(GAME_NOT_FOREGROUND_REASON)`，会话靠这个字符串分支判断 |
| OCR | ML Kit 中文识别，对外只用 `result.text.trim()`；调用形态是 `(Bitmap, (String?) -> Unit) -> Unit`；失败回调 `null` |
| 本地库 | Room 2.6.1，schema 版本 4，`android/app/schemas/` 下有导出的 schema JSON |
| 协议设备画像 | 由 `Build.*`、`ANDROID_ID`、显示尺寸、游戏包 versionName/versionCode/签名 MD5 组成（`AndroidBilibiliSdkRequestProfileFactory`、`AndroidGameProtocolProfileFactory`） |
| 已有 ADB 雏形 | `AdbForceStopTerminator`、`AdbRelauncher`、`ProcessAdbShellCommandRunner`。注意：这个 runner 等进程结束后才读输出，且只读 8 KB，大输出会死锁，桌面版不能直接用 |
| 诊断面板 | `LabyrinthDebugDashboardServer` 自带 ServerSocket，绑定 127.0.0.1:8765；日志通过本机 `logcat` 采集 |

## 3. 总体架构

```text
┌──────────────── Windows PC（LimingjieAssistant.exe，内置 JRE 21）────────────────┐
│ :app  Compose Desktop UI（桌面重设计）· DesktopAppGraph（对照上游装配根）              │
│       托盘 / 任务控制 / 环境检查 / 日志面板 / 诊断导出 / 首次向导                       │
│ :shared  上游 android/app/src/main/java（过滤后，原样编译）                             │
│          + desktop/shared/src/main/kotlin：框架垫片 android.*、同名替身、内部桥接       │
│ :device  ADB 定位/连接/进程执行 · 截图(raw/png) · MaaTouch 触控 · 应用控制 ·           │
│          前台探测 · uiautomator 节点树 · 设备信息                                       │
│ :ocr     ONNX Runtime + PP-OCR 中文 det/rec（纯 Kotlin 前后处理）                       │
│ :platform  Windows：DPAPI · 防睡眠 · 数据目录 · 单实例锁 · 模拟器发现 · 浏览器拉起      │
└──────────────────────────────┬──────────────────────────────────────────────────┘
                               │ adb (TCP 127.0.0.1:5037 → 模拟器)
                     ┌─────────▼──────────┐
                     │ 模拟器（Android 9/12）│  游戏客户端 + /data/local/tmp/maatouch
                     └────────────────────┘
```

**依赖方向**：`:app → :shared, :device, :ocr, :platform`；`:shared → (无桌面模块依赖)`；`:device`、`:ocr`、`:platform` 互不依赖，也不依赖 `:shared`。
`:shared` 中的替身需要桌面能力（例如 OCR）时，通过 `DesktopBindings`（在 `:shared` 中声明接口、由 `:app` 启动时一次性注入，未注入即 `check` 失败）获取。

## 4. 关键决策（已定，执行时不再讨论；推翻须有证据并记录）

| ID | 决策 | 理由 / 被拒方案 |
|---|---|---|
| D-01 | **共享上游源码**：Sync 任务把 `android/app/src/main/java`（按排除清单）复制到 `desktop/shared/build/` 再编译（D-22，SPEC-00 §3）；用框架垫片、同名替身和少量纯搬移补丁解决耦合 | 复用 9 万行逻辑和测试，能跟进上游。被拒：改写成 C#/C++/Python/MaaFramework（丢失逻辑和测试，无法同步）；复制一份源码（会分叉） |
| D-02 | 独立 Gradle 构建 `desktop/`；Kotlin 2.x + Compose Multiplatform Desktop + JDK 21 工具链 | 不动 `android/` 构建；JDK 21 默认 UTF-8（JEP 400），避开中文 Windows 上 GBK 默认编码的坑 |
| D-03 | 截图用 `adb exec-out screencap`，Raw 优先、PNG 备选；连接时测速选定；节奏对齐上游 300 ms | 不依赖模拟器私有 DLL。私有截图扩展放到 P2 |
| D-04 | 触控用 **MaaTouch**（Apache-2.0，minitouch 协议）常驻进程。`adb shell input` 只作为用户手动选择的「兼容模式」 | `input swipe` 无法实现 `holdMillis`（抬手速度为 0 的拖拽）语义 |
| D-05 | 无障碍替代：前台包名 → `dumpsys` 实时查询；渠道 SDK 节点 → `uiautomator dump`；全局返回 → KEYCODE_BACK | ADB 下的等价能力 |
| D-06 | OCR 使用 ONNX Runtime（Java）+ PP-OCR 中文 mobile det/rec 模型；对外保持上游 `readAsync(bitmap, callback)` 语义 | ML Kit 不能在桌面运行；Windows 自带 OCR 依赖系统语言包，不可靠 |
| D-07 | 本地库使用 Room 2.7+（JVM）+ `BundledSQLiteDriver`。桌面提供同名替身 `AppDatabase`：实体相同、版本号 4，导出 schema 的 identityHash 与上游一致。**备选**（spike 失败才启用）：`room-common` 注解 + 手写 JDBC DAO + 失效通知 | Entities/Daos 原样编译；identityHash 可自动校验对齐 |
| D-08 | 凭据通过 JNA 调用 Windows DPAPI（CurrentUser 作用域）。非 Windows 平台禁用凭据功能，不落明文 | 对应 Android Keystore |
| D-09 | 协议所需设备画像（Build、ANDROID_ID、显示尺寸、游戏版本和签名）由 ADB 从目标模拟器读取，持久化为快照 | 对应 Android 本机 API；签名用 `apksig` 解析，按 versionCode 缓存 |
| D-10 | Geetest：本机回环 HTTP 服务 + 系统默认浏览器打开上游 `GeetestPageBuilder` 页面；注入同名 JS 桥 `AndroidCaptcha`，用 fetch 回传结果。**备选**（实测被拒才启用）：KCEF 内嵌浏览器 | 体积小、无原生依赖；AutoPCR 也是外部页面方案 |
| D-11 | UI = Compose Desktop 的 MAA 式桌面布局 + **按桌面交互重设计的功能页面**。共享上游状态、ViewModel、业务动作和校验；适合的组件可复用，其余在 `:app/ui` 自有包实现。不为美化修改上游文件或复制业务逻辑。先给出主要页面原型，再实现真实接线；功能简表见 `UI_PARITY.md` | 用户授权修订（D-37）；功能行为兼容优先，页面结构和视觉不要求一致；不再强制照搬能编译的安卓屏幕 |
| D-12 | 通知 / 前台服务的替代：窗口内任务栏 + 系统托盘菜单；任务运行期间阻止系统睡眠 | 对应 Android 通知和 WakeLock |
| D-13 | 诊断面板直接在 PC 上运行上游 `LabyrinthDebugDashboardServer`（127.0.0.1:8765，端口被占用时顺延）；日志包结构不变，`logs/logcat.txt` 改为桌面进程日志（通过注入缝） | 反馈流程和工具与上游兼容 |
| D-14 | 数据目录：exe 同级存在 `portable.flag` 时用 `.\data`（发行包默认带此文件），否则用 `%LOCALAPPDATA%\LimingjieAssistant`；也可用 `--data-dir` 指定。目录不可写时直接报错，不静默换位置 | MAA 式便携 |
| D-15 | 发行：Compose `createReleaseDistributable`（jpackage app-image，内置 JRE，`includeAllModules = true`）→ 便携 zip。MSI 放到 P2 | 避免 jlink 漏掉 TLS/locale 模块；不依赖 WiX |
| D-16 | 版本号为 `<上游 versionName>+desktop.<n>`，versionCode 沿用上游；构建时从 `android/app/build.gradle.kts` 读取，生成 `BuildConfig` | 日志包可定位到具体构建 |
| D-17 | 单进程、单设备、单任务。多开 = 多个副本或不同 `--data-dir`（P2 验证） | 上游大量进程级单例（`CaptureFrameBus`、`CaptureStateRegistry` 等） |
| D-18 | adb 来源依次为：模拟器自带 adb → PATH → 用户指定 → 一键下载官方 platform-tools 到数据目录。**发行包不内置 adb** | 避免 adb server 版本冲突和再分发许可问题 |
| D-19 | 网络跟随系统代理（`-Djava.net.useSystemProxies=true`）；设置中可切换为「直连」 | 国内环境 GitHub 数据库下载需要代理，游戏协议通常直连 |
| D-20 | 线程模型逐项对齐（产出 `registers/THREADING.md`）：Android 主线程 ↔ `Dispatchers.Main`（Swing EDT）；MediaProjection HandlerThread ↔ 专用截图线程；手势 ↔ 触控 actor；ML Kit 回调（主线程）↔ `Dispatchers.Main` | 上游并发假设不能被破坏 |
| D-21 | 提供 `android.content.Context` / `SharedPreferences` 最小垫片（SPEC-00 §6.2）：`DesktopContext(dataDir)`；prefs 每个 name 一个 JSON 文件，`commit()`/`apply()` 都走**同步原子写** | 上游大量代码只用到 Context 的 5 个成员；垫片比逐个替身改动更小 |
| D-22 | 上游源码由 Sync 任务（`prepareUpstreamMain/Test/Assets`）按排除清单复制到 `desktop/shared/build/generated/upstream/`，少量文件做**派生改写**（只限 D-23 场景）。`android/` 下只允许 AGENTS.md §2 的两类补丁（P-xx，登记在 UPSTREAM_PATCHES.md） | `android/` 改动最小，同步上游只需重跑 Sync；不用 `srcDir + exclude` 是因为 exclude 会误伤同路径的桌面替身 |
| D-23 | 使用 JetBrains `lifecycle-viewmodel`（KMP）。`AccountViewModel` / `ClanBattleViewModel` 的 `Factory.create(Class<T>)` 在 JVM 上不存在，派生改写为 `create(KClass<T>, CreationExtras)`，字面量替换并在 Sync 中校验命中次数 | 无其他可行方案；改写是机械的、可验证的 |
| D-24 | 上游测试的工作目录 = `android/app`（上游测试依赖 `user.dir` 找 fixture）。测试前后 `android/` 的 git 快照必须一致（CI 上必须为空，SPEC-00 §11）（金标自举风险：`LabyrinthCharacterIconGoldenTest` 在金标缺失时会生成文件） | 与上游 CI 语义一致；防止测试悄悄改仓库 |
| D-25 | 桌面页面按实际内容区自适应布局。**仅在复用依赖 Android 环境的上游 UI 组件时**，外层提供 `LocalConfiguration` / `LocalContext`，尺寸为该组件内容区而非整个窗口 | 保留兼容垫片；重设计页面使用 Compose Desktop 布局约束，Android 紧凑布局分支不再是桌面视觉验收标准（D-37） |
| D-26 | `BitmapFactory` 使用已有 Skia 解码 PNG/JPEG/WebP，再读取非预乘 ARGB；不新增色彩校正 | D-45 实际 WebP 资源修正；上游 golden 和 fixture 继续直接验证 |
| D-27 | 前台包名：执行动作前**实时查询**（`dumpsys`，SPEC-10）；非挂起回调（`ChannelPromptScreen.foregroundPackage()`、`GameClientHardCloser` 的 `foregroundPackage`）读取 500 ms 轮询缓存，缓存超过 2 s 视为 `null` | 上游这两个接口不是挂起函数，不能在里面阻塞调用 adb；过期返回 null 让上游走它已有的“不确定”分支 |

## 5. Android → 桌面 映射

| 上游组件 | 作用 | 桌面实现 | WS |
|---|---|---|---|
| `MediaProjectionCaptureService` | 屏幕捕获 | `DesktopCaptureService`（`:shared` 内部桥接 + `:device` 截图器），驱动 `CaptureFrameBus` / `CaptureStateRegistry` | B |
| `LandosolAccessibilityService` + `AndroidAccessibilityActionBackend` | 手势、返回、前台包名 | `DeviceActionBackend`（MaaTouch + 实时前台校验，契约见 §2） | B |
| `AccessibilityChannelPromptScreen` | 渠道 SDK 节点读取和点击 | `UiAutomatorChannelPromptScreen` | B |
| `GameClientResolver` / 启动 Intent / `GameClientHardCloser` | 客户端解析、启动、硬关闭 | `pm list packages` / `cmd package resolve-activity` + `am start -n` / `am force-stop` | B |
| ML Kit `AndroidChineseRoleNameOcr` | OCR | 同名替身 → `:ocr` | C |
| Room `AppDatabase` | 本地库 | 同名替身（Room JVM） | D |
| `SharedPreferences` / Context 文件存储 | 设置、检查点、安装标识 | `Context` / `SharedPreferences` 垫片（D-21）：每个 name 一个原子写 JSON 文件，上游代码不改 | A/D |
| `AndroidKeystore*Store` / `SdkSessionStores` | 凭据和会话 | DPAPI 存储 | D |
| `AndroidLabyrinthCnDatabaseRepository` | 国服数据库 | 桌面仓库（Bundled SQLite，Windows 文件锁安全安装） | D |
| `Android*TemplateLoader` / `ResourcePackActivator` | 资源加载 | classpath 资源 + `BitmapFactory` 垫片 | A/D |
| `Android*ProfileFactory` / `Bilibili*GatewayFactory` | 协议画像和网关 | 设备画像快照 + 桌面工厂 | E |
| `GeetestCaptchaDialog`（WebView） | 人工验证码 | 回环服务 + 系统浏览器 | E |
| `LabyrinthRerollService`（前台服务） | 后台刷开局 | 应用作用域协程 + 防睡眠 | F/H |
| 通知栏 / 悬浮窗 | 任务控制 | 窗口任务栏 + 托盘 | F |
| `MainActivity` / `AppScreen` 导航 + 上游各 Compose 屏幕 | 功能与行为基准 | MAA 式桌面导航 + 重设计页面，按字段 / 操作 / 状态对照；适合的组件可复用（D-11、D-25）；`ClanBattleScreen` 与上游一样不接入 | F |
| `PermissionCenterScreen` | 权限中心 | 环境检查页 | F |
| `LabyrinthRuntimeDiagnostics` | 运行时诊断 | JVM / 进程 / 设备 / 截图指标 | F |
| `LandosolToolboxApplication` | 装配根 | `DesktopAppGraph`（逐段对照） | H |

## 6. 里程碑与 Gate

| 里程碑 | 内容 | 负责 | Gate |
|---|---|---|---|
| **M0** | 环境探测；重新测量基线；建立 `registers/`；确定实机能力级别 | 总指挥 | PROGRESS.md 完成 |
| **M1** | `desktop/` 骨架；`:shared` 编译上游源码；垫片、替身桩、纯搬移补丁；BuildConfig；上游 JVM 单测在桌面跑通；**冻结端口契约**：`DesktopBindings`、`:device` 公共 API、`OcrEngine`、存储接口、`DeviceIdentitySnapshot` | 总指挥 | G1 |
| **M2** | 并行开发：WS-B 设备层、WS-C OCR、WS-D 持久化与安全、WS-E 协议画像与验证码、WS-F UI 骨架、WS-G 打包和 CI 骨架 | 子代理 | 各自 G2-x |
| **M3** | `DesktopAppGraph` 装配；假设备端到端回放（FakeDevice 按脚本喂截图并核对动作） | 总指挥 + WS-H | G3 |
| **M4** | 页面真实接线；原功能完整可用；检查主要布局与典型操作，复用已有相关验证 | WS-F | G4 |
| **M5** | 打包、`--self-test`、CI（windows-latest）、中文 + 空格路径验证 | WS-G | G5 |
| **M6** | 实机 L1（无游戏）/ L2（游戏 dryRun），环境允许时执行；否则产出给用户的实机清单 | 总指挥 | G6 |
| **M7** | 文档、已知限制、上游同步手册、最终报告 | 总指挥 | 最终报告 |

**M1 冻结的契约**写入 `desktop/CONTRACTS.md`（签名 + KDoc）。冻结后再修改，必须由总指挥批准并通知受影响的 WS。

## 7. 并行计划

主要页面实现前先给出轻量原型，可与 M1 和后端准备并行；不新增里程碑或验收关卡。合并顺序：M1（总指挥）→ WS-G 骨架 → WS-B / WS-C / WS-D / WS-E（互不依赖，顺序任意）→ WS-F → WS-H → M5 / M6。
路径归属、提示词模板和报告模板见 `WORKSTREAMS.md`。

## 8. 风险登记

| ID | 风险 | 缓解措施 |
|---|---|---|
| R-01 | PP-OCR 与 ML Kit 输出差异，导致实体解析失败 | 建评估集（§SPEC-20），只在适配器层做有证据的归一化，不动上游阈值 |
| R-02 | Geetest 拒绝 localhost 源 | D-10 备选 KCEF；实机 L3 验证 |
| R-03 | MaaTouch 在某个模拟器配置下不可用 | 环境检查明确报错；用户可手动切兼容模式（拖拽类动作会被拒绝并停机） |
| R-04 | 截图慢（p95 > 600 ms） | 连接时测速；提示调整模拟器设置；P2 原生截图 |
| R-05 | 上游更新导致桌面编译失败 | 共享源码 + CI 漂移检测 + `UPSTREAM_SYNC.md` 流程 |
| R-06 | Room JVM spike 失败 | D-07 备选方案，最多 1 轮 spike 后定案 |
| R-07 | 用 K2 编译上游代码出现差异 | 记录在案；用最小注入缝或搬移解决，不改逻辑 |
| R-08 | 设备画像差异导致登录失败 | 字段对照测试 + 漂移检测 + L3 实机 |
| R-09 | 内置 JRE 缺模块（TLS / locale） | `includeAllModules`；self-test 覆盖 TLS 握手 |
| R-10 | Windows 文件锁（杀软、索引服务、文件被占用） | 登记重试；数据库安装延后到空闲时 |
| R-11 | 中文、空格路径或用户名 | self-test 在 `D:\测试 目录\` 下运行；ONNX 模型从字节加载 |
| R-12 | 多个 adb 版本冲突，反复杀 server | D-18；检测到冲突循环时停下并给出指引 |
| R-13 | 重设计页面遗漏字段、确认或状态，上游 UI 变化不再自动同步 | 按功能分组记录桌面去向；验证保存、取消和任务控制等典型路径；上游变更时只检查受影响页面 |

## 9. 上游同步

见 `registers/UPSTREAM_SYNC.md`。要点：`git merge upstream/main` → 运行 `desktop/tools/check-upstream-drift`（新增的 Android 耦合文件、替身签名漂移、协议字段漂移、Room schema 版本变化）→ 修复 → 跑全部 Gate。

## 10. 项目完成定义

G1–G5 全部通过；G6 在环境允许的范围内通过，其余项已写成用户实机清单；EDGE_CASES 每条都已映射；各登记表完整；最终报告已提交。
