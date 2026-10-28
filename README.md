# 黎明界助手 · Windows 桌面版

在 Windows 上管理《公主连结 Re:Dive》黎明界任务：模拟器运行游戏，助手负责配置、执行和记录。

> **非官方工具。** 自动操作和非官方接口可能导致账号受限，使用前请阅读[使用风险](docs/USER-GUIDE.md#使用风险与许可)。

[下载最新版](https://github.com/Flysky701/LimingjieDesktop/releases/latest) · [使用指南](docs/USER-GUIDE.md) · [开发说明](docs/DEVELOPMENT.md)

## 功能

- 多台模拟器并行，每台有独立运行页，可暂停、停止或全部停止。
- 账号与策略集中管理，所有设备共用。
- 自动发现 ADB 设备，支持 MuMu 端口发现与手动添加。
- 运行记录、日志搜索与诊断包导出。

## 快速开始

1. 下载 ZIP，完整解压后运行 `LimingjieAssistant.exe`（Windows 10/11 x64，内置 Java）。
2. 模拟器设为 **1920 × 1080 横屏**，开启 ADB 调试。
3. 在「设备」页连接并绑定账号，打开运行页，保存配置后按 F5 开始。

遇到问题见[常见问题](docs/USER-GUIDE.md#日志与常见问题)。

此前制作的简明使用手册：[PDF](output/pdf/黎明界助手桌面版使用说明书.pdf) · [浏览器版](output/pdf/黎明界助手桌面版使用说明书.html)。当前操作说明以[使用指南](docs/USER-GUIDE.md)为准。

## 从源码构建

准备 x64 JDK 21，从仓库根目录执行：

```powershell
desktop\gradlew.bat -p desktop :app:run
desktop\gradlew.bat -p desktop :app:packagePortableZip
```

包输出到 `desktop/app/build/dist/`。测试和模块说明见[开发说明](docs/DEVELOPMENT.md)。

## 许可

业务代码复用 [wbero/limingjie-assistant](https://github.com/wbero/limingjie-assistant)。项目贡献采用 [CC BY-NC-SA 4.0](LICENSE)，第三方代码与素材见[第三方声明](THIRD_PARTY_NOTICES.md)。本项目与游戏官方无关。
