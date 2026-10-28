# SPEC-00 共享上游源码（M1，总指挥本人完成）

**产出**：`desktop/` Gradle 工程骨架；`:shared` 模块在 JVM 上编译上游 `android/app/src/main/java`（过滤后）；框架垫片、同名替身桩、少量上游纯搬移补丁；上游 JVM 单测在桌面构建下全部通过；`desktop/CONTRACTS.md` 冻结。
本文的文件清单与分类基于 commit `b809b196` 的扫描结果。**M1 开始时用 §5.3 的脚本重新扫描**，以扫描结果和编译器报错为准，偏差记入 DECISIONS。

## 1. 目录结构

```text
desktop/
  settings.gradle.kts              # include(":shared", ":device", ":ocr", ":platform", ":app")；总指挥独占
  gradle/libs.versions.toml        # 全部依赖精确版本；总指挥独占
  gradle/wrapper/…                 # Gradle 8.10+，填写 distributionSha256Sum
  CONTRACTS.md                     # M1 冻结的端口契约（签名 + KDoc）
  shared/
    build.gradle.kts
    upstream-excludes.txt          # 主代码排除清单（每行：路径  # 类别 | 理由 | 桌面替代）
    upstream-test-excludes.txt     # 测试排除清单（同格式）
    upstream-derived.txt           # 派生改写清单（§3.1）
    src/main/kotlin/               # 垫片（android.* / androidx.* 包）、同名替身、internal 桥接、DesktopBindings
    src/test/kotlin/               # 垫片与替身自身的测试
  device/  ocr/  platform/  app/   # 见各 SPEC
  tools/                           # 扫描与漂移检查脚本（Kotlin 脚本或 Gradle 任务）
```

## 2. 版本约束（M0 解析出具体版本后写死，禁止动态版本）

| 组件 | 约束 | 说明 |
|---|---|---|
| JDK 工具链 | 21 | `kotlin { jvmToolchain(21) }` |
| Kotlin / KSP | 2.1.x，KSP 版本与 Kotlin 匹配 | 上游是 1.9.24（K1）。K2 编译差异按 R-07 处理 |
| Compose Multiplatform | ≥ 1.7（material3 ≥ 上游 BOM 2024.06 的 1.2.x） | 保留源码 / 组件编译与桌面自有呈现；编译保留不等于原样展示（D-11、D-25） |
| Room | ≥ 2.7（JVM），`androidx.sqlite:sqlite-bundled` 对应版本 | D-07 |
| Lifecycle | `org.jetbrains.androidx.lifecycle` 2.8.x（viewmodel、runtime-compose） | D-23 |
| kotlinx-coroutines | core + swing + test，≥ 1.9 | `Dispatchers.Main` = Swing EDT |
| kotlinx-serialization | runtime ≥ 1.7，插件版本 = Kotlin 版本 | 上游 1.6.3 |
| OkHttp / MockWebServer | 4.12.0（与上游一致） | |
| org.json | `org.json:json`（JSON-java，Public Domain） | 上游诊断面板用 Android 自带的 `org.json` |
| JUnit | 4.13.2（与上游一致） | |

## 3. 上游源码接入（D-22）

不使用 `srcDir + exclude`：Gradle 的 exclude 作用于全部源根，会把相对路径相同的桌面替身一起排除。改为三个 `Sync` 任务，把上游内容复制到 `desktop/shared/build/generated/upstream/`，再作为源根加入：

| 任务 | 来源 | 目标源根 | 处理 |
|---|---|---|---|
| `prepareUpstreamMain` | `android/app/src/main/java` | `…/upstream/main` | 应用 `upstream-excludes.txt`；对 `upstream-derived.txt` 中的文件做字面量改写 |
| `prepareUpstreamTest` | `android/app/src/test/java` | `…/upstream/test` | 应用 `upstream-test-excludes.txt` |
| `prepareUpstreamAssets` | `android/app/src/main/assets` | `…/upstream/assets/assets/**` | 加一层 `assets/` 前缀；作为 main resources 根。`AssetManager.open(p)` 读取类路径 `assets/<p>` |

- 测试资源：`android/app/src/test/resources` 与 `android/test-fixtures/protocol` 直接作为 test resources 源根（与上游 `sourceSets` 一致；只读，不复制）。
- `build/generated/upstream/**` 是生成物。**任何人不得编辑**；要改上游，只能按 AGENTS.md §2 改 `android/` 下的原文件。
- 排除清单中的路径如果在上游已不存在，任务失败（防止清单腐烂）。

### 3.1 派生改写（仅用于库包名冲突，D-23）

`upstream-derived.txt` 每条：`文件 | 查找字面量 | 替换字面量 | 期望命中次数 | 理由`。命中次数不等于期望值时构建失败。
只允许用于「垫片方案因第三方库已占用同一 FQN 而无法使用」的情况；不允许借此修改逻辑。预期条目只有两个文件：

- `account/AccountViewModel.kt`、`clanbattle/ClanBattleViewModel.kt`：JetBrains lifecycle 在 JVM 上的 `ViewModelProvider.Factory` 只有 `create(modelClass: KClass<T>, extras: CreationExtras)`，上游的 `override fun <T : ViewModel> create(modelClass: Class<T>): T` 无法编译。改写为 `override fun <T : ViewModel> create(modelClass: kotlin.reflect.KClass<T>, extras: androidx.lifecycle.viewmodel.CreationExtras): T`，并把函数体中的 `modelClass.isAssignableFrom(` 改为 `modelClass.java.isAssignableFrom(`。各 2 处，用 FQN，不增删 import。
- 不能用自写的 `androidx.lifecycle` 垫片来代替：Compose Desktop 会传递引入 JetBrains lifecycle，造成同名类冲突（见 §6.4 的冲突检查）。

## 4. 分类规则

对每个与 Android 耦合的文件（直接 import，或用 FQN 引用 `android.*` / `androidx.*` / ML Kit，或**传递引用了被排除文件中的声明**），按以下顺序选第一个可行的类别：

| 类别 | 含义 | 何时用 |
|---|---|---|
| **K** 保留 | 原样编译，缺的框架 API 由垫片提供 | 只用到 §6 垫片清单内的 API |
| **K+P** 保留 + 上游补丁 | 先做纯搬移或注入缝（AGENTS.md §2），再原样编译 | 文件里混有 Android 专属代码和被其他保留文件引用的纯声明 |
| **D** 派生 | §3.1 字面量改写 | 仅限库 FQN 冲突 |
| **S** 同名替身 | 排除；在 `desktop/shared` 写同包、同名、同公共签名的桌面实现 | 被保留文件引用，但实现本身是 Android 专属 |
| **A** 桌面适配器 | 排除；功能由 `:device` / `:platform` / `:app` 的新实现承担，只有装配根引用它 | 没有保留文件引用它 |
| **X** 排除 | 无对应物 | Android 专属且桌面不需要 |

替身和适配器都要登记到 `registers/SUBSTITUTES.md`：上游文件、桌面文件、公共签名清单、行为差异、对应测试。

### 4.1 编译复用与桌面呈现边界（D-37）

文件分类服务于共享编译与行为兼容，不规定窗口必须调用哪个 Composable。可编译的上游页面仍可保留，桌面在 :app/ui 以自有包名实现呈现，调用同一状态和动作，避免同名冲突。不得为美化复制上游页面及其内部业务代码或改写生成物。

页面内若混有业务校验或决策，先复用可访问入口；确需提取时由 A 按 AGENTS §2 的纯搬移 / 默认值注入规则登记最小补丁，并验证接线，不能在桌面另写规则。LocalConfiguration / LocalContext 垫片保留编译兼容职责，仅在实际复用相应组件时包裹它（D-25）。

## 5. 初始分类（基于 b809b196；路径相对 `com/landosol/toolbox/`）

### 5.1 保留（K / K+P / D）

| 文件 | 类别 | 需要的垫片或补丁 |
|---|---|---|
| `labyrinth/LabyrinthEntryRecognitionSession.kt`（9,720 行） | K | Log；依赖 P-02 |
| `automation/session/GameSessionResetWorkflow.kt` | K | Log |
| `automation/capture/CaptureContracts.kt` | K | Bitmap |
| `clanbattle/recognition/AndroidPixelImageAdapter.kt` | K | Bitmap |
| `clanbattle/recognition/Embedded{Battle,Control,Digit}TemplateLoader.kt` | K | BitmapFactory、Base64 |
| `labyrinth/vision/AndroidLabyrinth{ExEncounter,NodeRelicStack,RelicDetail,RelicStack,ShopItem}Resolver.kt`、`AndroidRelicRoiOcr.kt` | K | Bitmap、SystemClock |
| `labyrinth/vision/AndroidLabyrinthRoleRewardNameResolver.kt` | K+P | Bitmap、SystemClock；P-03 移出 ML Kit 对象 |
| `labyrinth/vision/AndroidLabyrinthEventChoiceResolver.kt` | K | Context.assets、Bitmap、Log、SystemClock |
| `labyrinth/vision/AndroidLabyrinthEntryFrameProcessor.kt`、`AndroidLabyrinth{BattleTeam,Character,Relic}TemplateLoader.kt`、`node/AndroidLabyrinthNodeTemplateLoader.kt` | K | Context.assets、BitmapFactory |
| `labyrinth/AndroidLabyrinthRoleDecisionDataLoader.kt` | K | Context.assets |
| `labyrinth/AndroidLabyrinthStrategySettingsStore.kt`、`labyrinth/LabyrinthRerollSettings.kt`、`labyrinth/batch/LabyrinthBatchCheckpointStore.kt` | K | Context、SharedPreferences（D-21） |
| `gamedata/ResourcePackActivator.kt` | K | Context.filesDir |
| `security/AndroidKeystoreCredentialStore.kt`、`security/SdkSessionStores.kt` | K | Context；依赖替身 `AndroidKeystoreEncryptedStore` |
| `protocol/bilibili/BilibiliGameGatewayFactory.kt`、`BilibiliSdkGatewayFactory.kt` | K | Context；依赖两个画像工厂替身 |
| `labyrinth/debug/LabyrinthDebugDashboardServer.kt` | K+P | Bitmap、Log、Process.myPid、org.json；P-07 日志采集注入缝；依赖替身 `LabyrinthRuntimeDiagnostics` |
| `automation/GameClientResolver.kt` | K | PackageManager（仅编译垫片：桌面不调用 `installedPackageNames`） |
| `account/AccountRepository.kt`、`labyrinth/LabyrinthRerollWorkflow.kt`、`labyrinth/LabyrinthRunState.kt` | K | Room JVM + `withTransaction` 垫片 |
| `data/local/Daos.kt`、`data/local/Entities.kt` | K | Room JVM（DAO 全是 suspend / Flow，兼容） |
| `account/AccountViewModel.kt`、`clanbattle/ClanBattleViewModel.kt` | D | JetBrains lifecycle；§3.1 |
| `ui/ResponsiveLayout.kt`、`ui/account/AccountScreen.kt`、`ui/clanbattle/ClanBattleScreen.kt`、`ui/labyrinth/LabyrinthOpeningRosterScreen.kt`、`ui/labyrinth/LabyrinthStrategySettingsScreen.kt` | K | Compose Multiplatform 同名包 |
| `ui/labyrinth/LabyrinthScreen.kt` | K+P | LocalConfiguration 垫片；依赖替身 `GeetestCaptchaDialog` 和 P-06 |
| `ui/labyrinth/LabyrinthRoleRatingsScreen.kt` | K（首选） | LocalContext、LruCache、`BitmapFactory.Options`（inJustDecodeBounds / inSampleSize）、`Bitmap.asImageBitmap()`。若这几个垫片合计超过约 150 行，改为 S 并记录 |

### 5.2 排除（S / A / X）

| 文件 | 类别 | 桌面对应（WS） |
|---|---|---|
| `data/local/AppDatabase.kt` | S | 同名 `AppDatabase`：同实体、version 4、同 `FILE_NAME`、保留 `seedRelatedRecordsForTest`；迁移改为 `migrate(connection: SQLiteConnection)`，SQL 文本与上游逐字一致（漂移检查比对）；`create(context)` 用 `BundledSQLiteDriver` + WAL（D） |
| `security/AndroidKeystoreEncryptedStore.kt` | S | 同名同签名；加解密走 `DesktopBindings.secretProtector`（DPAPI），密文存在同名 SharedPreferences 垫片里（D） |
| `protocol/bilibili/AndroidBilibiliSdkRequestProfileFactory.kt` | S | 字段来自设备画像快照（SPEC-40，E） |
| `protocol/bilibili/AndroidGameProtocolProfileFactory.kt` | S（P-04 之后） | 同上（E） |
| `labyrinth/vision/AndroidChineseRoleNameOcr.kt`（P-03 新文件） | S | `object AndroidChineseRoleNameOcr` → `DesktopBindings.ocr`（SPEC-20，C） |
| `labyrinth/debug/LabyrinthRuntimeDiagnostics.kt` | S | 同名同公共 API（`start()`、`environmentJson()`、`historyNdjson()` 等，以扫描为准）；内容换成 JVM、进程、设备、截图指标（F） |
| `ui/account/GeetestCaptchaDialog.kt` | S | 同名同签名的 Composable；回环服务 + 系统浏览器（SPEC-40，E） |
| `automation/capture/MediaProjectionCaptureService.kt` | A（P-01 之后） | `DesktopCaptureService`（B，桥接在 `:shared`） |
| `automation/accessibility/LandosolAccessibilityService.kt` | A（P-02 之后） | `DeviceActionBackend` + 前台监视器（B） |
| `automation/accessibility/AccessibilityChannelPromptScreen.kt` | A | `UiAutomatorChannelPromptScreen`（B） |
| `automation/session/AndroidSessionResetBackends.kt` | X（传递耦合：引用无障碍服务） | 桌面的大退和重启走 `GameClientHardCloser` 的桌面装配（H） |
| `automation/overlay/AndroidAutomationNotificationHost.kt` | A（P-05 之后） | 托盘 / 窗口任务栏实现 `AutomationOverlayHost`（F） |
| `automation/overlay/AutomationOverlayService.kt` | X | 悬浮窗由窗口任务栏取代 |
| `labyrinth/LabyrinthRerollService.kt` | A（P-06 之后） | 应用作用域协程 + 防睡眠（H） |
| `labyrinth/AndroidLabyrinthCnDatabaseRepository.kt` | A | `DesktopLabyrinthCnDatabaseRepository`：复用上游 `LabyrinthCnDatabaseUpdater` 及其接口；URL、目录名、文件名、最小字节数常量与上游一致（漂移检查）（D） |
| `permissions/PermissionStatusReader.kt`、`ui/permissions/PermissionCenterScreen.kt` | A | 环境检查页（F） |
| `LandosolToolboxApplication.kt` | A | `DesktopAppGraph`（H，逐段对照） |
| `ui/LandosolToolboxApp.kt` | A | MAA 外壳（F，UI_PARITY 对照） |
| `MainActivity.kt` | X | `Main.kt` |

### 5.3 重新扫描

`desktop/tools/scan-android-coupling`（M1 编写，CI 复用）输出三张表：直接 import；非 import 行里的 FQN 用法（例如 `android.os.SystemClock.elapsedRealtime()`、`android.os.Process.myPid()`）；**传递耦合**（引用了被排除文件中顶层声明的保留文件）。另外列出上游代码里用到的 `org.json`、`BuildConfig`、`R.` 引用。

## 6. 框架垫片

### 6.1 通用规则

- 位置：`desktop/shared/src/main/kotlin/<原包路径>/`，包名和类名与 Android 完全一致。
- **只实现保留文件编译所需的最小 API 面**：不预先补全 Android API。新增成员要能指出调用点。
- 语义与 Android 文档一致；做不到一致的成员，KDoc 写明差异并登记到 SUBSTITUTES。
- 仅编译垫片（如 `PackageManager`）：抽象成员，桌面不创建实例，KDoc 写明“桌面不可达”。
- 每个垫片都要有单测，覆盖本节写明的语义要点。

### 6.2 各垫片要点

| 垫片 | 语义要点 |
|---|---|
| `android.util.Log` | `d/i/w/e(tag, msg[, tr])` 返回 `Int`；写入桌面日志系统（SPEC-30 §7），格式与 logcat `threadtime` 一致：`MM-dd HH:mm:ss.SSS  pid  tid L tag: msg` |
| `android.os.SystemClock` | `elapsedRealtime()` / `uptimeMillis()` 单调递增，用 `System.nanoTime()` 换算，不用墙钟 |
| `android.os.Process` | `myPid()` = `ProcessHandle.current().pid().toInt()` |
| `android.graphics.Bitmap` | 内部是 ARGB_8888、**非预乘**的 `IntArray`。`recycle()` 后访问像素抛 `IllegalStateException`，`isRecycled` 为真。`createBitmap(src, x, y, w, h)` 在全尺寸时返回**同一实例**，`createScaledBitmap` 在同尺寸时返回同一实例（与 Android 一致，上游的 recycle 逻辑依赖这一点）；越界抛 `IllegalArgumentException`。`compress(JPEG/PNG, quality, out)` 用 ImageIO，JPEG 先转 RGB |
| `android.graphics.BitmapFactory` | 解码路径 = Compose 已携带的 Skia → 非预乘 ARGB（D-45）。PNG/JPEG/WebP 均支持；无效图像返回 `null`。不新增色彩校正或依赖。`Options` 实现尺寸预读及采样缩小；真实 WebP 与上游 golden/fixture 验证兼容 |
| `android.util.Base64` | `decode/encode` 的 flags（`DEFAULT`、`NO_WRAP`、`URL_SAFE`、`NO_PADDING`）与 Android 一致；`DEFAULT` 解码时忽略换行 |
| `android.content.Context` | 抽象类，只含 `assets`、`applicationContext`、`filesDir`、`cacheDir`、`getSharedPreferences(name, mode)`、`MODE_PRIVATE`。桌面实现 `DesktopContext(dataDir)`：`filesDir = <data>/files`，`cacheDir = <data>/cache`，`applicationContext = this` |
| `android.content.SharedPreferences` + `Editor` | 每个 name 一个文件 `<data>/shared_prefs/<name>.json`，类型化值。`commit()` 同步原子写（SPEC-30 §3），失败返回 `false` 并记日志；`apply()` 与 `commit()` 走同一同步写，失败只记日志（与 Android 一样调用方拿不到结果）。同一 name 返回同一实例，读写加锁 |
| `android.content.res.AssetManager` | `open(path)` 读类路径 `assets/<path>`，不存在抛 `FileNotFoundException(path)` |
| `android.content.res.Configuration` | 仅 `screenWidthDp`、`screenHeightDp`（以及扫描发现用到的字段） |
| `android.content.pm.PackageManager` | 仅编译垫片 |
| `android.view.Display` | 仅常量 `DEFAULT_DISPLAY = 0`（`CaptureStateRegistry` 使用）；不提供任何实例方法 |
| `android.util.LruCache` | 仅在保留 `LabyrinthRoleRatingsScreen` 时提供：`sizeOf` 可覆写，`maxSize` 语义、LRU 淘汰、`synchronized` 与 Android 一致 |
| `androidx.room.withTransaction` | JVM 上 Room 不提供该扩展。实现为 `useWriterConnection { it.immediateTransaction { block() } }`；块内 DAO 调用复用同一连接；异常回滚并原样抛出。单测覆盖：提交、回滚、块内 Flow 不死锁 |
| `androidx.compose.ui.platform.LocalConfiguration` / `LocalContext` | `staticCompositionLocalOf`，默认值 `error("…未提供")`。实际复用依赖此环境的上游 UI 组件时，在其内容区外层用 `BoxWithConstraints` 提供相应 dp 尺寸的 `Configuration` 与 `DesktopContext`；桌面自有页面不强制调用该垫片（D-25） |
| `androidx.compose.ui.graphics.asImageBitmap`（for 垫片 Bitmap） | 转成 Skia `ImageBitmap`；仅在保留 `LabyrinthRoleRatingsScreen` 时提供 |

### 6.3 不做垫片的

`Build`、`Settings`、`PowerManager`、`Notification*`、`AccessibilityService`、`MediaProjection`、`WebView`、`SQLiteDatabase`、`KeyStore` 等：这些只出现在被排除的文件里。如果重新扫描发现保留文件用到了，优先考虑纯搬移把用法隔离出去，而不是补垫片。

### 6.4 同名冲突检查（G1 必过）

Gradle 任务 `checkShimCollisions`：列出 `desktop/shared/src/main/kotlin` 中 `android.*` / `androidx.*` 包产生的全部类，断言**运行时类路径上任何 jar 都不含同名类**。有冲突就构建失败。

## 7. 同名替身

- 同包、同名、公共签名逐字一致（包括默认参数值、`suspend`、可空性）。
- 签名漂移检查 `checkSubstituteSignatures`：用 Kotlin 编译器产物或 kotlinx-metadata 导出上游原文件（在 Android 构建或单独的签名提取任务中）与替身的公共 API 签名并比对；无法自动提取时，退而对上游源文件做声明级文本提取（`fun` / `val` / 构造函数行），并在 DECISIONS 记录这一取舍。
- 替身需要桌面能力时，只能通过 `DesktopBindings`（§9）获取，不得直接依赖 `:device` / `:ocr` / `:platform`。

## 8. 预计上游补丁（全部登记到 UPSTREAM_PATCHES.md）

| ID | 文件 | 类别 | 内容 |
|---|---|---|---|
| P-01 | `automation/capture/MediaProjectionCaptureService.kt` | 纯搬移 | `CaptureDisplayMetrics`、`CaptureDisplayStabilityGate`、`CaptureRotationPolicy`、`CaptureRotationDecision`、`CaptureTransientRotationGate`、`rgba8888ToArgbPixels`、`CaptureFrameScratch`、`CaptureStateRegistry` → 同包新文件 `CaptureRuntime.kt` |
| P-02 | `automation/accessibility/LandosolAccessibilityService.kt` | 纯搬移 | `GAME_NOT_FOREGROUND_REASON` → 同包新文件 `AccessibilityContracts.kt` |
| P-03 | `labyrinth/vision/AndroidLabyrinthRoleRewardNameResolver.kt` | 纯搬移 | `object AndroidChineseRoleNameOcr` → 同包新文件 `AndroidChineseRoleNameOcr.kt` |
| P-04 | `protocol/bilibili/AndroidGameProtocolProfileFactory.kt` | 纯搬移 | `GameProtocolProfile` → 同包新文件 `GameProtocolProfile.kt` |
| P-05 | `automation/overlay/AndroidAutomationNotificationHost.kt` | 纯搬移 | `AutomationNotificationContent`、`notificationContent()`、`automationNotificationActionMatches` → 同包新文件 `AutomationNotificationContent.kt`（托盘复用通知文案） |
| P-06 | `labyrinth/LabyrinthRerollService.kt` | 纯搬移 | `labyrinthRerollStatusText` → 同包新文件 `LabyrinthRerollStatusText.kt` |
| P-07 | `labyrinth/debug/LabyrinthDebugDashboardServer.kt` | 注入缝 | 构造参数 `processLogSource: () -> ByteArray`，默认值为原 logcat 采集（把它提成文件内顶层 `private fun` 即可作为默认值） |

搬移规则：只移动声明及其 KDoc，不改可见性；import 随之调整；原文件删除对应声明。每条补丁都要确认 Android 端编译、单测仍通过。

## 9. DesktopBindings 与 internal 桥接

- `:shared` 中声明 `object DesktopBindings`，字段是接口（`ocr`、`secretProtector`、`deviceIdentity`、`captchaHost`、`processLogSource`、`deviceIdentityStore` 等，M1 定稿写进 CONTRACTS.md）。`:app` 启动时调用一次 `install(…)`；重复安装或未安装就读取，都用 `check` 失败。
- 上游的 `internal` 声明只在同一 Gradle 模块内可见。需要调用 `internal` API 的桌面代码（例如 `CaptureFrameBus.dispatch`、`CaptureStateRegistry` 的内部更新方法）必须放在 `:shared` 的桥接类里，例如 `DesktopCaptureBridge`，由 `:app` 调用。不得为此把上游声明改成 public。

## 10. BuildConfig 与版本

- 生成 `com.landosol.toolbox.BuildConfig`（`VERSION_NAME`、`VERSION_CODE`、`DEBUG = false`，以及扫描出的其他字段）。值从 `android/app/build.gradle.kts` 的 `versionName` / `versionCode` 解析，解析失败构建失败。
- 桌面显示版本为 `<versionName>+desktop.<n>`（D-16），`<n>` 在 `desktop/gradle.properties` 维护。`BuildConfig.VERSION_NAME` 保持上游原值（日志包和协议字段使用它）。

## 11. 上游测试接入

- 测试工作目录设为 `android/app`（与 Android Gradle 单测一致；上游测试用 `user.dir` 和 `../../素材/…` 这类相对路径）。
- `LabyrinthCharacterIconGoldenTest` 在金标文件缺失时会**自动生成并通过**。所以：测试前断言 `android/app/src/test/resources/character-icon-golden.txt` 存在；**测试前后对 `android/` 做快照并比对**：快照 = `git status --porcelain --untracked-files=all -- android/` 的输出 + 其中列出的每个文件的 SHA-256；前后不一致则构建失败（任何测试都不得在 `android/` 下写文件）。本地开发时 `android/` 可能有尚未提交的 P-xx 补丁，所以比对前后快照而不是要求为空；CI 是干净检出，快照应为空，非空也失败。
- 排除的测试只限于测试对象本身被排除的情况（例如 `ForegroundWindowTrackingTest`），逐条登记理由。不得因为“在桌面上失败”而排除；失败要查清根因（通常是垫片语义不对）。
- 上游 `androidTest` 中与桌面相关的部分要移植成桌面测试：`AppDatabaseTest`、`LabyrinthRunStateDatabaseTest`、`SchemaV1/V2/V4Test`（Room JVM 的 `MigrationTestHelper` + 上游导出的 schema JSON）、`EmbeddedControlTemplateAndroidTest`、`GameIconAndroidTest`。Keystore 两个测试改写为替身（DPAPI）测试。

## 12. 漂移检查（CI 每次运行）

`checkUpstreamDrift` 汇总：§5.3 扫描结果与排除清单不一致（出现新的耦合文件）；替身签名漂移（§7）；派生改写命中次数（§3.1）；`AppDatabase` 版本号、实体列表、迁移 SQL 与替身不一致；协议画像字段（SPEC-40）；`FRAME_INTERVAL_MILLIS`、手势时长常量、`GAME_NOT_FOREGROUND_REASON` 等桌面复刻的常量（以 SUBSTITUTES 中登记的为准）。任一不一致即失败，并输出需要处理的清单。

## 13. G1 标准

1. `desktop/gradlew :shared:compileKotlin` 通过；`checkShimCollisions`、`checkSubstituteSignatures`、`checkUpstreamDrift` 通过。
2. 上游 JVM 单测在 `:shared:test` 下全部通过（排除项已逐条登记）；测试后 `android/` 无改动。
3. Room：导出的 schema identityHash 与 `android/app/schemas/…/4.json` 一致；迁移测试通过。
4. 垫片和替身桩都有单测；替身桩未实现的方法统一 `TODO("WS-x")`，并在 SUBSTITUTES 中标明归属。
5. CONTRACTS.md 已冻结；P-01…P-07（以实际为准）已登记，Android 构建验证通过或已排入 CI。
