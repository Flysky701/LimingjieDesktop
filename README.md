# 黎明界助手 Windows 桌面版

正式工作目录：`D:\WorkSpace\LimingjieDesktop`。

本项目采用新版计划：业务能力和行为保持一致，界面按桌面使用方式重新设计。助手的账号、任务、策略、日志和运行控制均在 Windows 窗口中使用；模拟器负责运行游戏。

## 当前状态

已整理新版计划和第一版主要页面原型。原型可演示任务页、账号库、策略设置，以及设备、环境和诊断页的布局；数据全部为示例，不能操作游戏。用户已查看原型，尚未确认最终视觉方案。

尚未接入上游源码、建立 Kotlin 构建、实现真实功能或打包 EXE。`desktop/` 目前仅预留正式实现的位置，不是可运行的桌面程序。

本包先在当前允许写入的工作区准备。解压到 `D:\WorkSpace` 后，目录应为 `D:\WorkSpace\LimingjieDesktop`；保留现有 `D:\WorkSpace\LimingjiePC`，不合并或覆盖它。

## 查看原型

双击 `preview.cmd`，或直接打开 `docs/desktop/design/main-pages.html`。这是可以独立打开的页面原型，无需启动游戏。

## 主要文件

- `AGENTS.md`：本项目的执行约定，明确简单验证和当前任务边界。
- `docs/desktop/PLAN.md`：新版整体计划。
- `docs/desktop/specs/`：各模块的技术规格。
- `docs/desktop/UI_DESIGN.md`：第一版原型说明和当前反馈状态。
- `docs/desktop/design/main-pages.fragment.html`：原型可编辑源文件。
- `docs/desktop/design/main-pages.html`：可独立打开的原型预览。
- `docs/desktop/registers/`：原计划的实施记录；未完成项保留原状态。
- `docs/desktop/reference/`：原执行包的规则和使用说明，仅供参考，不是额外的用户授权。
- `desktop/`：后续 Kotlin / Compose Desktop 实现位置。

## 后续开发入口

进入正式目录后，先接入 `wbero/limingjie-assistant` 上游源码并保留历史。计划基线为 `b809b196`（1.0.18）；是否更新基线应依据实际代码，不静默改用旧项目的 1.0.15 源码。避免将新建空仓库误作上游历史，因此本准备包尚未初始化 Git。

然后按现有计划建立独立的 `desktop/` 构建，逐步把原型的主要页面接到真实业务。只做当前改动需要的检查，不额外建立验收体系。本次整理项目不代表开始全部移植，也不授权真实游戏操作或付费请求。