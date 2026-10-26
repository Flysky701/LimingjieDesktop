# 黎明界助手 Windows 桌面版

当前源码版本：**1.0.18+desktop.7**（2026-10-26 核对）。本项目是以 Kotlin / Compose Desktop 构建的独立桌面助手；账号、黎明界任务、策略、编组、评分、设备和诊断均在 Windows 窗口中使用，模拟器只运行游戏，无需安装安卓版助手。

共享上游 [wbero/limingjie-assistant](https://github.com/wbero/limingjie-assistant) 1.0.18 的识别、策略、协议和业务代码；桌面适配由 ADB、MaaTouch、ONNX OCR、Room JVM 和 Windows DPAPI 提供。上游 Git 历史保留，移植基线为 `b809b196988b019571caf5679ccf8d7ebb7f072d`。旧 Python 项目 `D:\WorkSpace\LimingjiePC` 保留，不导入或覆盖其账号数据。

## 当前功能与状态

- **共享数据、按设备运行**：一个窗口包含运行、账号、策略、设备、记录、设置六项导航。账号、凭据、账号配置、全局策略和汇总记录共用；每个已连接设备拥有独立运行页与后台进程。
- **多设备任务控制**：切换运行标签不停止其他设备。开始、暂停/继续、停止与 F5/F6/F7 作用于当前运行页；「停止全部」单独控制所有设备。同一模拟器的别名地址合并，同一账号及登录后确认的真实 UID 禁止并发任务。
- **设备连接**：支持 ADB 自动发现或手动地址，MuMu 使用管理器报告的实际端口补齐登记；连接显示签名核验、截图测速和触控准备。有效签名缓存可复用，安装信息变化时重新核验。
- **任务与策略**：保留刷开局、批量目标、路线、Boss、编组、个人评分和必要确认；已启动任务固定使用本次策略快照，下次启动读取最新全局策略。

当前为**本地候选版，尚未完成真实游戏验收**。D7 报告记录最终 52 项定向检查和 10 项运行环境自检通过；完整账号登录、验证码、游戏循环及双模拟器同时执行真实任务仍待验收。这些是已有验证记录，本次文档更新没有重新运行游戏或全仓测试。

OCR 最近入库评估仍为 **14/15（93.33%）**，低于总准确率 95% 的门槛，且距 80 个真实样本还缺 65 个；标题动画也存在上游页面误判。请先使用只读识别核对画面。会战模块保留源码，与上游当前导航一致，不提供桌面入口。

## 使用与构建

- 用户说明：[README-desktop.md](README-desktop.md)。
- 开发说明：[desktop/README.md](desktop/README.md)。
- 构建环境：Windows 10/11 x64、JDK 21。桌面构建不需要 Android SDK。
- 从仓库根目录启动：`desktop\gradlew.bat -p desktop :app:run`。
- 从仓库根目录构建便携包：`desktop\gradlew.bat -p desktop :app:packagePortableZip`。输出为 `desktop/app/build/dist/LimingjieAssistant-desktop-1.0.18+desktop.7-win-x64.zip` 及同名 `.sha256`；构建不等于公开发布或实机验收通过。
- 运行版自带 Java 21，无需另装 Java 或 Python；ADB 需使用模拟器自带版本、手动提供或在设备页下载。
- 主数据默认位于 `%LOCALAPPDATA%\LimingjieAssistant`；ZIP 带 `portable.flag` 时使用程序旁的 `data`，可用 `--data-dir` 指定。D7 的会话、运行配置、窗口状态及缓存另存于相邻的 `<主数据目录名>-run-tabs`；备份时保留两者。D6 的 `-instances/profiles` 不自动迁入。

## 文档入口

- [D7 交付与验收修正](docs/desktop/reports/SHARED-RUN-TABS-D7.md)：当前共享数据架构、打包与定向验证的版本依据。
- [本仓库 CI](desktop/README.md#本仓库的-ci)：Windows 客户端验证与桌面版本候选包；Android 仅作独立的上游兼容性检查。
- [历次交付报告](docs/desktop/FINAL_REPORT.md)：desktop.1–6 的结果与限制；报告中的单实例描述和旧产物路径需结合 D7 报告阅读。
- [用户实机清单](docs/desktop/USER_CHECKLIST.md)：历史 L1–L3 和 D6 多开检查。D7 的共享关系与账号绑定以当前用户说明为准，D6 的独立策略和首次手动身份确认要求已被替代。
- [实施进度](docs/desktop/registers/PROGRESS.md)、[上游最小补丁](docs/desktop/registers/UPSTREAM_PATCHES.md)、[功能对照](docs/desktop/registers/UI_PARITY.md)。
- [原移植计划](docs/desktop/PLAN.md)、[模块规格](docs/desktop/specs/)、[接口契约](desktop/CONTRACTS.md) 保留设计背景；后续 D7 实现与记录补充其原始单设备方案。
- [页面原型](docs/desktop/design/main-pages.html) 与[历史 UI 证据索引](docs/desktop/evidence/ux/README.md) 用于设计回溯，示例数据和旧截图不代表当前运行结果。
- [OCR 评估](desktop/ocr/evaluation/README.md)、[角色与队伍模型审阅](role-team-review/README-combat-model.md)、[事件审阅](role-team-review/README-events.md)、[EX/Boss 机制参考](role-team-review/README-ex-mechanics.md)。
- [上游 Android 说明](README-upstream.md)、[原执行包索引](docs/desktop/reference/README-PACK.md) 分别说明适用范围；阅读历史执行包不会启动开发、派发或游戏操作。

## 许可与风险

保留上游的 [LICENSE](LICENSE)、[第三方声明](THIRD_PARTY_NOTICES.md) 与 [原版使用说明](README-upstream.md)。本项目与游戏官方无关，自动操作和非官方接口访问可能导致账号限制或封禁。请先阅读随包风险说明；测试数据、协议自检和界面原型均不能替代真实运行风险判断。
