# 架构与实现决策

## 桌面模块与进程

- `desktop/` 是独立的 Gradle 工程。原生桌面 `:core` 直接编译 Kotlin/JVM 业务、Room 持久化和资源，已移除 Android 垫片、`android/` 源码树和上游自动派生流程。`:app` 完成应用装配，`:device`、`:ocr`、`:platform` 的生产依赖独立于 `:core`。
- 主服务持有账号、凭据、策略和历史。每台在线模拟器有独立的设备 worker JVM，处理捕获、任务和本地缓存。同账号与同 UID 通过租约互斥，重连需要重新启动任务。参见 [桌面开发与构建](DEVELOPMENT.md)。
- 总控和 worker 通过 `ControlSnapshot` 交换数据。`ControlModelCodec` 对常用配置、策略和设备画像使用生成式序列化，保留原有字段和类型标签。其他模型沿用按预期类型解码的通用路径。

## 配置与持久化

| 域 | 持久化方式 |
| --- | --- |
| 应用环境和界面偏好 | `AtomicConfigStore<AppConfig>` 写入 `settings/app.json`。 |
| 策略库和账号刷开局设置 | `AtomicConfigStore<StrategyLibrary>` 写入 `settings/strategies.json`。共享数据模式下，worker 使用主服务提供的运行快照。 |
| 账号凭据和 SDK 会话 | 独立的 Windows DPAPI 存储按当前用户加密。worker 按需取得凭据并仅保留在内存中。 |

配置原子替换，旧数据按需迁移，损坏文件保留备份并提示用户。数据库使用 Room 和 `BundledSQLiteDriver`，损坏时先备份再初始化。数据目录优先级和备份方式见 [使用指南](USER-GUIDE.md#数据与备份)。

## 设备、识别与内核

- ADB 优先使用模拟器自带路径，其次查找系统路径，可在设置中指定。截图优先使用 Raw，保留 PNG 回退。触控默认使用 MaaTouch，保留 ADB 输入模式。派发前实时核对前台包名和分辨率，设备画像缺失时阻止登录。
- OCR 使用固定的中文 ONNX 模型，角色名称和头像可互证。紧凑区域先整图识别，再尝试带边框的检测与识别。像素解码复用 Skia，识别回调返回 Swing 主线程。参见 [OCR 评估](../desktop/ocr/evaluation/README.md)。
- `LabyrinthKernelBundle` 为每个会话注册七个 `KernelDomainModule`，统一处理事件、重置和状态。战斗估值通过纯 Kotlin 接口注入，模型校验失败或禁用时回退至规则评分。Path B 与离线 Python 参考实现对齐。参见 [迷宫事件内核](KERNEL_REFACTOR.md)。

## 日志、诊断与分发

- `AppLogger` 通过 `LogRedactor` 和 `RollingFileLogSink` 保存脱敏的进程日志，用户控制台通过 `RunTelemetryBus` 写入同一进程日志。每个运行会话绑定完整的 `traceId`。回环诊断服务按需导出 `/logs.zip`，其中的事故现场 PNG 附带含 `traceId` 的 JSON。
- 便携包使用 `jpackage` 生成自带 Java 运行时的应用镜像，内置 OCR 和战斗模型。`--self-test` 用独立数据目录检查运行环境，联网项仅执行 TLS 握手。检查档位和打包入口见 [桌面开发与构建](DEVELOPMENT.md)。
