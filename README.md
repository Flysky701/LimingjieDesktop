# 黎明界助手 · Windows 桌面版

黎明界助手用于在 Windows 上配置、执行和记录《公主连结 Re:Dive》的黎明界任务。游戏在模拟器内运行，桌面助手提供多设备管理和独立的任务控制。

> **非官方工具。** 自动操作可能触发账号限制，使用前请了解[使用风险与许可](docs/USER-GUIDE.md#使用风险与许可)。

[下载 Windows 便携版](https://github.com/Flysky701/LimingjieDesktop/releases/latest) · [查看所有发布](https://github.com/Flysky701/LimingjieDesktop/releases) · [使用指南](docs/USER-GUIDE.md) · [开发说明](docs/DEVELOPMENT.md)

## 主要功能

- 多台模拟器可并行运行。每台设备有独立的运行页和后台进程。
- 集中管理账号与策略，各设备分别保存任务配置。
- 自动发现 MuMu 设备的 ADB 端口，也可手动添加设备。
- 按设备查看运行记录和日志，按需导出诊断包。

## 快速开始

1. 下载便携 ZIP 和同名 `.sha256` 文件。核对校验值后完整解压，运行 `LimingjieAssistant.exe`。适用于 64 位 Windows 10（1809 或更高版本）和 Windows 11，内置 Java。
2. 将模拟器设为 **1920 × 1080 横屏**，开启 ADB 调试。
3. 在「设备」页连接模拟器并绑定账号。打开该设备的运行页，保存配置后按 `F5` 开始。

使用说明和排障方法见[使用指南](docs/USER-GUIDE.md)。另提供 [PDF 手册](output/pdf/黎明界助手桌面版使用说明书.pdf)和 [HTML 手册](output/pdf/黎明界助手桌面版使用说明书.html)。

## 从源码构建

准备 x64 JDK 21，从仓库根目录执行：

```powershell
desktop\gradlew.bat -p desktop :app:run
desktop\gradlew.bat -p desktop :app:packagePortableZip
```

包输出到 `desktop/app/build/dist/`。测试和模块说明见[开发说明](docs/DEVELOPMENT.md)。

## 许可

本项目源自 [wbero/limingjie-assistant](https://github.com/wbero/limingjie-assistant)。本项目的贡献采用 [CC BY-NC-SA 4.0](LICENSE)，第三方代码与素材的来源和许可见[第三方声明](THIRD_PARTY_NOTICES.md)。
