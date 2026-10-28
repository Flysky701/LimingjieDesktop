# 桌面界面设计记录

2026-10-25：用户指定 VS Code 默认 Light Modern / Dark Modern，并确认“先改成这样就行了”。当前实现为 `1.0.18+desktop.2`，实现与验证边界见 [UX-VSCODE](reports/UX-VSCODE.md)。正式实现位于 desktop/app/.../ui/，与 HTML 示例分开保存。

六项导航为运行、账号、策略、设备、记录、设置。运行台展示就绪状态、五个任务入口、配置摘要、运行状态与分类日志；固定底栏提供开始、暂停/继续和停止。宽窗口右栏显示预览与日志，窄窗口使用切换页，各栏可滚动。

完整批量目标与刷取配置以完整页面复用 LabyrinthScreen；策略、开局编组继续复用上游表单，以约 880dp 的桌面模态外框承载。角色评分提供桌面表格与原版入口，保留范围、草稿、保存、取消和离开确认。桌面新布局不改业务算法。

主题、文字与尺寸集中在 `ui/theme/DesktopTheme.kt`：浅色背景 #f8f8f8、面板 #ffffff、主按钮 #005fb8；深色背景 #181818、面板 #1f1f1f、主按钮 #0078d4。使用 Microsoft YaHei UI（不可用回落系统字体），正文 13–14sp、小字 12.5sp，可选放大至 1.12 倍；间距 4/8/12/16/24dp，圆角 8/10/14dp。图标为 Material Symbols Rounded SVG，许可随仓库保存。

保留早期文件：design/main-pages.fragment.html、design/main-pages.html。当前设计预览为 [ux-review.html](design/ux-review.html)，已同步 VS Code 配色。原型只使用示例数据；真实功能状态以 Kotlin 实现和验证报告为准。

## 验证范围

编译、本地应用镜像与相关定向检查通过。完整原生 DPI 矩阵、10 分钟实机性能及运行中关闭分支尚未完成；策略外框 Alt+F4 关闭仍需复核。用户确认本轮保留当前效果，不继续追加采集或新便携包。早期窗口记录见 [UI-1](reports/UI-1.md)，本轮实际边界以 [UX-VSCODE](reports/UX-VSCODE.md) 为准。
