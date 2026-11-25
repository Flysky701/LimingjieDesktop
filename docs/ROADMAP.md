# 跨仓统一工程路线图

本文件是 `LimingjieDesktop` 与 `LimingjieNN` 的**唯一权威总路线图**。云端训练细则见 `LimingjieNN/TASK.md`，架构决策见 [DECISIONS.md](DECISIONS.md)，构建入口见 [DEVELOPMENT.md](DEVELOPMENT.md)。

## 1. 定位与分工

| 仓库 / 模块 | 职责定位 | 核心约束 |
| --- | --- | --- |
| **`LimingjieDesktop`** | 产品主干、桌面运行时、多 JVM 隔离装配、ONNX 宿主及总路线图源 | 保持多 JVM 隔离、实时前台/分辨率守卫、同账号租约、DPAPI、原子存储与 `upstream-drift.properties` SHA 锁 |
| **`LimingjieDesktop/android/`** | Android 与桌面共享的纯 Kotlin 业务与 [7 域事件内核](KERNEL_REFACTOR.md) | **保持零 ONNX 依赖**；通过策略 SPI（`LabyrinthBattleValueEstimator`）解耦规则与模型，同步评估 Android 影响 |
| **`LimingjieNN`** | 离线采样/训练、Path B 规划参考实现（Python）与轻量 ONNX 发布源 | 模型契约 `limingjie_twotower_onnx_v1`；锁库 `202611131613`；仅发布通过 S5 封存与对齐门禁的包 |

## 2. 当前基线

- **`LimingjieNN`（`main` @ `91286d1` 后继文档对齐）**：Round 1（S0–S5）已收官，发布 `models/s4_m2b_r1_seed43`（双塔 7.915 MiB、AUROC 0.9914、ECE 0.0085、CPU 512 候选 p95 53.54 ms、`max_abs_diff = 7.629e-6`，三档阈值 `≥0.70 / 0.50–0.70 / <0.50` 回退，遮蔽金币 Win Brier 仅 `+0.00003`；详见 `LimingjieNN/models/s4_m2b_r1_seed43/README.md`）。Python Path B（`route_dp` / `shop_dp` / `marginal_eval` / `run_env`）已成型，Phase 5 待实机 `replays.jsonl`；另保留 **20 CC** 算力储备。
- **`LimingjieDesktop`（单一主干 `main`，`1.0.18+desktop.17`）**：已完成 M0 路线图统一与分支收口（历史分支已归档并清理冗余 worktree），集成 7 域事件内核、ADB 30s 恢复与 Raw 保留、编组确认防误点、失败重试卸载、上游 **PR #29**（3/4/5 区商店重构、三档前排生存模式、EX/遗物模板、事件复用、1088 恢复）、Level 1 双塔 ONNX 战斗引擎（Tower A 缓存、EX 映射、Boss 多队规划、状态 UI）及共享 `kernel/planning/` Path B 初步接线；`desktop.17` 便携包（`166,013,293` 字节，内置 JDK `21.0.12.1`、`msvcp140.dll 14.40.33810.0`）已通过 `--self-test --offline-startup` 10/10 项全绿自测。

## 3. 统一里程碑（M0 → M4）

### M0 · 路线图统一与主干收口（已完成）
- **M0-A（已完成）**：确立本文件为唯一权威源，同步对齐双仓工程文档。
- **M0-B（已完成）**：将 `refactor/event-kernel`（NN 战斗引擎与 Path B 接线）合流至最新 `main`，完成与 PR #29 三档前排、3/4/5 区商店与失败重试的语义整合，通过 `check`、`-Pfull :shared:test`、`BattleOnnxGoldenTest`、`:app:packagePortableZip` 与 `--self-test --offline-startup` 验收，并清理历史工作树与分支为单一 `main`。

### M1 · 视觉方案全面审查、DEBUG 与冗余增强（下一阶段核心专项）

| 审查域 | 涉及范围 | 核心问题与冗余改造 |
| --- | --- | --- |
| **页面分类与状态过渡** | [LabyrinthEntryClassifier.kt](../android/app/src/main/java/com/landosol/toolbox/labyrinth/vision/LabyrinthEntryClassifier.kt)、[LabyrinthEntryFrameProcessor.kt](../android/app/src/main/java/com/landosol/toolbox/labyrinth/vision/LabyrinthEntryFrameProcessor.kt) | 消除单按钮高阈值兜底与过场误判（`UNKNOWN`/`BATTLE_FAILED`）及缩放偏移；引入多锚点投票、辅助文本/色块佐证与邻近帧时序滞回滤波 |
| **地图节点与拓扑闭环** | [LabyrinthNodeClassifier.kt](../android/app/src/main/java/com/landosol/toolbox/labyrinth/node/LabyrinthNodeClassifier.kt)、[NodePositionMapper.kt](../android/app/src/main/java/com/landosol/toolbox/labyrinth/node/NodePositionMapper.kt)、[NodeTopologyMapper.kt](../android/app/src/main/java/com/landosol/toolbox/labyrinth/node/NodeTopologyMapper.kt)、[NodeClickAdmission.kt](../android/app/src/main/java/com/landosol/toolbox/labyrinth/node/NodeClickAdmission.kt) | 根治 `FIX-06` 类低置信错绑与连点无效；建立「当前帧视觉 × 协议拓扑 × 视口微移重检 × 点击后推进回执」闭环，严格隔离冲突 |
| **角色 / 编组 / 奖励双通道** | [LabyrinthCharacterIconMatcher.kt](../android/app/src/main/java/com/landosol/toolbox/labyrinth/vision/LabyrinthCharacterIconMatcher.kt)、[LabyrinthCharacterGridDetector.kt](../android/app/src/main/java/com/landosol/toolbox/labyrinth/vision/LabyrinthCharacterGridDetector.kt)、[LabyrinthBattleTeamRecognition.kt](../android/app/src/main/java/com/landosol/toolbox/labyrinth/vision/LabyrinthBattleTeamRecognition.kt)、[AndroidLabyrinthRoleRewardNameResolver.kt](../android/app/src/main/java/com/landosol/toolbox/labyrinth/vision/AndroidLabyrinthRoleRewardNameResolver.kt) | 解决缩放边框漂移、1088 特殊网格及 `FIX-08` 角色池误判；打通「头像匹配 + 名称 OCR + 属性角标 + 持有池先验」双通道印证与统一证据门槛 |
| **OCR 稳定性与全链路状态** | [OnnxOcrEngine.kt](../desktop/ocr/src/main/kotlin/com/landosol/toolbox/desktop/ocr/OnnxOcrEngine.kt)、[evaluation/README.md](../desktop/ocr/evaluation/README.md)、[AndroidLabyrinthShopCoinResolver.kt](../android/app/src/main/java/com/landosol/toolbox/labyrinth/vision/AndroidLabyrinthShopCoinResolver.kt) | 补齐 `:ocr:evaluate` 样本（当前 15/80 → ≥80 条、每类 ≥8 条、总≥95%）；加固 64 条/64 MiB 队列边界与粘连数字；将金币观测从商店单点扩展到地图/事件/商店 + 局内收支账本，强化遗物/印记层数核验 |
| **可观测性与离线 DEBUG** | 识别失败 / 冲突 / 连点无效 / 未知页超时 | 自动留存「原帧 + ROI 裁剪 + 锚点/OCR 原始得分快照」随诊断包导出，并建立一键沉淀为离线单测与回放用例的回归路径 |

- **退出门禁**：`:ocr:evaluate` 达标；核心视觉链路具备多信号冗余或安全降级；`FIX-06` 及高频视觉根因均有离线回归与实机抽检。

### M2 · Level 1 战斗模型端到端交付与实机闭环
- 对齐并实机验证 `ModelGuidedBattleTeamSearcher` 与 PR #29 三档前排生存策略。
- **分发倾向**：桌面便携包（`:app:packagePortableZip`）倾向直接内置 `s4_m2b_r1_seed43` 开箱即用，同时保留 `-Dlimingjie.nn.bundle`、`LIMINGJIE_NN_BUNDLE` 与 `data/models/` 外部覆盖；严格校验文件 SHA-256 与 `catalog_sha256`，失败或关闭开关时回退规则引擎。
- 接通 `ShadowBattleSample` 实战落盘通道，并在运行记录中区分选队来源（NN 稳妥 / NN 边缘 / 规则回退）。
- 实机复测并关闭 `FIX-01`–`FIX-05`、`FIX-07`、`FIX-08`，并在真机核实 Path B 所需的 3 项游戏语义：`retire()` 扣票与结算时点、老虎机 `23161/33161` 后续阶段是否净扣本金、事件战与 `50002` 免死的结算计数口径。
- **退出门禁**：`check -Pfull` + 便携包自检通过；实机多局稳定跑通；3 项游戏语义结论明确。

### M3 · Path B 全局高分规划深化与跨语言对齐
- 共享 Kotlin `kernel/planning/`（`Scoring`、`RouteDp`、`ShopDp`、`MarginalEval`、`ModelBattlePassEstimator`）及显式开关初步接入已并入 `main`；本阶段完成与 Python 参考实现的黄金用例跨语言数值对齐。
- 依托 M1 补齐的全链路金币观测与账本，完善 `t=0` 开局筛图、`T=0`/`T>0` 路线早停、商店 DP 及选角/遗物/事件边际估值的线上校验；Android 保持零 ONNX 依赖（规则估算器驱动同一 SPI）。
- 导出真实开局 `replays.jsonl`，回填 `LimingjieNN` Phase 5（`env/run_env.py` 策略基准对照）。

### M4 · 线上校准监控与 10 层演进
- 基于实机 `ShadowBattleSample` 持续审计线上 `p_win` 校准与高置信失败难例。
- 按需启用 `LimingjieNN` 预留 **20 CC**，应对国服 DB 更新、分布偏移或 10 层多队版本重训；新包通过 S5 封存门禁与 `BattleOnnxGoldenTest` 后直接滚动替换。

## 4. 验证档位
- 文档改动：核对链接与事实；常规/共享改动：`check` + `*BattleOnnxGoldenTest`；M1 OCR/视觉：`+ :ocr:evaluate` 与离线回放；M2/M3 交付：`check -Pfull`、`:app:packagePortableZip`、`:app:packagedRuntimeTest`、`--self-test --offline-startup` 及实机回归。
