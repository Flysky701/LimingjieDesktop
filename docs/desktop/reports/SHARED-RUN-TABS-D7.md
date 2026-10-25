# D7 共享数据与运行页标签交付

日期：2026-10-26。分支：`codex/shared-run-tabs`。版本：`1.0.18+desktop.7`。

## 已实现

- 一个窗口、一套全局导航。账号、策略、设备、记录、设置共用；只有运行页按设备会话切换。
- 主数据服务唯一保存账号、凭据、账号配置、全局策略和汇总记录。设备进程保留运行缓存，凭据及 SDK 会话只在该进程内存中使用。
- 连接设备后绑定共享账号；切换标签只切换视图和预览订阅。开始、暂停、停止、快捷键针对当前运行页；停止全部单独保留。
- 同一模拟器的 ADB 地址按本次启动身份合并。同一账号先取得账号 ID 占用锁，正常登录后再取得真实 UID 占用锁；不要求用户先手工读取开局。
- 运行中的策略固定，下一次启动取最新全局策略。新运行目录首次继承 D5 已保存的任务目标与任务选择，以后保留自己的任务配置。
- 同一已核实启动身份下，重连和重开助手复用运行目录、配置及绑定。模拟器重启后需要重新确认绑定；不单凭旧地址自动绑定账号。
- 记录统一汇总，可按设备和账号筛选。关闭运行标签时确认停止；其他设备继续运行。

## 验证

先完成单设备检查，再进行共享数据、并行隔离和真实打包进程检查。全部使用临时数据、模拟设备或既有图片素材，未登录真实游戏、未控制用户的模拟器。

| 阶段 | 定向检查 | 结果 |
| --- | --- | --- |
| 单设备 | DesktopGraphIntegrationTest 5、GraphControlServiceTest 4、SharedSupervisorModelTest.phase1 5、RunHomeUiTest 1、RunConsoleUiTest 1 | 16 项通过 |
| 共享与并行 | SharedDataIntegrationTest 2、SharedSupervisorModelTest.phase2 1、SharedRunTabsUiTest 3、WorkerTransportTest 4、WorkerLifecycleTest 4、RuntimeDeviceIdentityTest 3 | 17 项通过 |
| 最后修正 | 同步任务忙碌状态、配置保存顺序相关的 Graph / Service / SharedData 11 项；更新后的运行页截图用例 1 项 | 12 项通过（相关重跑） |
| 打包 | packagePortableZip，包括既有运行依赖许可证检查 | 通过 |
| 真实打包程序 | SharedPackagedRuntimeTest：先一台运行进程绑定、保存，再加入第二台；共用账号/策略、各自配置、关闭隔离、无持久凭据、目录锁释放 | 1 项通过，0 跳过 |

共 34 个不同的定向用例通过。未运行全仓回归或 Android 全量构建。界面截图：`desktop/app/build/reports/shared-run-tabs.png`，为离线示例数据。

## 保留与产物

- D5 稳定程序：`D:/Project/LimingjieDesktop/desktop/app/build/acceptance-desktop5/compose/binaries/main-release/app/LimingjieAssistant/LimingjieAssistant.exe`。未覆盖或启动。
- D6 原实现保留于 `codex/multi-instance-tabs-d6-preserved`（`3d449a1`），原工作区和数据未迁移。
- D7 验收程序：本工作区 `desktop/app/build/compose/binaries/main-release/app/LimingjieAssistant/LimingjieAssistant.exe`。
- D7 ZIP：本工作区 `desktop/app/build/dist/LimingjieAssistant-desktop-1.0.18+desktop.7-win-x64.zip`。
- 验收程序默认读取既有主数据目录；ZIP 中的 `portable.flag` 使用其便携数据目录。没有自动导入 D6 各 profile 的账号。

当前已达到离线验收就绪。真实游戏中的单设备验收、随后两台模拟器同时执行任务尚未进行；当前用户测试程序没有被关闭或切换。
