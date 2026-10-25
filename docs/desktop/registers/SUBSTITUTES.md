# SUBSTITUTES.md —— 同名替身、垫片与桌面适配器登记

**规则**：替身与上游原文件同包、同名、公共签名逐字一致（SPEC-00 §7），由 `checkSubstituteSignatures` 校验。替身只能通过 `DesktopBindings` 获得桌面能力。
M1 由总指挥创建全部桩（未实现的方法统一 `TODO("WS-x")`），然后移交给表中的归属 WS。子代理只能修改自己 WS 的小节。
「签名检查」列填 `checkSubstituteSignatures` 是否覆盖；「行为对照」列填与上游行为一致性的测试名。

## 1. 同名替身（类别 S）

| 上游文件 | 替身要点 | 归属 | 签名检查 | 行为对照测试 | 状态 |
|---|---|---|---|---|---|
| `data/local/AppDatabase.kt` | 同实体、version 4、同 `FILE_NAME`、迁移 SQL 逐字一致、`create(context)` 用 BundledSQLiteDriver + WAL | D | | | 桩 |
| `security/AndroidKeystoreEncryptedStore.kt` | DPAPI；entropy = `LimingjieAssistant\|v1\|<storageName>\|<key>`（竖线分隔）；密文 `v1:` + Base64 存在 prefs 垫片 | D | | | 桩 |
| `protocol/bilibili/AndroidBilibiliSdkRequestProfileFactory.kt` | 字段来自 `DeviceIdentitySnapshot` | E | | | 桩 |
| `protocol/bilibili/AndroidGameProtocolProfileFactory.kt`（P-04 之后） | 同上 | E | | | 桩 |
| `labyrinth/vision/AndroidChineseRoleNameOcr.kt`（P-03 新文件） | 复制像素 → `DesktopBindings.ocr` → Main 上回调 | C | | | 桩 |
| `labyrinth/debug/LabyrinthRuntimeDiagnostics.kt` | 同公共 API；内容为 JVM / 进程 / 设备 / 截图指标 | F | | | 桩 |
| `ui/account/GeetestCaptchaDialog.kt` | 同签名 Composable；回环服务 + 系统浏览器 | E | | | 桩 |

## 2. 桌面适配器（类别 A，替代被排除的 Android 外壳）

| 上游文件 | 桌面实现（路径） | 归属 | 行为对照测试 | 状态 |
|---|---|---|---|---|
| `automation/capture/MediaProjectionCaptureService.kt` | `shared/…/desktop/bridge/capture/DesktopCaptureService` + `:device` 截图器 | B | | |
| `automation/accessibility/LandosolAccessibilityService.kt` | `app/…/adapters/device/DeviceActionBackend` + 前台监视器 | B | | |
| `automation/accessibility/AccessibilityChannelPromptScreen.kt` | `app/…/adapters/device/UiAutomatorChannelPromptScreen` | B | | |
| `automation/overlay/AndroidAutomationNotificationHost.kt` | `app/…/adapters/overlay/`（任务栏 + 托盘，实现 `AutomationOverlayHost`） | F | | |
| `labyrinth/LabyrinthRerollService.kt` | `app/…/tasks/`（应用作用域协程 + 防睡眠） | H | | |
| `labyrinth/AndroidLabyrinthCnDatabaseRepository.kt` | `app/…/adapters/cndb/DesktopLabyrinthCnDatabaseRepository` | D | | |
| `permissions/PermissionStatusReader.kt`、`ui/permissions/PermissionCenterScreen.kt` | `app/…/ui/` 设置与环境页 | F | | |
| `ui/LandosolToolboxApp.kt` | `app/…/ui/` MAA 式桌面导航与页面接线（UI_PARITY） | F | | |
| `LandosolToolboxApplication.kt` | `app/…/DesktopAppGraph`（对照见 ASSEMBLY.md） | H | | |
| `MainActivity.kt` | `app/…/Main.kt` | H | | |
| `automation/session/AndroidSessionResetBackends.kt`（X） | 桌面装配中的 `GameClientHardCloser` 接线 | H | | |
| `automation/overlay/AutomationOverlayService.kt`（X） | 无（悬浮窗由任务栏取代） | — | — | 不适用 |

## 3. 框架垫片（归属 A，M1 完成）

| 垫片 | 语义来源 | 单测 | 状态 |
|---|---|---|---|
| `android.util.Log` | SPEC-00 §6.2 | | |
| `android.os.SystemClock` | 同上 | | |
| `android.os.Process` | 同上 | | |
| `android.graphics.Bitmap` | 同上 | | |
| `android.graphics.BitmapFactory`（+ `Options`） | 同上；D-26 | | |
| `android.util.Base64` | 同上 | | |
| `android.content.Context` | 同上；D-21 | | |
| `android.content.SharedPreferences` + `Editor` | 同上；D-21；FB-04 | | |
| `android.content.res.AssetManager` | 同上 | | |
| `android.content.res.Configuration` | 同上 | | |
| `android.content.pm.PackageManager`（仅编译） | 同上 | — | |
| `android.view.Display`（仅 `DEFAULT_DISPLAY`） | 同上 | — | |
| `android.util.LruCache` | 同上（保留角色评分屏幕时） | | |
| `androidx.room.withTransaction` | 同上 | | |
| `LocalConfiguration` / `LocalContext` | 同上；D-25 | | |
| `asImageBitmap`（垫片 Bitmap） | 同上 | | |
| `org.json`（依赖库，不是垫片） | SPEC-00 §2 | — | |

（M1 扫描发现的其他垫片按同样格式追加。）

## 4. 存储映射

以 SPEC-30 §5 为起点，M1 扫描后补全到这里：每个 prefs 名称、文件位置、读写方、是否加密。

| prefs 名称 / 文件 | 上游读写方 | 桌面位置 | 加密 | 归属 |
|---|---|---|---|---|
| | | | | |

## WS-F：桌面自有呈现（D-37）

重设计页面使用 …desktop.app.ui 自有包名，不属于与上游同名同签名的替身。简记实际页面位置、复用状态 / 动作与 UI_PARITY 功能分组，不将同名替身检查套用于自有页面。上游页面可保留编译，是否直接使用以设计为准。

| 上游功能基准 | 桌面呈现位置（执行期填写） | 业务状态 / 动作来源 | UI_PARITY 分组 / 备注 |
|---|---|---|---|
| LabyrinthScreen | | | |
| AccountScreen | | | |
| 策略 / 开局编组 / 角色评分 | | | |
