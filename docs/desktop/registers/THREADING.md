# THREADING.md —— 线程模型对照（D-20）

**规则**：上游代码隐含的线程假设，桌面必须逐条保持。每一行都要有一个**断言测试**（例如在回调里断言 `SwingUtilities.isEventDispatchThread()` 或线程名），测试名填进最后一列（G3-11）。上游代码原样运行、桌面没有介入的行，最后一列写「—（上游原样）」。
桌面 `Dispatchers.Main` = Swing EDT（`kotlinx-coroutines-swing`，Compose Desktop 默认）。`Dispatchers.Main.immediate` 在 EDT 上同样可用。
子代理只修改自己 WS 的小节；有疑问的行标「待确认」并说明。

## WS-A（垫片）

| 上游假设 | 上游位置 | 桌面对应 | 保证方式 | 断言测试 |
|---|---|---|---|---|
| `SharedPreferences.apply()` 异步、`commit()` 同步；上游调用方可能在 Main 上调用 | 各 `Android*Store` | 两者都同步原子写（D-21）；文件很小，EDT 上的耗时可接受 | 垫片实现 | `commit()` / `apply()` 返回时文件已落盘 |
| `android.util.Log` 可在任意线程调用 | 全局 | 日志器线程安全，异步刷盘 | 日志器实现 | 并发写入测试 |
| `withTransaction` 块内 DAO 调用在同一连接上 | `AccountRepository`、`LabyrinthRerollWorkflow`、`LabyrinthRunState` | `useWriterConnection` + `immediateTransaction` | 垫片实现 | 块内 Flow 不提前发射、回滚测试 |

## WS-B（设备）

| 上游假设 | 上游位置 | 桌面对应 | 保证方式 | 断言测试 |
|---|---|---|---|---|
| 截图帧在专用 `HandlerThread("landosol-capture")` 上产生并投递到 `CaptureFrameBus` | `MediaProjectionCaptureService.kt:119` | 专用截图线程（同名 `landosol-capture`） | `DesktopCaptureService` | 订阅者回调中断言线程名 |
| 显示变化监听在主线程 | `MediaProjectionCaptureService.kt:76` | 旋转与尺寸判定在截图线程内完成（帧尺寸即显示尺寸），结果经 `CaptureStateRegistry` 发布 | 桥接实现 | 旋转判定测试 |
| 无障碍手势回调经 `mainHandler` 回到主线程 | `LandosolAccessibilityService.kt:179/261/322` | 触控 actor 完成后，在调用方协程上下文中返回结果（挂起函数语义不变） | actor 实现 | 返回线程测试 |
| `ChannelPromptScreen.click` 的节点操作在主线程执行（`onMain`） | `AccessibilityChannelPromptScreen.kt:96` | uiautomator / adb 命令**不得**在 EDT 上执行；`click` 内部切到 IO | 适配器实现 | 在 EDT 上调用 `click` 时不阻塞 EDT 的测试 |
| `ChannelPromptScreen.windows()` 非挂起，在调用线程上同步执行 | 上游接口 | 同步执行 adb；WS-H 确认调用方不在 EDT（SPEC-10 §8） | 调用方核查 | 待确认 |
| `foregroundPackage()` 非挂起、需要立即返回 | `ChannelPromptScreen`、`GameClientHardCloser` | 读取 500 ms 轮询缓存，超过 2 s 返回 `null`（D-27） | 前台监视器 | 缓存过期测试 |

## WS-C（OCR）

| 上游假设 | 上游位置 | 桌面对应 | 保证方式 | 断言测试 |
|---|---|---|---|---|
| ML Kit 回调在主线程 | `AndroidChineseRoleNameOcr` | 回调派发到 `Dispatchers.Main` | 替身实现 | 回调中断言 EDT |
| 调用方在回调里 `recycle` 位图 | 各 resolver | 提交时同步复制像素 | 替身实现 | 提交后立即 recycle 的测试 |

## WS-D（持久化与平台）

| 上游假设 | 上游位置 | 桌面对应 | 保证方式 | 断言测试 |
|---|---|---|---|---|
| Room DAO 为 `suspend` / `Flow`，不在主线程做 I/O | `data/local/**` | Room JVM 使用自身的查询协程上下文 | Room 配置 | 在 EDT 上调用 DAO 不阻塞 |
| 加密存储使用 `Dispatchers.IO` + `Mutex` | `AndroidKeystoreEncryptedStore` | 替身保持相同 | 替身实现 | 并发读写测试 |
| 防睡眠状态绑定调用线程 | Windows `SetThreadExecutionState` | 专用常驻线程设置和清除 | 平台实现 | 假 kernel32 记录调用线程 |

## WS-F（界面）

| 上游假设 | 上游位置 | 桌面对应 | 保证方式 | 断言测试 |
|---|---|---|---|---|
| `LabyrinthController` 的作用域是 `Dispatchers.Main` | `LabyrinthController.kt:226` | EDT | 依赖 Swing Main | 状态更新线程测试 |
| 通知宿主和悬浮窗的作用域是 `Dispatchers.Main.immediate` | `AndroidAutomationNotificationHost.kt:60` | 任务栏 / 托盘适配器同样使用 `Main.immediate`；托盘（AWT `SystemTray`）回调先切回 EDT 再处理 | 适配器实现 | 托盘菜单回调线程测试 |
| `LabyrinthRuntimeDiagnostics` 使用自己的单线程调度器 | `LabyrinthRuntimeDiagnostics.kt:37` | 替身保持相同结构 | 替身实现 | 调度线程名测试 |

## WS-H（装配）

| 上游假设 | 上游位置 | 桌面对应 | 保证方式 | 断言测试 |
|---|---|---|---|---|
| 识别会话动作在 `Dispatchers.Default` 上执行 | `LabyrinthEntryRecognitionSession.kt:1148` | 不变 | — | —（上游原样） |
| 应用级作用域：数据库更新、账号服务器监听在 `Dispatchers.IO`；自动运行在 `Dispatchers.Default` | `LandosolToolboxApplication.kt:77-78/687` | `DesktopAppGraph` 中同样的作用域划分 | 装配 | 装配测试 |
| 后台刷取服务的作用域是 `Dispatchers.Main.immediate` | `LabyrinthRerollService.kt:39` | 任务宿主使用 `Main.immediate` | 装配 | 状态更新线程测试 |
| 诊断面板使用自己的守护线程 | `LabyrinthDebugDashboardServer.kt:87` | 不变 | — | —（上游原样） |
| 角色搜索、图标评分使用固定线程池 | `LabyrinthRoleDecisionPolicy.kt:35`、`LabyrinthCharacterIconMatcher.kt:29` | 不变；上游线程已设 `isDaemon = true`，不阻止 JVM 退出 | — | 退出测试中确认不阻止 JVM 退出 |
