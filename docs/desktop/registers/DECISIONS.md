# DECISIONS.md —— 决策登记（只有总指挥可以修改）

**规则**
- D-01 … D-27 的权威文本在 `PLAN.md §4`，这里只放索引和状态。要推翻其中一条，必须在本文件新开一条，写明证据，并把原条目的状态改为「已推翻（见 D-xx）」，同时更新 PLAN.md。
- D-28…D-36 为原方案预登记，D-37 为 2026-10-25 用户授权修订；执行中新决策从 D-38 顺延，按下面模板写。
- 「方案假设不成立」类条目（AGENTS.md 开头的规则）也写在这里，标题以「事实修正：」开头。
- 子代理不直接修改本文件，在报告里提出，由总指挥写入。

**模板**

```markdown
### D-<nn> <标题>
- 状态：已定 / 待确认（由谁在何时确认）/ 已推翻（见 D-xx）
- 背景：<问题；上游代码事实（文件:行）>
- 决策：<做什么>
- 被拒方案：<方案 + 原因>
- 证据：<测试、测量、日志>
- 影响：<WS、SPEC 章节、EDGE 编号、FALLBACKS 编号>
```

## 1. 方案阶段已定（索引）

| ID | 摘要 | 状态 |
|---|---|---|
| D-01 | 共享上游源码，垫片 + 同名替身 + 少量纯搬移补丁 | 已定 |
| D-02 | 独立 Gradle 构建 `desktop/`，Kotlin 2.x + Compose Multiplatform | 已定 |
| D-03 | 截图用 `adb exec-out screencap`，Raw 优先、PNG 备选，连接时测速 | 已定 |
| D-04 | 触控用 MaaTouch 常驻进程 | 已定 |
| D-05 | 无障碍替代：前台包名用 `dumpsys`，渠道 SDK 页用 uiautomator | 已定 |
| D-06 | OCR = ONNX Runtime + PP-OCR 中文 mobile | 已定 |
| D-07 | Room 2.7+（JVM）+ BundledSQLiteDriver，`AppDatabase` 同名替身 | 已定（M1 spike 后确认） |
| D-08 | 凭据用 DPAPI（CurrentUser） | 已定 |
| D-09 | 协议设备画像来自目标模拟器的快照 | 已定 |
| D-10 | Geetest：回环 HTTP + 系统浏览器；备选 KCEF | 已定（L3 验证） |
| D-11 | MAA 式桌面布局 + 重设计页面，共享业务状态与动作 | 已修订（D-37；PLAN 为当前文本） |
| D-12 | 通知 / 悬浮窗 → 窗口任务栏 + 托盘 | 已定 |
| D-13 | 诊断面板在 PC 上运行上游服务；日志包结构不变 | 已定 |
| D-14 | 数据目录：portable.flag → `.\data`，否则 `%LOCALAPPDATA%`，`--data-dir` 优先 | 已定 |
| D-15 | 发行 = jpackage app-image → 便携 zip，`includeAllModules` | 已定 |
| D-16 | 显示版本 `<versionName>+desktop.<n>` | 已定 |
| D-17 | 单进程、单设备、单任务 | 已定 |
| D-18 | adb 来源顺序：模拟器自带 → PATH → 用户指定 | 已定 |
| D-19 | 网络跟随系统代理，可切换直连 | 已定 |
| D-20 | 线程模型逐项对齐（THREADING.md） | 已定 |
| D-21 | Context / SharedPreferences 最小垫片，同步原子写 | 已定 |
| D-22 | Sync 任务复制上游源码 + 派生改写 | 已定 |
| D-23 | JetBrains lifecycle + 两个 ViewModel Factory 的派生改写 | 已定 |
| D-24 | 上游测试工作目录 = `android/app`；测试前后 `android/` 快照一致 | 已定 |
| D-25 | 桌面按内容区布局；复用组件时提供其所需环境垫片 | 已修订（D-37） |
| D-26 | `BitmapFactory` 使用已有 Skia 解码 → 非预乘 ARGB（D-45） | 已按真实资源修正 |
| D-27 | 动作前实时查询前台；非挂起回调读 500 ms 轮询缓存，超过 2 s 视为 null | 已定 |

## 2. 方案阶段预登记（SPEC 中要求“登记到 DECISIONS”的取舍）

### D-28 按住期间原地不动
- 状态：已定
- 背景：上游无障碍手势在 hold 期间漂移 1 px，原因是 Android 的 `GestureDescription` 不允许零长度笔画（SPEC-10 §5）。
- 决策：MaaTouch 用 `w <hold>` 原地按住，不模拟 1 px 漂移。
- 影响：WS-B；SPEC-10 §5。

### D-29 硬关闭使用 `am force-stop`
- 状态：待确认（L3-04）
- 背景：上游 `GameClientHardCloser` 用 `killBackgroundProcesses`；PC 侧没有同等 API。
- 决策：`killBackgroundProcesses` 接到 `am force-stop` + `pidof` 轮询。`force-stop` 更彻底，可能影响渠道 SDK 的登录状态，L3-04 验证。
- 被拒方案：`am kill`（只杀后台进程，游戏在前台时无效）。
- 影响：WS-H；SPEC-10 §6；EDGE D 组。

### D-30 坐标不做额外越界校验
- 状态：已定
- 背景：上游后端只拒绝负坐标（EDGE C-02）。
- 决策：与上游一致，只检查 ≥ 0；越界坐标透传给 MaaTouch。
- 影响：WS-B。

### D-31 画像中的显示尺寸按横屏记录
- 状态：已定
- 背景：上游取助手自身的 `displayMetrics`，模拟器横屏使用时就是横屏尺寸（SPEC-40 §2）。
- 决策：快照中 `displayWidth × displayHeight` 按横屏记录。
- 影响：WS-E。

### D-32 Room 库损坏时保留备份
- 状态：已定
- 背景：Android 默认的损坏处理会删除数据库文件。
- 决策：移到 `databases/corrupt-<时间>/` 后新建空库，并提示用户（SPEC-30 §4，FB-07）。
- 影响：WS-D。

### D-33 ONNX Runtime 加载前显式加载 VC++ 运行库
- 状态：待确认（Windows CI 的 self-test `ocr`）
- 决策：`<java.home>/bin/` 中存在 `msvcp140.dll`、`vcruntime140_1.dll` 时先 `System.load`（SPEC-20 §2）。
- 影响：WS-C、WS-G。

### D-34 替身签名的提取方式
- 状态：待确认（M1）
- 决策：优先用编译产物或 kotlinx-metadata 提取公共签名；做不到时退回声明级文本提取（SPEC-00 §7）。M1 结束时在这里写明最终采用哪种。
- 影响：WS-A；G1-2。

### D-35 诊断面板端口：启动前探测
- 状态：已定
- 背景：上游 `LabyrinthDebugDashboardServer(port = DEFAULT_PORT = 8765)` 绑定失败时只记日志并返回，外部无法得知失败（`LabyrinthDebugDashboardServer.kt:72-85`）；上游设置了 `reuseAddress = true`，在 Windows 上 `SO_REUSEADDR` 允许重复绑定同一端口，第二个实例可能“成功”绑定到已被占用的端口。
- 决策：装配层在构造前，用 `reuseAddress = false` 的 `ServerSocket` 依次探测 127.0.0.1:8765–8775，取第一个可用端口，通过上游已有的 `port` 构造参数传入（不需要补丁）。都不可用时不启动面板，诊断页显示原因（FB-10）。
- 影响：WS-H、WS-F；EDGE H-11。

### D-36 截图帧派发不依赖窗口可见性
- 状态：已定
- 背景：Android 上 MediaProjection 在应用切到后台后仍继续产帧。
- 决策：主窗口最小化或隐藏到托盘时，截图线程、识别和任务照常运行；只有预览停止渲染。
- 影响：WS-B、WS-F。

## 3. 用户确认的产品修订

### D-37 MAA 式桌面产品与功能页面重设计
- 状态：已定（2026-10-25，用户在会话中授权按核心原则更新）。
- 用户意图：类似 MAA 的 Windows 助手；原安卓版功能在桌面使用，界面可调整和美化。
- 决策：业务能力和行为保持一致，界面按桌面使用方式重新设计。保留共享源码及设备 / OCR / 协议 / 存储 / 打包路线；桌面自有呈现仍调用上游状态和动作。模拟器只承载游戏，无需安卓版助手。
- 取代的旧限制：D-11 的“能编译的屏幕必须直接复用”、SPEC-50 的“布局与展示文案一律不改”、WS-F 的“不要重写上游屏幕”。D-25 只对需要环境适配的复用组件提供尺寸垫片。禁止修改上游业务和 android/ 的限制继续有效。
- 执行：先给主要页面原型和简短功能清单；G4 以典型操作和少量代表页面确认可用。按用户补充要求不新增 Gate、不逐字段列证据、不搭建截图矩阵，已有检查直接复用。授权内视觉细节不逐页审批；原型、实现与实机验证如实区分。
- 证据：本次会话用户确认与 REVISION-NOTES。此为需求修订，不是实施或测试通过证据。
- 影响：PLAN、AGENTS、KICKOFF、SPEC-00/50/60、WORKSTREAMS、ACCEPTANCE、UI_PARITY、PROGRESS、SUBSTITUTES、EDGE_CASES 与 UPSTREAM_SYNC。

## 4. 执行期新增（D-38 起）

### D-38 正式开发授权与工作目录
- 状态：已定（2026-10-25 当前用户指示）。
- 决策：用户已授权按项目文档完成项目、使用子代理、一次性推进。当前正式目录为 `D:\Project\LimingjieDesktop`；此前仅整理准备包的边界已被本次明确开发授权替代。旧项目和其账号数据不修改。
- 上游：完整 Git 历史接入当前目录，remote 为 upstream，开发分支 desktop/main；HEAD 基线 b809b196988b019571caf5679ccf8d7ebb7f072d。

### D-39 M1 工具链与数据库 spike
- 状态：已定。
- 决策：Temurin JDK 21、Gradle 8.12.1、Kotlin 2.1.20、Compose 1.8.2、KSP 2.1.20-1.0.32、Room 2.7.2、SQLite 2.5.2，精确依赖见 desktop/gradle/libs.versions.toml。
- 证据：共享主代码与测试编译成功；Room v1–v4 迁移保留账号，transaction 提交/回滚、Flow 查询和外键级联通过。生成 v4 identityHash 与上游均为 bfde7e38b202249fe444412498f13b28。无需启用 JDBC 备选。
- 编译器报告了上游已有的条件恒真、实验性 API 和数据类 copy 可见性警告；未为压制错误新增 Suppress。

### D-40 平台端口的依赖解释
- 状态：已定。
- 决策：遵守 PLAN 的模块依赖方向。platform 提供纯 JVM DPAPI facade，由 app 实现 shared.SecretProtector 适配；device 画像由 app 映射为 shared DTO。避免 SPEC-30 中“platform 实现 shared 接口”的文字与无反向依赖原则冲突。

### D-41 M1 额外纯搬移与签名检查方式
- 状态：已定。
- 事实：上游测试还引用 Android 文件内的 PermissionCenterState 和 wrapAutomationOverlayLabel；新增 P-08/P-09 纯搬移，使测试对象继续共享，不为桌面排除其测试。
- D-34 结论：采用声明级文本提取检查同名替身函数、构造参数及默认值；排除 private 类型成员，Room migrate 参数的 JVM 差异明确允许。另锁定被适配上游文件内容以检测实现常量/字段漂移，更新需人工审阅。
- 测试缺口：上游自身因缺少本地素材跳过 46 项；未把跳过计为通过。排除清单仅含 Android AccessibilityEvent 跟踪测试一份。

### D-42 桌面生命周期与连接画像
- 状态：已定。
- 决策：P-10 给 controller、入口识别、会话重置注入拥有者可取消的作用域，Android 默认表达式不变；P-11 给诊断服务注入 socket 工厂及地址复用开关。桌面退出最多等待任务停止 10 秒，随后逐项释放资源。
- 任务启动和停止都保持互斥锁定；截图稳定等待上限 5 秒，使用专用 desktop-capture 线程，每 300ms 采样，预览复制像素且不超过 2fps。
- 每次连接或自动重连到 Ready 都保存新画像并清理协议网关缓存，防止切换模拟器后使用旧设备信息；重连不恢复原任务。
- 刷开局监视任务使用 Default 线程读取 StateFlow，不执行 UI 操作；controller 本身保持 Swing Main。Windows 防睡眠由独立专用线程持有。
- 证据：共享入口识别/会话重置 15 项定向测试通过；DesktopAdaptersTest 验证截图断线、OCR 回调线程与位图所有权、诊断包及端口释放。Graph 端到端结果另记。

### D-43 事实修正：上游没有启动图标资源
- 状态：已定。
- 事实：基线 AndroidManifest 没有 icon 属性，res 只有 values 与 accessibility XML，无法转换方案中假定存在的图标。
- 决策：采用简单的紫色底月牙与金色圆点桌面标记，窗口与发行 ico 保持同一图形；不引入第三方品牌或素材。来源写入第三方声明。

### D-44 自检与构建环境隔离
- 状态：已定。
- 决策：新增 --self-test 使用独立临时目录、固定假账号和内置 OCR 样本；仅 TLS 握手触及 SDK 主机，不发送业务请求。生产数据目录先检查可写并加实例锁；测试不读取旧项目账号。
- 本机 SDK 与 Temurin 17 仅安装于 .tools，专门验证 Android 上游兼容。桌面使用 Temurin 21，依赖版本不升级上游 Android 项目。

### D-45 WebP 资源使用已有 Skia 解码
- 状态：已定。
- 证据：真实 Graph 首次加载 `icon_unit_100661.webp` 时，ImageIO 无 WebP 解码器导致识别会话启动失败。上游角色图标广泛使用 WebP，属于运行必需资源。
- 决策：BitmapFactory 使用 Compose 已依赖的 Skia 统一解码，读取 BGRA_8888 / UNPREMUL 并转换为 ARGB IntArray；不增加额外图像库或改写识别算法。保留尺寸预读、采样、无效图返回 null 和位图所有权语义。
- 验证：原 PNG 往返/无效图用例通过；实际 128×128 WebP 的透明度、已知像素颜色、尺寸及 2 倍采样通过。完整共享 fixture 结果见最终报告。

### D-46 OCR 模型选择、预处理事实修正与短 ROI 回退
- 状态：已定；准确率门槛仍失败，不作为 G2-C 通过。
- 证据：`desktop/ocr/evaluation/baseline-v4.json` 为 7/14；按 SPEC-20 仅比较一次 v5，`baseline-v5.json` 为 9/14。v4 对真实孤立数字 5/4/1 的检测概率图为全零，但直接识别同一 ROI 可读 5/4/2/1，证明检测阶段遗漏。
- 决策：采用官方固定 PP-OCRv5 mobile det/rec 及对应字典；全部来源与 SHA-256 见 `desktop/ocr/MODELS.md`。保留上游 matcher、阈值、缓存、调度。
- 事实修正：SPEC 的识别宽度简写遗漏官方短序列补边。按 PaddleOCR `tools/infer/predict_rec.py` 中 `rec_image_shape=(3,48,320)` 与 `resize_norm_img`，短文字内容保持等比缩放，张量右侧补零至至少 320，长文字仍限 1280。不改变像素通道或识别置信度。
- 有界回退：FB-14 只对检测/识别为空、宽高均 32..256、长宽比 0.5..2、非纯色的输入追加一次整图识别。仍使用 0.5 置信度；不以特定字词补齐结果。
- 最终：`final-v5.json` 为 12/14（85.71%），p50 18.08 ms、p95 152.78 ms；系列长列与低分辨率数字 5 仍失败。按已有两组模型补做 v5 det + v4 rec 组合验证仍 12/14且系列计数更少（`final-v5det-v4rec.json`），无改善，保留纯 v5并停止尝试。
- 覆盖：仅 14 条真实 ROI，80 条目标尚缺 66，角色奖励类为 0。`check` 依赖 `evaluate`，准确率不足不能悄悄通过。缺口交 L2 用户实采清单。
- 影响：WS-C、SPEC-20、EDGE E 组、FB-14；没有上游业务修改。
- 继续诊断的事实纠正：此前v4直接识别5/0.986来自1920×1080的`map-boss-0`，不是低清`map-link-0`；不能据此认定320补边导致低清数字回归。
- 追加FB-14末级分支：已有紧凑ROI原检测及整图识别均无有效文本时，四周补短边1/4黑边，最多再做一次det+rec；不递归、不改原像素/阈值、不覆盖已有有效结果。低清5从空恢复至5/0.835，另外5个真计数保持原结果，5个真实无文字图标/背景对照无新增识别。运行代价与负样本限制见`evaluation/preprocessing-diagnosis.md`。
- 当前原14评估：`final-v5-border.json`为13/14（92.86%），p50 23.32 ms / p95 157.57 ms；仍未达到95%整体/85%每类门槛。17项定向回归通过。所有此前失败报告保留。
- 系列定位缺口：原图2/1/1区域det最大概率分别0.00782/0.00000319/0.00000671，没有达到0.3的候选，排除候选框过滤和坐标映射错误。独立像素裁剪可读但不用于生产按位置补字；常规变换、长列分块、四周及左右补边均未补全且部分出现误识别。保留失败，未启用这些不充分方案。模型仍为同一固定v5。

- 最终扩大清单：另加一张真实地图计数且原14真值不变，`final-v5-expanded.json` 为14/15（93.33%），p50 23.27ms/p95 151.04ms；仍失败且15/80覆盖不足。新增来源见SAMPLE_AUDIT。

### D-47 桌面发行版本的测试期待
- 状态：已定。
- 证据：最终共享测试 1,256 项中只有 AppVersionTest 的固定 `1.0.18` 期待失败；生产版本按 D-16 必须显示 `1.0.18+desktop.1`。其余 1,209 通过、46 因原素材缺失跳过。
- 决策：仅对生成目录内 AppVersionTest 的这一条文字期待追加桌面后缀，映射登记在 `desktop/shared/upstream-test-derived.txt`，构建校验精确命中一次。保留原测试、原 versionCode 及统一显示标签断言，不修改 Android 测试或业务源码。上游版本变更仍要求审阅映射。
- 验证：只重跑失败类，并保留此前完整测试报告摘要，避免重复运行无变化的其他用例。

### D-48 第 2 轮体验设计先交检查点 A
- 状态：盘点与设计稿完成；视觉方向待用户确认，正式实现未启动。
- 依据：用户要求按照 UX 包优化 UI；包内 §9 明确设定 UX-1 后交用户确认。由 `bf1bad8` 创建 `codex/ux-round-2`，原包和参考稿留在 docs，保留可审阅 Git 记录。
- 决策：先交五类关键页 × 浅深色设计稿及独立数据目录的现有界面截图，用户确认后进入 UX-2。现有 `desktop.1` 程序和事实性验证结论不提前改成新版本已完成。
- 验证：20 组 HTML 布局与代表性交互通过，产出 14 张设计截图；七个真实导航页两种尺寸加三个表单共 17 张基线截图。范围见 `ux/INVENTORY.md` 与 `ux/DESIGN-REVIEW.md`。

### D-49 设计只展示可溯源的状态
- 状态：设计约束已定，实际 presenter / 存储接线待实现。
- 决策：joinedCharacters 标“已加入角色”；路线节点只按数量绘制；遗物观察次数不视为持有数量；批量开始时间不能借用 updatedAt。数据库展示缓存与运行记录在桌面层提供，缺字段显示空状态，不在 UI 重组中做 I/O。
- 表单边界：完整任务、策略和编组仍复用上游；策略 Dialog 尺寸和原生评分的独立入口先验证可行性，不修改 Android，不复制表单。角色评分空输入不提交，恢复原评分才产生 null 语义。
- 原型边界：截图和运行数值明确为示例，画面占位不假装实机。当前 SVG 是内联线框示意，正式 Material Symbols 资源与许可归 UX-2 处理；没有新增外部图标依赖。

### D-50 使用 VS Code 默认配色并保留本轮效果
- 用户批准实现，明确用 Light Modern / Dark Modern 替代包内紫色方案；主题集中到 DesktopTheme.kt，Material Symbols SVG 与对应许可已加入。
- 为避开原目录持续变化的设备/业务修改，工作隔离到 `codex/ux-vscode`，基线 `157bc4b`。Android、OCR 和设备模块没有本轮改动。
- 用户随后确认“先改成这样就行了”。当前实现作为保留状态提交；不追加实机采集、便携 ZIP 或交接包。窗口关闭转发、原生 DPI 与长时性能的未验证项如实记录在 `reports/UX-VSCODE.md`。

