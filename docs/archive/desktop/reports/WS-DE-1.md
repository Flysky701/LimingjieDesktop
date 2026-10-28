# WS-D/E 平台与协议辅助实现

日期：2026-10-25。基线：M1 `321a3f1`。范围为平台模块、app 的 storage/captcha/cndb 适配器，未改动 shared 契约或上游业务代码。

## 已实现与装配入口

- `DataDirectories.resolve` 按命令行、portable、LOCALAPPDATA/XDG 顺序解析；`ensureWritable` 保留明确路径错误；取得 `InstanceLock.acquire` 后调用 `cleanTemporaryFiles`。
- `DesktopSettingsStore.state/save/update` 保存桌面设置、窗口位置和引导状态，解析失败改名保留并通知。`FileDeviceIdentityStore` 保存当前与逐设备快照；文件名用设备键 SHA-256 防止串口中的非法文件名字符，快照内部保留完整设备键。
- `WindowsSecretProtector` 注入 shared bindings；底层为当前 Windows 用户 DPAPI、`CRYPTPROTECT_UI_FORBIDDEN`，不使用机器作用域。非 Windows 拒绝凭据存储。解密异常转换为 shared `SecretUnavailableException`（`IllegalArgumentException` 子类），沿 `AccountViewModel.safeMessage` 显示中文原因。
- `DatabaseRecovery.open` 在 Room 打开前用作用域明确的只读连接执行 `quick_check`，只对确认损坏备份 db/wal/shm 后重建；备份失败回滚已移动文件并停止打开。先检测可避免 Room 首次打开坏库后 Windows 文件句柄仍被占用。调用方必须将 `onRecovered` 作为显著提示。
- `PowerSaveController.setMode` 所有设置/清理都在同一专用常驻线程；`NETWORK` 保持系统、`VISUAL` 同时保持显示、`IDLE`/close/设置异常清除。`verifyNativeSymbol` 可用于启动预检。
- `NetworkProxyController` 必须先于网关创建；安装可变但稳定的 ProxySelector，从而上游已创建的 OkHttp 客户端捕获同一选择器后，后续请求仍跟随系统/直连切换。close 恢复原选择器。
- `LoopbackCaptchaHost` 实现 shared `CaptchaHost`；随机回环端口、192 位随机路径、10 分钟超时、Host/Origin 校验、8 KB 限制、一次性结果、Main 回调、浏览器回退均已完成。无令牌或账号日志。页面直接复用上游 GeetestPageBuilder，在首个 `<head>` 后注入桥。
- `DesktopLabyrinthCnDatabaseRepository` 复用上游 updater；候选在同目录生成。构造时先尝试安装 pending；保留 pending 数据及元数据直到安装和版本提交完成。占用后保存 pending 并提示下次启动生效；原数据库和已安装版本信息保持一致。

shared 已由 M1/装配负责人提供的 AtomicFiles、Context/prefs、EncryptedStore、Room/日志和画像工厂继续复用。平台模块不依赖 shared。

## 国服数据库迁移对照

| 上游步骤 | 桌面实现 |
|---|---|
| SQLite 文件头与长度 | 同样的 16 字节头和中文错误 |
| OPEN_READONLY / NO_LOCALIZED_COLLATORS | BundledSQLiteDriver 的 SQLITE_OPEN_READONLY；bundled SQLite 无 Android 本地化排序器 |
| PRAGMA quick_check | 首行必须为 `ok`，同样错误 |
| sqlite_master 表枚举 | 同样参数化查询、排除 sqlite_%、排序、标识符筛选 |
| PRAGMA table_info | 同样 name/type/pk 提取和安全标识符筛选 |
| 逻辑表识别 | 原样使用命名 resolver；失败后原样使用语义 resolver |
| 主键探测 | 单整数主键、SELECT DISTINCT、候选分组 400、绑定 long |
| 引用值探测 | 同样整数列投影、按主键查询、忽略 NULL、范围过滤、分组 400 |
| 安装 | 同样先备份再原子替换；Windows 文件占用增加 pending 回退 |
| 下载与版本 | 同样 URL、文件/目录名、最小 1,000,000 字节；常量漂移测试 |

上游结果类型没有 Deferred，因此 pending 状态返回 `Failed(PENDING_MESSAGE)` 并通知装配层，禁止声称 Updated。`hasPendingInstall()` 可用于 UI 区分提示；数据库继续使用旧版本。更新网络/校验失败仍由原 updater 输出。

初始 WS-DE 提交使用临时 `moveAtomic`；装配集成后已删除该函数，CN 安装与 pending 保存统一使用共享 `AtomicFiles.move`（5 次、100 ms、禁止非原子回退）。

## 定向验证

- `:platform:compileKotlin`、`:app:compileKotlin` 通过。
- `:platform:test` 7 项通过：目录优先级/不可写、锁、遗留临时文件清理、Windows 当前用户 DPAPI 往返/错误 entropy/篡改拒绝、防睡眠线程与异常清理、已捕获代理选择器切换。
- app 12 项通过（首次 11 项通过、修复 Room Windows 句柄问题后定向重跑 storage 的 3 项通过）：验证码 4、CN 数据库 5、存储 3。
- 数据库 fixture 使用本地合成 SQLite（命名列及全部散列列两种 schema），证明只读不改文件、错误哈希/结构保留旧库、文件占用暂存及重启安装、旧库备份、下载常量与最小长度。HTTP 使用 OkHttp 拦截器提供确定性响应，无真实网络；未增加 MockWebServer 依赖。
- 验证码测试以真实回环 HTTP 模拟浏览器，覆盖错误令牌/Host/Origin、超大正文、重复提交、超时、桥注入顺序、Main 回调、空结果和错误截断。

没有真实登录、真实 Geetest、模拟器操作、旧项目数据访问或线上数据库下载。以上为离线与本机平台验证，不代替集成启动、打包及实机验收。Windows DPAPI 测试使用临时合成文本，不包含用户凭据。
