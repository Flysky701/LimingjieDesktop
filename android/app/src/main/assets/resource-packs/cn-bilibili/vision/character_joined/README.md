# 角色加入通用页面

2026-10-26 按当前 `LabyrinthCharacterRecognizer` 核对。本目录用于 Android 与桌面共用的角色加入页；不要与初始奖励三选一的文字优先识别流程混用。

页面本身继续使用通用的“角色加入”标题、说明和关闭按钮模板。

运行时的角色身份识别使用 `icons.json` 全角色头像模板和共享
`LabyrinthCharacterIconMatcher`。当前页面只提供角色头像 ROI；识别结果通过统一的
confidence + rival margin 安全门槛后才允许写入本局角色池。

以下角色名称模板仅作为**历史回归素材**保留，不再决定正式流程中的角色身份：

- `characters/character_1209_name.png`：伊莉亚(新年)
- `characters/character_1257_name.png`：花凛(炼金术师)
- `characters/character_1139_name.png`：纺希(万圣节)

因此角色加入页不再只支持这三个旧样本。头像识别不可信时不会猜测角色；关闭动作仍由通用
关闭按钮锚点控制，角色池还会由稳定的战斗编组页做 append-only 自愈补全。

只读模式不执行关闭；截图中出现角色不等于已写入本局角色池，仍需满足身份可信与流程条件。桌面真实角色加入场景的验收不能用这些旧模板替代。
