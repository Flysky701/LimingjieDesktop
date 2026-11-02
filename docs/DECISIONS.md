# 架构与实现决策

本文汇总桌面版的核心架构决策与实现取舍。早期方案背景保存在 `docs/archive/`。

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
| D-17 | 多设备隔离：主服务管理共享数据，每台在线模拟器运行在独立设备 worker JVM 中 | 已演进（由初版单设备升级为多 JVM 隔离） |
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

### D-28 MaaTouch 长按保持原地坐标
- 上游 Android 无障碍手势因 `GestureDescription` 限制在长按时偏移 1 px；桌面端 MaaTouch 直接使用 `w <hold>` 原地按住。

### D-29 游戏进程硬关闭使用 `am force-stop`
- 桌面端通过 `am force-stop` 配合 `pidof` 轮询实现 `GameClientHardCloser`（`am kill` 无法终止前台进程）。

### D-30 触控坐标校验与上游对齐
- 与上游一致仅拒绝负坐标，其余坐标直接透传给触控后端。

### D-31 设备画像按横屏尺寸记录
- 模拟器以 1920 × 1080 横屏运行，协议画像中的 `displayWidth × displayHeight` 固定按横屏记录。

### D-32 数据库损坏时先备份再重建
- Room 数据库损坏时先移入 `databases/corrupt-<时间>/` 保留现场，再初始化空库并提示用户。

### D-33 预加载内置 JRE 的 VC++ 运行库
- 初始化 ONNX Runtime 前，若 `<java.home>/bin/` 下存在 `msvcp140.dll`、`vcruntime140_1.dll`，先显式调用 `System.load`。

### D-34 替身签名与上游漂移校验
- 采用声明级提取校验同名替身的函数、构造参数与默认值，并通过 `upstream-drift.properties` 锁定被替身文件的 SHA-256。

### D-35 诊断面板端口启动前探测
- 构造诊断服务前先以 `reuseAddress = false` 探测 `127.0.0.1:8765–8775` 的首个可用端口传入，避免 Windows 下 `SO_REUSEADDR` 重复绑定同一端口。

### D-36 最小化或隐藏到托盘时不中断后台采集
- 窗口最小化或隐藏至托盘时仅停止 UI 预览渲染，截图采样、识别与自动化任务照常运行。

### D-37 桌面端界面重设计
- 保持上游业务能力与规则不变，界面按桌面多标签操作重设计；模拟器仅运行游戏客户端。

### D-38 仓库与上游基线
- 接入上游 Git 历史（初始基线 `b809b196988b019571caf5679ccf8d7ebb7f072d`），共享源码位于 `android/`，桌面工程位于 `desktop/`。

### D-39 工具链与 Room 数据库迁移校验
- 采用 JDK 21、Gradle 8.12.1、Kotlin 2.1.20、Compose 1.8.2、Room 2.7.2 与 SQLite 2.5.2；Room v1–v4 迁移及 schema identity hash（`bfde7e38b202249fe444412498f13b28`）与上游一致。

### D-40 模块单向依赖边界
- `platform`、`device`、`ocr` 保持纯 JVM 实现，不反向依赖 `shared`；由 `app` 模块完成 `SecretProtector`、设备画像 DTO 等适配组装。

### D-41 共享测试纯搬移
- 将上游测试引用的 `PermissionCenterState` 和 `wrapAutomationOverlayLabel` 纳入共享搬移，使上游单元测试直接在桌面构建中复用。

### D-42 生命周期作用域与连接画像刷新
- 为控制器、入口识别和会话重置注入可取消作用域；桌面退出时等待任务停止（上限 10 秒）后依次释放资源。
- 每次设备连接就绪时更新设备画像并清理协议网关缓存；断线重连后不自动恢复原任务。
- 截图采用独立 `desktop-capture` 线程（300 ms 采样，UI 预览最高 2 fps）；Windows 防睡眠由独立线程持有。

### D-43 桌面应用图标
- 上游基线不含应用图标资源，桌面版使用自绘几何月牙与菱形矢量图标（`desktop/app/src/main/resources/desktop/app.svg`）生成窗口图标与 `.ico`。

### D-44 `--self-test` 独立自检模式
- `--self-test` 使用独立数据目录、测试数据与内置 OCR 样图检验运行环境，网络部分仅执行 TLS 握手。

### D-45 使用 Skia 解码 WebP 与 PNG 资源
- 上游角色图标大量使用 WebP，桌面 `BitmapFactory` 统一通过 Compose 已依赖的 Skia 解码为 `ARGB` 像素数组，无需额外引入图像库。

### D-46 OCR 模型、短 ROI 补边回退与系列计数核验
- 采用固定 PP-OCRv5 mobile 检测/识别模型与字典（SHA-256 见 `desktop/ocr/MODELS.md`），并使用 `compact_ctc_model.py` 在模型尾部追加 `ArgMax`/`ReduceMax` 以缩减张量体积与包大小。
- 短文本按 PaddleOCR 标准 `rec_image_shape=(3,48,320)` 等比缩放并右侧补零，长文本上限 1280。
- **紧凑 ROI 回退（FB-14）**：对宽高在 `32..256`、长宽比 `0.5..2` 且非纯色的 ROI，若常规检测+识别无结果，先直接整图识别一次；若仍无结果，四周补短边 1/4 黑边后再执行一次 `det+rec`。
- **系列面板计数**：标题通过后按行读取图标右下角数字，要求原图与去背景字形读数一致。当前 15 条真实 ROI 评估全部通过（15/15），完整 80 条样本覆盖仍在补充中（见 `desktop/ocr/evaluation/README.md`）。

### D-47 版本号统一注入
- 显示版本、安装包数字版本及派生测试中的版本断言统一从 `desktop/build.gradle.kts` 读取，构建期通过 `@DESKTOP_VERSION@` 注入。

### D-48 … D-50 桌面视觉主题与可溯源状态展示
- 界面统一采用 VS Code Light Modern / Dark Modern 配色（`DesktopTheme.kt`）与 Material Symbols 图标。
- 界面仅展示有真实数据源支撑的字段（如已加入角色、节点计数、完成局分数与耗时趋势），缺失字段显示空状态，且不在 Compose 重组期间执行 I/O。
