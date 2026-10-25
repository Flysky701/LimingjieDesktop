# 桌面功能与行为对照

更新：2026-10-25。基线为上游 ui/LandosolToolboxApp.kt 及其页面。完整 app 已编译，真实 Compose 窗口代表性检查已完成，见 [UI-1](../reports/UI-1.md)。源码接线与界面检查不替代真实游戏验证；设备及游戏实测以 FINAL_REPORT 为准。

桌面源码位于 desktop/app/src/main/kotlin/com/landosol/toolbox/desktop/app/ui/。

2026-10-25 体验优化补充：当前仅完成 [UX-0 / UX-1 设计确认稿](../ux/DESIGN-REVIEW.md)，下面入口仍描述正式程序现状。拟合并为运行、账号、策略、设备、记录、设置六项；待实际接线后逐行更新可达路径，不用 HTML 示例替代功能对照验证。

| 功能组 | 桌面入口与接线 | 保留的行为 / 当前验证 |
|---|---|---|
| 外壳 | DesktopApp：总览、黎明界任务、账号库、策略与编组、设备、日志与诊断、设置与环境 | 默认总览；进程内保留当前页；桌面导航重设计。会战页依上游边界不接入 |
| 当前开局 | TasksPage → labyrinthController.checkStatus | 使用当前账号按需登录、读取及原结果；不切换游戏账号；忙碌时不能启动另一任务 |
| 批量目标与刷开局 | 任务页「配置目标与执行」打开完整 LabyrinthScreen | 公会目标、次数/目标模式、刷取配置、难度/路线/Boss、刷到出、彻底撤退确认、首轮复用和结果全部保留 |
| 刷取启停 | 完整表单 → start(retreatConfirmed=true,guildIdOverride) / stop | 确认由原表单执行；Android 通知权限不适用；刷取仅停止、不新增暂停 |
| 画面任务 | 明显展示「只读识别」「执行入口流程」→ graph.startRecognition(dryRun) | graph 检查设备、屏幕、OCR、触控；只读无需触控。停止走 stopAll 并等待释放 |
| 批量执行 | 完整表单 → startLabyrinthAutoRun / stopLabyrinthAutoRun | 原 batch、会话重置、终态与 checkpoint；starting/stopping 期间互斥 |
| 账号 | AccountsPage 列表与编辑区 → 原 AccountViewModel 全部增改选删/消息方法 | 新增无默认服务器；渠道字段、换服务器必填密码、编辑留空不改密码、UID、删除确认、保存/取消均保留。任务忙碌时禁用新增、字段/服务器修改、保存和删除确认，取消仍可用。假账号增改取消删除与空表单校验已实看 |
| 设备画像 | 账号页顶部与设备页 | 当前/上次连接设备名与时间；无快照提示登录前置；android_id 仅末4位 |
| 验证码 | shared GeetestCaptchaDialog + LoopbackCaptchaHost | 原 controller solved/error/cancel；浏览器再次打开、复制、失败提示；回环4项测试通过，真实 Geetest 未验证 |
| 策略 | StrategiesPage → 完整 LabyrinthStrategySettingsScreen | 评分、选人、Boss、失败处理、遗物、专武、商店、事件、响应参数；draft/范围/默认恢复/未保存离开确认、防重入、保存异常、取消异常传播 |
| 编组与评分 | 策略内部 → LabyrinthOpeningRosterScreen / LabyrinthRoleRatingsScreen | 原排序/筛选、头像搜索、0–100评分、恢复原评分、保存/取消；graph.loadLabyrinthRoleRatings。361角色及头像可显示；评分保存重启保留；空槽校验、禁存、放弃草稿均实看 |
| 设备 | EnvironmentPages.kt / DevicesPage | 检测/手动地址、连接/断开、ADB选择下载、测速、触控模式、重连、非游戏前台中心点击测试、画像与客户端；未执行真实操作 |
| 设置 | SettingsPage | 设备/截图/尺寸/触控/屏幕/前台/OCR/DPAPI/目录、代理/直连、用户手动网络测试、关闭行为、库更新、停止截图、关于与向导。游戏内动画确认仅提醒 |
| 日志/预览 | TaskRail、Preview、DiagnosticsPage | 2,000条用户事件、复制/清空；默认折叠预览、独立像素副本和框、≤2fps。窄窗口收侧栏，日志页仍可用 |
| 诊断 | DiagnosticsPage → 原 /logs.zip | 面板、原ZIP入口/文件名、用户选保存位置、日志目录和崩溃记录；不上传 |
| 任务控制 | 底栏与 Main 托盘 | desktopNotification 桥接和 rerollStatusText；暂停/继续经 coordinator；停止归 graph，释放完成前保持禁用 |
| 启动/关闭 | SetupWizard、Main | 上游风险原文、模拟器准备、可跳过连接、只读/实际操作区别；窗口/托盘/关闭确认/记住选择/退出释放 |

Android 通知、MediaProjection、无障碍和悬浮窗权限由设备/截图/触控前置替代；账号凭据说明改为 Windows 当前用户加密。复杂表单直接复用上游，少量上游诊断阶段英文保留原输出，不改变机器状态与错误字符串。

已做源码盘点、WS-DE定向检查及共享桥接测试。2026-10-25 用独立临时数据目录检查默认1280×800与最小1100×700布局、全部导航、完整任务表单、账号校验与增改取消删除、策略保存/取消、未连接设备提示。界面检查未连接ADB、未发游戏登录请求；验证码、触控与游戏流程由各自实测记录说明。
