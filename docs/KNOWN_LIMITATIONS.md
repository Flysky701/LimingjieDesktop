# 已知限制与待办

用户侧常见问题与排查方法见 [使用指南](USER-GUIDE.md#日志与常见问题)。本文记录待修复问题、下一版本待发布修复及当前平台限制。

## 待修复与验证问题

以下问题涉及 `android/` 中的共享业务流程，修复时需同时兼顾桌面与 Android 端行为：

- [ ] **FIX-01：重新挑战时序与挑战页回收（已完成离线回放，待实机连续战败回归）**
  - **现象**：单局记录“EX战连续3次挑战失败”并停止，随后重置接手时画面已处于 `BATTLE_CHALLENGE`，因重置阶段无挑战页推进路径而超时。
  - **本次修复**：[LabyrinthEntryRecognitionSession.kt](../android/app/src/main/java/com/landosol/toolbox/labyrinth/LabyrinthEntryRecognitionSession.kt) 在点击“重新挑战”后标记等待新一轮战斗启动，在进入新战斗或执行开战前不对残留失败帧再次累计重试次数或触发三次战败终止；[SessionExpiryFrameTracker.kt](../android/app/src/main/java/com/landosol/toolbox/automation/session/SessionExpiryFrameTracker.kt) 补充编组页取消、挑战页查看地图及地图页撤退确认的会话失效触发链。该修复同时作用于桌面与 Android；残留失败帧与挑战页撤退链的定向离线回放已通过，实机连续战败交接仍待复测。

- [ ] **FIX-02：启动阶段慢加载时限与重置失败原因透传（已完成离线回放，待实机冷启动复测）**
  - **现象**：从标题页点击后若加载漫画或数据同步耗时较长，任务在正常加载中被 45 秒上限终止并笼统记录为“会话重置失败”。
  - **本次修复**：[LabyrinthEntryActionPlanner.kt](../android/app/src/main/java/com/landosol/toolbox/labyrinth/LabyrinthEntryActionPlanner.kt) 将已识别的 `GAME_LOADING_PROGRESS` / `PRE_HOME_DATA_LOADING` 与未知页面受限点击分开计时，单阶段给予最长 90 秒加载预算且不消耗公告前点击次数（仍受 120 秒入口总超时约束），进入主页公告及后续页面后清除公告前点击武装；[GameSessionResetWorkflow.kt](../android/app/src/main/java/com/landosol/toolbox/automation/session/GameSessionResetWorkflow.kt) 与 [LabyrinthAutoRunWorkflow.kt](../android/app/src/main/java/com/landosol/toolbox/labyrinth/LabyrinthAutoRunWorkflow.kt) 向上层透传具体阶段失败原因，[LandosolToolboxApplication.kt](../android/app/src/main/java/com/landosol/toolbox/LandosolToolboxApplication.kt) 与桌面装配同步校验 `READY_FOR_NEXT_ROUND` 终态。桌面与 Android 共享此修复；慢加载阶段切换与失败原因透传的离线回放已通过，待实机慢速冷启动复测。

- [ ] **FIX-03：通关结算阶段衔接（待实机连续多局回归）**
  - **现象**：关闭分数后若停留在 `SCORE_RESULT` 阶段，进入 `RUN_CLEAR_CHEST_RESULT` 时不触发确认点击，导致连续多局在宝箱结果页超时中断。
  - **当前进展**：已在共享会话中补充宝箱动画、宝箱结果与最终道具页的阶段推进恢复，并通过残留分数帧与漏帧的连续两局离线回放；待实机连续多局验证后闭环。

- [ ] **FIX-04：长耗时与多队 Boss 战进展感知等待上限（已完成离线回放，待实机多队 Boss 复测）**
  - **现象**：多队或高血量 Boss 战（如 `Boss#30701`）在战斗仍正常进行、倒计时未结束时满 5 分钟被强制终止。
  - **本次修复**：[LabyrinthEntryFrameProcessor.kt](../android/app/src/main/java/com/landosol/toolbox/labyrinth/vision/LabyrinthEntryFrameProcessor.kt) 在 `BATTLE_IN_PROGRESS` 帧对顶部血条与倒计时 HUD 区域计算量化摘要；[LabyrinthBattleWaitPolicy.kt](../android/app/src/main/java/com/landosol/toolbox/labyrinth/LabyrinthBattleWaitPolicy.kt) 在画面静止或无战斗进展时保持 5 分钟基础上限，仅当 HUD 持续变化时将等待上限按“最近一次进展 + 60 秒停滞保护”顺延至 `5 + 3 × 计划队伍数` 分钟硬上限（单队 8 分钟、双队 11 分钟、三队 Boss 14 分钟），桌面运行台同步匹配动态分钟数的超时解释。桌面与 Android 共享此修复；静止超时、HUD 推进顺延及停滞保护的离线测试已通过，待实机多队 Boss 战复测。

- [ ] **FIX-05：地图恢复与移动确认后重置未知页面计时（已完成离线回放，待实机回归）**
  - **现象**：2026-11-14 的 1920 × 1080 运行在点击普通战斗节点移动确认约 1.8 秒后，以“路线阶段未知页面超过限定时间”提前停止。
  - **本次修复**：[LabyrinthEntryRecognitionSession.kt](../android/app/src/main/java/com/landosol/toolbox/labyrinth/LabyrinthEntryRecognitionSession.kt) 在任意已识别路线页面及移动确认、遗物详情/奖励、特别装备弹窗帧统一清空旧的 `postEntryUnknownSince`，保留连续未知页面满 30 秒的停止保护。桌面与 Android 共享此修复；“未知页 → 地图停留 → 移动确认 → 新过场”的离线回放已通过，待实机复核。

- [ ] **FIX-06：普通战斗节点连续点击未确认，移动视角后才恢复（下一轮核对）**
  - **现象**：同次运行的普通战斗节点 `20303` 最初使用置信度约 0.405 的语义补全匹配，连续三次点击均未出现移动确认；向后挪动地图后改用置信度 0.940 的拓扑匹配，随后成功点击移动确认。
  - **待确认部分**：日志能确认点击未生效和视角恢复过程，但缺少对应画面，尚不能确定是节点错绑、坐标偏移、动画遮挡还是点击未落地。
  - **当前进展**：已合入上游当前帧点击证据与弱候选限制，阻止低置信语义补全和旧缓存直接派发点击；原场景仍需对应画面回放核对，不据此认定问题已闭环。
  - **核对与验证**：核对节点身份、可达集合与屏幕位置的绑定，重点检查低置信语义补全与拓扑匹配切换。取得对应画面后建立定向离线回放，确认问题再修复，保留现有点击守卫及有界重试。

- [ ] **FIX-07：统一不足五人队伍的首战与失败重试规则（已完成离线回放，待实机回归）**
  - **现象**：首次编组推荐三人队伍出战后失利，重试阶段重复提示“可用角色不足5名”并在编组页等待 120 秒后以 `ABORTED_UNKNOWN` 停止；后续在大角色池均衡模式下，`BATTLE_FAILED` 阶段同步评估可重试阵容触发 `T2 cannot call synchronous invokeAll`。
  - **本次修复**：[LabyrinthBattleTeamRecommendation.kt](../android/app/src/main/java/com/landosol/toolbox/labyrinth/LabyrinthBattleTeamRecommendation.kt) 与 [LabyrinthRoleDecisionPolicy.kt](../android/app/src/main/java/com/landosol/toolbox/labyrinth/LabyrinthRoleDecisionPolicy.kt) 统一首战与单队重试的 `minOf(5, resolved.size)` 合法人数及小角色池兜底规则（Boss 多队仍保持五人要求，已失败阵容仍禁止重复提交）；当失败页或编组页确认已无可替换的未失败阵容时，[LabyrinthEntryRecognitionSession.kt](../android/app/src/main/java/com/landosol/toolbox/labyrinth/LabyrinthEntryRecognitionSession.kt) 立即按批次 `FAILED_MAX_RETRY` 交接或按单局策略保留页面停止，不再空等 120 秒，并在均衡模式下通过 `battleRetryPlanning` 将失败页的重试可行性评估卸载到 CPU 工作线程，避免 T2 同步调用 `searchExecutor.invokeAll`。桌面与 Android 共享此修复；三/四/五人、空角色池及大角色池均衡模式 T2 离线回放已通过，待实机复核。

- [ ] **FIX-08：普通战编组提示一号位生存资格不足后停滞（下一轮核对）**
  - **现象**：2026-11-14 15:55:33 进入普通战斗节点 `10402`；15:55:35 起持续提示“自动战斗要求实际一号位满足生存资格；当前角色池无法组成满足条件的队伍”，没有派发编组动作，15:57:35 因连续 120 秒无进展停止，单局记为 `ABORTED_UNKNOWN`。此前同局普通战斗节点 `10202` 曾成功生成三人推荐，随后经过连结节点 `10302`。
  - **已定位路径**：[LabyrinthRoleDecisionPolicy.kt](../android/app/src/main/java/com/landosol/toolbox/labyrinth/LabyrinthRoleDecisionPolicy.kt) 的 `LabyrinthTeamPlanSearcher.initialSearch` 在特定前排约束下返回空方案；`vanguardlessPlan` 无法生成补充队伍时也使用同一句原因。日志不能区分两条路径，尚未确认是实际站位冲突、资格规则、角色池变化还是角色资料不足。
  - **当前进展**：已合入上游第一层战斗无合格前排时的编组例外，并接入默认关闭的“前排生存门槛”开关；原日志反映的是此前规则，仍需按原角色池复核新行为。
  - **核对与验证**：核对进入 `10402` 时的可用角色、站位与生存资格，以及连结节点前后的角色池变化；区分拒绝原因并明确编组不可用时的后续处理。补充角色池跨过五人、前排站位冲突及资料不足的定向离线回放，保留现有资格判定与点击守卫，并与 FIX-07 的重试人数门槛分别跟踪。

## 下一版本待发布修复

- **桌面端运行中短暂 ADB 离线复核与稳定截图选型**（已修复，纳入下一版本）
  - 运行中若遇到短暂 ADB 离线、连接重置、ADB 守护进程闪退重启或启动身份读取超时，保持当前设备租约并在最多 30 秒内复核同一模拟器的 `boot_id`，TCP 通道卡在 `offline` / `already connected` 时先执行 `disconnect` 再重连；即使后台监控先于截图恢复线程完成传输重连，或截图遇到瞬时 `ConnectionRefused` / `DeviceOffline`，`recoverCapture` 也优先保留原截图方式（`Raw`）并重算恢复窗口，不误降级为 `Png`；触控通道恢复遇到短暂 ADB 中断时复核连接后自动重试，稳定后与成功操作后均重置单次重连预算，并降低后台窗口/电源轮询频次以减轻模拟器负荷。
  - 截图基准测速优先在 `p95 ≤ 600 ms` 的稳定模式中选择最低 `p50` 方案，避免偶发长尾卡顿的 `Raw` 通道压过更稳定的 `Png`。
  - 验证：20 秒短暂离线恢复、后台监控先恢复时的瞬时传输重连、卡死 TCP 通道断开重连、ADB 守护进程闪退重启、启动身份超时恢复、触控恢复遇短暂断连及测速选型的定向回归通过。仅影响桌面端。

- **编组页开始战斗待确认窗口误点及未执行动作回滚**（已修复，纳入下一版本）
  - [LabyrinthEntryRecognitionSession.kt](../android/app/src/main/java/com/landosol/toolbox/labyrinth/LabyrinthEntryRecognitionSession.kt) 在 `pendingBattleTeamAction != null` 期间锁定 `planBattleTeamSelectionFrame`，消除 `START_PAGE_GRACE_MILLIS`（3 秒）与 `BATTLE_TEAM_ACTION_ACK_TIMEOUT_MILLIS`（4 秒）之间的空窗，防止多余角色池下对未确认的“开始战斗”误派发取消编组点击；并将 `START_BATTLE` 待确认状态与普通战编组记忆移至 `beginGuardedAction()` 获批后提交，在执行被抑制或拒绝时即时回滚并重置冷却。
  - 桌面与 Android 共享此修复；10 角色多余角色池 3–4 秒确认窗口及非前台拒绝回滚的定向回归通过，实机复测待更新本地运行包后进行。

- **桌面端首次校验游戏安装信息时 ADB 超时**（已修复，纳入下一版本）
  - 安装包复制的时限由 60 秒延长至 10 分钟，移除总控连接请求的 3 分钟提前中断限制，保留各步骤超时、断线检测和主动取消。
  - 补充复制与签名校验的日志，超时报错明确指出复制步骤和对应安装包；保留签名验证、缓存复用及失败后的临时文件清理。
  - 验证：慢速复制、缓存复用、失败清理、取消与断线的定向回归及跨模块快档检查通过；反馈者模拟器环境待复测。仅影响桌面端。

## 平台与运行限制

| 范围 | 限制说明 | 处理或规避 |
| --- | --- | --- |
| 操作系统与发行包 | 仅构建 Windows 10/11 x64 便携包，可执行文件未做代码签名 | 核对 `.sha256` 后在 SmartScreen 提示中继续运行 |
| 模拟器与分辨率 | 自动执行要求 **1920 × 1080 横屏**，需开启 ADB 调试并关闭游戏内「播放角色加入动画」 | 非 1920 × 1080 仅支持连接、预览与只读识别 |
| ADB 工具 | 发行包不内置 `adb.exe` | 自动使用模拟器自带 ADB，或在设备页指定路径 / 下载官方 Platform Tools |
| 模拟器渲染与电源 | 截图依赖模拟器持续渲染；防睡眠仅阻止系统空闲睡眠 | 运行时勿最小化模拟器窗口；避免手动睡眠、合盖或锁屏 |
| 节点树导出 | MuMu 部分系统设置页在 `uiautomator` 导出时可能返回空节点树 | 助手记录原因并返回空结果，不影响常规截图与触控 |
| 网络与证书 | 支持系统固定代理与直连，不执行 PAC 脚本；内置 JRE 使用自带根证书库 | 使用固定代理或直连；企业中间人证书环境需排查 TLS 信任链 |
| 凭据加密 | 账号凭据通过 Windows DPAPI 绑定当前系统用户 | 迁移电脑或更换 Windows 用户后重新输入凭据 |
| 人工验证码 | B 服二次验证通过系统默认浏览器完成 | 无默认浏览器时复制界面提供的回环链接手动打开 |
| 会战助手界面 | 与上游一致，暂未接入导航入口 | 待上游接入后同步 |
| 标题页过渡帧识别 | 标题页动画纯色过渡帧偶发被共享规则分类为 `UNKNOWN` 或 `BATTLE_FAILED` | 受稳定帧与冷却约束，不影响常规点击推进 |
| OCR 样本覆盖 | 固定 PP-OCRv5 在当前 15 条真实 ROI 上全部通过（15/15），但距 80 条全量目标（每类至少 8 条，含角色奖励类）仍有样本缺口 | 持续补充真实游戏截图与 ROI 真值，见 [OCR 评估](../desktop/ocr/evaluation/README.md) |

## 后续可选方向

MuMu / 雷电原生截图扩展、安装包与自动更新、定时任务与完成后动作（关模拟器/关机）、全局热键、从助手启动模拟器、Windows on ARM 验证。
