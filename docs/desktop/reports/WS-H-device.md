# WS-H 设备与真实装配定向验证

日期：2026-10-25。真实 DesktopAppGraph、ONNX OCR、上游识别/策略、Room 与 prefs；只替换设备 I/O 和刷开局 LabyrinthApi。

`DesktopGraphIntegrationTest#realGraphRecognizesUpstreamFixturePausesStopsAndReleasesResources` 使用上游 `shop/shop.png`，分类为 SHOP。已通过实际帧投递、dryRun 零后端动作、暂停/继续、停止释放帧所有者、旧 session 拒绝、实时前台拒绝、断线停止且恢复不续跑、启动中停止；退出后回环/诊断端口可重新绑定、DB 文件可移动、DesktopBindings 释放，同 JVM 可重开。暂停验证等待 UI overlay 完成状态发布，避免第二次点击早于显示状态。该用例运行 4.731 秒（包括完整场景，不是仅退出耗时）。

`DesktopGraphIntegrationTest#upstreamRerollPersistsAndActionReplayUsesRealGraph` 使用上游 `LabyrinthRerollWorkflowTest` 的两轮地图：第一轮不匹配并撤退，第二轮成功；`maxAttempts=1`、`rerollUntilFound=true` 仍找到第 2 轮，断言上游原期望。真实 Room 保存路线和 TARGET 检查点；真实 prefs 保存刷开局配置。关闭重开 Graph 后三者均读回。随后同一真实 Graph 用 `shop/exit_confirmation.png` 跑 dryRun=false，自动派发且只派发 Tap(295,187) 到假设备；期望来自 `LabyrinthEntryFrameProcessor.kt:851` 的确认锚框及 `LabyrinthEntryRecognitionSession.kt:5771`，不是测试手工提交动作。

注意 G3-7 文档所写“检查点写入 prefs”与当前上游不符：当前刷开局 checkpoint 使用 Room，prefs 使用 AndroidLabyrinthRerollSettingsStore；测试遵循真实实现，没有改业务存储。

验证命令：`:app:test --tests '*DesktopGraphIntegrationTest'` 首次因真实 WebP 不能解码失败，主线程 6f2052f 修复后识别成功；暂停等待改为 UI 状态后第一项通过。第二项首次使用上游单元测试的虚拟 guildId=101，被真实设置校验正确拒绝；改用有效 guildId=1 后，定向命令 `:app:test --tests '*DesktopGraphIntegrationTest.upstreamRerollPersistsAndActionReplayUsesRealGraph'` exit 0。没有重复已通过场景。

范围限制：两项是最短必要集成，不宣称完整 G3/G6 通过；完整 12 次重连、长时 soak、真实 MaaTouch/模拟器行为另按 L1/L2 记录。设备层 FakeAdb 和进程测试证据见 WS-B-1。

只读环境盘点：MuMu 安装可用，ADB 36.0.0 查询 exit 0、在线设备为空；MuMuManager info -v all 显示 Android 12 的实例 0/1 和 Android 15 的实例 3 均关闭。未读取账号数据；ADB 查询自行启动本地守护进程。随后是否启动及 L1 结果单独记录。

## 停止与退出资源补充（2026-10-25）

针对 G3-4/G3-8 追加到原首项用例的断言已通过：运行后 Windows 原生防睡眠控制器处于 VISUAL，停止后回到 IDLE；识别/重置及截图 scope 的子 Job 数归零；停止后跨两个截图周期没有继续抓帧。退出时 graph 所有自有 scope 均完成，截图、OCR、防睡眠、验证码和诊断的新增专用线程全部消失，原有端口、数据库移动、设备 close 和绑定释放断言仍通过。

此次完整首项用例 5.351 秒，**shutdown 连同等待自有 worker 退出为 35ms**，满足本例 ≤5 秒；结果为 `graphJobs=0 ownedThreads=0 power=IDLE`。证据见 `../evidence/final-tests/graph-lifecycle.xml`。这是实际测试耗时，不声称异常路径的全局强制退出上限。生产 `shutdown` 的 10 秒只限等待任务停止，随后才逐项关闭资源。

任务停止时保持设备连接，截图 executor 的闲置线程随 graph 保留供重启使用，实际截图 Job 已结束；设备触控连接由 DeviceService 持有，退出时关闭。此处不将“闲置连接线程”称为已销毁，也不将假 DeviceService 的 close 替代 MaaTouch kill 的设备层证据。G3-11 逐行线程断言仍不是本用例的覆盖范围。
