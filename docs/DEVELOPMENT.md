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

验证范围按可逆性、重要程度和影响范围选择，见 [项目执行约定](../AGENTS.md#精简约束)。`check` 是快档：桌面单元测试及上游替换文件的 SHA 锁。全档另外运行上游测试、Compose UI、子进程、独立的打包进程测试、OCR 评估和构建期扫描。普通 `:app:test` 即使加 `-Pfull` 也只运行测试，不生成应用镜像；打包进程测试由 `:app:packagedRuntimeTest` 单独运行，全档 `check` 会包含它。

纯打包直接使用 `:app:packagePortableZip`，该任务生成应用镜像和 ZIP，无需先执行 `check`。源码、依赖和相关环境未变时复用已有可靠验证结果；同步或上传已有包时复用产物及打包生成的校验文件，不重复全量回归、烟测或计算哈希。运行时、依赖或打包配置变化时补对应验证。

应用镜像位于 `desktop/app/build/compose/binaries/main-release/app/LimingjieAssistant/`，ZIP 和校验文件位于 `desktop/app/build/dist/`。便携包包含 Java、识别模型和许可，仅裁剪其他平台的原生库。打包进程测试默认生成并使用该镜像；传入 `-PpackagedWorkerExecutable=<绝对路径>` 则复用指定 exe，跳过镜像生成。运行环境自检见[使用指南](USER-GUIDE.md#日志与常见问题)。

## 模块与运行边界

- `app`：Compose UI、共享数据服务与每设备独立 JVM；入口为 `Main.kt`，总控为 `SharedSupervisorModel`。
- `shared`：编译共享上游、机械派生、桌面替身及 Room；生成目录由 Gradle 管理，不手改。
- `device`：ADB、设备身份与签名、截图、MaaTouch；动作前检查前台，断线停止任务，重连不自动恢复任务。
- `ocr`：固定 ONNX 模型、异步识别及独立评估；不得保留调用方 Bitmap，关闭时释放资源。
- `platform`：数据目录、Windows DPAPI、防睡眠和进程管理；device、ocr、platform 不依赖 shared。

`DesktopBindings` 每实例安装一次，退出按对象身份释放；OCR 回调由 shared 切到 Swing Main。凭据不允许明文回退，设备画像缺失阻止登录。配置同步原子写入，大型数据库在同目录原子替换，损坏先备份；失败不得改成非原子覆盖。

账号、凭据、策略及历史记录由主服务保存，设备 worker 只持有运行快照与缓存。同账号/UID 租约、分辨率和前台守卫不得绕过。数据位置见[使用指南](USER-GUIDE.md#数据与备份)。

## 测试与 CI

PR、main、桌面标签与手动触发默认运行快档；手动勾选 `full_checks` 才运行完整验证。标签或勾选 `package_candidate` 时，在同一次 Gradle 调用中生成 ZIP；同时勾选 `full_checks` 才追加便携包自检。ZIP 校验文件由打包生成一次。共享业务改动按影响选择桌面或 Android 兼容性检查；已知失败应原样记录，不为变绿降低断言或准确率要求。当前 OCR 已通过现有样本的准确率门槛，真实样本覆盖仍未完成，见 [OCR 评估](../desktop/ocr/evaluation/README.md)。

`src/probe` 中的手动探针不属于 test/check；显式 `:device:probeDevice` 或 `:app:probeGraph` 才会操作本机设备，使用前确认目标和授权。普通测试使用临时数据和离线输入，不接触真实账号或模拟器。

## 维护入口

- [上游同步与修改边界](UPSTREAM.md)、[决策](DECISIONS.md)、[已知限制](KNOWN_LIMITATIONS.md)
- [角色与配队工具](../tools/role-team-review/README-combat-model.md)、[事件](../tools/role-team-review/README-events.md)、[EX/Boss](../tools/role-team-review/README-ex-mechanics.md)
- [Android 说明](../android/README.md)、[历史资料](archive/desktop/FINAL_REPORT.md)

交付说明写提交信息、PR 或 Release，不新增报告、证据截图或阶段记录。性能需要在相同设备和场景下测量，静态减少编码或传输次数不能替代实测收益。
