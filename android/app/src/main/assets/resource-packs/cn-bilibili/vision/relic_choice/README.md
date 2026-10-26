# 迷宫遗物三选一

本目录保存“获得道具的机会 → 请选择要获得的迷宫遗物”的页级指令模板，Android 与桌面共用。

它与 `vision/link_choice/` 分开：连结页使用“请选择要获得的连结印记”和属性徽记；遗物页不运行连结属性识别，避免把遗物图标误判为火/暗/光/风/水。

具体遗物由 `LabyrinthRelicRecognizer` 识别，`LabyrinthRelicChoicePolicy` 结合已保存策略推荐选项。桌面自动执行使用 1920×1080 横屏。
