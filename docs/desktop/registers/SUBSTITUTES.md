# 替身、垫片与桌面适配器

更新：2026-10-25。排除清单以 desktop/shared/upstream-excludes.txt 为准；同名替身保留原公共入口，原业务源码经生成目录编译。checkSubstituteSignatures 检查七个 S 类的可见函数/构造声明，Room迁移参数按JVM适配；这不是运行行为证明。M1检查已通过，诊断扩展后签名复查亦通过。

## 同名替身（shared/src/main/kotlin 下的原包路径）

| 原文件 | 替身行为与边界 |
|---|---|
| data/local/AppDatabase.kt | 原9实体、版本4、库名、迁移SQL；Room JVM、BundledSQLite、WAL、IO查询 |
| security/AndroidKeystoreEncryptedStore.kt | 原save/read/delete/clearAllForTest；IO+Mutex；通过Bindings调用DPAPI，entropy为LimingjieAssistant\|v1\|storageName\|key，prefs只保存v1:Base64密文 |
| protocol/bilibili/AndroidBilibiliSdkRequestProfileFactory.kt | 同字段顺序/常量与派生公式；读取目标模拟器画像；android_id为空回落installId；无客户端签名阻止登录 |
| protocol/bilibili/AndroidGameProtocolProfileFactory.kt | headers来自模拟器包版本、系统/API、brand/model；不使用PC主机信息 |
| labyrinth/vision/AndroidChineseRoleNameOcr.kt | 同步复制像素后OcrPort提交，Main回调；可立即释放原Bitmap |
| labyrinth/debug/LabyrinthRuntimeDiagnostics.kt | 同公共入口；守护采样，JVM/OS/CPU及装配注入的设备/截图指标，显式close |
| ui/account/GeetestCaptchaDialog.kt | 同签名；回环会话、系统浏览器、复制链接与取消；业务回调不变 |

DPAPI实现在platform/WindowsDpapi，shared的SecretProtector经app/adapters/storage/WindowsSecretProtector适配，platform不依赖shared。失败转SecretUnavailableException（IllegalArgumentException子类），使AccountViewModel.safeMessage透传中文原因；非Windows拒绝，不回落明文。

## 平台外壳替代

| Android能力 | 实际桌面位置 |
|---|---|
| MediaProjectionCaptureService | shared/.../desktop/shared/DesktopCaptureService + device/ScreenCapturer |
| Accessibility服务/动作 | app/adapters/device/DeviceActionBackend、ManagedDeviceService + device/AdbDeviceService/MaaTouch |
| AccessibilityChannelPromptScreen | shared/.../DesktopChannelPromptScreen，graph传入UIAutomator/前台/点击 |
| 通知宿主/悬浮窗 | app/DesktopTaskHost、ui/DesktopApp底栏、Main托盘；保留AutomationOverlayCoordinator |
| LabyrinthRerollService | graph.startRerollHost、受控scope与期限、防睡眠监听 |
| AndroidLabyrinthCnDatabaseRepository | app/adapters/cndb/DesktopLabyrinthCnDatabaseRepository；仍使用原Updater/Source/Validator/Installer/Metadata接口 |
| 权限中心 | ui/EnvironmentPages的设备与设置页；无效Android权限入口删除 |
| LandosolToolboxApp / MainActivity | ui/DesktopApp、Main；业务装配见ASSEMBLY |
| AndroidSessionResetBackends | graph将HOME/force-stop/launch/前台连接到原重置流程 |

国服SQLite有意逐项迁移：同文件头、只读打开、quick_check、sqlite_master/安全标识符、table_info(name/type/pk)、命名resolver→语义resolver、整数主键与引用值探测（每组400绑定）、范围/NULL过滤。备份后原子替换，占用时保留pending与独立元数据，下次读前安装；不能用Updated冒充尚未安装。细节与合成测试见WS-DE-1。

## 框架垫片

shared提供android.util的Log/Base64/LruCache、os的SystemClock/Process、graphics的Bitmap/BitmapFactory、content的Context/SharedPreferences/AssetManager/Configuration、必要编译用PackageManager/Display，以及Compose LocalContext/LocalConfiguration/asImageBitmap和Room withTransaction。org.json为真实依赖。

Bitmap保持ARGB与所有权检查，resources从classpath读取；Context只支持任务范围内本地文件/私有prefs。commit/apply均同步原子持久化。桌面内容区通过BoxWithConstraints提供局部尺寸，不读取PC分辨率冒充模拟器。未实现的Android系统服务不模拟为成功。

## 存储映射

| 名称 | 桌面位置（相对data） | 读写方与保护 |
|---|---|---|
| labyrinth-strategy | shared_prefs/labyrinth-strategy.json | 原策略store，原codec |
| labyrinth-reroll-settings | shared_prefs/labyrinth-reroll-settings.json | 原账号刷取设置 |
| labyrinth-batch-checkpoints | shared_prefs/labyrinth-batch-checkpoints.json | 原批次checkpoint |
| protocol-install-identity / install-id | shared_prefs/protocol-install-identity.json | profile工厂生成UUID，commit失败抛错 |
| account-credentials | shared_prefs/account-credentials.json | 原CredentialStore + DPAPI密文 |
| sdk-sessions | shared_prefs/sdk-sessions.json | 原SdkSessionStore + DPAPI密文 |
| Room v4 | databases/landosol-toolbox.db及wal/shm | 账号元数据/路线/运行状态，不放明文密码 |
| 设备画像 | device/identity-<设备键SHA256>.json、current.json | FileDeviceIdentityStore；摘要用于安全文件名，原设备键在JSON内部 |
| 桌面偏好 | settings/desktop.json | DesktopSettingsStore；原子写，坏文件改名保留 |
| 资源/国服库 | files/game-data、files/labyrinth/master-cn | 原资源激活器/桌面国服仓库 |
| 缓存 | cache/labyrinth-frame-archive、cache/apk | 诊断与设备签名读取 |
| 工具/日志 | tools/platform-tools、logs | ADB用户触发下载；桌面日志脱敏/滚动 |

SharedPreferences/settings解析损坏保留.corrupt备份；Room先只读检查，确认SQLite损坏才备份db/wal/shm后重建；不因任意打开失败清库。上游策略/检查点codec坏数据行为保持原样。

验证范围：ShimTest/RoomCompatibilityTest为M1已有检查；DesktopAdaptersTest与WS-DE/WS-B报告为本轮定向检查。完整签名/资源门槛由已有Gradle检查任务承担；UI未要求同名签名，功能对照见UI_PARITY。实际窗口、打包与实机结果分别记录，不互相替代。
