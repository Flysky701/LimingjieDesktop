# 桌面工程路线图

`LimingjieDesktop` 是独立的 Windows 原生桌面项目。`:core` 直接构建桌面 Kotlin/JVM 业务，Android 垫片、`android/` 源码树与上游自动同步均已退役。桌面应用由 `:app` 装配，设备接入、OCR 和 Windows 平台能力分别由 `:device`、`:ocr` 和 `:platform` 提供。每台设备使用独立的 worker JVM，模拟器只运行游戏。

## 已完成

- 配置分为环境与界面、策略、凭据三个域。前两个域分别通过 `AtomicConfigStore<T>` 保存到 `settings/app.json` 和 `settings/strategies.json`，凭据由独立的 Windows DPAPI 存储管理。参见 [架构与实现决策](DECISIONS.md)。
- 迷宫七个业务域统一实现 `KernelDomainModule`，由 `LabyrinthKernelBundle` 装配。总控的 `ControlModelCodec` 为配置和设备画像提供生成式序列化快路径，沿用既有快照格式。参见 [迷宫事件内核](KERNEL_REFACTOR.md)。
- `AppLogger`、`LogRedactor`、`RollingFileLogSink` 和 `RunTelemetryBus` 统一进程日志与用户控制台遥测。`LabyrinthDiagnosticsRecorder` 从设备进程直接导出诊断 ZIP，日志、内核事件和事故现场 PNG 元数据通过 `traceId` 关联。参见 [日志与诊断](DEVELOPMENT.md#日志与诊断)。
- 页面、地图节点、角色奖励和金币识别采用多信号互证。低置信或冲突场景可保留无损原图和状态快照，供离线复查。参见 [已知限制与待办](KNOWN_LIMITATIONS.md)。
- 便携包内置战斗 ONNX 模型，禁用或加载失败时回退到规则评分。局内金币账本和战斗样本记录已接入会话。Path B 的折扣、路线、商店和边际估值逻辑已与 Python 参考实现完成离线数值对齐，可导出匿名回放。参见 [跨语言对齐用例](../desktop/core/src/test/kotlin/com/landosol/toolbox/labyrinth/kernel/planning/CrossLanguagePathBParityTest.kt)。

## 待验证与后续

- 扩充角色奖励、边界帧和商店 OCR 的实机样本，将固定评估集扩充至至少 80 条，并抽检地图节点绑定与视觉回退。参见 [OCR 评估](../desktop/ocr/evaluation/README.md)。
- 用实机回放核对退场扣票与结算时点、老虎机本金、事件战与免死计数，持续校准战斗预测和高置信失败难例。
- `LimingjieNN` 负责离线训练、Path B 参考实现与模型发布。按需为新数据库、分布偏移或十层多队玩法重训，发布前校验模型文件并运行桌面黄金用例。参见 [历史来源与维护边界](UPSTREAM.md)。

日常跨模块验证用快档 `check`，扩大常规范围用 `-Pfull`。按影响使用 `-PincludePackagedRuntime` 和 `-PincludeOcrEval` 纳入打包进程验证和 OCR 评估。完整命令见 [桌面开发与构建](DEVELOPMENT.md)。
