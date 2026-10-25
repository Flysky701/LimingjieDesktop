# FALLBACKS.md —— 回退与重试登记

**规则**（AGENTS.md §3）：代码中任何重试、回退、降级、放宽超时都必须在这里有一条，否则视为违规。每条必须写明触发条件、次数或时间上限、用户可见信号、对应的 EDGE 编号、实现位置和测试。
编号用 `FB-xx`（与 EDGE_CASES 的 F 组编号区分）。作废的编号保留，不复用。子代理只能修改自己 WS 的小节。

| 列 | 含义 |
|---|---|
| 触发 | 精确的触发条件（错误类型、输出特征） |
| 行为 | 回退做什么 |
| 上限 | 次数、间隔、总时长 |
| 用户可见 | UI 文案或日志级别 |
| EDGE | 对应边界情况 |
| 位置 / 测试 | 实现文件、测试名（实现后填写） |

## WS-A

| ID | 触发 | 行为 | 上限 | 用户可见 | EDGE | 位置 / 测试 |
|---|---|---|---|---|---|---|
| FB-04 | 原子替换时遇到 `AccessDeniedException` / `FileSystemException`（Windows 杀软、索引服务短暂占用） | 重试 `Files.move` | 5 次，每次间隔 100 ms | 最终失败：prefs `commit()` 返回 `false` 并记 `W` 日志；其他调用方抛出带路径的异常，由调用方按自身语义显示 | F-03 | shared/AtomicFiles.kt；ShimTest#prefsTypesPersistenceAndCorruptionBackup 验证写入；真实杀软锁未诱发，L1-16 |

## WS-B

| ID | 触发 | 行为 | 上限 | 用户可见 | EDGE | 位置 / 测试 |
|---|---|---|---|---|---|---|
| FB-01 | 连接状态进入 Lost（判据见 SPEC-10 §3） | 自动重连；**正在运行的任务不续跑** | 每 5 s 一次，最多 12 次 | 顶栏「重连中（第 n/12 次）」；失败后「连接已断开：…，请检查模拟器后点击重新连接」 | A-06 | device/AdbDeviceService.kt；AdbDeviceServiceTest#fiveFailedCapturesNotifyLostBeforeReconnectAndNeverResumeTasks；L1-11 待实机 |
| FB-02 | 连接 Ready 后选择截图模式 | Raw / PNG 各取 5 帧，选 p50 更低且解码成功的一种 | 只在连接时执行一次，运行中不切换 | 设备页显示所选模式和耗时 | B 组（测速） | device/ScreenCapturer.kt；ScreenAndParserTest#benchmarkRejectsAnyFailedModeAndUsesSeparateChannel |
| FB-03 | `uiautomator dump` 输出 `ERROR: could not get idle state` | 重新 dump | 最多 3 次，每次间隔 500 ms | 仍失败：返回空列表，记 `W` 日志，UI 显示「无法读取渠道登录界面」 | D-09 | device/AdbDeviceService.kt#uiTree；AdbDeviceServiceTest#uiAutomatorRetriesThreeTimesThenWarnsAndCanRecover（首次 + 最多3次重试） |
| FB-06 | adb 命令报告 server 未运行 / 连接被拒 | 执行一次 `adb start-server` 后重试原命令一次 | 1 次 | 仍失败：设备页显示原始错误与处理建议 | A-04 | device/ProcessAdbExecutor.kt；ProcessAdbExecutorTest#daemonStartsOnlyOnceAndVersionConflictNeverRestarts |

## WS-C

| ID | 触发 | 行为 | 上限 | 用户可见 | EDGE | 位置 / 测试 |
|---|---|---|---|---|---|---|
| FB-13 | OCR 工作队列已满（容量 64） | 新请求立即回调 `null`（与引擎不可用时相同的上游语义） | — | `W` 日志（每分钟最多一条汇总） | E-04 | ocr/OnnxOcrEngine.kt；OnnxOcrEngineTest loading queues up to 64 requests and rejects only excess request |

## WS-D

| ID | 触发 | 行为 | 上限 | 用户可见 | EDGE | 位置 / 测试 |
|---|---|---|---|---|---|---|
| FB-07 | prefs 文件或 `settings/desktop.json` 无法解析；Room 报 `SQLITE_CORRUPT` / `SQLITE_NOTADB` | 改名备份（数据库整体移入 `databases/corrupt-<时间>/`）后用默认值或空库继续 | 每个文件每次启动 1 次 | 启动后显著提示被重置的内容和备份位置；数据库损坏时阻断提示 | F-05 | shared/DesktopContext.kt、platform/DesktopSettings.kt、app/adapters/storage/DatabaseRecovery.kt；ShimTest、DesktopStorageTest 的损坏保留用例 |
| FB-08 | 安装国服数据库时目标文件被占用，FB-04 重试后仍失败 | 把候选文件暂存为 `master_cn.pending.db`，下次启动、任何读取之前安装 | 每次启动 1 次 | 「数据库更新已下载，将在下次启动时生效」 | F-09 | app/adapters/cndb/DesktopLabyrinthCnDatabaseRepository.kt；DesktopCnDatabaseTest#lockedInstallIsPendingUntilNextStartupAndKeepsMetadataHonest（注入锁定） |
| FB-12 | DPAPI 解密失败 | 不回退：抛出带说明的异常，引导用户重新填写密码 | — | 「凭据无法解密：数据目录可能来自其他电脑或 Windows 用户，请重新填写该账号的密码」 | F-08 | platform/WindowsDpapi.kt、app/adapters/storage/DesktopStorage.kt#WindowsSecretProtector；PlatformTest#dpapiProtectsCurrentUserAndRejectsWrongEntropyOrTampering |

## WS-E

| ID | 触发 | 行为 | 上限 | 用户可见 | EDGE | 位置 / 测试 |
|---|---|---|---|---|---|---|
| FB-05 | `Desktop.browse` 不可用或失败，且 `rundll32 url.dll,FileProtocolHandler` 也失败 | 对话框直接显示可复制的链接和说明 | — | 「无法自动打开浏览器，请复制下面的链接到浏览器中打开」 | G-09 | platform/BrowserLauncher.kt、shared GeetestCaptchaDialog；可复制链接接线已实现，系统浏览器失败分支待L3-01 |
| FB-11 | 模拟器 `settings get secure android_id` 为空或为 `null` | 使用协议安装标识 `installId`（与上游的回落规则一致） | — | 账号页的画像来源显示「android_id 不可用，已使用安装标识」 | G-03 | shared/protocol/bilibili/AndroidBilibiliSdkRequestProfileFactory.kt；DeviceIdentityReaderTest 与设备空android_id用例；协议字段漂移检查 |

## WS-F

（暂无）

## WS-G

（暂无。CI 中**不得**为不稳定的步骤加重试；需要时先登记。）

## WS-H

| ID | 触发 | 行为 | 上限 | 用户可见 | EDGE | 位置 / 测试 |
|---|---|---|---|---|---|---|
| FB-10 | 诊断面板端口 8765 已被占用（D-35 的探测） | 依次尝试 8766…8775 | 11 个端口 | 诊断页显示实际端口；全部被占用时显示「诊断面板端口 8765–8775 均被占用，面板未启动」 | H-11 | app/DesktopAppGraph.kt#debugPort；DesktopAdaptersTest 诊断socket释放；真实端口占用分支另见Graph集成记录 |

## 作废

| ID | 原内容 | 作废原因 |
|---|---|---|
| FB-09 | 连续黑帧时自动切换截图模式 | 与上游语义不符（黑帧照常投递，由上游识别处理）。改为只警告，见 SPEC-10 §4。编号保留不复用 |
