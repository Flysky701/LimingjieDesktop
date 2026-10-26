# 桌面构建

当前版本为 **1.0.18+desktop.7**，2026-10-26 按源码核对。独立 Gradle 多模块工程，Windows 构建使用 x64 JDK 21；无需配置 Android SDK。Wrapper 固定 Gradle 8.12.1，Kotlin 2.1.20、Compose Desktop 1.8.2，版本来源分别为 `gradle/wrapper/gradle-wrapper.properties` 与 `gradle/libs.versions.toml`。

以下命令均从仓库根目录执行：

```powershell
desktop\gradlew.bat -p desktop :app:run
desktop\gradlew.bat -p desktop :app:packagePortableZip
```

便携 ZIP 与 SHA-256 写入 `desktop/app/build/dist/`；应用镜像位于 `desktop/app/build/compose/binaries/main-release/app/LimingjieAssistant/`。包内含 Java 21 运行环境、用户说明和许可；不包含 ADB、用户数据或账号。打包任务本身不代表全部验证已通过。

## 模块与运行方式

| 模块 | 职责 |
| --- | --- |
| `app` | Compose 窗口、共享数据主服务、每设备后台进程、运行标签、全局页面与组装 |
| `shared` | 按排除、机械派生与替身清单编译上游业务源码及测试，提供 Room JVM 等桌面适配 |
| `device` | ADB 发现与连接、设备身份、签名核验、截图、MaaTouch 和兼容输入 |
| `ocr` | 固定 ONNX 模型与前处理；评估 source set 单独依赖 `shared`，生产模块不反向依赖业务 |
| `platform` | 数据目录、Windows DPAPI、防睡眠及本地环境能力 |

D7 仅由共享数据主服务持久保存账号、凭据、账号配置、全局策略与汇总记录；每设备进程使用独立任务快照与缓存。程序入口是 `app/.../Main.kt`，总控为 `SharedSupervisorModel`，界面为 `SharedRunTabsApp`；旧多实例实现保留的源码不等于当前入口。

主数据目录按 `--data-dir`、程序旁 `portable.flag`、系统默认位置依次解析。D7 在其相邻的 `<主数据目录名>-run-tabs` 保存窗口状态、会话和 `sessions/<会话ID>` 缓存；D6 的 `-instances/profiles` 不自动导入。自测应指定独立目录，避免占用用户正在使用的目录。详见[用户说明](../README-desktop.md)。

不要编辑 `build/generated`。共享上游 1.0.18，原移植基线 `b809b196988b019571caf5679ccf8d7ebb7f072d`；上游必要补丁见[补丁登记](../docs/desktop/registers/UPSTREAM_PATCHES.md)。协议、识别和决策继续复用上游实现，更新基线时按[上游同步流程](../docs/desktop/registers/UPSTREAM_SYNC.md)处理漂移。

## 定向检查与完整检查

按修改影响选择测试，例如：

```powershell
desktop\gradlew.bat -p desktop :app:test --tests '*SharedSupervisorModelTest'
desktop\gradlew.bat -p desktop :ocr:evaluate
desktop\gradlew.bat -p desktop check
```

`check` 包含共享源码漂移、测试、模型校验与 OCR 质量评估。最近入库 OCR 结果为 14/15（93.33%）、覆盖 15/80，未达到门槛；当前不能承诺 `check` 全绿，也不要跳过门槛后宣称完整验收。样本、输出与诊断命令见 [OCR 评估说明](ocr/evaluation/README.md)。

部分测试需要实际打包程序；构建应用镜像后显式提供路径，否则相关检查可能跳过：

```powershell
desktop\gradlew.bat -p desktop :app:test --tests '*SharedPackagedRuntimeTest' '-PpackagedWorkerExecutable=E:\path\to\LimingjieAssistant.exe'
```

从解压后的便携目录执行运行环境自检：

```powershell
.\LimingjieAssistant.exe --self-test --report .\self-test.json --data-dir '.\自检 数据'
```

自检使用假数据，网络部分仅执行 TLS 握手，不登录账号、不操作游戏。D7 已有验证记录为最终 52 项定向检查、10 项环境自检通过；不代表全仓 `check`、Android 全量构建或真实双设备游戏任务已通过，见 [D7 报告](../docs/desktop/reports/SHARED-RUN-TABS-D7.md)。

## 本仓库的 CI

[Windows Desktop CI](../.github/workflows/desktop-ci.yml) 面向本项目的 Windows x64 客户端，版本读取 `desktop/build.gradle.kts`（当前 `1.0.18+desktop.7`）。默认在 `main` 的相关代码改动或 PR 上验证；普通说明文档变化不触发构建。许可证和随包模型声明等打包输入另行保留触发。开发分支可通过 PR 或手动运行验证，避免同一分支 push 与 PR 重复构建。

- 使用 Windows、JDK 21，一次完成桌面 `check`、共享源码漂移、原生模块、Compose 与 OCR 检查，不再先跑 Linux 全套后又在 Windows 重跑。先构建应用镜像并将 exe 路径传入测试，实际打包进程检查不再因缺少路径而跳过。
- `--continue` 保留其他独立检查的结果；OCR 门槛不变，未通过仍使工作流失败，并阻止候选 ZIP 打包与上传。运行摘要直接展示测试通过/失败/跳过、OCR 准确率和覆盖，不能把本地既有结果当作该次云端结果。
- 默认只验证，不上传 APK、大型 ZIP 或详细报告。手动选择 `package_candidate`，或推送匹配源码版本的 `desktop-v<完整桌面版本>` 标签（当前为 `desktop-v1.0.18+desktop.7`），才在全部检查通过后构建候选 ZIP、核对 SHA-256、运行中文/空格目录自检并上传 ZIP 与校验文件，保留 3 天。标签不会创建 GitHub Release。
- 详细报告仅在手动勾选 `upload_reports` 时上传，保留 7 天；需要 GitHub 账号有可用产物存储额度。最近旧流程曾因上传 APK 超出额度而失败，不能把这种上传失败说成编译失败。本轮不删除其他仓库或旧运行的产物。
- 同一分支/PR 的过时运行自动取消，Gradle 使用构建缓存；只有 `main` 写回共享缓存，其他分支只读。云端任务有超时限制。

[Upstream Android Compatibility](../.github/workflows/android-ci.yml) 单独验证保留的 Android 工程，使用 JDK 17 和 Android 自身版本（当前 `1.0.18`）。仅在 Android 代码/构建、相关检查脚本变化或手动运行时执行 lint、单测与 APK 编译，**不上传或发布 APK**；它不是桌面发行流水线，也不重复放在桌面工作流中。
