# 角色加入通用页面

本目录用于 `LabyrinthCharacterRecognizer` 的角色加入页，页面以通用标题、说明和关闭按钮确认。

运行时的角色身份识别使用 `icons.json` 全角色头像模板和共享
`LabyrinthCharacterIconMatcher`。当前页面只提供角色头像 ROI；识别结果通过统一的
confidence + rival margin 安全门槛后才允许写入本局角色池。

历史名称回归素材：

- `characters/character_1209_name.png`：伊莉亚(新年)
- `characters/character_1257_name.png`：花凛(炼金术师)
- `characters/character_1139_name.png`：纺希(万圣节)

头像证据不足时等待下一帧。关闭动作由通用按钮锚点控制；稳定的战斗编组页可向角色池补充已确认角色。
