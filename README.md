# 黎明界助手 Windows 桌面版

在 Windows 中管理《公主连结 Re:Dive》黎明界任务、账号与策略。模拟器负责运行游戏，助手负责配置、执行和查看结果。

**当前源码：1.0.18+desktop.8 测试候选版 · Windows 10/11 x64 · Kotlin / Compose Desktop**

本次冻结兼容路线的现有成果，待用户实机测试后发布。包含设备检测修复与五项公共性能优化，详见[基础版说明](docs/desktop/reports/BASELINE-DESKTOP8.md)；Release 下载入口仍以已发布版本为准。

[下载 Release](https://github.com/Flysky701/LimingjieDesktop/releases/latest) · [使用指南](README-desktop.md) · [开发与构建](desktop/README.md)

## 功能

- **多设备运行**：每台模拟器有独立运行页，支持开始、暂停、停止和停止全部。
- **共享账号与策略**：集中管理账号、刷开局配置、编组和个人评分；运行中的任务使用启动时的策略。
- **设备连接**：自动发现或手动添加 ADB 地址，支持 MuMu 端口发现、截图预览、MaaTouch 与兼容输入。
- **记录与诊断**：按设备、账号查看运行记录，搜索日志并导出诊断包。

## 开始使用

1. 从 [Release](https://github.com/Flysky701/LimingjieDesktop/releases/latest) 下载 Windows ZIP 和校验文件，完整解压后运行 `LimingjieAssistant.exe`。程序自带 Java 21。
2. 将模拟器设为 **1920 × 1080 横屏**，开启 ADB 调试，在助手「设备」页连接并绑定账号。
3. 打开设备运行页，配置任务与策略，保存后开始。

安装、快捷键、数据备份和常见问题见[使用指南](README-desktop.md)。

## 从源码运行

准备 x64 JDK 21，在仓库根目录执行：

```powershell
desktop\gradlew.bat -p desktop :app:run
```

构建便携包：

```powershell
desktop\gradlew.bat -p desktop :app:packagePortableZip
```

ZIP 与 SHA-256 输出到 `desktop/app/build/dist/`。模块结构、测试和 CI 见[开发说明](desktop/README.md)。

## 文档

| 内容 | 入口 |
| --- | --- |
| 日常使用 | [安装与使用](README-desktop.md) |
| 开发维护 | [构建与 CI](desktop/README.md)、[上游同步](docs/desktop/registers/UPSTREAM_SYNC.md)、[接口契约](desktop/CONTRACTS.md) |
| 识别与策略 | [OCR 评估](desktop/ocr/evaluation/README.md)、[角色与配队模型](role-team-review/README-combat-model.md)、[事件](role-team-review/README-events.md)、[EX/Boss](role-team-review/README-ex-mechanics.md) |
| 版本与设计 | [D7 记录](docs/desktop/reports/SHARED-RUN-TABS-D7.md)、[历次报告](docs/desktop/FINAL_REPORT.md)、[页面原型](docs/desktop/design/main-pages.html)、[历史 UI 图集](docs/desktop/evidence/ux/README.md) |
| 上游资料 | [Android 说明](README-upstream.md)、[原移植计划](docs/desktop/PLAN.md)、[规格](docs/desktop/specs/)、[规划索引](docs/desktop/reference/README-PACK.md) |

## 来源与许可

业务代码复用 [wbero/limingjie-assistant](https://github.com/wbero/limingjie-assistant) 1.0.18。项目自身贡献采用 [CC BY-NC-SA 4.0](LICENSE)，第三方代码和资源见[第三方声明](THIRD_PARTY_NOTICES.md)。

本项目与游戏官方无关。自动操作及非官方接口访问可能影响账号状态，使用前请阅读[使用风险](README-desktop.md#使用风险与许可)。
