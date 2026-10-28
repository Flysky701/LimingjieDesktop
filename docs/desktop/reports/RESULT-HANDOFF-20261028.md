# RESULT 边界与下一轮交接核查

2026-10-28；修复基于发布线 `299749042f752e9f796a333464c45017abac8e1e`（desktop.11），只在隔离分支准备，未发布或安装。

## 实际故障与证据边界

FLY-LAPTOP 的 desktop.11 日志三次记录了“单轮通关、分数未识别”，随后重置从 `RUN_CLEAR_RESULT` 开始，在 5 秒内没有点击，返回 `SESSION_EXPIRY/TIMEOUT`，批次停止。时间为 2026-10-28 UTC+8：01:25:13→01:25:39、02:15:51→02:16:10、02:35:02→02:35:17。

首次失败路径为 `SessionExpiryFrameTracker.sessionInvalidationTrigger` 在 RESULT 返回 null，`AnchorTriggerSessionExpiryTerminator` 等待触发点超时，真实 reset workflow 和批次停止。这不是抛异常崩溃。日志和脱敏摘录保留本机，未提交账号或运行数据。

未找到这些时刻的截图/帧归档，不能唯一确认用户所指轮次，也不能证明当时是角色汇总页还是总分页。仓库历史 fixture 的像素已经人工查看：Boss 摘要为 WIN/下一步；角色汇总为 RESULT/加入的角色/下一步；真正总分页为 RESULT/总分与明细/关闭。历史样图不是当次失败截图。

## 当前修复

- 从已有角色汇总 fixture 提取中心“下一步”模板（752,948,416,92），与同位置“关闭”模板分别匹配。RESULT 大标题加明确下一步判为角色汇总；大标题加明确关闭判为总分页；按钮不可信时保持 UNKNOWN，避免落回背景地图。
- 角色汇总继续按已识别按钮推进；只有总分页接入既有分数采集。分数仍可空，最多等待 6 秒；最终 RESULT 到达即算通关，时间记首次到达，不要求返回主页。
- 下一轮 reset 可以从结果、角色汇总、宝箱结果、道具奖励的可信按钮继续导航。仍受既有点击次数和超时约束；地图、动画和 UNKNOWN 不给固定点击。只有实际会话失效并回标题，或已同步原生主页，才能完成终止阶段，随后还须到黎明界可接管页。
- 修正另一项回归实证：入口规划器的 Complete 也包括 RESULT 等局内页，不能被 reset 当作 READY。桌面适配器同时明确要求 `READY_FOR_NEXT_ROUND`、`STOPPED`、无阻塞。
- 最后一轮结束不启动 reset，保留结果；重复帧及重复终态不重复记账。
- 托盘实现保持原样，仅恢复 DesktopSelfTest 使用实际 `trayMenuFont()` 和完整菜单字符集验证。

## 验证方法与范围

先对旧发布代码运行实际 fixture/生产 frame tracker 回归，角色摘要分类及 RESULT 导航起点两项均失败。修复后转绿。追加“标题后意外重现 RESULT”回放，旧 reset 实际错误启动第二轮；修复后要求拒绝该起点。

`LabyrinthTwoRoundHandoffTest` 串联生产分类器、单轮会话、批次控制器、frame tracker、AnchorTrigger 终止器及 GameSessionResetWorkflow。捕获输入与点击后的画面由离线回放提供；协议刷开局只返回测试值，不访问账号/游戏。第一轮有分数，第二轮无分数超时，覆盖摘要推进、重复终态、真实 READY 回调和最后一轮结果保留。这不是实机两轮验收。

共享层定向测试 104 项通过（含两条完整离线交接测试）；桌面实际托盘字体与位置测试 4 项通过；真实 OCR 总分 fixture 测试 1 项通过、无跳过。桌面 app 编译通过。未操作真实游戏、未运行 Android 构建、未打包或替换运行程序。Boss 历史 fixture 在原发布线分类 UNKNOWN 的限制单独保留并断言；本次同时验证其不误记整轮通关，未宣称修复全部 Boss 识别。

`checkUpstreamDrift` 失败：发布线已经存在的 `AndroidLabyrinthResultScoreResolver.kt` 未登记在 `desktop/shared/upstream-coupling.txt`。用 `git show 2997490:<path>` 核对了源码存在和清单遗漏；本次未改该文件、未绕过或更新检查基线。该失败不代表单测失败，也不能宣称完整 check 通过。没有发现本轮所测 OCR 用例失败；未运行完整 OCR 评估。

## 分支成果保全

远端只读核对仍为四条：发布线 `2997490`，main `308368d`，shared-run-tabs `ad82cf5`，托盘 `f07a85e`。shared-run-tabs 是 main 的祖先。主 checkout 的 README 和 output 未提交内容保持原状。

本机 `codex/logic-decomposition` 位于独立 worktree，tip `ef73ef5`，干净且有发布线未包含的 18 条提交。没有整批合入，也没有删除任何成果：

| 功能 | 独有提交 |
|---|---|
| 拆解规划、规则与工作流基础 | `9566e81`、`2aa95f8`、`be995f4` |
| 地图移动、定位、滚动与恢复 | `e1cdec0`、`934a7df`、`44c9ef1`、`c859b1c` |
| 队伍执行与准备 | `838c893`、`b1c2ca0` |
| 奖励批次与获得状态 | `ab46841` |
| 特别装备所有权与边界 | `46ffb84`、`ef73ef5` |
| 预览、列表及角色目录复用 | `e2c955f`、`3098588`、`2ff75b8`、`b50a378`、`60a9ec3` |
| 领域所有权说明 | `79d756a` |

托盘实现及既有测试已在发布线；本分支只补遗漏的实际字体自检。logic-decomposition 与发布线已经分叉，逐项承接必须保留发布线的 ADB、托盘、结果统计及本次边界修复，不能用整树覆盖。仍待实机补齐故障帧与连续两轮复现验证。
