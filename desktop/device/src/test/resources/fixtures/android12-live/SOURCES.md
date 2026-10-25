# 实采 fixture：MuMu Android 12

2026-10-25，Windows 主机、MuMu Android 12 / API 32、ADB 36.0.0。用户授权计划内实机验证，实例原来关闭，验证对象仅系统设置。

- `ui-settings.xml`：18:48 的真实 Settings 根页成功 dump，来自 MaaTouch hold 返回根页后的最后一份成功 XML（47 节点）。随后 MuMu 的 uiautomator 出现 null-root/idle 错误，该文件没有冒充后续失败 dump 的结果。
- `window-settings.txt`：18:56 同一实例 Settings 前台，仅保留 dumpsys window 的 mCurrentFocus/mFocusedApp 两行，其他应用窗口信息不保存。
- `power.txt`、`display-size.txt`：18:47 实采 wakefulness 和 wm size。wm 为 720×1280，实际横屏截图 1280×720，触控允许转置且实测点击正确。

没有访问账号/登录存储；节点内容为系统设置的通用文本。既有 android12/ 与 android9/ 目录仍为合成边界 fixture，不将这些文件改标为实采；本组补充 Android 12 真实性，Android 9 仍待可用设备。
前台片段中的 Window/ActivityRecord 随机记录 ID 与任务编号已替换为占位值；没有保存 android_id、boot_id、设备 serial 或完整 identity 文件。其余字段结构与系统设置通用文字保留。
