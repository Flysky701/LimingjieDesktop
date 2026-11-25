# 桌面开发与构建

`LimingjieDesktop` 是独立的 Windows 原生桌面项目。Android 兼容和按上游提交 SHA 锁定的自动同步已永久退役。构建使用 x64 JDK 21，项目版本由 [根构建脚本](../desktop/build.gradle.kts) 定义，依赖版本见 [版本目录](../desktop/gradle/libs.versions.toml)。以下命令从仓库根目录执行。

| 任务 | 命令 |
| --- | --- |
| 启动开发版 | `desktop\gradlew.bat -p desktop :app:run` |
| 单个回归（按需替换模块和测试类） | `desktop\gradlew.bat -p desktop :app:test --tests '*测试类名'` |
| 跨模块日常验证 | `desktop\gradlew.bat -p desktop check` |
| 扩大常规测试范围 | `desktop\gradlew.bat -p desktop check -Pfull` |
| 常规验证并测试打包进程 | `desktop\gradlew.bat -p desktop check -PincludePackagedRuntime` |
| 常规验证并评估 OCR | `desktop\gradlew.bat -p desktop check -PincludeOcrEval` |
| 仅打包进程验证 | `desktop\gradlew.bat -p desktop :app:packagedRuntimeTest` |
| 仅 OCR 评估 | `desktop\gradlew.bat -p desktop :ocr:evaluate` |
| 只打便携包 | `desktop\gradlew.bat -p desktop :app:packagePortableZip` |

`check` 运行各模块的常规检查，包括 `:core` 的业务逻辑测试和 OCR 模型完整性检查。`-Pfull` 运行全部 `:core:test`，用例涵盖截图、回放和资源审计，同时恢复其他模块的集成、Compose UI、子进程及设备用例。`-PincludePackagedRuntime` 和 `-PincludeOcrEval` 分别把 `:app:packagedRuntimeTest` 和 `:ocr:evaluate` 加入 `check`，可与 `-Pfull` 组合。验证范围按影响选择，见 [项目执行约定](../AGENTS.md#精简约束)。

普通 `:app:test` 不生成应用镜像。`:app:packagedRuntimeTest` 默认构建含战斗模型的发布镜像，传入 `-PpackagedWorkerExecutable=<exe 绝对路径>` 可复用已有的可执行文件。

`:app:packagePortableZip` 生成应用镜像（`desktop/app/build/compose/binaries/main-release/app/LimingjieAssistant/`）以及便携 ZIP 和校验文件（`desktop/app/build/dist/`）。包内含 Java、识别模型、战斗模型和许可文件，打包时裁剪多平台依赖中其他平台的原生库。战斗模型默认取自相邻 `LimingjieNN` 仓库的 `models/s4_m2b_r1_seed43`，也可用 `-PlimingjieNnBundleDir=<模型目录>` 或 `LIMINGJIE_NN_BUNDLE` 指定，打包时核对模型文件的 SHA-256。运行环境自检见 [使用指南](USER-GUIDE.md#日志与常见问题)。

## 模块与运行边界

| 模块 | 职责 |
| --- | --- |
| `:app` | Compose UI、共享数据服务与每设备独立 JVM。入口为 `Main.kt`，总控为 `SharedSupervisorModel`。 |
| `:core` | 桌面核心业务、迷宫事件内核、Room 持久化与资源。Gradle 从模块内源码和输入直接构建。 |
| `:device` | ADB、设备身份与签名、截图和 MaaTouch。断线停止任务，重连不自动恢复。 |
| `:ocr` | 固定 ONNX 模型、异步识别及独立评估。提交时复制调用方 Bitmap，关闭时释放资源。独立评估使用 `:core` 的匹配器。 |
| `:platform` | 数据目录、Windows DPAPI、防睡眠和进程管理。 |

`DesktopBindings` 每实例安装一次，退出按对象身份释放。`:core` 的 OCR 回调使用 Swing 主线程。动作前校验分辨率与实时前台，同账号或同 UID 的运行由租约互斥。凭据强制使用 Windows DPAPI 加密，日志须脱敏，设备画像缺失时阻止登录。配置同步原子写入，大型数据库在同目录原子替换，损坏时先备份。

账号、凭据、策略及历史记录由主服务保存，设备 worker 只持有运行快照与缓存。数据目录见[使用指南](USER-GUIDE.md#数据与备份)。

## 测试与 CI

Windows CI 在桌面构建输入变更的 PR 和 `main` 推送、`desktop-v*` 标签推送及手动触发时运行。默认执行 `check`，勾选 `full_checks` 加上 `-Pfull -PincludeOcrEval`。桌面标签或 `package_candidate` 触发 `:app:packagePortableZip`。同时启用 `full_checks` 与打包时，追加 `-PincludePackagedRuntime`，并对解压后的便携包运行 `--self-test`。

GitHub 托管运行器通过仓库变量 `LIMINGJIE_NN_BUNDLE_URL` 获取战斗模型 ZIP。ZIP 根目录应直接包含 `tower_a.onnx`、`tower_b.onnx`、`manifest.json`、`golden_io.json` 和 `feature_pack.json`。日常检查和单独的 OCR 评估使用仓库内的输入。OCR 的报告 `ocr-eval.json` 和差异表 `ocr-eval-diff.tsv` 位于 `desktop/ocr/build/reports/`，样本来源见 [OCR 样本清单](../desktop/ocr/evaluation/manifest.json)。

`src/probe` 中的手动探针（`:device:probeDevice`、`:app:probeGraph`）用于连接本机设备调试，不包含在常规 `test` / `check` 中。

## 维护入口

- [统一路线图](ROADMAP.md) · [迷宫事件内核](KERNEL_REFACTOR.md) · [历史来源与维护边界](UPSTREAM.md) · [架构与实现决策](DECISIONS.md) · [已知限制与待办](KNOWN_LIMITATIONS.md)
- [角色与配队工具](../tools/role-team-review/README-combat-model.md) · [事件](../tools/role-team-review/README-events.md) · [EX/Boss](../tools/role-team-review/README-ex-mechanics.md)
- [历史归档](archive/desktop/FINAL_REPORT.md)
