# 架构与实现决策

本文汇总桌面端架构决策与跨仓演进契约；早期背景归档于 `docs/archive/`。

## 1. 基础架构决策（D-01 … D-27）

| ID | 决策摘要 | 当前状态 |
| --- | --- | --- |
| D-01 | 共享上游源码，通过垫片、同名替身与少量纯搬移补丁复用业务逻辑 | 已定 |
| D-02 | 独立 Gradle 工程 `desktop/`，基于 Kotlin 2.x + Compose Multiplatform | 已定 |
| D-03 | 截图使用 `adb exec-out screencap`，Raw 优先、PNG 备选，连接时自动测速 | 已定 |
| D-04 | 触控默认使用 MaaTouch 常驻进程，保留 ADB 输入兼容模式 | 已定 |
| D-05 | 前台包名通过 `dumpsys` 查询，渠道 SDK 登录页通过 `uiautomator` 读取节点 | 已定 |
| D-06 | OCR 采用 ONNX Runtime + PP-OCRv5 中文 mobile 模型 | 已定（见 D-46） |
| D-07 | 数据库采用 Room 2.7+（JVM）+ `BundledSQLiteDriver`，提供 `AppDatabase` 同名替身 | 已定（见 D-39） |
| D-08 | 账号凭据使用 Windows DPAPI（CurrentUser）加密，不回退明文 | 已定 |
| D-09 | 协议设备画像取自所连接模拟器的硬件与系统属性快照 | 已定 |
| D-10 | Geetest 验证通过本机回环 HTTP 服务在系统默认浏览器中完成 | 已定 |
| D-11 | 采用桌面重设计布局与 VS Code Modern 浅/深色主题，复用上游业务状态与动作 | 已修订（见 D-37、D-50） |
| D-12 | 状态提示集成到桌面窗口任务栏与系统托盘 | 已定 |
| D-13 | 在桌面本机运行诊断面板服务，保持诊断 ZIP 结构兼容 | 已定 |
| D-14 | 数据目录优先级：`--data-dir` > `portable.flag` 旁的 `.\data` > `%LOCALAPPDATA%\LimingjieAssistant` | 已定 |
| D-15 | 发行采用 `jpackage` 生成含内置 JRE 的应用镜像并打包便携 ZIP | 已定 |
| D-16 | 显示版本格式为 `<versionName>+desktop.<n>`，统一由 `desktop/build.gradle.kts` 驱动 | 已定（见 D-47） |
| D-17 | 多设备隔离：主服务管理共享数据，每台在线模拟器运行在独立设备 worker JVM 中 | 已演进 |
| D-18 | ADB 查找顺序：模拟器自带路径 → 系统 `PATH` → 用户指定路径 | 已定 |
| D-19 | 网络默认使用系统固定代理，可在设置中切换直连 | 已定 |
| D-20 | 协程与 UI 线程边界对齐，OCR 及业务状态回调切回 Swing Main | 已定 |
| D-21 | `Context` 与 `SharedPreferences` 使用最小桌面垫片，配置文件同步原子写入 | 已定 |
| D-22 | 构建期通过 Sync 任务复制上游源码并执行机械派生改写 | 已定 |
| D-23 | 采用 JetBrains Lifecycle 与桌面派生 ViewModel Factory | 已定 |
| D-24 | 上游共享测试以 `android/app` 为工作目录，测试前后校验 `android/` 工作树不变 | 已定 |
| D-25 | 桌面页面按桌面内容区布局，对复用的共享组件提供所需环境垫片 | 已修订（见 D-37） |
| D-26 | `BitmapFactory` 使用 Compose 内置 Skia 解码 PNG/WebP 为非预乘 ARGB 像素 | 已定（见 D-45） |
| D-27 | 点击动作前实时校验前台包名；非挂起回调读取 500 ms 轮询缓存（超 2 s 视为失效） | 已定 |

## 2. 平台适配与运行细节（D-28 … D-50）

| ID | 决策摘要 | 关键实现与约束 |
| --- | --- | --- |
| D-28 | MaaTouch 长按保持原地 | 直接使用 `w <hold>` 原地按住，不复刻 Android 无障碍 1 px 偏移 |
| D-29 | 游戏进程硬关闭 | 通过 `am force-stop` 配合 `pidof` 轮询实现 `GameClientHardCloser` |
| D-30 | 触控坐标校验 | 仅拒绝负坐标，其余与上游一致透传给触控后端 |
| D-31 | 横屏设备画像 | 模拟器按 1920 × 1080 横屏运行，协议画像 `displayWidth × displayHeight` 固定按横屏记录 |
| D-32 | 数据库损坏保护 | 先移入 `databases/corrupt-<时间>/` 保留现场，再初始化空库并提示用户 |
| D-33 | 预加载 VC++ 运行库 | 初始化 ORT 前若 `<java.home>/bin/` 存在 `msvcp140.dll`、`vcruntime140_1.dll`，先显式 `System.load` |
| D-34 | 替身签名与漂移锁 | 提取校验同名替身签名与默认值，并通过 `upstream-drift.properties` 锁定 SHA-256 |
| D-35 | 诊断端口启动前探测 | 以 `reuseAddress = false` 探测 `127.0.0.1:8765–8775` 首个可用端口，规避 Windows `SO_REUSEADDR` 冲突 |
| D-36 | 最小化/托盘不中断采集 | 窗口隐藏时仅停止 UI 预览渲染，截图采样、识别与任务照常运行 |
| D-37 | 桌面界面重设计 | 保持上游业务规则不变，界面按桌面多标签重设计；模拟器仅运行游戏 |
| D-38 | 上游 Git 基线 | 接入上游历史（初始基线 `b809b196988b019571caf5679ccf8d7ebb7f072d`），共享源码在 `android/`，桌面工程在 `desktop/` |
| D-39 | 工具链与 Room 校验 | JDK 21、Gradle 8.12.1、Kotlin 2.1.20、Compose 1.8.2、Room 2.7.2、SQLite 2.5.2；保持 v1–v4 迁移与 schema identity `bfde7e38b202249fe444412498f13b28` |
| D-40 | 单向依赖边界 | `platform`、`device`、`ocr` 保持纯 JVM，不依赖 `shared`；由 `app` 完成平台适配组装 |
| D-41 | 共享测试纯搬移 | 纳入 `PermissionCenterState` 与 `wrapAutomationOverlayLabel`，使上游单测直接在桌面复用 |
| D-42 | 作用域、画像与线程 | 注入可取消作用域，退出等待上限 10s；每次连接就绪刷新画像并清网关缓存，重连不自动续跑；独立 `desktop-capture` 线程（300 ms 采样，预览 ≤ 2 fps）与防睡眠线程 |
| D-43 | 桌面应用图标 | 使用自绘几何矢量图标 `desktop/app/src/main/resources/desktop/app.svg` 生成窗口图标与 `.ico` |
| D-44 | `--self-test` 自检 | 使用独立数据目录、测试数据与内置 OCR 样图检验环境，网络仅做 TLS 握手 |
| D-45 | Skia 图像解码 | 复用 Compose 内置 Skia 解码 WebP/PNG 为 ARGB 像素，不引入额外图像依赖 |
| D-46 | OCR 模型与回退 | 固定 PP-OCRv5 mobile + `compact_ctc_model.py`；短文本补零至 320 宽、长文本上限 1280；紧凑 ROI（32..256）先整图识别再 1/4 黑边重试；系列计数原图与去背景双重一致，当前 15/15 通过（目标 80 条，见 [evaluation/README.md](../desktop/ocr/evaluation/README.md)） |
| D-47 | 版本号统一注入 | 显示版本、安装包版本与测试断言统一由 `desktop/build.gradle.kts` 经 `@DESKTOP_VERSION@` 注入 |
| D-48…D-50 | 视觉主题与可溯源状态 | 采用 VS Code Light/Dark Modern 配色（`DesktopTheme.kt`）与 Material Symbols；仅展示有真实数据源的字段，不在 Compose 重组期间执行 I/O |

## 3. 跨仓协同与演进契约（D-51 … D-54）

| ID | 决策摘要 | 关键实现与约束 |
| --- | --- | --- |
| D-51 | 跨仓单一路线图源 | 总里程碑以 [ROADMAP.md](ROADMAP.md) 为准；`android/` 保持零 ONNX 依赖并通过 SPI 解耦规则与模型；`LimingjieNN` 为离线训练、Path B 参考实现与模型发布源 |
| D-52 | 战斗模型内置与回退 | 桌面端双塔 ONNX 包（`limingjie_twotower_onnx_v1`）倾向随便携 ZIP 内置，保留 JVM 参数、环境变量与 `data/models/` 覆盖；严格校验文件 SHA-256 与 `catalog_sha256`，失败或禁用自动回退规则 |
| D-53 | 视觉冗余与可回放诊断 | 页面、节点、角色/奖励、OCR 与金币感知建立多信号交叉验证与安全降级；低置信/冲突自动留存原帧、ROI 与得分快照以支持离线回放 |
| D-54 | Path B 分层与默认兼容 | 官方结算分规划实现于共享纯 Kotlin `kernel/planning/`，与 Python 参考实现黄金对齐；新策略由显式开关控制，默认兼容既有规则 |
