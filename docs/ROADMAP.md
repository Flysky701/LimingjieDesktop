# 桌面工程路线图

`LimingjieDesktop` 是独立的 Windows 原生桌面项目，Android 兼容和按上游提交 SHA 锁定的自动同步已永久退役。本文记录桌面演进与 `LimingjieNN` 的模型对接计划，云端训练细则见模型仓的 `TASK.md`。参见 [架构决策](DECISIONS.md)、[构建入口](DEVELOPMENT.md)和[历史来源](UPSTREAM.md)。

## 1. 定位与分工

| 仓库 / 模块 | 职责定位 | 核心约束 |
| --- | --- | --- |
| **`LimingjieDesktop`** | `:app` 装配桌面运行时及战斗 ONNX 模型，`:device` 提供设备通道，`:ocr` 提供识别，`:platform` 提供 Windows 平台能力 | 保持多 JVM 设备隔离、实时前台与分辨率点击守卫、同账号/UID 租约、Windows DPAPI 与日志脱敏、原子存储及损坏备份 |
| **`:core`** | 桌面业务规则、持久化与 [7 域事件内核](KERNEL_REFACTOR.md) | 通过纯 Kotlin 策略接口 `LabyrinthBattleValueEstimator` 解耦规则与 ONNX 模型 |
| **`LimingjieNN`** | 离线采样与训练、Path B 规划参考实现（Python）与轻量 ONNX 发布源 | 模型契约 `limingjie_twotower_onnx_v1`，锁库 `202611131613`，仅发布通过 S5 封存与对齐门禁的包 |

## 2. 当前基线

- **`LimingjieNN`（`main`）**：Round 1（S0–S5）已收官，发布 `models/s4_m2b_r1_seed43`（双塔 7.915 MiB、AUROC 0.9914、ECE 0.0085、CPU 512 候选 p95 53.54 ms、`max_abs_diff = 7.629e-6`，三档阈值 `≥0.70 / 0.50–0.70 / <0.50` 回退，遮蔽金币 Win Brier 仅 `+0.00003`；详见 `LimingjieNN/models/s4_m2b_r1_seed43/README.md`）。Python Path B（`route_dp` / `shop_dp` / `marginal_eval` / `run_env`）已成型并与 Kotlin 完成跨语言对齐；另保留 **20 CC** 算力储备。
- **`LimingjieDesktop`（单一主干 `main`）**：已合入 M0 路线图统一与分支收口、M1 视觉多信号冗余与无损事故现场环形快照、M2 便携包内置 `s4_m2b_r1_seed43` + α 币收支账本 + 实机 `ShadowBattleSample` 落盘闭环、M3 Path B 跨语言黄金数值对齐与 `replays.jsonl` 导出器，以及上游 PR #29（3/4/5 区商店重构、三档前排生存模式、EX/遗物模板、事件复用、1088 恢复）与 7 域事件内核。

## 3. 统一里程碑（M0 → M4）

### M0 · 路线图统一与主干收口（已完成）
- **M0-A / M0-B（已完成）**：确立本文件为唯一权威源，将 `refactor/event-kernel` 与 PR #29 语义整合至单一 `main`，并清理历史工作树与分支。

### M1 · 视觉方案全面审查、DEBUG 与冗余增强（离线改造已完成，待样本扩充与实机抽检）

| 审查域 | 涉及范围 | 核心改动与当前状态 |
| --- | --- | --- |
| **页面分类与状态过渡** | [LabyrinthEntryClassifier.kt](../desktop/core/src/main/kotlin/com/landosol/toolbox/labyrinth/vision/LabyrinthEntryClassifier.kt)、[LabyrinthEntryFrameProcessor.kt](../desktop/core/src/main/kotlin/com/landosol/toolbox/labyrinth/vision/LabyrinthEntryFrameProcessor.kt) | 已合入统一 `BATTLE_FAILED`/`BATTLE_RESULT` 仲裁、`GradientTemplateMatcher` 全黑护栏、作用域 `LINK_CHOICE` 加成、`ITEM_REWARD` 叠加优先与 `RUN_RESULT_LOGO` 关闭按钮护栏。`UNKNOWN` 帧不清空 `attemptsForState` |
| **地图节点与拓扑闭环** | [LabyrinthNodeClassifier.kt](../desktop/core/src/main/kotlin/com/landosol/toolbox/labyrinth/node/LabyrinthNodeClassifier.kt)、[NodePositionMapper.kt](../desktop/core/src/main/kotlin/com/landosol/toolbox/labyrinth/node/NodePositionMapper.kt)、[NodeTopologyMapper.kt](../desktop/core/src/main/kotlin/com/landosol/toolbox/labyrinth/node/NodeTopologyMapper.kt)、[LabyrinthNodeActionPlanner.kt](../desktop/core/src/main/kotlin/com/landosol/toolbox/labyrinth/node/LabyrinthNodeActionPlanner.kt) | 已合入 `FULL_COLUMN` 冲突列护栏、`>= 0.45` 最小绑定分、空帧/无点击 `scanMemo` 2 帧上限、`currentColumnProtectedRects` ≥2 列校准与 `REACHABLE_SUBSET` Y 带校核。待实机抽检确认 `FIX-06` |
| **角色 / 编组 / 奖励双通道** | [LabyrinthCharacterIconMatcher.kt](../desktop/core/src/main/kotlin/com/landosol/toolbox/labyrinth/vision/LabyrinthCharacterIconMatcher.kt)、[LabyrinthCharacterGridDetector.kt](../desktop/core/src/main/kotlin/com/landosol/toolbox/labyrinth/vision/LabyrinthCharacterGridDetector.kt)、[LabyrinthBattleTeamRecognition.kt](../desktop/core/src/main/kotlin/com/landosol/toolbox/labyrinth/vision/LabyrinthBattleTeamRecognition.kt)、[角色奖励文字识别](../desktop/core/src/main/kotlin/com/landosol/toolbox/labyrinth/vision/LabyrinthRoleRewardTextIdentity.kt) | 已修复同名换装截断、5s OCR 挂起恢复、355px 名牌锚框、Top-3 `iconCandidates` 与 1088 左/中槽位外推。保留属性角标、名称 OCR 与头像互证 |
| **OCR 稳定性与全链路状态** | [OnnxOcrEngine.kt](../desktop/ocr/src/main/kotlin/com/landosol/toolbox/desktop/ocr/OnnxOcrEngine.kt)、[OCR 样本清单](../desktop/ocr/evaluation/manifest.json)、[LabyrinthAlphaRupeeLedger.kt](../desktop/core/src/main/kotlin/com/landosol/toolbox/labyrinth/LabyrinthAlphaRupeeLedger.kt) | 已补充 `OcrEngineTelemetry`、瞬时异常存活与 FB-14 宽行裁剪回退、双尺度商店金币二次确认及未知状态保护，并通过局内 α 币账本串联商店和事件。`:ocr:evaluate` 当前固定样本通过 `15/15`，待实机扩充至 ≥80 条 |
| **可观测性与离线 DEBUG** | [LabyrinthDebugDashboardServer.kt](../desktop/core/src/main/kotlin/com/landosol/toolbox/labyrinth/debug/LabyrinthDebugDashboardServer.kt)、[DesktopAppGraph.kt](../desktop/app/src/main/kotlin/com/landosol/toolbox/desktop/app/DesktopAppGraph.kt) | 已上线 10 槽 / 30 MiB 无损 PNG 事故现场环形缓冲区、`/logs.zip` `incidents/` 导出、`ocrHealth`、商店/队伍/节点绑定实时诊断 JSON 与停止前终端帧发布 |

- **退出状态**：核心视觉链路冗余、无损事故现场导出与离线回归已合入并验证通过；待补充实机样本扩充 `:ocr:evaluate` 至 ≥80 条并完成实机抽检。

### M2 · Level 1 战斗模型端到端交付与实机闭环（离线交付已完成，待实机闭环）
- **内置模型分发**：`:app:packagePortableZip` 通过 `syncBundledBattleModel` 校核 SHA-256 并内置 `s4_m2b_r1_seed43` 到 `LimingjieAssistant/models/s4_m2b_r1_seed43`；保留 `-Dlimingjie.nn.bundle`、`LIMINGJIE_NN_BUNDLE` 与默认数据目录覆盖优先级及规则回退；`DesktopSelfTest` 新增 `battle-model` 启动推理自检项。
- **实机采样与账本接线**的入口是 [LabyrinthEntryRecognitionSession.kt](../desktop/core/src/main/kotlin/com/landosol/toolbox/labyrinth/LabyrinthEntryRecognitionSession.kt)。会话于 `START_BATTLE` 捕获战斗上下文与账本余额，随后在 `BATTLE_RESULT`、`BATTLE_FAILED` 或等待超时时去重写入 `BATTLE_SAMPLE_OBSERVED`。实现见 [LabyrinthAlphaRupeeLedger.kt](../desktop/core/src/main/kotlin/com/landosol/toolbox/labyrinth/LabyrinthAlphaRupeeLedger.kt) 和 [LabyrinthShadowBattleRecorder.kt](../desktop/core/src/main/kotlin/com/landosol/toolbox/labyrinth/LabyrinthShadowBattleRecorder.kt)。
- **待实机核验项**：实机复测 `FIX-01`–`FIX-08`，并在真机核实 `retire()` 扣票与结算时点、老虎机 `23161/33161` 后续是否净扣本金、事件战与 `50002` 免死的结算计数口径。

### M3 · Path B 全局高分规划深化与跨语言对齐（离线对齐与导出已完成，待实机回放验证）
- **算法跨语言对齐**覆盖折扣上限、`RELIC_4STAR`、`ShopPlanEvaluation`、`BattleExam.isEx/expectedTimeSec`、路线 `WalletModel` 与 `cursor` 列过滤，由 [CrossLanguagePathBParityTest.kt](../desktop/core/src/test/kotlin/com/landosol/toolbox/labyrinth/kernel/planning/CrossLanguagePathBParityTest.kt) 对照 `LimingjieNN` 的 Python 黄金向量以 `1e-9` 精度验证。实现见 [Scoring.kt](../desktop/core/src/main/kotlin/com/landosol/toolbox/labyrinth/kernel/planning/Scoring.kt)、[ShopDp.kt](../desktop/core/src/main/kotlin/com/landosol/toolbox/labyrinth/kernel/planning/ShopDp.kt)、[RouteDp.kt](../desktop/core/src/main/kotlin/com/landosol/toolbox/labyrinth/kernel/planning/RouteDp.kt)、[MarginalEval.kt](../desktop/core/src/main/kotlin/com/landosol/toolbox/labyrinth/kernel/planning/MarginalEval.kt)、[LabyrinthRunPlanningContext.kt](../desktop/core/src/main/kotlin/com/landosol/toolbox/labyrinth/kernel/planning/LabyrinthRunPlanningContext.kt) 与 [LabyrinthShopPolicy.kt](../desktop/core/src/main/kotlin/com/landosol/toolbox/labyrinth/LabyrinthShopPolicy.kt)。
- **匿名回放导出闭环**由 [LabyrinthReplayExporter.kt](../desktop/core/src/main/kotlin/com/landosol/toolbox/labyrinth/kernel/planning/LabyrinthReplayExporter.kt) 从路线与事件日志提取并原子导出匿名化 `replays.jsonl`，已通过模型仓 `run_env.py` 的 `load_replays` 和 `compare_policies` 验证，待实机跑局积累回放样本后闭环评测。

### M4 · 线上校准监控与 10 层演进
- 基于实机 `ShadowBattleSample` 持续审计线上 `p_win` 校准与高置信失败难例。
- 按需启用 `LimingjieNN` 预留 **20 CC**，应对国服 DB 更新、分布偏移或 10 层多队版本重训；新包通过 S5 封存门禁与 `BattleOnnxGoldenTest` 后直接滚动替换。

## 4. 验证档位

- 文档改动核对链接和事实。常规跨模块改动运行 `check`，战斗模型变更按需验证 `BattleOnnxGoldenTest`。
- OCR 或视觉变更按影响运行离线回放和 `:ocr:evaluate`。需要纳入常规检查时用 `check -PincludeOcrEval`。
- `check -Pfull` 扩大常规测试范围。用 `:app:packagePortableZip` 生成分发包，按影响用 `:app:packagedRuntimeTest` 或 `check -PincludePackagedRuntime` 验证打包进程，必要时追加便携包自检及实机回归。完整命令见 [开发文档](DEVELOPMENT.md)。
