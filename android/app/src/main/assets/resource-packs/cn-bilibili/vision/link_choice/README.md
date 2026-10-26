# 连结印记三选一模板

本目录保存 `LabyrinthLinkChoiceRecognizer` 使用的页级锚点和五种属性徽记：

- `link_choice_title.png`、`link_choice_instruction.png`、`link_choice_remaining.png`：识别“获得道具的机会 / 请选择要获得的连结印记”页面。
- `element_fire.png`、`element_dark.png`、`element_light.png`、`element_wind.png`、`element_water.png`：卡片右下角属性徽记。

运行时以 1920×1080 标准画布的相对位置映射到当前截图，属性识别读取徽记的色相和形状区域，不读取卡片标题 OCR。默认优先级为：火 > 暗 > 光 > 风 > 水。

优先级由 `LabyrinthLinkChoicePriority` 定义，在已确认属性的选项间选择。素材来自项目所有者提供的通关视频和模拟器截图；桌面自动执行使用 1920×1080 横屏。
