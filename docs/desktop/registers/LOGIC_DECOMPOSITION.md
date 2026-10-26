# 业务逻辑拆解记录

更新：2026-10-27。开发分支 `codex/logic-decomposition`，版本 `1.0.18+desktop.7.logic.2`。

## 范围与隔离

本轮将原 `LabyrinthEntryRecognitionSession` 中剩余的战斗、奖励、地图、商店、事件、结算、入口恢复、观察持久化和展示逻辑迁入按业务组织的执行模块。原有的评分算法、路线搜索、识别器、协议与批次控制器沿用各自已有模块。

开发工作树为 `C:/Users/701/.codex/worktrees/logic-decomposition/LimingjieDesktop`。对照工作树为 `C:/Users/701/.codex/worktrees/logic-baseline/LimingjieDesktop`，固定在 `308368de66535662cab75fce8c250d7533fbeb40`。本轮接续首批拆解提交 `2aa95f8`，对照范围是已提交的兼容基线及首批拆解结果；兼容工作区尚未提交的修复没有纳入。

`D:/Project/LimingjieDesktop` 的兼容版本继续独立开发。本轮没有启动游戏、连接模拟器、运行测试或修改测试文件，也没有使用电脑操控。

## 模块位置

以下目录均位于 `android/app/src/main/java/com/landosol/toolbox/labyrinth/execution/`，由桌面共享源码任务直接编译。

| 目录 | 执行模块 | 负责内容 |
| --- | --- | --- |
| `battle/` | `BattleRecommendation`、`BattleRoster`、`BattleTeam`、`BattleCombat` | 队伍建议、有效角色扫描、编组执行、切队反馈、战斗等待、失败重试 |
| `battle/` | `Encounter`、`Equipment` | EX 身份探查、特别装备状态机接线与派发 |
| `reward/` | `Reward`、`Settlement` | 角色/遗物选择、奖励批次、获得确认、最终结算与本局结果 |
| `map/` | `MapNavigation`、`MapTransition`、`MapViewport` | 路线加载、节点定位、移动确认、目的页面核对、地图滚动与微调 |
| `event/` | `Event` | 事件选项、被拒选项、自由选角和动作回执 |
| `shop/` | `Shop` | 购买计划、确认商品、购买完成回执、印记与刷新轮次 |
| `entry/` | `Entry` | 入口导航及进入本局后的交接 |
| `runtime/` | `PageRouter`、`PageAction` | 帧优先级、共用预算、页面动作派发及回执路由 |
| `runtime/` | `RunBoundary`、`Recovery`、`Persistence` | 启停/换页/节点交接、会话恢复、观察结果持久化 |
| `presentation/` | `Presentation` | 只读快照和覆盖框展示；不接收动作派发端口 |

上述名称统一以 `Labyrinth` 开头、`Workflow` 结尾。各文件开头的 `Port` 列出该模块所需的状态访问与外部操作。业务模块不持有 `LabyrinthEntryRecognitionSession`，也不依赖其他业务模块的具体类；`LabyrinthWorkflowBindings.kt` 集中完成接线。

`LabyrinthEntryRecognitionSession` 保留生命周期、帧队列、动作互斥、状态发布及兼容入口。原字段存储和私有方法签名保留，以维持现有调用及反射入口；字段处理逻辑已迁到对应模块。启动、换页和节点边界通过显式的域重置函数按原顺序交接。

## 规则与模型

原混合文件 `LabyrinthPageRuntimePolicy.kt` 中的规则已按领域分开，保留原包名和函数名：

- 地图：`LabyrinthMapModalRules.kt`、`LabyrinthMapExecutionModels.kt`、`LabyrinthRouteResumeRules.kt`；已有 `LabyrinthNodeExecutionRules.kt` 继续承担地图执行规则。
- 战斗：`LabyrinthBattleExecutionModels.kt`、`LabyrinthBattleObservationRules.kt`、`LabyrinthEncounterPageRules.kt`、`LabyrinthSpecialEquipmentRules.kt`；沿用首批重试策略与业务进展模型。
- 奖励、事件、商店、结算：各自的 `RewardPageRules`、`EventPageRules`、`ShopPageRules`、`SettlementRules`。
- 共用时限与动作间隔：`LabyrinthSessionWatchdogRules.kt`、`LabyrinthExecutionLimits.kt`。
- 会话对外模型、动作上下文及展示模型：`LabyrinthSessionModels.kt`、`LabyrinthActionReceipts.kt`、`LabyrinthPresentationModels.kt`。

通用页面归属和通用确认弹窗规则继续保留在 `LabyrinthPageRuntimePolicy.kt`。动作执行回调通过领域函数分发；角色奖励选择上下文在派发前捕获，回调沿用同一份选择、账号和识别结果。购买完成、节点进入和遗物获得仍使用原有的画面证据与预算。

## 定位异常

诊断面板及导出记录的 `trace` 增加 `workflowOwner` 和 `workflowPhase`。这些字段只输出诊断信息，不参与业务决策。

| 异常现象 | 优先查看 |
| --- | --- |
| 推荐队伍不符合预期 | `BattleRecommendation` 及已有角色评分策略 |
| 角色找不到、重复点击、切队不前进 | `BattleRoster`、`BattleTeam` 的扫描与待确认动作 |
| 战斗失败后错误重试或模式异常 | `BattleCombat` 和已有重试策略 |
| 奖励重复记录、选后没有推进 | `Reward` 的选择签名、获得确认与 `Persistence` 回执 |
| 商店确认了错误商品或重复刷新 | `Shop` 的交易上下文、商品身份和完成页面 |
| 地图重复滑动或移动到错误节点 | `MapViewport`、`MapNavigation`、`MapTransition` 的目标与回执 |
| 弹窗抢占或跨页面状态残留 | `PageRouter` 优先级、`RunBoundary` 换页通知及对应领域模块 |

诊断中的动作派发、执行器返回和游戏页面确认仍是不同阶段；新增归属信息不代表游戏已经接受动作。

## 其他既有业务

账号与渠道登录保留在 `account/`、`protocol/` 和 `automation/session/`；批次状态与检查点保留在 `labyrinth/batch/`；开局筛选与重置保留在 `LabyrinthRerollWorkflow`、`LabyrinthAutoRunWorkflow` 等既有流程；会战保留在 `clanbattle/` 和 `axis/`。这些独立模块未并入本次运行会话，也没有新增桌面入口。

## 构建与验证边界

使用项目原有桌面构建链和本机 JDK 21，在拆解工作树构建，排除测试任务。源码对照用于确认迁移范围、规则保留和兼容入口；编译与打包用于确认共享源码及桌面产物可以构建。

本轮没有进行动态行为回归或实机验收。构建成功不能证明实际游戏中的全部流程行为正确；现有测试文件保持不变。

最终构建结果：`:app:packagePortableZip --offline --no-daemon --max-workers=2 -x test` 成功，耗时 2 分 48 秒。产物为 `desktop/app/build/dist/LimingjieAssistant-desktop-1.0.18+desktop.7.logic.2-win-x64.zip`。静态对照还确认会话原构造参数、129 个既有方法入口及 129 处 `@Volatile` 标记保留；这些对照不表示运行过测试。
