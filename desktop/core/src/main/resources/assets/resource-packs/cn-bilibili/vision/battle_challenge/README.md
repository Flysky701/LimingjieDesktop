# 普通战斗挑战页模板

Android 与桌面共用的挑战页锚点。素材来源：`通关视频/分析输出/ADB只读回归/用户所指界面_当前帧.png`。

参考分辨率：1920×1080 横屏。

| 文件 | 用途 | 原图裁剪区域 |
| --- | --- | --- |
| `battle_challenge_header_normal.png` | 左上角“战斗格子（普通）”标题锚点 | x=60, y=60, w=620, h=100 |
| `battle_challenge_button.png` | 右下角“挑战”按钮 | x=1420, y=850, w=440, h=125 |
| `battle_challenge_view_map_button.png` | “查看地图”辅助锚点 | x=975, y=850, w=440, h=125 |

`battle_challenge_normal_suffix.png` 和 `battle_challenge_extreme_suffix.png` 用于区分普通 / 极难挑战，由 `AndroidLabyrinthExEncounterResolver` 读取。

普通分支联合标题和“挑战”按钮判定；EX/Boss 可复用同一按钮而具有不同标题，因此当前分类器还允许按钮达到更严格置信门槛时独立确认。“查看地图”模板保留为辅助素材，当前分类得分不以它为必要项。自动执行另需满足稳定页面与当前战斗上下文条件。

`BATTLE_CHALLENGE` 由 `LabyrinthEntryRecognitionSession` 衔接 `BATTLE_START_CHALLENGE` 与后续编组流程，结合战斗上下文、难度和 EX 策略决定动作。桌面自动执行使用 1920×1080 横屏。
