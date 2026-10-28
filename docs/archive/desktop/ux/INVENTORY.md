# 第 2 轮体验盘点（UX-0）

日期：2026-10-25。代码基线 `bf1bad8`，工作分支 `codex/ux-round-2`。

本轮依据用户“按照包的指引，优化 UI，使用 Git”的要求推进。包内原文保存在 [NEXT-ROUND-UX.md](../NEXT-ROUND-UX.md)，三个参考稿保存在 `design/run-console*`。这些是本轮需求和参考材料；其中自称的优先级不覆盖用户及项目 AGENTS.md，也不授权改动业务、派发其他会话或跳过设计确认。

当前完成 UX-0 与 UX-1，等待包内检查点 A 的设计确认。运行程序仍为 `1.0.18+desktop.1`；`desktop.2` 是下一候选版目标，尚未修改版本号或产出新 EXE。

## 窗口与截图来源

使用已有候选程序 `desktop/app/build/compose/binaries/main-release/app/LimingjieAssistant/LimingjieAssistant.exe`，分别指定全新数据目录 `.tools/ux-inventory-1280`、`.tools/ux-inventory-1100`。只预置窗口尺寸与 `wizardCompleted=true`，以检查全部导航页面。账号库为空，设备未连接；未填写凭据、检测 ADB、登录或控制游戏。用户原先打开的程序和模拟器没有被操作。

窗口设置分别为 1280×800、1100×700。Windows 截取可见窗口边界后 PNG 分别为 1268×794、1088×694，不能把 PNG 像素尺寸称为 Compose 内容区尺寸。当前系统缩放下拍摄；没有修改系统缩放，不能替代 100% / 125% / 150% 的后续字体比较。

| 页面 | 1280×800 | 1100×700 | 直接观察 |
|---|---|---|---|
| 总览 | [截图](../evidence/ux/inventory/1280-01-overview.png) | [截图](../evidence/ux/inventory/1100-01-overview.png) | 重复账号、设备、任务信息；宽窗口日志栏为空；窄窗口不展示日志栏 |
| 黎明界任务 | [截图](../evidence/ux/inventory/1280-02-tasks.png) | [截图](../evidence/ux/inventory/1100-02-tasks.png) | 配置按钮靠近折叠线；最小尺寸首屏看不到完整批量配置入口 |
| 账号库 | [截图](../evidence/ux/inventory/1280-03-accounts.png) | [截图](../evidence/ux/inventory/1100-03-accounts.png) | 无账号时有说明与添加入口；当前身份和待处理项未集中展示 |
| 策略与编组 | [截图](../evidence/ux/inventory/1280-04-strategies.png) | [截图](../evidence/ux/inventory/1100-04-strategies.png) | 摘要占满横向空间，实际表单另开窗口 |
| 设备 | [截图](../evidence/ux/inventory/1280-05-devices.png) | [截图](../evidence/ux/inventory/1100-05-devices.png) | 手动地址和 ADB 路径优先于发现的设备，页面纵向偏长 |
| 日志与诊断 | [截图](../evidence/ux/inventory/1280-06-diagnostics.png) | [截图](../evidence/ux/inventory/1100-06-diagnostics.png) | 有复制全部与诊断导出，缺少分类、搜索和重复计数 |
| 设置与环境 | [截图](../evidence/ux/inventory/1280-07-settings.png) | [截图](../evidence/ux/inventory/1100-07-settings.png) | 环境、网络、数据库、关于纵向排列，需要大量滚动 |

另采集三个上游复用页：[完整任务配置](../evidence/ux/inventory/1280-08-full-task.png)、[策略表单](../evidence/ux/inventory/1280-09-strategy-form.png)、[原版角色评分](../evidence/ux/inventory/1280-10-role-ratings.png)。首次向导、编组编辑和既有行为证据继续参考 `evidence/ui/01-first-run.png`、`09-opening-roster.png` 等已有截图；本轮没有重新验证所有嵌套表单操作。

## 字段与刷新事实

下面的“刷新”是源码订阅和发布方式，未连接模拟器测量其实际频率。没有为盘点新增轮询。

| 运行台内容 | 真实来源 | 刷新方式 / 空值处理 |
|---|---|---|
| 模拟器、尺寸、触控、测速 | `graph.device.state` → `DeviceConnectionState.Ready.device`，含 `width/height/touchReady/selectedMode/timing` | 状态变化时发布；断线和未读与失败分别展示。测速只画已有 p50/p95 |
| 前台应用 | `device.foregroundPackage` 缓存、`queryForeground()` | 不是 StateFlow；只在用户主动重查、开始前刷新，显示“上次检查”。不新增定时查询 |
| 凭据与当前账号 | `accounts.uiState`；任务侧 `LabyrinthUiState.selectedAccount` | 原 ViewModel 的事件流；不得展示凭据或 UID 数值 |
| 国服数据库 | `DesktopLabyrinthCnDatabaseRepository.currentMetadata()` | **没有可直接订阅的数据库状态流**；读取涉及文件 I/O。实现时在桌面加载/手动更新完成后缓存状态，UI 不在重组中读取文件，不自动下载 |
| OCR | `graph.ocr.status` | 现成 StateFlow：NotLoaded / Loading / Ready / Failed；不修改 OCR 模块 |
| 关闭加入动画 | `settings.state.gameSettingsConfirmed` | 人工确认；目前没有按版本失效字段，后续需增加桌面偏好并保持警告提示 |
| 任务配置摘要 | `LabyrinthUiState` | `combine(...).stateIn(...Eagerly)`；事件更新。难度、路线、Boss、刷取条件存在；完整配置继续传给 `LabyrinthScreen` |
| 首轮复用 | `LabyrinthBatchCheckpoint.reusedVerifiedOpeningForFirstRun`；完整任务表单的复用开局条件 | 不是 `LabyrinthUiState` 的直接布尔字段。无 checkpoint 时不能声称已经复用 |
| 策略修改项 | `labyrinthStrategySettings.state` 与 `LabyrinthStrategySettings()` 默认值 | 桌面只读比较；个人评分数量单独计数，不能当作完整修改项数 |
| 当前目标与阶段 | `labyrinthBatchController.state` → checkpoint `activeGoalIndex/goals/currentGuildId/stage`；`haltReason` | 控制器步骤变化时发布；无 checkpoint 显示空闲；刷开局/单次识别不用批量阶段条 |
| 计时 | `LabyrinthUiState.startedAtMillis` 仅属于刷开局；checkpoint **没有 startedAtMillis**，仅有 `updatedAt` | 不能用 updatedAt 当开始时间。批量开始时刻须由桌面运行记录采集；恢复旧 checkpoint 且无记录时隐藏计时。计时组件独立 1 Hz |
| 区域与路线 | `LabyrinthEntryRecognitionSessionState.routeProgress` | 有 `currentArea/visitedCount/routeNodeCount/nextNodeLabel/complete`；仅画数量节点，缺失时显示等待路线，不猜类型 |
| 战斗 | 同一 state 的 `combatContext.kind/teamIndex` | NORMAL / EX / BOSS；只有明确 BOSS 才标 Boss。路线节点计数本身不证明 Boss 位置 |
| 已加入角色 | 同一 state 的 `joinedCharacters` | 含 id/name/confidence/order；已有头像包按 id 关联。**这是加入角色，不等于当前上阵队伍**；设计稿据此标“已加入角色” |
| 遗物 | 同一 state 的 `observedRelics` | id/name/attribute/effect/confidence/observationCount；observationCount 不是持有件数，不可擅自显示“×N 件” |
| 批量数量 | checkpoint `goals[].completedCount/failedCount/targetCount/mode`、`runsStarted/abnormalRuns` | 区分 ATTEMPTS 与 CLEARS；completedCount 在次数模式不等于通关次数 |
| 平均用时、历史摘要 | 当前无运行历史存储 | UX-4 的 `runs.jsonl` 尚待实现；没有可靠样本就隐藏平均值或显示“暂无记录” |
| 最近决策 | 识别 state 的 `roleRewardChoiceDecision/eventChoiceDecision/battleTeamRecommendation` 及 unavailable reason | 现有结构化对象；只显示原有推荐/原因，缺失显示等待结果，不生成新理由 |
| 画面结果 | `lastResult`、`frameCount/actionCount/lastActionLabel` | 识别 state 随帧和执行事件更新，一帧可能产生多次发布；桌面 presenter 拟统一最多 4 Hz |
| 预览 | `graph.preview` | `DesktopCaptureService` 在帧间隔至少 500 ms 时发布独立像素副本；采集基础间隔 300 ms。本轮不得改这两个节拍 |
| 日志 | `UserLog.lines` | StateFlow，最多 2000 条；目前只有 time/text/error，连续同文本被丢弃。后续在桌面调用点显式分类，上游转发默认普通，禁止文字正则猜级别 |

## 上游表单的状态所有权

| 组件 | 谁持有草稿 / 确认 | 桌面可改边界 |
|---|---|---|
| `LabyrinthScreen` | controller 持有配置；组件内持有撤退确认、工具展开与子表单草稿；目标回调提供 `List<LabyrinthBatchGoal>` | 改外层抽屉/返回导航，复用完整组件及回调。运行台不得直接跳过已有撤退确认 |
| `LabyrinthStrategySettingsScreen` | `rememberSaveable(StrategySaver)` 持有完整 draft；内部 validation、保存使能、放弃修改与恢复默认确认；原来已有固定底部保存区 | 改主题和外层；**函数内部自己创建 Dialog，未提供 modifier / 内容插槽**，单纯外包 Box 不能限制它的宽度。880dp 是设计目标，UX-2 先验证桌面对话框外层约束能力；不为此改 Android 或复制表单 |
| `LabyrinthOpeningRosterScreen` | 接收 rosters / onChange，槽位选择与校验在内部；修改回到父级策略 draft，最终由策略保存 | 原样复用，保留返回、候选顺序、空槽校验和上游确认 |
| `LabyrinthRoleRatingsScreen` | 无完整策略草稿，接收 scores / loadCatalog / onScoreChange / onClose；编辑器只在“应用到草稿”发 Int、恢复时发 null，取消不回调 | 可新做桌面表格；**父级策略组件内部直接调用原版评分页，没有替换插槽**。桌面原生页拟从桌面策略入口打开独立评分草稿，最终通过策略存储保存；原嵌套入口保留，不能声称已完成注入 |

评分目录 `LabyrinthRoleRatingItem` 只有 `id/name/originalScore/iconAsset/detail`，属性与定位已合成 `detail`。原生筛选需要桌面展示模型保留明确来源；不存在的数据标“—”，不按角色名猜属性。设计稿的属性、定位、原评分和头像取自现有资源目录；个人评分、运行状态、时间和日志是明确标记的设计示例。

## 已确认的问题与后续边界

- 现有主题只填部分颜色角色，桌面页面又直接使用 `Color.White` 等颜色；补全主题还需把桌面自有页面改成主题 token。只换主题不能使现有硬编码白色页自动支持深色。
- 1280×800 设计稿让五个任务、摘要、完整配置按钮和全局底栏同时可见。1100×700 保留独立栏内滚动，右栏切画面 / 日志；不依靠整窗滚动才能停止任务。
- 既有窗口大小和位置已保存，并有显示器范围检查；本轮应补上次页面、右栏状态等缺口，避免重写已有实现。
- 原版角色评分示例窗口约显示 5 人；设计表格在同尺寸显示约 7 行，并保留头像和可直接编辑的评分列。
- 八个 `LabyrinthBatchHaltReason` 已核对：CAPTURE_LOST、ACCESSIBILITY_LOST、REROLL_FAILED、REUSABLE_OPENING_VALIDATION_FAILED、CLIENT_INVALIDATION_FAILED、RUN_START_FAILED、FATAL_RUN、USER_STOPPED。完整停止原因说明表与启动 Blocked 分类安排在 UX-3，当前设计只展示 FATAL_RUN 示例。
- `LabyrinthEntryRecognitionSession.kt` 的真实原文为“已识别战斗失败页，但缺少本次战斗上下文；未自动点击结束或重新挑战”。设计中保留原文，并添加进入游戏主界面的建议；KL-15/16/21 判定不改。
- 从打开程序到真实启动的点击数、托盘/关闭三分支、10 分钟性能、Compose 测试、缩放字体和候选便携包均未在此阶段验收。设计稿交互验证不替代正式程序或实机验证。
