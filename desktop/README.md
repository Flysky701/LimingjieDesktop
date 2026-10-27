# 桌面开发与构建

**当前版本：1.0.18+desktop.10 测试候选版**。独立 Gradle 多模块工程，使用 x64 JDK 21，桌面构建无需 Android SDK。基础范围见[基础版说明](../docs/desktop/reports/BASELINE-DESKTOP8.md)，后续变更见[ADB 修复记录](../docs/desktop/reports/ADB-DAEMON-DESKTOP9.md)与[预览优化记录](../docs/desktop/reports/PREVIEW-DEMAND-DESKTOP10.md)。

工具链：Gradle 8.12.1、Kotlin 2.1.20、Compose Desktop 1.8.2。配置位于 `gradle/wrapper/gradle-wrapper.properties` 和 `gradle/libs.versions.toml`。

## 常用命令

从仓库根目录执行：

```powershell
# 启动开发版本
desktop\gradlew.bat -p desktop :app:run

# 构建 Windows 便携包
desktop\gradlew.bat -p desktop :app:packagePortableZip

# 完整检查
desktop\gradlew.bat -p desktop check
```

| 输出 | 位置 |
| --- | --- |
| ZIP 与 SHA-256 | `desktop/app/build/dist/` |
| Windows 应用镜像 | `desktop/app/build/compose/binaries/main-release/app/LimingjieAssistant/` |

便携包包含 Java 21、用户说明和许可。ADB 可由用户提供或在设备页下载。

Windows ZIP 会裁剪 ONNX Runtime、SQLite 和 JNA 中其他平台的原生库及调试符号，保留 Windows x64 运行库、Java 代码、识别模型与完整许可声明。

## 模块

| 模块 | 职责 |
| --- | --- |
| `app` | Compose 窗口、共享数据服务、每设备运行进程和全局页面 |
| `shared` | 上游源码编译、机械派生、桌面替身与 Room JVM 适配 |
| `device` | ADB、设备身份、签名核验、截图、MaaTouch 和兼容输入 |
| `ocr` | ONNX 模型、前处理和独立评估 source set |
| `platform` | 数据目录、Windows DPAPI、防睡眠与本地环境 |

入口为 `app/src/main/kotlin/com/landosol/toolbox/desktop/app/Main.kt`，总控与界面分别为 `SharedSupervisorModel`、`SharedRunTabsApp`。共享主服务保存账号、凭据、账号配置、策略与记录，设备进程保存任务快照和运行缓存。数据目录见[使用指南](../README-desktop.md#数据与备份)。

## 版本与上游维护

桌面版本统一定义在 `desktop/build.gradle.kts`，用于显示标签、ZIP 名、原生包数字基础版本与派生版本测试。Android 的协议相关版本代码沿用上游值。

共享上游 1.0.18，移植基线为 `b809b196988b019571caf5679ccf8d7ebb7f072d`。从源文件或派生清单维护共享代码，生成目录 `build/generated` 由构建任务管理。必要修改见[补丁登记](../docs/desktop/registers/UPSTREAM_PATCHES.md)，更新步骤见[上游同步](../docs/desktop/registers/UPSTREAM_SYNC.md)。

## 测试与自检

按修改范围选择测试：

```powershell
desktop\gradlew.bat -p desktop :app:test --tests '*SharedSupervisorModelTest'
desktop\gradlew.bat -p desktop :ocr:evaluate
```

完整 `check` 包含单测、共享源码漂移、模型校验和 OCR 样本评估。样本与结果见 [OCR 评估](ocr/evaluation/README.md)。

打包进程测试需要先生成应用镜像，再提供 exe 路径：

```powershell
desktop\gradlew.bat -p desktop :app:test --tests '*SharedPackagedRuntimeTest' '-PpackagedWorkerExecutable=E:\path\to\LimingjieAssistant.exe'
```

从解压后的便携目录执行环境自检：

```powershell
.\LimingjieAssistant.exe --self-test --report .\self-test.json --data-dir '.\自检 数据'
```

自检使用临时数据，覆盖资源、原生依赖、数据库、DPAPI 与 TLS 握手。版本记录见 [D7 报告](../docs/desktop/reports/SHARED-RUN-TABS-D7.md)。

## 本仓库的 CI

| 流程 | 触发与产物 |
| --- | --- |
| [Windows Desktop CI](../.github/workflows/desktop-ci.yml) | `main` 相关代码改动、PR 或手动触发；在 Windows / JDK 21 上运行完整检查 |
| Windows 候选 ZIP | 手动选择 `package_candidate`，或推送 `desktop-v<完整桌面版本>` 标签；检查通过后打包、校验并自检，ZIP 与 SHA-256 保留 3 天 |
| 详细报告 | 手动选择 `upload_reports`，保留 7 天 |
| [Upstream Android Compatibility](../.github/workflows/android-ci.yml) | Android 代码/构建改动或手动触发；使用 JDK 17 执行 lint、单测和 APK 编译，仅作兼容性检查 |

desktop.10 当前为本地测试候选，尚未创建或推送正式发布标签。测试完成后使用与源码版本一致的 `desktop-v1.0.18+desktop.10` 标签；已有 desktop.7 标签保留。普通说明文档变化跳过构建；同一分支或 PR 的旧运行自动取消。Gradle 使用构建缓存，`main` 写回，其他分支只读。默认将结果写入运行摘要，按需上传文件；标签构建与 GitHub Release 分别管理。
