# 普通战斗挑战页模板

2026-10-26 按当前共享识别与执行源码核对。本目录是 Android 与桌面共用的页级模板；有模板或识别命中不等于真实游戏流程已经验收。

历史来源截图：`通关视频/分析输出/ADB只读回归/用户所指界面_当前帧.png`。这是原始素材的来源记录，不是当前仓库必备文件。

参考分辨率：1920×1080 横屏。

| 文件 | 用途 | 原图裁剪区域 |
| --- | --- | --- |
| `battle_challenge_header_normal.png` | 左上角“战斗格子（普通）”标题锚点 | x=60, y=60, w=620, h=100 |
| `battle_challenge_button.png` | 右下角“挑战”按钮 | x=1420, y=850, w=440, h=125 |
| `battle_challenge_view_map_button.png` | “查看地图”辅助锚点 | x=975, y=850, w=440, h=125 |

目录还包含 `battle_challenge_normal_suffix.png` 和 `battle_challenge_extreme_suffix.png`，供 `AndroidLabyrinthExEncounterResolver` 区分普通/极难挑战难度；它们不是另一套页面或分辨率适配保证。

普通分支联合标题和“挑战”按钮判定；EX/Boss 可复用同一按钮而具有不同标题，因此当前分类器还允许按钮达到更严格置信门槛时独立确认。“查看地图”模板保留为辅助素材，当前分类得分不以它为必要项。自动执行另需满足稳定页面与当前战斗上下文条件。

当前已接入 `BATTLE_CHALLENGE` 识别及 `LabyrinthEntryRecognitionSession` 的 `BATTLE_START_CHALLENGE` 动作，并包含后续战斗编组流程，不再是「只完成识别」。能否发起挑战由战斗上下文、难度及 EX 策略等条件决定；只读识别模式不点击。桌面自动执行还要求实际截图为 1920×1080 横屏，其他尺寸的坐标映射不表示已支持自动操作。
