# 黎明界助手 · Windows 桌面版

黎明界助手用于在 Windows 上配置、执行和记录《公主连结 Re:Dive》的黎明界任务。游戏在模拟器内运行，桌面助手提供多设备管理和独立的任务控制。

> **非官方工具。** 自动操作可能触发账号限制，使用前请了解[使用风险与许可](docs/USER-GUIDE.md#使用风险与许可)。

[下载 Windows 便携版](https://github.com/Flysky701/LimingjieDesktop/releases/latest) · [查看所有发布](https://github.com/Flysky701/LimingjieDesktop/releases) · [使用指南](docs/USER-GUIDE.md) · [开发说明](docs/DEVELOPMENT.md)

## 主要功能

- 多台模拟器可并行运行。每台设备拥有独立运行标签与后台进程，支持快捷键（`F5` / `F6` / `F7`）及「停止全部」。
- 集中管理多服务器账号与全局策略（支持策略分享码与导入预览），各设备分别保存任务目标。
- 内置由 [黎明界NN（LimingjieNN）](https://github.com/Flysky701/LimingjieNN) 训练的双塔 ONNX 本地战斗选队模型与 Path B 全局收益规划（官方高分路线规划、局内重规划及商店/事件/遗物/选角边际估值），可在策略页开关并在异常或关闭时自动回退规则评分。
- 自动发现 MuMu 设备的 ADB 端口，也可手动添加设备；支持 **1920 × 1080** 横屏（**1280 × 720** 开放测试）识别、MaaTouch / ADB 触控与本地 ONNX OCR。
- 按设备查看运行记录、分数/耗时图表与脱敏日志，支持一键导出诊断包（含日志、内核事件与事故现场截图）及匿名回放数据（`replays.jsonl`）。

## 快速开始

1. 下载便携 ZIP 和同名 `.sha256` 文件。核对校验值后完整解压，运行 `LimingjieAssistant.exe`。适用于 64 位 Windows 10（1809 或更高版本）和 Windows 11，内置 Java。
2. 将模拟器设为 **1920 × 1080 横屏**（`1280 × 720` 开放测试说明见[使用指南](docs/USER-GUIDE.md)），开启 ADB 调试。
3. 在「设备」页连接模拟器并绑定账号。打开该设备的运行页，保存配置后按 `F5` 开始。

使用说明和排障方法见[使用指南](docs/USER-GUIDE.md)。另提供 [PDF 手册](output/pdf/黎明界助手桌面版使用说明书.pdf)和 [HTML 手册](output/pdf/黎明界助手桌面版使用说明书.html)。

## 从源码构建

准备 x64 JDK 21，从仓库根目录执行：

```powershell
desktop\gradlew.bat -p desktop :app:run
desktop\gradlew.bat -p desktop :app:packagePortableZip
```

`:app:packagePortableZip` 会随应用打包并按 SHA-256 校验 `LimingjieNN` 战斗模型包（默认读取同级 `LimingjieNN` 仓库，也可用 `-PlimingjieNnBundleDir` 或 `LIMINGJIE_NN_BUNDLE` 指定目录）。包输出到 `desktop/app/build/dist/`。测试和模块说明见[开发说明](docs/DEVELOPMENT.md)。

## 相关项目与致谢

- **[黎明界NN（LimingjieNN）](https://github.com/Flysky701/LimingjieNN)**：配套的离线战斗神经网络训练、Path B 全局结算分规划参考与双塔 ONNX 模型发布仓库。
- **[wbero/limingjie-assistant](https://github.com/wbero/limingjie-assistant)**：Android 原作与本项目最初的核心业务基础，感谢原作者 wbero 的开发与开源。
- **[SatenRuiko10032/limingjie-assistant](https://github.com/SatenRuiko10032/limingjie-assistant)**：历史社区分支，曾参考合并商店重构、三档前排生存模式与执行流程修复。

其他参考项目（如 AutoPCR、LandosolRoster、priconne-database、MaaTouch 等）与完整第三方声明见[第三方声明](THIRD_PARTY_NOTICES.md)。

## 许可

本项目的贡献采用 [CC BY-NC-SA 4.0](LICENSE)，第三方代码与素材的来源和许可见[第三方声明](THIRD_PARTY_NOTICES.md)。
