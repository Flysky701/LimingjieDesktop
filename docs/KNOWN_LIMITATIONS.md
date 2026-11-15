# 已知限制与待办

用户侧常见问题与排查方法见 [使用指南](USER-GUIDE.md#日志与常见问题)。本文记录待修复的共享业务问题及当前平台限制。

## 待修复与验证问题

以下问题涉及 `android/` 中的共享业务流程，修复时需同时兼顾桌面与 Android 端行为：

- [ ] **FIX-01：重新挑战后的残留失败画面可能被误计为第三次战败**
  - **现象**：单局记录“EX战连续3次挑战失败”并停止，随后重置接手时画面已处于 `BATTLE_CHALLENGE`，因重置阶段无挑战页推进路径而超时。
  - **原因**：[LabyrinthEntryRecognitionSession.kt](../android/app/src/main/java/com/landosol/toolbox/labyrinth/LabyrinthEntryRecognitionSession.kt) 在“重新挑战”点击返回成功时立即递增 `battleRetryCount`，仅重置稳定帧数而未等待离开失败页；连续两帧残留失败画面即可再次触发计数，且三次失败终止判断位于点击冷却检查之前。
  - **修复方向**：要求离开失败页或进入新一轮战斗后再计入下一次战败，并核对 [SessionExpiryFrameTracker.kt](../android/app/src/main/java/com/landosol/toolbox/automation/session/SessionExpiryFrameTracker.kt) 在挑战页的回收衔接。

- [ ] **FIX-02：启动阶段公告前 45 秒总时限可能在慢加载时提前终止任务**
  - **现象**：从标题页点击后若加载漫画或过渡页耗时较长，任务在正常加载中被终止并笼统记录为“会话重置失败”。
  - **原因**：[LabyrinthEntryActionPlanner.kt](../android/app/src/main/java/com/landosol/toolbox/labyrinth/LabyrinthEntryActionPlanner.kt) 从首次标题页动作起累计公告前 45 秒固定上限，页面变化不重置或延长预算；[GameSessionResetWorkflow.kt](../android/app/src/main/java/com/landosol/toolbox/automation/session/GameSessionResetWorkflow.kt) 捕获失败后未向上层保留具体超时原因。
  - **修复方向**：区分页面仍有进展的正常加载与持续卡死，并将具体停止原因传递到运行记录。

- [ ] **FIX-03：通关结算阶段衔接（待实机连续多局回归）**
  - **现象**：关闭分数后若停留在 `SCORE_RESULT` 阶段，进入 `RUN_CLEAR_CHEST_RESULT` 时不触发确认点击，导致连续多局在宝箱结果页超时中断。
  - **当前进展**：已在共享会话中补充宝箱动画、宝箱结果与最终道具页的阶段推进恢复，并通过残留分数帧与漏帧的连续两局离线回放；待实机连续多局验证后闭环。

- [ ] **FIX-04：长耗时 Boss 战受固定 5 分钟等待上限截断**
  - **现象**：多队或高血量 Boss 战（如 `Boss#30701`）在战斗仍正常进行、倒计时未结束时满 5 分钟被强制终止。
  - **原因**：[LabyrinthBattleWaitPolicy.kt](../android/app/src/main/java/com/landosol/toolbox/labyrinth/LabyrinthBattleWaitPolicy.kt) 采用固定 300,000 ms 现实时间上限，未结合多队接力、倒计时或血量变化判断战斗进展。
  - **修复方向**：结合战斗进展信号调整多队 Boss 战等待预算，同时保留卡死保护上限与诊断状态。

- [ ] **FIX-05：地图恢复后未重置未知页面计时，后续过场可能提前终止（下一轮优先修复）**
  - **现象**：2026-11-14 的 1920 × 1080 运行在 15:29:47 点击普通战斗节点的移动确认，约 1.8 秒后以“路线阶段未知页面超过限定时间”停止；此前已有约半分钟的地图识别、点击重试和视角恢复。
  - **已确认缺陷**：[LabyrinthEntryRecognitionSession.kt](../android/app/src/main/java/com/landosol/toolbox/labyrinth/LabyrinthEntryRecognitionSession.kt) 的 `postEntryUnknownSince` 仅在部分页面处理路径重置；`NODE_SELECTION` 走独立地图分支，移动确认也可提前返回，导致已恢复的地图流程未清空旧计时。该缺陷与本次停止时间线吻合。
  - **修复与验证**：在确认恢复到已识别页面时清空旧的未知页面计时，覆盖地图与移动确认的分流路径。补充“未知页 → 地图停留及重试 → 移动确认 → 新过场”的离线回放，验证新过场不会继承旧计时，同时保留持续未知页面的 30 秒停止保护。

- [ ] **FIX-06：普通战斗节点连续点击未确认，移动视角后才恢复（下一轮核对）**
  - **现象**：同次运行的普通战斗节点 `20303` 最初使用置信度约 0.405 的语义补全匹配，连续三次点击均未出现移动确认；向后挪动地图后改用置信度 0.940 的拓扑匹配，随后成功点击移动确认。
  - **待确认部分**：日志能确认点击未生效和视角恢复过程，但缺少对应画面，尚不能确定是节点错绑、坐标偏移、动画遮挡还是点击未落地。
  - **当前进展**：已同步上游弱语义候选点击准入、视觉与拓扑证据分离、当前帧复核及旧点击许可撤销修复；覆盖了低置信候选与缓存证据的相关风险，但尚不能据此确认上述实机现象已解决。
  - **核对与验证**：核对节点身份、可达集合与屏幕位置的绑定，重点检查低置信语义补全与拓扑匹配切换。取得对应画面后建立定向离线回放，确认问题再修复，保留现有点击守卫及有界重试。

- [ ] **FIX-07：不足五人的 EX 战首次编组可用，失败重试却被人数门槛阻断（下一轮修复）**
  - **现象**：2026-11-14 15:47:50 首次推荐为三人队伍；15:50:15 起重复提示“战斗失败后可用角色不足5名，无法生成完整重试队伍”，编组页未再派发动作，15:52:15 因连续 120 秒无进展停止，单局结果记为 `ABORTED_UNKNOWN`。
  - **已确认缺陷**：[LabyrinthBattleTeamRecommendation.kt](../android/app/src/main/java/com/landosol/toolbox/labyrinth/LabyrinthBattleTeamRecommendation.kt) 的 `initialRecommendation` 使用 `minOf(5, resolved.size)` 接受不足五人的队伍，而 `retryRecommendation` 直接拒绝不足五人的角色池，并在规划结果处再次要求完整五人。两条路径的人数规则不一致；[LabyrinthEntryRecognitionSession.kt](../android/app/src/main/java/com/landosol/toolbox/labyrinth/LabyrinthEntryRecognitionSession.kt) 最后由编组页的 120 秒停滞保护收尾。
  - **修复与验证**：统一首次与重试的合法人数规则，检查失败队伍记录、重试搜索和执行器对不足五人队伍的支持；无法继续时沿用批次策略明确结束或交接。补充三人、四人、正常五人及无可用角色的定向离线回放，保留重试次数与点击守卫，并与 FIX-01 的失败计数问题分别验证。

- [ ] **FIX-08：普通战编组提示一号位生存资格不足后停滞（下一轮核对）**
  - **现象**：2026-11-14 15:55:33 进入普通战斗节点 `10402`；15:55:35 起持续提示“自动战斗要求实际一号位满足生存资格；当前角色池无法组成满足条件的队伍”，没有派发编组动作，15:57:35 因连续 120 秒无进展停止，单局记为 `ABORTED_UNKNOWN`。此前同局普通战斗节点 `10202` 曾成功生成三人推荐，随后经过连结节点 `10302`。
  - **已定位路径**：[LabyrinthRoleDecisionPolicy.kt](../android/app/src/main/java/com/landosol/toolbox/labyrinth/LabyrinthRoleDecisionPolicy.kt) 的 `LabyrinthTeamPlanSearcher.initialSearch` 在特定前排约束下返回空方案；`vanguardlessPlan` 无法生成补充队伍时也使用同一句原因。日志不能区分两条路径，尚未确认是实际站位冲突、资格规则、角色池变化还是角色资料不足。
  - **当前进展**：已同步上游第一层全部战斗的无合格前排编组例外，角色池扩充到超过五人后仍可回退；保留后续楼层的原有资格限制。该改动覆盖第一层前排门槛导致拒绝编组的路径，当时角色池及实机表现仍待核对；不包含 FIX-07 的不足五人重试修复。
  - **核对与验证**：核对进入 `10402` 时的可用角色、站位与生存资格，以及连结节点前后的角色池变化；区分拒绝原因并明确编组不可用时的后续处理。补充角色池跨过五人、前排站位冲突及资料不足的定向离线回放，保留现有资格判定与点击守卫，并与 FIX-07 的重试人数门槛分别跟踪。

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
