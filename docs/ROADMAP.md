# 跨仓统一工程路线图

本文件是 `LimingjieDesktop` 与 `LimingjieNN` 的**唯一权威总路线图**。云端训练细则见 `LimingjieNN/TASK.md`，架构决策见 [DECISIONS.md](DECISIONS.md)，构建入口见 [DEVELOPMENT.md](DEVELOPMENT.md)。

## 1. 定位与分工

| 仓库 / 模块 | 职责定位 | 核心约束 |
| --- | --- | --- |
| **`LimingjieDesktop`** | 产品主干、桌面运行时、多 JVM 隔离装配、ONNX 宿主及总路线图源 | 保持多 JVM 隔离、实时前台/分辨率守卫、同账号租约、DPAPI、原子存储与 `upstream-drift.properties` SHA 锁 |
| **`LimingjieDesktop/android/`** | Android 与桌面共享的纯 Kotlin 业务与 [7 域事件内核](KERNEL_REFACTOR.md) | **保持零 ONNX 依赖**；通过策略 SPI（`LabyrinthBattleValueEstimator`）解耦规则与模型，同步评估 Android 影响 |
| **`LimingjieNN`** | 离线采样/训练、Path B 规划参考实现（Python）与轻量 ONNX 发布源 | 模型契约 `limingjie_twotower_onnx_v1`；锁库 `202611131613`；仅发布通过 S5 封存与对齐门禁的包 |

## 2. 当前基线

- **`LimingjieNN`（`main`）**：Round 1（S0–S5）已收官，发布 `models/s4_m2b_r1_seed43`（双塔 7.915 MiB、AUROC 0.9914、ECE 0.0085、CPU 512 候选 p95 53.54 ms、`max_abs_diff = 7.629e-6`，三档阈值 `≥0.70 / 0.50–0.70 / <0.50` 回退，遮蔽金币 Win Brier 仅 `+0.00003`；详见 `LimingjieNN/models/s4_m2b_r1_seed43/README.md`）。Python Path B（`route_dp` / `shop_dp` / `marginal_eval` / `run_env`）已成型并与 Kotlin 完成跨语言对齐；另保留 **20 CC** 算力储备。
- **`LimingjieDesktop`（单一主干 `main`）**：已合入 M0 路线图统一与分支收口、M1 视觉多信号冗余与无损事故现场环形快照、M2 便携包内置 `s4_m2b_r1_seed43` + α 币收支账本 + 实机 `ShadowBattleSample` 落盘闭环、M3 Path B 跨语言黄金数值对齐与 `replays.jsonl` 导出器，以及上游 PR #29（3/4/5 区商店重构、三档前排生存模式、EX/遗物模板、事件复用、1088 恢复）与 7 域事件内核。

## 3. 统一里程碑（M0 → M4）

### M0 · 路线图统一与主干收口（已完成）
- **M0-A / M0-B（已完成）**：确立本文件为唯一权威源，将 `refactor/event-kernel` 与 PR #29 语义整合至单一 `main`，并清理历史工作树与分支。

### M1 · 视觉方案全面审查、DEBUG 与冗余增强（离线改造已完成，待样本扩充与实机抽检）

| 审查域 | 涉及范围 | 核心改动与当前状态 |
| --- | --- | --- |
| **页面分类与状态过渡** | [LabyrinthEntryClassifier.kt](../android/app/src/main/java/com/landosol/toolbox/labyrinth/vision/LabyrinthEntryClassifier.kt)、[LabyrinthEntryFrameProcessor.kt](../android/app/src/main/java/com/landosol/toolbox/labyrinth/vision/LabyrinthEntryFrameProcessor.kt) | 已合入统一 `BATTLE_FAILED`/`BATTLE_RESULT` 仲裁、`GradientTemplateMatcher` 全黑护栏、作用域 `LINK_CHOICE` 加成、`ITEM_REWARD` 叠加优先与 `RUN_RESULT_LOGO` 关闭按钮护栏；`UNKNOWN` 帧不清空 `attemptsForState` |
| **地图节点与拓扑闭环** | [LabyrinthNodeClassifier.kt](../android/app/src/main/java/com/landosol/toolbox/labyrinth/node/LabyrinthNodeClassifier.kt)、[NodePositionMapper.kt](../android/app/src/main/java/com/landosol/toolbox/labyrinth/node/NodePositionMapper.kt)、[NodeTopologyMapper.kt](../android/app/src/main/java/com/landosol/toolbox/labyrinth/node/NodeTopologyMapper.kt)、[LabyrinthNodeActionPlanner.kt](../android/app/src/main/java/com/landosol/toolbox/labyrinth/node/LabyrinthNodeActionPlanner.kt) | 已合入 `FULL_COLUMN` 冲突列护栏、`>= 0.45` 最小绑定分、空帧/无点击 `scanMemo` 2 帧上限、`currentColumnProtectedRects` ≥2 列校准与 `REACHABLE_SUBSET` Y 带校核；待实机抽检确认 `FIX-06` |
| **角色 / 编组 / 奖励双通道** | [LabyrinthCharacterIconMatcher.kt](../android/app/src/main/java/com/landosol/toolbox/labyrinth/vision/LabyrinthCharacterIconMatcher.kt)、[LabyrinthCharacterGridDetector.kt](../android/app/src/main/java/com/landosol/toolbox/labyrinth/vision/LabyrinthCharacterGridDetector.kt)、[LabyrinthBattleTeamRecognition.kt](../android/app/src/main/java/com/landosol/toolbox/labyrinth/vision/LabyrinthBattleTeamRecognition.kt)、[AndroidLabyrinthRoleRewardNameResolver.kt](../android/app/src/main/java/com/landosol/toolbox/labyrinth/vision/AndroidLabyrinthRoleRewardNameResolver.kt) | 已修复同名换装截断、5s OCR 挂起恢复、355px 名牌锚框、Top-3 `iconCandidates` 与 1088 左/中槽位外推；保留属性角标 + 名称 OCR + 头像双通道互证 |
| **OCR 稳定性与全链路状态** | [OnnxOcrEngine.kt](../desktop/ocr/src/main/kotlin/com/landosol/toolbox/desktop/ocr/OnnxOcrEngine.kt)、[evaluation/README.md](../desktop/ocr/evaluation/README.md)、[AndroidLabyrinthShopCoinResolver.kt](../android/app/src/main/java/com/landosol/toolbox/labyrinth/vision/AndroidLabyrinthShopCoinResolver.kt)、[LabyrinthAlphaRupeeLedger.kt](../android/app/src/main/java/com/landosol/toolbox/labyrinth/LabyrinthAlphaRupeeLedger.kt) | 已补充 `OcrEngineTelemetry`、瞬时异常存活与 FB-14 宽行裁剪回退，双尺度商店金币二次确认及未知状态保护，并通过局内 α 币账本串联商店/事件；`:ocr:evaluate` 当前固定样本 `15/15` 全绿，待实机扩充至 ≥80 条 |
| **可观测性与离线 DEBUG** | [LabyrinthDebugDashboardServer.kt](../android/app/src/main/java/com/landosol/toolbox/labyrinth/debug/LabyrinthDebugDashboardServer.kt)、[DesktopAppGraph.kt](../desktop/app/src/main/kotlin/com/landosol/toolbox/desktop/app/DesktopAppGraph.kt) | 已上线 10 槽 / 30 MiB 无损 PNG 事故现场环形缓冲区、`/logs.zip` `incidents/` 导出、`ocrHealth`、商店/队伍/节点绑定实时诊断 JSON 与停止前终端帧发布 |

- **退出状态**：核心视觉链路冗余、无损事故现场导出与离线回归已合入并验证通过；待补充实机样本扩充 `:ocr:evaluate` 至 ≥80 条并完成实机抽检。

### M2 · Level 1 战斗模型端到端交付与实机闭环（离线交付已完成，待实机闭环）
- **内置模型分发**：`:app:packagePortableZip` 通过 `syncBundledBattleModel` 校核 SHA-256 并内置 `s4_m2b_r1_seed43` 到 `LimingjieAssistant/models/s4_m2b_r1_seed43`；保留 `-Dlimingjie.nn.bundle`、`LIMINGJIE_NN_BUNDLE` 与默认数据目录覆盖优先级及规则回退；`DesktopSelfTest` 新增 `battle-model` 启动推理自检项。
- **实机采样与账本接线**：[LabyrinthAlphaRupeeLedger.kt](../android/app/src/main/java/com/landosol/toolbox/labyrinth/LabyrinthAlphaRupeeLedger.kt) 与 [LabyrinthShadowBattleRecorder.kt](../android/app/src/main/java/com/landosol/toolbox/labyrinth/LabyrinthShadowBattleRecorder.kt) 已接入 [LabyrinthEntryRecognitionSession.kt](../android/app/src/main/java/com/landosol/toolbox/labyrinth/LabyrinthEntryRecognitionSession.kt)，于 `START_BATTLE` 捕获完整战斗上下文与账本余额并在 `BATTLE_RESULT`/`BATTLE_FAILED`/等待超时单次去重写入 `BATTLE_SAMPLE_OBSERVED`。
- **待实机核验项**：实机复测 `FIX-01`–`FIX-08`，并在真机核实 `retire()` 扣票与结算时点、老虎机 `23161/33161` 后续是否净扣本金、事件战与 `50002` 免死的结算计数口径。

### M3 · Path B 全局高分规划深化与跨语言对齐（离线对齐与导出已完成，待实机回放验证）
- **算法跨语言对齐**：[Scoring.kt](../android/app/src/main/java/com/landosol/toolbox/labyrinth/kernel/planning/Scoring.kt)、[ShopDp.kt](../android/app/src/main/java/com/landosol/toolbox/labyrinth/kernel/planning/ShopDp.kt)、[RouteDp.kt](../android/app/src/main/java/com/landosol/toolbox/labyrinth/kernel/planning/RouteDp.kt)、[MarginalEval.kt](../android/app/src/main/java/com/landosol/toolbox/labyrinth/kernel/planning/MarginalEval.kt)、[LabyrinthRunPlanningContext.kt](../android/app/src/main/java/com/landosol/toolbox/labyrinth/kernel/planning/LabyrinthRunPlanningContext.kt) 与 [LabyrinthShopPolicy.kt](../android/app/src/main/java/com/landosol/toolbox/labyrinth/LabyrinthShopPolicy.kt) 已完成与 `LimingjieNN` Python 参考实现的公式/策略对齐（支持折扣上限、`RELIC_4STAR`、`ShopPlanEvaluation`、`BattleExam.isEx/expectedTimeSec`、路线 `WalletModel`、`cursor` 列过滤），并由 [CrossLanguagePathBParityTest.kt](../android/app/src/test/java/com/landosol/toolbox/labyrinth/kernel/planning/CrossLanguagePathBParityTest.kt) 对照 Python 黄金向量以 `1e-9` 精度验证；Android 保持零 ONNX 依赖。
- **匿名回放导出闭环**：[LabyrinthReplayExporter.kt](../android/app/src/main/java/com/landosol/toolbox/labyrinth/kernel/planning/LabyrinthReplayExporter.kt) 支持从路线与事件日志提取并原子导出匿名化 `replays.jsonl`，已通过 `LimingjieNN/src/labyrinth_nn/env/run_env.py`（`load_replays` + `compare_policies`）兼容验证，待实机跑局积累真实回放样本后闭环评测。

### M4 · 线上校准监控与 10 层演进
- 基于实机 `ShadowBattleSample` 持续审计线上 `p_win` 校准与高置信失败难例。
- 按需启用 `LimingjieNN` 预留 **20 CC**，应对国服 DB 更新、分布偏移或 10 层多队版本重训；新包通过 S5 封存门禁与 `BattleOnnxGoldenTest` 后直接滚动替换。

## 4. 验证档位
- 文档改动：核对链接与事实；常规/共享改动：`check` + `*BattleOnnxGoldenTest`；M1 OCR/视觉：`+ :ocr:evaluate` 与离线回放；M2/M3 交付：`check -Pfull`、`:app:packagePortableZip`、`:app:packagedRuntimeTest`、`--self-test --offline-startup` 及实机回归。
