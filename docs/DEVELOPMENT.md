# 桌面开发与构建

`LimingjieDesktop` 是独立的 Windows 原生桌面项目。`:core` 直接构建 Kotlin/JVM 桌面源码，Android 垫片、`android/` 源码树和上游自动同步均已退役。构建使用 x64 JDK 21。以下命令从仓库根目录执行。

| 任务 | 命令 |
| --- | --- |
| 启动开发版 | `desktop\gradlew.bat -p desktop :app:run` |
| 单个回归（按需替换模块和测试类） | `desktop\gradlew.bat -p desktop :app:test --tests '*测试类名'` |
| 跨模块日常验证（快档） | `desktop\gradlew.bat -p desktop check` |
| 扩大常规测试范围 | `desktop\gradlew.bat -p desktop check -Pfull` |
| 常规验证并测试打包进程 | `desktop\gradlew.bat -p desktop check -PincludePackagedRuntime` |
| 常规验证并评估 OCR | `desktop\gradlew.bat -p desktop check -PincludeOcrEval` |
| 仅打包进程验证 | `desktop\gradlew.bat -p desktop :app:packagedRuntimeTest` |
| 仅 OCR 评估 | `desktop\gradlew.bat -p desktop :ocr:evaluate` |
| 只打便携包 | `desktop\gradlew.bat -p desktop :app:packagePortableZip` |

`check` 运行各模块的快档检查，包括 `:core` 的业务逻辑测试和 OCR 模型完整性检查。`-Pfull` 启用 `:core` 的截图、回放和资源审计，也启用快档排除的应用集成、Compose UI、子进程和设备用例。用 `--tests` 单独运行这些用例时也需加上 `-Pfull`。`-PincludePackagedRuntime` 和 `-PincludeOcrEval` 分别把 `:app:packagedRuntimeTest` 和 `:ocr:evaluate` 加入 `check`，可与 `-Pfull` 组合。验证范围按影响选择，见 [项目执行约定](../AGENTS.md#精简约束)。

普通 `:app:test` 不生成应用镜像。`:app:packagedRuntimeTest` 默认构建含战斗模型的发布镜像，传入 `-PpackagedWorkerExecutable=<exe 绝对路径>` 可复用已有的可执行文件。

`:app:packagePortableZip` 生成应用镜像（`desktop/app/build/compose/binaries/main-release/app/LimingjieAssistant/`）以及便携 ZIP 和校验文件（`desktop/app/build/dist/`）。包内含 Java、识别模型、战斗模型和许可文件，打包时裁剪多平台依赖中其他平台的原生库。默认战斗模型由构建脚本指定，也可用 `-PlimingjieNnBundleDir=<模型目录>` 或 `LIMINGJIE_NN_BUNDLE` 覆盖，打包时核对模型文件的 SHA-256。参见 [应用构建脚本](../desktop/app/build.gradle.kts)和[运行环境自检](USER-GUIDE.md#日志与常见问题)。

## 模块与运行边界

| 模块 | 职责 |
| --- | --- |
| `:app` | Compose UI、共享数据服务与每设备独立 JVM。入口为 `Main.kt`，总控为 `SharedSupervisorModel`。 |
| `:core` | 桌面核心业务、迷宫七域内核、Room 持久化与资源。Gradle 从模块内源码和输入直接构建。 |
| `:device` | ADB、设备身份与签名、截图和 MaaTouch。断线停止任务，重连不自动恢复。 |
| `:ocr` | 固定 ONNX 模型、异步识别及独立评估。提交时复制调用方 Bitmap，关闭时释放资源。独立评估使用 `:core` 的匹配器。 |
| `:platform` | 数据目录、Windows DPAPI、防睡眠和进程管理。 |

`DesktopBindings` 每实例安装一次，退出按对象身份释放。`:core` 的 OCR 回调使用 Swing 主线程。动作前校验分辨率与实时前台，同账号或同 UID 的运行由租约互斥。凭据强制使用 Windows DPAPI 加密，日志须脱敏，设备画像缺失时阻止登录。配置同步原子写入，大型数据库在同目录原子替换，损坏时先备份。

配置分三个域。应用环境和界面偏好在 `settings/app.json`，策略库和刷开局设置在 `settings/strategies.json`，两个文件均由 `AtomicConfigStore<T>` 管理。账号凭据和 SDK 会话保存在独立的 DPAPI 存储。主服务持有共享数据，设备 worker 使用运行快照和缓存。`ControlModelCodec` 通过生成式序列化处理常用配置和设备画像，保持既有 `ControlSnapshot` 格式。参见 [数据与备份](USER-GUIDE.md#数据与备份)和[架构决策](DECISIONS.md)。

## 日志与诊断

`AppLogger` 使用 `LogRedactor` 脱敏，交给 `RollingFileLogSink` 写入 `logs/app.log` 并按大小轮换。`UserLog` 将脱敏后的事件发布到 `RunTelemetryBus`，由总线写入同一进程日志。控制台条目及重复折叠保存在各设备实例。

调试面板的 `/logs.zip` 包含 `process.log`、有内容时的 `user-console.log` 和已绑定的内核事件。`manifest.json` 记录最近一次会话的 `traceId`，`incidents/` 下的无损 PNG 与采集时的 JSON 元数据成对存放。通过完整的 `traceId` 可查询同一运行的内核事件、进程日志和事故现场。参见 [迷宫事件内核](KERNEL_REFACTOR.md#因果与落盘)。

## 测试与 CI

Windows CI 在桌面构建输入变更的 PR 和 `main` 推送、`desktop-v*` 标签推送及手动触发时运行。默认执行 `check`，勾选 `full_checks` 加上 `-Pfull -PincludeOcrEval`。桌面标签或 `package_candidate` 触发 `:app:packagePortableZip`。同时启用 `full_checks` 与打包时，追加 `-PincludePackagedRuntime`，并对解压后的便携包运行 `--self-test`。

GitHub 托管运行器通过仓库变量 `LIMINGJIE_NN_BUNDLE_URL` 获取战斗模型 ZIP。ZIP 根目录应直接包含 `tower_a.onnx`、`tower_b.onnx`、`manifest.json`、`golden_io.json` 和 `feature_pack.json`。日常检查和单独的 OCR 评估使用仓库内的输入。OCR 的报告 `ocr-eval.json` 和差异表 `ocr-eval-diff.tsv` 位于 `desktop/ocr/build/reports/`，样本来源见 [OCR 样本清单](../desktop/ocr/evaluation/manifest.json)。

`src/probe` 中的手动探针（`:device:probeDevice`、`:app:probeGraph`）用于连接本机设备调试，不包含在常规 `test` / `check` 中。

## 维护入口

- [统一路线图](ROADMAP.md) · [迷宫事件内核](KERNEL_REFACTOR.md) · [历史来源与维护边界](UPSTREAM.md) · [架构与实现决策](DECISIONS.md) · [已知限制与待办](KNOWN_LIMITATIONS.md)
- [角色与配队工具](../tools/role-team-review/README-combat-model.md) · [事件](../tools/role-team-review/README-events.md) · [EX/Boss](../tools/role-team-review/README-ex-mechanics.md)
- [历史归档](archive/desktop/FINAL_REPORT.md)
