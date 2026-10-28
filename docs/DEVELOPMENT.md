# 桌面开发与构建

使用 x64 JDK 21；桌面工程无需 Android SDK。版本统一定义在 `desktop/build.gradle.kts`，依赖版本见 `desktop/gradle/libs.versions.toml`。从仓库根目录执行：

```powershell
desktop\gradlew.bat -p desktop :app:run
desktop\gradlew.bat -p desktop check
desktop\gradlew.bat -p desktop :app:createReleaseDistributable check -Pfull
desktop\gradlew.bat -p desktop :app:packagePortableZip
```

`check` 是日常快档：桌面单元测试及上游替换文件的 SHA 锁。全档另外运行上游测试、Compose UI、子进程、打包进程、OCR 评估和构建期扫描；发布、修改共享上游或手动检查时使用。单个回归可用 `:app:test --tests '*测试类名'`；全档测试需同时加 `-Pfull`。

应用镜像位于 `desktop/app/build/compose/binaries/main-release/app/LimingjieAssistant/`，ZIP 和校验文件位于 `desktop/app/build/dist/`。便携包包含 Java、识别模型和许可，仅裁剪其他平台的原生库。打包进程测试从默认镜像路径读取 exe，也可用 `-PpackagedWorkerExecutable=<绝对路径>` 指定。运行环境自检见[使用指南](USER-GUIDE.md#日志与常见问题)。

## 模块与运行边界

- `app`：Compose UI、共享数据服务与每设备独立 JVM；入口为 `Main.kt`，总控为 `SharedSupervisorModel`。
- `shared`：编译共享上游、机械派生、桌面替身及 Room；生成目录由 Gradle 管理，不手改。
- `device`：ADB、设备身份与签名、截图、MaaTouch；动作前检查前台，断线停止任务，重连不自动恢复任务。
- `ocr`：固定 ONNX 模型、异步识别及独立评估；不得保留调用方 Bitmap，关闭时释放资源。
- `platform`：数据目录、Windows DPAPI、防睡眠和进程管理；device、ocr、platform 不依赖 shared。

`DesktopBindings` 每实例安装一次，退出按对象身份释放；OCR 回调由 shared 切到 Swing Main。凭据不允许明文回退，设备画像缺失阻止登录。配置同步原子写入，大型数据库在同目录原子替换，损坏先备份；失败不得改成非原子覆盖。

账号、凭据、策略及历史记录由主服务保存，设备 worker 只持有运行快照与缓存。同账号/UID 租约、分辨率和前台守卫不得绕过。数据位置见[使用指南](USER-GUIDE.md#数据与备份)。

## 测试与 CI

PR 和普通 main 提交运行快档；桌面标签与手动触发运行全档并先生成镜像。改动 `android/` 或 `desktop/shared/` 时在本地执行全档；已知失败应原样记录，不为变绿降低断言或准确率要求。完整 OCR 仍有历史 `series-visible` 失败，见 [OCR 评估](../desktop/ocr/evaluation/README.md)。

`src/probe` 中的手动探针不属于 test/check；显式 `:device:probeDevice` 或 `:app:probeGraph` 才会操作本机设备，使用前确认目标和授权。普通测试使用临时数据和离线输入，不接触真实账号或模拟器。

## 维护入口

- [上游同步与修改边界](UPSTREAM.md)、[决策](DECISIONS.md)、[已知限制](KNOWN_LIMITATIONS.md)
- [角色与配队工具](../tools/role-team-review/README-combat-model.md)、[事件](../tools/role-team-review/README-events.md)、[EX/Boss](../tools/role-team-review/README-ex-mechanics.md)
- [Android 说明](../android/README.md)、[历史资料](archive/desktop/FINAL_REPORT.md)

交付说明写提交信息、PR 或 Release，不新增报告、证据截图或阶段记录。性能需要在相同设备和场景下测量，静态减少编码或传输次数不能替代实测收益。
