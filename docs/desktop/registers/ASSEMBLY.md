# 桌面装配记录

更新：2026-10-25。实际装配根为 desktop/app/src/main/kotlin/com/landosol/toolbox/desktop/app/DesktopAppGraph.kt，入口为同目录 Main.kt。上游 Application 的胶水迁入 graph，只替换平台端口，不重写识别/策略/协议。

Main 解析和检查目录、取得锁、清理临时文件、初始化日志/异常处理，经 DatabaseRecovery.open 打开 Room 后注入 graph。业务对象取用前安装 DesktopBindings（OCR、DPAPI、画像、验证码、日志）。graph 记录版本/客户端，设置渠道旋转策略，监听选中服务器、创建诊断面板、预热OCR、监听用户日志/防睡眠并更新国服库。测试可注入设备/OCR并禁用更新。

| 上游依赖/流程 | 桌面替换与保留语义 |
|---|---|
| 会话/动作 | 原 AutomationSessionManager、SessionBoundActionExecutor + DeviceActionBackend；共用会话仲裁，目标包解析失败使用 NO_GAME_CLIENT_SENTINEL |
| 客户端 | 原 GameClientResolver + 设备画像；账号服务器决定目标，缺包/未知/多客户端不猜测；连接及重连Ready刷新画像 |
| 渠道提示 | 原 ChannelLoginPromptAssistant + DesktopChannelPromptScreen；规则/节点/决策原样，来源改 UIAutomator、前台缓存和触控 |
| 账号 | 原 AccountRepository、两种 Keystore 上层 store、InMemoryGameSessionRegistry；加密底层改DPAPI；ViewModelStore持有，退出clear |
| 网关 | 原Factory/NativeLoginCoordinator；SDK缓存按完整画像判等，游戏网关键为服务器+画像；切换设备不复用旧画像 |
| 角色/策略 | 原 loader、AndroidLabyrinthStrategySettingsStore、评分catalog；Context供文件/资源，原策略快照与runtime，保存后下次生效 |
| 路线/运行 | 原Room store、DAO、路线/checkpoint校验 |
| 刷取 | LabyrinthController + Main scope；start冻结请求后startRerollHost调用startFromService；5小时55分期限和前后回调 |
| 大退/重启 | 原HardCloseLedger/HardCloser；HOME后force-stop、canKillOtherApps=true、一次性ledger、渠道分支、解析activity启动 |
| 入口识别 | 原RecognitionSession/FrameProcessor；策略provider、角色属性、奖励/事件/Boss决策、planner、trace、run/route store、终态监听和reroll requester |
| 识别前置 | Ready+触控+目标前台；画面任务检查awake/OCR，capture.start等待稳定画面且5秒超时取消 |
| 会话重置 | 原ExpiryTerminator/Tracker、CompositeSessionResetBackend、GameSessionResetWorkflow；分类与返回标题/重启/冷启动证明原样 |
| 批量 | 原CheckpointStore/BatchController/AutoRunWorkflow；goals、刷取→重置→执行→记录，识别终态交回batch，内部失败不抢跑重刷 |
| 首轮复用 | consumeReusableOpeningForBatch 只在外部批次启动消费一次，后续恢复原强制重刷 |
| 诊断 | 原DebugDashboardServer + desktop runtime/log；127.0.0.1和8765–8775；socket归graph；缓存帧归档；CPU/JVM/设备/截图指标 |
| 会战 | 保留惰性ClanBattleRecognitionSession，不接导航 |

taskStarting 在截图准备前置起；taskStopping 在取消/释放前同步置起，均计入busy。UI经LocalTaskBusy订阅。stopAll返回归属Job：取消并等待启动、停止batch/reset/recognition，capture.stopAndJoin完成后清锁。

退出最多等10秒业务停止，再清ViewModel、取消scope、关闭capture/device/MaaTouch/OCR/captcha/诊断socket与采样/Room/防睡眠/代理，Main最后释放实例锁。网络刷取不提供暂停。

验证：DesktopAdaptersTest 3项（截图线程与连续失败、OCR复制/EDT、诊断ZIP与socket）通过；补5秒超时与诊断字段后受影响2项通过；原识别/重置相关15项通过。WS-DE19项、WS-B30项见报告。完整Graph/FakeDevice/OCR集成、窗口与打包正在整合，最终以FINAL_REPORT为准。未验证真实游戏登录、渠道界面与批量循环。
