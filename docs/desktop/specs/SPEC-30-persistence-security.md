# SPEC-30 持久化、安全与 Windows 平台（WS-D；`:platform` + `:shared` 中的存储替身）

## 1. 数据目录（D-14）

**解析顺序**（取第一个命中的，启动时确定，运行期间不变）：

1. 命令行 `--data-dir <路径>`。
2. exe 同级目录存在 `portable.flag`：使用 `<exe 目录>\data`。exe 目录取 jpackage 启动器设置的系统属性 `jpackage.app-path` 的父目录；开发运行（没有该属性）时跳过这一步。
3. Windows：`%LOCALAPPDATA%\LimingjieAssistant`；其他系统（仅开发用）：`$XDG_DATA_HOME/LimingjieAssistant`，未设置时用 `~/.local/share/LimingjieAssistant`。

**可写检查**：创建并删除一个临时文件。失败时弹出阻断对话框，写明路径、系统返回的原因，以及建议（例如「请不要把助手放在 Program Files 下，可移到 D:\Tools\ 之类的目录」），然后退出。**不静默换到其他位置。**

**布局**：

```text
<data>/
  instance.lock
  files/                 # Context.filesDir：game-data/、labyrinth/master-cn/ 等（与上游 filesDir 下的结构一致）
  cache/                 # Context.cacheDir：labyrinth-frame-archive/、apk/
  shared_prefs/<name>.json
  databases/landosol-toolbox.db (+ -wal / -shm)
  device/identity-<设备键>.json
  settings/desktop.json  # 桌面专属设置：adb 路径、上次设备、触控模式、关闭行为、网络模式
  logs/app.log、app.1.log…、crash-<时间>.log
  tools/platform-tools/
  tmp/
```

## 2. 单实例

`instance.lock` 用 `FileChannel.tryLock()`。获取失败：提示「另一个助手实例正在使用这个数据目录（<路径>）」后退出。不同数据目录可以同时运行（D-17，多开属于 P2，不做额外支持）。

## 3. 原子写（所有桌面自管文件、SharedPreferences 垫片共用一个实现）

1. 在**目标同目录**创建 `<文件名>.tmp-<随机>`，写入后 `FileChannel.force(true)`，关闭。
2. `Files.move(tmp, target, ATOMIC_MOVE, REPLACE_EXISTING)`。
3. Windows 上遇到 `AccessDeniedException` 或 `FileSystemException`（杀毒软件、索引服务短暂占用）：重试，最多 5 次、每次间隔 100 ms（FB-04）；仍失败则删除 tmp 并返回失败（垫片的 `commit()` 返回 `false`，其他调用方抛出带路径的异常）。
4. 启动时（拿到实例锁之后）清理遗留的 `*.tmp-*`。

## 4. 损坏处理（FB-07）

- SharedPreferences 文件或 `settings/desktop.json` 无法解析：改名为 `<名>.corrupt-<时间>.json`，以空内容或默认值继续；启动后显著提示哪些设置被重置，以及备份文件的位置。
- Room 数据库打开时报 `SQLITE_CORRUPT` / `SQLITE_NOTADB`：把 db、wal、shm 移到 `databases/corrupt-<时间>/`，新建空库，弹出阻断提示（「本地账号库已损坏并已备份到…，账号需要重新添加」）。**不直接删除**（Android 默认的错误处理会删除损坏的库，桌面改为保留备份，登记到 DECISIONS）。
- 上游自身编解码器（策略设置、检查点等）遇到坏数据时的行为保持不变。

## 5. 存储映射

| 上游 | 位置 / 名称 | 桌面实现 |
|---|---|---|
| `AndroidLabyrinthStrategySettingsStore` | prefs `labyrinth-strategy`，键 `settings.v1` | 保留上游代码 + prefs 垫片 |
| `AndroidLabyrinthRerollSettingsStore` | prefs `labyrinth-reroll-settings` | 同上 |
| `AndroidLabyrinthBatchCheckpointStore` | prefs `labyrinth-batch-checkpoints` | 同上 |
| 协议安装标识 | prefs `protocol-install-identity`，键 `install-id` | 画像工厂替身沿用同名同键（SPEC-40） |
| `AndroidKeystoreCredentialStore` / `AndroidKeystoreSdkSessionStore` | prefs `account-credentials` / `sdk-sessions`（密文） | 保留上游代码 + 加密存储替身（§6） |
| `AppDatabase`（Room） | `databases/landosol-toolbox.db` | 替身（SPEC-00 §5.2） |
| `ResourcePackActivator` | `files/game-data/` | 保留上游代码 + Context 垫片 |
| 国服数据库 | `files/labyrinth/master-cn/` | §8 |
| 诊断逐帧存档 | `cache/labyrinth-frame-archive/` | 装配层传入目录 |

以 M1 扫描结果为准补全这张表，写进 SUBSTITUTES.md。

## 6. 凭据加密（D-08）

**`SecretProtector`**（`:shared` 中声明，`:platform` 实现，经 `DesktopBindings` 注入）：

```kotlin
interface SecretProtector {
    fun protect(plaintext: ByteArray, entropy: ByteArray): ByteArray
    /** 失败抛 SecretUnavailableException（带中文原因）。 */
    fun unprotect(blob: ByteArray, entropy: ByteArray): ByteArray
}
```

- Windows 实现：JNA `Crypt32Util.cryptProtectData` / `cryptUnprotectData`，CurrentUser 作用域，`CRYPTPROTECT_UI_FORBIDDEN`。
- 替身 `AndroidKeystoreEncryptedStore`：同签名（`save` / `read` / `delete` / `clearAllForTest`，`Dispatchers.IO` + `Mutex`）。entropy = UTF-8 的 `"LimingjieAssistant|v1|<storageName>|<key>"`（相当于上游 GCM 的 AAD，防止密文在键之间互换）。prefs 中保存 `v1:` + Base64（`NO_WRAP`）。写入失败与上游一致：`check(commit())`。
- 解密失败（数据目录被复制到其他电脑或其他 Windows 用户，或 Windows 密码被重置）：抛出带说明的异常，「凭据无法解密：数据目录可能来自其他电脑或 Windows 用户，请重新填写该账号的密码」（FB-12）。WS-D 要沿上游调用链确认这条消息能显示到 UI。例如 `AccountViewModel.safeMessage` 只透传 `IllegalArgumentException` 的消息，那么异常类型就要选它的子类，并在 SUBSTITUTES 中写明原因。
- 非 Windows：`SecretProtector` 的实现直接抛出「凭据存储仅支持 Windows」。测试中使用确定性的假实现，**不得**在任何环境下落明文。
- 日志、诊断包、崩溃日志中不出现明文或密文。

## 7. 日志

- `android.util.Log` 垫片和桌面代码共用一个日志器：
  - 文件 `logs/app.log`，单个达到 10 MB 时滚动，保留 5 个；UTF-8；行格式与 logcat `threadtime` 一致。
  - 内存环形缓冲：最近 5,000 行，供诊断包使用（P-07 的 `processLogSource` 输出这里的内容，大小上限与上游 `MAX_LOGCAT_BYTES` 一致）。
- 网络请求不记录报文，不安装 OkHttp 日志拦截器。
- 崩溃：`Thread.setDefaultUncaughtExceptionHandler` 写 `logs/crash-<时间>.log`（含版本、JVM、系统信息、堆栈），然后显示对话框（含日志路径和「导出诊断包」按钮）。应用作用域协程的 `CoroutineExceptionHandler` 记录日志，并通过任务控制停止当前任务，UI 显示原因。

## 8. 国服数据库（`DesktopLabyrinthCnDatabaseRepository`）

- 复用上游 `LabyrinthCnDatabaseUpdater` 及其 `Source` / `Validator` / `Installer` / `MetadataStore` 接口。
- 下载：OkHttp，URL 模板、目录、文件名、最小字节数与上游常量一致（漂移检查）。临时文件建在同一目录。
- 校验：用 `BundledSQLiteDriver` 以只读方式打开，`PRAGMA quick_check`、表与列检查、探测查询逐项对照上游 `AndroidCandidateValidator` 和 `AndroidMasterDataProbe` 实现（这是一份有意的重复实现，登记到 SUBSTITUTES，并附逐项对照表）。
- 安装：与上游 `AtomicInstaller` 相同的备份 + 替换顺序，使用 §3 的原子移动。目标文件被占用、重试仍失败时（FB-08）：把候选文件暂存为 `master_cn.pending.db`，下次启动、在任何读取发生之前完成安装；UI 显示「数据库已下载，将在重启后生效」。
- 网络失败、校验失败的消息沿用上游的结果类型和文案。

## 9. 防止系统睡眠（Windows）

- `SetThreadExecutionState` 的状态绑定在**调用它的线程**上，所以必须在一个专用的常驻线程上调用和清除。不得在协程或线程池线程上调用。
- 画面自动化任务运行时：`ES_CONTINUOUS | ES_SYSTEM_REQUIRED | ES_DISPLAY_REQUIRED`（截图依赖模拟器持续渲染）。纯网络任务（刷开局的协议阶段）：`ES_CONTINUOUS | ES_SYSTEM_REQUIRED`。任务结束（任何原因）时清除为 `ES_CONTINUOUS`。
- 单测：用假的 kernel32 接口验证调用线程始终是同一个，并验证开始、结束、异常结束三种情况都会清除状态。

## 10. 测试

- 数据目录解析的全部分支（命令行参数、portable、LOCALAPPDATA、不可写）。
- 原子写：并发写同一文件；重试路径（假文件系统注入 `AccessDeniedException`）；崩溃后的遗留 tmp 清理。
- prefs 垫片：类型化读写、`commit` / `apply`、同名同实例、损坏改名。
- Room：上游 androidTest 的桌面移植（SPEC-00 §11）；identityHash 对照；损坏库备份。
- DPAPI：Windows CI 上实测往返、错误 entropy 失败、篡改密文失败；替身与上游 `AndroidKeystoreCredentialStoreTest` / `AndroidKeystoreSdkSessionStoreTest` 的断言对齐。
- 国服数据库：MockWebServer + 合成 SQLite 文件，覆盖校验失败、安装占用（暂存）、重启后安装。
