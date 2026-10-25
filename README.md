# 黎明界助手 Windows 桌面版

以 Kotlin / Compose Desktop 构建的独立桌面助手。账号、黎明界任务、策略、编组、评分、设备和诊断均在 Windows 窗口中使用，模拟器只运行游戏。

共享上游 `wbero/limingjie-assistant` 1.0.18 的识别、策略、协议和业务代码；桌面适配由 ADB、MaaTouch、ONNX OCR、Room JVM 和 Windows DPAPI 提供。上游 Git 历史保留，基线为 `b809b196988b019571caf5679ccf8d7ebb7f072d`。旧项目 `D:\WorkSpace\LimingjiePC` 未修改。

## 使用与构建

- 用户说明：[README-desktop.md](README-desktop.md)。
- 构建环境：Windows 10/11 x64、JDK 21。桌面构建不需要 Android SDK。
- 启动开发版本：`desktop\gradlew.bat -p desktop :app:run`。
- 构建便携包：`desktop\gradlew.bat -p desktop packagePortableZip`。
- 输出目录：`desktop/app/build/dist/`。运行版自带 Java，不需要用户另装 JDK。
- 数据默认位于 `%LOCALAPPDATA%\LimingjieAssistant`；发行包带 `portable.flag` 时使用程序旁的 `data`。可用 `--data-dir` 指定独立目录。

## 实现与验证记录

- [最终报告](docs/desktop/FINAL_REPORT.md)：交付物、实际验证结果和剩余限制。
- [用户实机清单](docs/desktop/USER_CHECKLIST.md)：需要模拟器、游戏或本人参与的验证。
- [实施进度](docs/desktop/registers/PROGRESS.md)、[上游最小补丁](docs/desktop/registers/UPSTREAM_PATCHES.md)、[功能对照](docs/desktop/registers/UI_PARITY.md)。
- [整体计划](docs/desktop/PLAN.md)、[模块规格](docs/desktop/specs/)、[接口契约](desktop/CONTRACTS.md)。
- [页面原型](docs/desktop/design/main-pages.html) 保留作设计参考，原型中的示例数据不属于运行程序。

离线测试与真实游戏验证分别记录；没有账号登录或游戏实测证据时，不视作已通过。单设备、单任务；不包含会战入口，与当前上游导航一致。

## 许可与风险

保留上游的 [LICENSE](LICENSE)、[第三方声明](THIRD_PARTY_NOTICES.md) 与 [原版使用说明](README-upstream.md)。本项目与游戏官方无关，自动操作和非官方接口访问可能导致账号限制或封禁。请先阅读随包风险说明；测试数据、协议自检和界面原型均不能替代真实运行风险判断。
