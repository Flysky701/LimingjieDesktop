# 历史 UI 证据索引

采集日期：2026-10-25；索引核对日期：2026-10-26。这里保留 UX-0 / UX-1、早期原生窗口和 P1 首页证据，不是 desktop.7 的最新截图集。

- `inventory/`：当时 `1.0.18+desktop.1` 的真实 Windows 窗口，独立空数据目录；七个导航页 × 两种窗口设置，另有三个上游表单，共 17 张。无模拟器连接、无真实账号。窗口尺寸设置 1280×800 / 1100×700；系统捕获 PNG 为 1268×794 / 1088×694。
- `design/`：当轮 HTML 设计稿的浏览器截图。五类关键页 × 浅深色 × 1280×800 共 10 张，另外运行中和评分页 × 浅深色 × 1100×700 共 4 张。**所有运行数据均为示例，不是实机或 Graph 回放**。画面预览明确使用占位，不伪造游戏截图。
- `design/preview-check.json`：20 组 HTML 布局检查与代表性交互检查结果；不代表 Kotlin presenter、Compose、存储、运行性能或游戏流程已经验证。

- [native/](native/README.md)：三张布局调整过程中的 Windows 原生窗口截图。
- [p1-home/](p1-home/README.md)：三张实际 Compose 渲染器生成的离线首页图，不是 Windows DPI 验证。

原阶段背景见[盘点](../../ux/INVENTORY.md)和[检查点 A 说明](../../ux/DESIGN-REVIEW.md)。当前程序已采用六项导航、共享全局页面与设备运行标签，见[D7 报告](../../reports/SHARED-RUN-TABS-D7.md)。该报告引用的离线截图输出为 `desktop/app/build/reports/shared-run-tabs.png`，属于本机构建产物，不保证存在于新检出仓库。旧截图不用于证明当前布局、性能、DPI 或真实游戏验收通过。
