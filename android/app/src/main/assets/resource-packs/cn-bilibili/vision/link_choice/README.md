# 连结印记三选一模板

2026-10-26 按当前 `LabyrinthLinkChoiceRecognizer` 与 `LabyrinthLinkChoicePriority` 核对。本目录是 Android 与桌面共享的视觉素材，属性优先级来自策略代码，不写在模板图片中。

本目录只存连结节点三选一的页级锚点和五种属性徽记模板：

- `link_choice_title.png`、`link_choice_instruction.png`、`link_choice_remaining.png`：识别“获得道具的机会 / 请选择要获得的连结印记”页面。
- `element_fire.png`、`element_dark.png`、`element_light.png`、`element_wind.png`、`element_water.png`：卡片右下角属性徽记。

运行时以 1920×1080 标准画布的相对位置映射到当前截图，属性识别读取徽记的色相和形状区域，不读取卡片标题 OCR。默认优先级为：火 > 暗 > 光 > 风 > 水。

模板来源为项目所有者提供的历史通关视频及模拟器只读截图；只读识别模式不会点击三选一按钮。默认属性顺序只决定已可靠识别选项之间的优先级，不把未知属性猜成最高优先项。

坐标映射用于识别，不代表任意截图尺寸都支持自动点击；桌面自动执行要求实际截图为 1920×1080 横屏。真实连结奖励流程仍需单独的游戏场景验收。
