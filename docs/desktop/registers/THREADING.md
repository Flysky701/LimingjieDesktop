# 线程与资源所有权

更新：2026-10-25。依据实际实现；Main由kotlinx-coroutines-swing提供，为Swing EDT。

| 工作 | 实际线程/所有权 | 证据范围 |
|---|---|---|
| Compose/AccountViewModel/Controller | UI和控制器Main scope；ViewModel退出在Main清理 | 构造接线；全部UI线程暂无独立断言 |
| 识别/重置/自动执行 | graph的recognition/reset/autoRun scope为Default，统一异常处理 | 识别/重置相关测试；同步windows调用路径源码核查为后台动作 |
| 刷取宿主 | 原controller工作在Main；宿主计时/通知轮询在Default | 不修改刷取算法 |
| ADB | ProcessAdbExecutor用IO，二进制stdout与stderr分离 | WS-B进程/超时/触控测试 |
| ChannelPromptScreen.windows | 同步API，在后台Default中runBlocking(IO)读取UIAutomator | 不在EDT调用；尚无独立EDT心跳测试 |
| 前台 | DeviceService缓存/监视，getter只读缓存 | WS-B缓存过期与状态检查 |
| 截图 | DesktopCaptureService单线程dispatcher，实际名desktop-capture；source可挂起转IO，恢复后投递 | captureUsesOwnedThreadAndStopsAfterFiveFailures通过 |
| 捕获帧 | 新Bitmap交原CaptureFrameBus所有权协议，UI不持有此Bitmap | capture测试及源码 |
| 预览 | 500ms至多发布一次IntArray副本，UI另复制为BufferedImage/ImageBitmap | 源码；视觉/框位置待窗口检查 |
| OCR桥 | 同步复制ARGB后交OcrPort，Main回调；调用方可立即recycle | ocrBridgeCopiesPixelsAndReturnsOnSwingMain通过 |
| OCR推理 | OnnxOcrEngine单守护线程、容量64、初始化复用，close使请求完成并释放模型 | WS-C-1的17项测试；实际模型自检和质量评估分别记录 |
| Room | BundledSQLite+WAL，查询context IO；writer connection事务 | RoomCompatibilityTest事务/外键/旧版迁移；未重复M1检查 |
| 损坏恢复 | DatabaseRecovery整段IO；明确use范围只读检查，关闭后移动db/wal/shm | DesktopStorageTest通过，避免Windows坏库首次打开留句柄 |
| 加密 | suspend接口、IO+每实例Mutex；DPAPI在IO执行 | 本机合成DPAPI往返/entropy/篡改拒绝通过 |
| prefs/原子文件 | commit/apply均同步原子写；上游store调度保持；新增UI settings切IO | ShimTest持久化/损坏备份；并非Android异步apply |
| 日志 | DesktopLog同步锁内环与文件追加；UserLog StateFlow最近2000行 | 实际为同步追加，不声称异步刷盘；诊断导出检查 |
| 验证码 | 两个守护HTTP worker、守护超时调度器、Main回调；接受结果即注销 | 回环一次性/超时/EDT测试通过 |
| 防睡眠 | 专用常驻单线程executor统一设/清 | 假kernel32同线程与异常清理通过；真实Graph原生状态VISUAL→IDLE及退出线程释放通过 |
| 国服库 | databaseUpdateScope IO；仓库更新synchronized；同目录候选与原子替换 | 合成SQLite/拦截HTTP测试通过；公开44MB下载、真实SQLite占用暂存及新JVM恢复安装通过 |
| 诊断 | 上游面板守护线程；采样每5秒最多720条，desktop-runtime-diagnostics | socket关闭测试通过；采样线程无独立断言 |
| 退出 | 停止job归graph，stopAndJoin后清busy；shutdown等待停止上限10秒，随后关闭资源 | DesktopGraphIntegrationTest验证端口/数据库/绑定释放与同JVM重开；新增自有scope/线程归零断言，实测shutdown35ms；实际窗口正常退出 |

上游角色搜索/图标评分守护线程池保持原样。阻塞平台操作在IO/Default，Windows文件选择在EDT模态运行。未做全部动作、托盘回调或每个scope的逐线程断言，源码确认不冒充这些测试。
