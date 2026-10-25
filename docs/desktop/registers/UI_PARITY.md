# UI_PARITY.md —— 功能与桌面呈现对照（G4-1）

**规则**：基准是上游 `ui/LandosolToolboxApp.kt`（被排除的外壳）和它接线的各屏幕。每一行的状态只能是 `已对齐`、`不适用 + 理由`、`未开始`、`进行中`。G4 要求没有 `未开始` / `进行中`。
「桌面接线」写明调用的 `DesktopAppGraph` 入口；「测试」填 SPEC-50 §9 中对应的测试名或截图路径。行号基于 `b809b196`，M1 复核。
对照单位是用户功能与行为，不要求控件位置或像素一致。原有外壳与 Route 对照保留作参考；页面内部按功能分组记录去向，重点核对本次改动与容易遗漏处，不逐字段填表。不能以重设计或简化清单为由删除业务功能。
原型完成不等于功能已接通，只有实现和相关检查完成才记为 `已对齐`。已有测试或一次操作记录可以供多行共同引用，不重复验证。F 维护 §8 简表，§1–7 的跨节调整由 A 汇总。

## 1. 导航（`LandosolToolboxApp.kt:69-83, 168-253`）

| 上游行为 | 桌面对应 | 状态 | 测试 |
|---|---|---|---|
| `AppScreen` 三个入口：黎明界、账号库、设置；默认黎明界 | 左侧导航：黎明界、账号库、设备、设置与环境、诊断（新增的「设备」「诊断」为桌面页面）；默认黎明界 | 未开始 | |
| `rememberSaveable` 保存当前页面 | 进程内保存；重启后回到黎明界（与上游冷启动一致） | 未开始 | |
| 通知点击带 `openLabyrinth` extra → 切到黎明界 | 托盘菜单「打开黎明界」、任务栏点击 → 显示窗口并切到黎明界 | 未开始 | |
| `NavigationRail` / 底部导航按屏幕尺寸切换 | 固定为 MAA 风格左侧导航（不适用：桌面窗口始终足够宽，最小 1100×700） | 未开始 | |
| `isCompactLandscape` 时使用 `CompactLandscapeTypography` | 桌面按内容区重排，不强制 Android 紧凑排版；复用依赖该环境的组件时按 D-25 提供尺寸，验证桌面可用性 | 未开始 | |
| `ClanBattleScreen` 未接入导航 | 同样不接入（KL-12） | 已对齐 | — |

## 2. 截图与权限前置（`LandosolToolboxApp.kt:87-153`）

| 上游行为 | 桌面对应 | 状态 | 测试 |
|---|---|---|---|
| `requestCaptureThen`：先确认通知可用，否则申请通知权限或跳转设置 | 不适用：桌面没有通知权限。直接进入下一步 | 未开始 | |
| `beginCaptureRequest`：截图已运行则直接执行；否则申请 MediaProjection，启动后等待 `CaptureState.Running`，最多 5000 ms（`CAPTURE_START_TIMEOUT_MILLIS`），超时则放弃动作 | 设备 Ready 时启动 `DesktopCaptureService`，等待 `CaptureState.Running` 最多 5000 ms，超时放弃动作并提示；设备未 Ready 时弹出「请先在设备页连接模拟器」并提供跳转 | 未开始 | |
| 用户拒绝录屏授权 → 静默放弃 | 不适用（没有授权步骤）；设备未连接的提示见上一行 | 未开始 | |
| `KeepScreenAwake(enabled = entryRecognition.running ‖ sessionReset.running ‖ autoRun.running ‖ batchActive)`，`batchActive` = 批量阶段属于 REROLLING / INVALIDATING_OLD_CLIENT_SESSION / RUNNING_LABYRINTH / RECORDING_RESULT | 防睡眠（SPEC-30 §9）使用**完全相同的条件**；`ES_DISPLAY_REQUIRED` 也按此条件开启 | 未开始 | |

## 3. 黎明界路由（`LabyrinthRoute`，`LandosolToolboxApp.kt:322-463`）

| 回调 / 行为 | 上游实现 | 桌面接线 | 状态 | 测试 |
|---|---|---|---|---|
| 「需要无障碍权限」对话框 | 无障碍未连接时弹出，按钮「前往设置」 | 改为「需要连接设备」：设备未 Ready 或触控服务未连接时弹出，文案说明原因，按钮「前往设备页」 | 未开始 | |
| `onOpenStrategies` / 策略页 | `showStrategies = true`，显示 `LabyrinthStrategySettingsScreen`；`onSave` 防重入、保存失败显示 `failure.message ?: "保存失败，请重试"` | 桌面策略页或对话框；保存、防重入、错误与取消异常语义不变，功能去向见 §8 | 未开始 | |
| `loadRoleRatings` | `application::loadLabyrinthRoleRatings` | `DesktopAppGraph.loadLabyrinthRoleRatings` | 未开始 | |
| `onCheckStatus` | `labyrinthViewModel::checkStatus` | 相同 | 未开始 | |
| `onSaveSettings` | `labyrinthViewModel::saveSettings` | 相同 | 未开始 | |
| `onStart(guildId)` | 检查通知权限 / 通知开关，通过后 `start(retreatConfirmed = true, guildIdOverride = guildId)`；失败时 `reportMessage(…)` | 直接调用 `start(retreatConfirmed = true, guildIdOverride = guildId)`（通知检查不适用） | 未开始 | |
| `onStop` | `labyrinthViewModel::stop` | 相同 | 未开始 | |
| `onDismissMessage` | `labyrinthViewModel::dismissMessage` | 相同 | 未开始 | |
| `onStartEntryRecognition` | `onRequestCapture { session.start(accountId) }`（不检查无障碍） | 截图前置（§2）后 `start(accountId)`；不检查触控 | 未开始 | |
| `onStartEntryAutomation` | 先检查无障碍已连接，否则弹对话框；再 `onRequestCapture { session.startAutomation(accountId) }` | 先检查设备 Ready 且触控已连接，否则弹「需要连接设备」；再截图前置后 `startAutomation(accountId)` | 未开始 | |
| `onStopEntryRecognition` | `session.stop()` | 相同 | 未开始 | |
| `onStartAutoRun(goals)` | 检查无障碍 → `onRequestCapture { startLabyrinthAutoRun(accountId, goals) }` | 检查设备与触控 → 截图前置 → `DesktopAppGraph.startLabyrinthAutoRun` | 未开始 | |
| `onStopAutoRun` | `stopLabyrinthAutoRun()` | 相同 | 未开始 | |
| `onCaptchaSolved` / `onCaptchaError` / `onCancelCaptcha` | `submitCaptcha` / `reportCaptchaError` / `cancelCaptcha` | 相同；对话框由 `GeetestCaptchaDialog` 替身提供 | 未开始 | |
| 状态来源 | `labyrinthController.uiState`、`entryRecognitionSession.state`、`gameSessionResetWorkflow.state`、`labyrinthAutoRunWorkflow.progress`、`labyrinthBatchController.state / haltReason` | 同名来源，由 `DesktopAppGraph` 暴露 | 未开始 | |

## 4. 账号库路由（`AccountRoute`，`LandosolToolboxApp.kt:466-489`）

| 回调 | 上游 | 桌面 | 状态 | 测试 |
|---|---|---|---|---|
| ViewModel | `viewModel(factory = AccountViewModel.Factory(accountRepository))` | `DesktopAppGraph` 创建（D-23 的派生改写），应用退出时 `clear` | 未开始 | |
| `onAdd` / `onEdit` / `onSelect` | `openNewAccount` / `openEditAccount` / `selectAccount` | 相同 | 未开始 | |
| `onRequestDelete` / `onDismissDelete` / `onConfirmDelete` | `requestDelete` / `dismissDelete` / `confirmDelete` | 相同 | 未开始 | |
| `onEditorChange` / `onDismissEditor` / `onSaveEditor` | `updateEditor` / `dismissEditor` / `saveEditor` | 相同 | 未开始 | |
| `onDismissMessage` | `dismissMessage` | 相同 | 未开始 | |
| 设备画像来源提示 | 无（Android 读本机） | 账号页顶部显示画像来源（SPEC-40 §2，FB-11） | 未开始 | |

## 5. 设置路由（`PermissionRoute` → 设置与环境页，`LandosolToolboxApp.kt:279-320`）

| 上游 | 桌面 | 状态 | 测试 |
|---|---|---|---|
| `PermissionStatusReader.read(context)`：无障碍启用 / 连接、悬浮窗、通知、截图会话、`canStartVisualAutomation` | 环境检查：adb、设备连接、截图模式与耗时、触控服务、前台检测、OCR 状态、数据目录、网络 / 代理、国服数据库版本；上游 `canStartVisualAutomation` = 无障碍启用且连接 + 通知可用 + 截图会话运行中；桌面等价条件 = 设备 Ready + 触控已连接 + OCR Ready（截图由 §2 按需启动，不作为前置条件，在 DECISIONS 记一条） | 未开始 | |
| `ON_RESUME` 时刷新状态 | 窗口获得焦点、设备状态变化时刷新 | 未开始 | |
| `onOpenAccessibilitySettings` | 不适用 → 「前往设备页」 | 未开始 | |
| `onRequestNotifications` | 不适用（没有通知权限） | 未开始 | |
| `onStopCapture` | 「停止截图」按钮：停止 `DesktopCaptureService`，刷新状态 | 未开始 | |

## 6. 通知与悬浮窗（被排除的 `AndroidAutomationNotificationHost`、`AutomationOverlayService`）

| 上游 | 桌面 | 状态 | 测试 |
|---|---|---|---|
| 通知内容 `notificationContent()`（P-05 搬出） | 任务栏和托盘提示使用同一函数生成的文案 | 未开始 | |
| 通知动作（暂停 / 继续 / 停止），`automationNotificationActionMatches` | 任务栏按钮和托盘菜单，调用相同的上游动作入口 | 未开始 | |
| 悬浮窗 `AutomationOverlayPresentation`（title / status / detail / dryRun / paused / boxes） | 任务栏显示 title / status / detail / dryRun / paused；画面预览叠加 `boxes` | 未开始 | |
| 刷取服务通知 `labyrinthRerollStatusText`（P-06 搬出） | 任务栏文案 | 未开始 | |

## 7. 桌面新增页面（无上游对应，只需满足 SPEC-50）

| 页面 | SPEC | 状态 | 测试 |
|---|---|---|---|
| 设备页 | SPEC-50 §3.3 | 未开始 | |
| 诊断页 | SPEC-50 §3.5 | 未开始 | |
| 首次启动向导 | SPEC-50 §7 | 未开始 | |
| 关闭窗口的三种分支 | SPEC-50 §6 | 未开始 | |

## 8. WS-F：简短功能清单

按下列功能分组记录桌面入口与结果即可；只对本次变更或易遗漏的行为补充说明。无需拆成逐字段、逐状态证据表，验证可直接引用已有测试或同一份典型操作记录。

| 原功能 | 桌面位置 | 需保留的要点 | 状态 / 验证备注 |
|---|---|---|---|
| 黎明界与刷开局 | 主任务页 | 账号、目标、原配置、刷到出、首轮复用、启动 / 停止与结果 | 未开始 |
| 账号库与登录 | 列表、编辑区和对话框 | 服务类型、增删改选、登录、校验、确认和人工验证码 | 未开始 |
| 策略设置 | 子页或宽对话框 | 原配置含义、默认值、范围、保存 / 取消 | 未开始 |
| 开局编组 | 子页或宽对话框 | 编组操作、排序含义、约束与保存 | 未开始 |
| 角色评分 | 子页或宽对话框 | 原有查看 / 编辑能力、范围与保存 | 未开始 |
| 任务状态与控制 | 页面、任务栏和托盘 | 按任务能力显示状态与操作，刷开局不新增暂停 | 未开始 |
| 设备、设置和诊断 | 对应桌面页 | 连接、环境提示、原设置、日志与诊断入口 | 未开始 |

布局和展示文案变化可在备注中简述；实际字段仍按源码和原业务入口保留。功能或行为确需变更时按范围规则处理。
