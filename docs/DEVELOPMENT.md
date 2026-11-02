# 桌面开发与构建

使用 x64 JDK 21；桌面工程无需 Android SDK。版本统一定义在 `desktop/build.gradle.kts`，依赖版本见 `desktop/gradle/libs.versions.toml`。从仓库根目录按任务选择入口：

| 任务 | 命令 |
| --- | --- |
| 启动开发版 | `desktop\gradlew.bat -p desktop :app:run` |
| 单个回归（按需替换模块和测试类） | `desktop\gradlew.bat -p desktop :app:test --tests '*测试类名'` |
| 跨模块日常验证 | `desktop\gradlew.bat -p desktop check` |
| 显式完整验证 | `desktop\gradlew.bat -p desktop check -Pfull` |
| 仅打包进程验证 | `desktop\gradlew.bat -p desktop :app:packagedRuntimeTest` |
| 只打便携包 | `desktop\gradlew.bat -p desktop :app:packagePortableZip` |

验证范围按可逆性、重要程度和影响范围选择，见 [项目执行约定](../AGENTS.md#精简约束)。`check` 是快档（桌面单元测试及上游替换文件的 SHA 锁）；`check -Pfull` 额外运行上游测试、Compose UI、子进程、打包进程测试、OCR 评估和构建期耦合扫描。普通 `:app:test` 不生成应用镜像；打包进程测试由 `:app:packagedRuntimeTest` 单独运行，传入 `-PpackagedWorkerExecutable=<绝对路径>` 可直接复用指定 `exe`。

`:app:packagePortableZip` 直接生成应用镜像（`desktop/app/build/compose/binaries/main-release/app/LimingjieAssistant/`）以及便携 ZIP 和校验文件（`desktop/app/build/dist/`）。便携包包含 Java、识别模型和许可，仅裁剪其他平台的原生库。运行环境自检见[使用指南](USER-GUIDE.md#日志与常见问题)。

## 模块与运行边界

- `app`：Compose UI、共享数据服务与每设备独立 JVM；入口为 `Main.kt`，总控为 `SharedSupervisorModel`。
- `shared`：编译共享上游、机械派生、桌面替身及 Room；生成目录由 Gradle 管理。
- `device`：ADB、设备身份与签名、截图、MaaTouch；动作前检查前台，断线停止任务，重连不自动恢复任务。
- `ocr`：固定 ONNX 模型、异步识别及独立评估；调用方 Bitmap 在提交时复制，关闭时释放资源。
- `platform`：数据目录、Windows DPAPI、防睡眠和进程管理；`device`、`ocr`、`platform` 不依赖 `shared`。

`DesktopBindings` 每实例安装一次，退出按对象身份释放；OCR 回调由 `shared` 切到 Swing Main。凭据强制 DPAPI 加密，设备画像缺失时阻止登录。配置同步原子写入，大型数据库在同目录原子替换，损坏时先备份。

账号、凭据、策略及历史记录由主服务保存，设备 worker 只持有运行快照与缓存。数据目录见[使用指南](USER-GUIDE.md#数据与备份)。

## 测试与 CI

PR、`main`、桌面标签与手动触发默认运行快档；手动勾选 `full_checks` 运行完整验证。标签或勾选 `package_candidate` 时生成便携 ZIP；同时勾选 `full_checks` 会追加便携包自检。OCR 评估说明见 [OCR 评估](../desktop/ocr/evaluation/README.md)。

`src/probe` 中的手动探针（`:device:probeDevice`、`:app:probeGraph`）用于连接本机设备调试，不包含在常规 `test` / `check` 中。

## 维护入口

- [上游同步与修改边界](UPSTREAM.md) · [架构与实现决策](DECISIONS.md) · [已知限制与待办](KNOWN_LIMITATIONS.md)
- [角色与配队工具](../tools/role-team-review/README-combat-model.md) · [事件](../tools/role-team-review/README-events.md) · [EX/Boss](../tools/role-team-review/README-ex-mechanics.md)
- [Android 说明](../android/README.md) · [历史归档](archive/desktop/FINAL_REPORT.md)
