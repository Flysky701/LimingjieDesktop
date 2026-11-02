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
