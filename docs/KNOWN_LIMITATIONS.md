# 已知限制与待办

常见问题排查见 [使用指南](USER-GUIDE.md#日志与常见问题)；视觉全面审查/冗余增强、NN 模型交付与 Path B 规划路线见 [统一路线图](ROADMAP.md)。

## 待修复与验证问题

以下问题位于 `android/` 共享业务中，修复与回归同步作用于桌面端与 Android：

| ID | 问题与当前修复摘要 | 状态与验证要求 |
| --- | --- | --- |
| **FIX-01** | **重挑战时序与挑战页回收**：[LabyrinthEntryRecognitionSession.kt](../android/app/src/main/java/com/landosol/toolbox/labyrinth/LabyrinthEntryRecognitionSession.kt) 点击“重新挑战”后在进入新战斗前不重复累计战败；[SessionExpiryFrameTracker.kt](../android/app/src/main/java/com/landosol/toolbox/automation/session/SessionExpiryFrameTracker.kt) 补齐编组取消、挑战页看图与撤退确认失效链 | 离线回放通过，待实机连续战败交接回归 |
| **FIX-02** | **慢加载时限与失败原因透传**：[LabyrinthEntryActionPlanner.kt](../android/app/src/main/java/com/landosol/toolbox/labyrinth/LabyrinthEntryActionPlanner.kt) 为已识别加载/数据同步页提供单阶段 90s 预算且不扣未知页点击配额（总入口仍受 120s 约束）；[GameSessionResetWorkflow.kt](../android/app/src/main/java/com/landosol/toolbox/automation/session/GameSessionResetWorkflow.kt) 与 [LabyrinthAutoRunWorkflow.kt](../android/app/src/main/java/com/landosol/toolbox/labyrinth/LabyrinthAutoRunWorkflow.kt) 透传阶段失败原因并校验 `READY_FOR_NEXT_ROUND` | 离线回放通过，待实机慢速冷启动复测 |
| **FIX-03** | **结算宝箱衔接**：补充 `SCORE_RESULT` 后宝箱动画/结果及漏帧直达最终道具页的推进恢复 | 连续两局离线回放通过，待实机连续多局闭环 |
| **FIX-04** | **长耗时/多队 Boss 等待上限**：[LabyrinthEntryFrameProcessor.kt](../android/app/src/main/java/com/landosol/toolbox/labyrinth/vision/LabyrinthEntryFrameProcessor.kt) 与 [LabyrinthBattleWaitPolicy.kt](../android/app/src/main/java/com/landosol/toolbox/labyrinth/LabyrinthBattleWaitPolicy.kt) 监测 `BATTLE_IN_PROGRESS` 顶部 HUD，静止保持 5 分钟，持续进展按「最近进展 + 60s 停滞」顺延至 `5 + 3 × 队伍数` 分钟（8/11/14 分钟） | 离线测试通过，待实机多队 Boss 复测 |
| **FIX-05** | **路线恢复清空未知计时**：[LabyrinthEntryRecognitionSession.kt](../android/app/src/main/java/com/landosol/toolbox/labyrinth/LabyrinthEntryRecognitionSession.kt) 在已识别路线页与弹窗清空旧 `postEntryUnknownSince`，保留连续未知 30s 保护 | 离线回放通过，待实机复核 |
| **FIX-06** | **普通战节点连点未确认**：曾因低置信（~0.405）语义补全误匹配，挪动视角切到高置信（0.940）拓扑匹配后恢复；已合入当前帧点击证据、弱候选限制、`FULL_COLUMN` 冲突列护栏与 `>= 0.45` 绑定门槛，并在 [LabyrinthDebugDashboardServer.kt](../android/app/src/main/java/com/landosol/toolbox/labyrinth/debug/LabyrinthDebugDashboardServer.kt) 接入 10 槽 / 30 MiB 无损 PNG 事故现场导出 | M1 视觉冗余与事故留存已离线回归，待实机抽检关闭 |
| **FIX-07** | **不足五人编组与重排去重**：[LabyrinthBattleTeamRecommendation.kt](../android/app/src/main/java/com/landosol/toolbox/labyrinth/LabyrinthBattleTeamRecommendation.kt) 与 [LabyrinthRoleDecisionPolicy.kt](../android/app/src/main/java/com/landosol/toolbox/labyrinth/LabyrinthRoleDecisionPolicy.kt) 统一单队 `minOf(5, resolved.size)` 及小池兜底（Boss 多队仍限 5 人、已失败阵容不重复），无可替换阵容立即按失败收口并将重试评估卸载至后台线程 | 离线回放通过，待实机复核 |
| **FIX-08** | **普通战一号位生存资格不足停滞**：[LabyrinthRoleDecisionPolicy.kt](../android/app/src/main/java/com/landosol/toolbox/labyrinth/LabyrinthRoleDecisionPolicy.kt) 在 `10402` 触发空方案后空等 120s 停止；已合入第一层例外、前排生存门槛开关、355px 角色奖励锚框与 1088 特殊网格左外推校准 | M1 角色识别与离线单测已通过，待实机按原角色池复核 |

## 平台与运行限制

| 范围 | 限制说明 | 处理或规避 |
| --- | --- | --- |
| 发行包与签名 | 仅构建 Windows 10/11 x64 便携包，未做代码签名 | 核对 `.sha256` 后在 SmartScreen 提示中继续运行 |
| 分辨率与动画 | 自动执行支持 **1920 × 1080**（720p 开放测试中），需开启 ADB 并关闭「播放角色加入动画」 | 非支持尺寸仅开放连接、预览与只读识别 |
| ADB 工具 | 发行包不内置 `adb.exe` | 自动探测模拟器自带 ADB 或在设备页指定路径 |
| 渲染与电源 | 截图依赖模拟器持续渲染；防睡眠仅阻空闲睡眠 | 运行时勿最小化模拟器，避免手动睡眠、合盖或锁屏 |
| 节点树导出 | MuMu 部分系统设置页 `uiautomator` 可能返回空树 | 记录原因返回空结果，不影响常规截图与触控 |
| 网络与证书 | 支持系统固定代理与直连，不支持 PAC；使用自带 JRE 根证书库 | 企业中间人证书环境需排查 TLS 信任链 |
| 凭据与验证码 | 凭据通过 Windows DPAPI 绑定当前用户；B 服二次验证走默认浏览器 | 迁移用户/电脑后重填凭据；无默认浏览器时手动复制回环链接 |
| 会战入口与过渡帧 | 会战助手导航暂未接入；标题页纯色过渡帧偶发误判 `UNKNOWN`/`BATTLE_FAILED` | 会战待上游接入；过渡帧受稳定帧与冷却保护 |
| OCR 样本覆盖 | 固定 PP-OCRv5 当前真值 **15/15** 通过，局内 α 币收支账本已弥补商店单点观测空白；距 80 条全覆盖目标仍有缺口 | 待实机补充角色奖励与边界帧样本，见 [ROADMAP.md](ROADMAP.md) M1 与 [OCR 评估](../desktop/ocr/evaluation/README.md) |
