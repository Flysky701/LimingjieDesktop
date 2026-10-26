# 获得道具界面模板

用于识别连结印记三选一完成后的“获得道具”弹窗，Android 与桌面共用。

- `item_reward_title.png`：弹窗标题“获得道具”
- `item_reward_instruction.png`：弹窗说明“获得了以下道具。”
- `item_reward_close.png`：底部“关闭”按钮

本组弹窗联合标题、说明和关闭按钮确认。`ITEM_REWARD` 分类还包含「迷宫遗物效果」与「遗物效果结果」的锚点分支。

`LabyrinthEntryActionPlanner` 在页面稳定后生成 `CLOSE_ITEM_REWARD` 动作，由会话跟进关闭结果。
