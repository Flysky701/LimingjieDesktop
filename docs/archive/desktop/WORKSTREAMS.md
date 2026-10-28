# WORKSTREAMS.md —— 工作流划分、路径归属、子代理模板

## 1. 总览

| WS | 名称 | 里程碑 | 执行者 | SPEC | Gate | 依赖 |
|---|---|---|---|---|---|---|
| A | 骨架、共享源码、契约 | M0–M1 | **总指挥本人** | SPEC-00 | G1 | — |
| B | 设备层 | M2 | 子代理 | SPEC-10 | G2-B | G1 |
| C | OCR | M2 | 子代理 | SPEC-20 | G2-C | G1 |
| D | 持久化、安全与 Windows 平台 | M2 | 子代理 | SPEC-30 | G2-D | G1 |
| E | 协议画像与人工验证码 | M2 | 子代理 | SPEC-40 | G2-E | G1 |
| F | 桌面界面 | 主要页面原型 → M2（骨架）→ M4（对齐） | 总指挥准备原型；M1 后可派子代理 | SPEC-50 | G2-F → G4 | 原型可与 M1 并行；实现依赖 G1；M4 依赖 G3 |
| G | 打包、自检与 CI | M2（骨架）→ M5 | 子代理 | SPEC-60 | G2-G → G5 | G1 |
| H | 装配与假设备端到端 | M3 | **总指挥本人**（可派子代理写 e2e 测试） | PLAN §5、SPEC-10/50 中的接线说明 | G3 | G2-B/C/D/E/F |

没有子代理能力的环境：总指挥完成 M0，准备主要页面原型后，按 A → G → B → C → D → E → F → H 实现顺序自己执行卡片，Gate 要求不变。

## 2. 路径归属

### 2.1 包名约定

| 内容 | 包名 |
|---|---|
| 框架垫片 | 与 Android 原包一致（`android.*`、`androidx.*`） |
| 同名替身 | 与上游原文件一致（`com.landosol.toolbox.<原路径>`） |
| 桌面自有代码 | `com.landosol.toolbox.desktop.<模块>.<领域>`，例如 `…desktop.device.capture`、`…desktop.app.adapters.device` |
| `:shared` 中的桌面代码 | `…desktop.bindings`（端口接口与 `DesktopBindings`）、`…desktop.bridge.<领域>`（访问上游 `internal` 的桥接）、`…desktop.io`（原子写）、`…desktop.log`（日志器） |

**适配器规则**：`:device`、`:ocr`、`:platform` 不依赖 `:shared`，只暴露纯 JVM API。把它们接到上游类型（`AutomationActionBackend`、`ChannelPromptScreen`、`OcrEngine` → 替身等）的适配器放在 `:app` 的 `…desktop.app.adapters.<领域>`，**归提供能力的 WS 所有**，这样语义和测试由同一个 WS 负责。

### 2.2 归属表（只能修改自己名下的路径；`desktop/` 前缀省略）

| WS | 可写路径 |
|---|---|
| A | `settings.gradle.kts`、`build.gradle.kts`、`gradle.properties`、`gradle/**`、`gradlew*`、`CONTRACTS.md`、`shared/build.gradle.kts`、`shared/upstream-*.txt`、`shared/src/{main,test}/kotlin/{android,androidx}/**`、`shared/src/{main,test}/kotlin/com/landosol/toolbox/desktop/{bindings,io,log}/**`、`shared/src/**/BuildConfig*`、`tools/**`；仓库根下 `android/**`（只限 P-xx，AGENTS §2）；`docs/desktop/**`（登记表规则见 §2.3）；M1 期间创建的全部替身桩（之后按 SUBSTITUTES.md 移交） |
| B | `device/**`（含 `build.gradle.kts`、`MAATOUCH.md`、`src/testFixtures/**` 中的 `FakeAdb`、fixtures 及其 `SOURCES.md`）；`shared/src/{main,test}/kotlin/com/landosol/toolbox/desktop/bridge/capture/**`（`DesktopCaptureService` 桥接）；`app/src/{main,test}/kotlin/com/landosol/toolbox/desktop/app/adapters/device/**`（`DeviceActionBackend`、`UiAutomatorChannelPromptScreen`、前台监视器、应用控制适配） |
| C | `ocr/**`（含模型、`eval/`）；替身 `shared/src/main/kotlin/com/landosol/toolbox/labyrinth/vision/AndroidChineseRoleNameOcr.kt` 及其测试；`app/src/{main,test}/kotlin/…/desktop/app/adapters/ocr/**` |
| D | `platform/**`（数据目录、实例锁、DPAPI、防睡眠）；替身 `shared/…/data/local/AppDatabase.kt`、`shared/…/security/AndroidKeystoreEncryptedStore.kt` 及其测试（包括上游 androidTest 中数据库测试的桌面移植）；`app/src/{main,test}/kotlin/…/desktop/app/{settings,crash}/**`、`…/desktop/app/adapters/cndb/**`（`DesktopLabyrinthCnDatabaseRepository`） |
| E | 替身 `shared/…/protocol/bilibili/AndroidBilibiliSdkRequestProfileFactory.kt`、`AndroidGameProtocolProfileFactory.kt`、`shared/…/ui/account/GeetestCaptchaDialog.kt` 及其测试；`app/src/{main,test}/kotlin/…/desktop/app/adapters/{identity,captcha}/**` |
| F | `app/src/{main,test}/kotlin/…/desktop/app/ui/**`；替身 `shared/…/labyrinth/debug/LabyrinthRuntimeDiagnostics.kt` 及其测试；`app/src/{main,test}/kotlin/…/desktop/app/adapters/overlay/**`（`AutomationOverlayHost` 的窗口任务栏和托盘实现）；`app/src/main/resources/{icons,ui}/**`；仓库根下 `docs/desktop/UI_DESIGN.md`、`docs/desktop/design/**`（A 可先准备，M1 后移交 F） |
| G | `app/build.gradle.kts`（打包配置）；`.github/workflows/desktop-ci.yml`（仓库根）；`app/src/{main,test}/kotlin/…/desktop/app/selftest/**`；`dist/**`（`README-desktop.md`、`THIRD_PARTY_NOTICES.md` 桌面部分、`licenses/`、图标源文件） |
| H | `app/src/main/kotlin/…/desktop/app/Main.kt`、`DesktopAppGraph.kt`、`…/desktop/app/tasks/**`（替代 `LabyrinthRerollService`）、`…/desktop/app/adapters/session/**`（`GameClientHardCloser` 等接线）；`app/src/test/kotlin/…/desktop/app/e2e/**` |

- 表中没有列出的路径一律归 A。需要修改别人名下的路径：在报告里提变更请求（§4），不要直接改。
- 模块构建脚本归模块所属 WS，但**依赖坐标只能写在 `gradle/libs.versions.toml`**（A 独占）。新增或升级依赖一律走变更请求，并附许可证（AGENTS §8）。

### 2.3 登记表与共享文档

| 文件 | 规则 |
|---|---|
| `registers/DECISIONS.md`、`PROGRESS.md`、`UPSTREAM_PATCHES.md`、`desktop/CONTRACTS.md` | 只有 A 能改。子代理在报告中提出条目，由 A 写入 |
| `registers/FALLBACKS.md`、`SUBSTITUTES.md`、`THREADING.md`、`UI_PARITY.md`、`ASSEMBLY.md`、`KNOWN_LIMITATIONS.md` | 每个文件按 `## WS-x` 分节。子代理只能在自己的节里增删行；跨节修改走变更请求 |
| `docs/desktop/EDGE_CASES.md` | 子代理只能修改归属本 WS 的行的「证据」列，以及在对应分组末尾追加新行（编号顺延）。归属：A–D 组 → B；E → C；F → D；G → E；H → F（界面行为）/ H（生命周期），有歧义时由 A 指定；I → G；J → A |
| `docs/desktop/specs/**`、`PLAN.md`、`ACCEPTANCE.md`、`AGENTS.md` | 只有 A 能改。发现 SPEC 与上游代码事实冲突时，子代理在报告里写明证据（文件:行），按 AGENTS 开头的规则先以代码事实为准继续工作 |

界面文档归属补充：UI_PARITY §8 简表由 F 维护；§1–7 的现有对照需要调整时交 A 汇总。UI_DESIGN 与 design/ 在 M1 后按 §2.2 移交 F；设计不能修改已冻结的业务契约。

## 3. 依赖、合并顺序与契约

```text
M1(A) ──► G 骨架 ──► B ┐
                   C ├─► F 骨架 ──► H(M3) ──► F 对齐(M4) ──► G 完成(M5) ──► M6 ──► M7
                   D │
                   E ┘
```

- B / C / D / E 只依赖 `CONTRACTS.md` 和 `:shared`，彼此独立，可以同时开工；合并顺序任意，每次合并后 A 在 `desktop/main` 上跑一次 `./gradlew -p desktop check`。
- 主要页面原型可与 M1 并行，供用户查看后按已授权原则实现，不增加 Gate 或逐页确认；独立后端工作继续。
- F 的骨架可以在 B–E 进行时开工：页面先接 `CONTRACTS.md` 中接口的**测试替身**（放在 F 自己的测试路径下），不得在 `src/main` 中写假实现。
- 契约（`CONTRACTS.md`）冻结后，修改必须经 A 批准：A 更新 CONTRACTS.md，在 DECISIONS 记一条，并在受影响 WS 的下一次派发中说明。
- 子代理分支每次开始工作前先 rebase 到最新 `desktop/main`。

## 4. 变更请求（CR）

在报告的「变更请求」一节按以下格式提出，A 逐条批复（同意 / 拒绝 + 理由），同意后由 A 或指定的 WS 实施：

```text
CR-<WS>-<序号>
类型：依赖 / 契约 / 越界路径 / SPEC 与事实冲突 / 新回退
内容：<要改什么，精确到文件和签名>
理由与证据：<文件:行、测试输出、日志>
影响范围：<哪些 WS、哪些 Gate>
不改的后果：<…>
```

## 5. 子代理卡片

每张卡片 = 派发时的任务说明。卡片里没写的细节以对应 SPEC 为准。规模是**非测试代码**的估算行数，用于 AGENTS §3 的 1.5 倍自检。

### WS-B 设备层（SPEC-10，估算 2,500 行）
- 交付：`AdbExecutor`（唯一的子进程出口，超时、kill、stdout/stderr 并发排空）、adb 定位（D-18）、设备发现与连接状态机、`ScreenCapturer`（Raw / PNG、测速）、MaaTouch 客户端（部署、头部解析、单写者 actor、兼容模式）、应用控制、前台监视器（实时查询 + 500 ms 缓存）、uiautomator dump 与 XML 解析、设备信息读取、屏幕状态；`:shared` 中的 `DesktopCaptureService` 桥接（复用 P-01 搬出的上游纯类）；`:app` 中的上游类型适配器；`FakeAdb` 测试夹具。
- 必测：SPEC-10 §11 全部；适配器层动作校验表（SPEC-10 §5 的「公共」行）逐条；`GAME_NOT_FOREGROUND_REASON` 来自上游常量而不是复制字符串。
- 陷阱：不要给每条 adb 命令各写一套重试；重试只有 FALLBACKS 中登记的几条。不要在截图循环里吞异常继续跑；失败计数交给状态机（Lost 判据见 SPEC-10 §3）。

### WS-C OCR（SPEC-20，估算 1,200 行）
- 交付：ONNX Runtime 引擎（字节加载、懒加载 + 预热、单线程队列 64）、DB 检测后处理、CTC 解码、多框拼接、替身 `AndroidChineseRoleNameOcr`、`:app` 适配器、评估集与 `:ocr:evaluate`。
- 必测：SPEC-20 §6 全部；评估阈值作为 `check` 的一部分。
- 陷阱：只允许在本模块后处理中做有评估集证据的归一化，并记进 DECISIONS（经 A）；不准改上游匹配器或阈值。模型文件和字典的 SHA-256 写进 THIRD_PARTY_NOTICES 的请求里交给 G。

### WS-D 持久化、安全与平台（SPEC-30，估算 1,500 行）
- 交付：数据目录解析、实例锁、tmp 清理、损坏处理（FB-07）、`AppDatabase` 替身（Room JVM + BundledSQLiteDriver + 逐字一致的迁移 SQL）、DPAPI `SecretProtector`、`AndroidKeystoreEncryptedStore` 替身、`settings/desktop.json`、崩溃处理器、`DesktopLabyrinthCnDatabaseRepository`、防睡眠专用线程。
- 必测：SPEC-30 §10 全部；DPAPI 用例在 Windows CI 上真实运行。
- 陷阱：`AtomicFiles` 和日志器由 A 在 M1 写在 `:shared`，D 直接使用，不要再写一份；数据目录不可写时直接报错，不要悄悄换目录。

### WS-E 协议画像与人工验证码（SPEC-40，估算 900 行）
- 交付：`DeviceIdentitySnapshot` 的采集与持久化（`:app` 适配器，数据来自 `:device`）、两个画像工厂替身、`GeetestCaptchaDialog` 替身、回环服务 `CaptchaHost`。
- 必测：SPEC-40 §6 全部；画像字段与上游工厂逐字段对照（字段名、顺序、取值来源）。
- 陷阱：不得为“更像真机”而编造上游没有的字段；`android_id` 为空的回落只按 FB-11。

### WS-F 桌面界面（SPEC-50）
- 先给出主要页面原型与简短功能清单，展示整体布局；不要求所有页面、状态组合一次画齐，不新增独立设计验收。
- 骨架（G2-F）：窗口、桌面导航、重设计页面、必要的组件适配、任务栏、托盘、日志与诊断替身；测试数据不进入正式运行路径。
- 对齐（G4）：原有功能真实接线；按功能分组记桌面入口；走几条受影响的典型流程，查看代表页面。已有验证直接引用，不逐字段列证据，不建截图矩阵。
- 可重写桌面呈现，仍共享业务状态和动作，不新增校验、默认值、存储或确认规则。Geetest 协议归 E，涉及其组件时协调改动。
- 原“整屏复用”的代码量估算不再强制；按实际页面简要估算即可，不为代码数量删功能，也不为美化扩大架构。


### WS-G 打包、自检与 CI（SPEC-60，估算 600 行 + 工作流）
- 骨架（G2-G）：`desktop-ci.yml` 三个 job、`createReleaseDistributable` + `packagePortableZip`、`--self-test` 框架（已有的检查项先接上）。
- 完成（G5）：全部 self-test 检查项、中文 + 空格路径验证、THIRD_PARTY_NOTICES 与 licenses、`README-desktop.md`（M7 前由 A 审阅）。
- 陷阱：不要因为 CI 不稳定就加 `continue-on-error` 或放宽阈值；根因写进报告。

### WS-H 装配与端到端（总指挥）
- 交付：`DesktopAppGraph`（逐段对照上游 `LandosolToolboxApplication`，结果写进 `ASSEMBLY.md`）、`Main.kt`（单实例、数据目录、`DesktopBindings.install`、崩溃处理器、窗口启动）、任务宿主（应用作用域协程 + 防睡眠）、`GameClientHardCloser` 接线、`FakeDevice` 与 ACCEPTANCE §3 的全部场景、`THREADING.md` 的断言测试。

## 6. 派发提示词模板（复制后替换尖括号）

```text
你是「黎明界助手桌面版移植」的子代理，负责 WS-<X>（<名称>）。

先读：AGENTS.md（最高优先级）；docs/desktop/PLAN.md；docs/desktop/specs/<SPEC 文件>；
desktop/CONTRACTS.md；docs/desktop/WORKSTREAMS.md 中 WS-<X> 的卡片和 §2 路径归属；
docs/desktop/EDGE_CASES.md 中归属本 WS 的分组；docs/desktop/ACCEPTANCE.md 中的 G2-<X>。
需要时阅读上游源码：android/app/src/main/java/com/landosol/toolbox/<相关目录>。

工作区：git worktree，分支 desktop/ws-<x>，基于最新 desktop/main。
只修改 WORKSTREAMS §2.2 中 WS-<X> 名下的路径。需要改别处、加依赖、改契约，
写成变更请求放进报告，不要自己改；不影响的部分继续做。

本次目标：<卡片中的交付清单，或本轮的子集>
完成标准：G2-<X> 全部满足；./gradlew -p desktop check 在你的分支上通过；
EDGE_CASES 中归属本 WS 的行都填了证据。

严格遵守 AGENTS.md §3：只在规定的边界上 try/catch；不加未登记的重试、回退或配置项；
不写只断言“没有抛异常”的测试；上游业务逻辑一律复用，不复制、不改写。WS-F 可按 SPEC-50 重设计桌面呈现，保留字段、校验、确认和动作语义。

完成后按 WORKSTREAMS.md §7 的模板，把报告写到 docs/desktop/reports/WS-<X>-<轮次>.md 并提交。
遇到 AGENTS.md §9 的情况，停下来在报告里写明，不要猜。
```

## 7. 子代理报告模板（`docs/desktop/reports/WS-<X>-<轮次>.md`）

```markdown
# WS-<X> 报告 第 <n> 轮
- 分支 / 最后 commit：
- 结论：完成 / 部分完成（缺什么）/ 阻塞（原因）

## 交付清单
| 项 | 状态 | 文件 |

## Gate G2-<X>
| 判据 | 结果 | 证据（命令、报告路径） |

## 测试
- 新增测试数量；`./gradlew -p desktop check` 结果
- EDGE_CASES 映射：<ID → 测试名 / 实机项 / N/A 理由>

## 登记（A 负责写入的部分）
- DECISIONS 提议：
- FALLBACKS 新增（触发条件 / 上限 / 用户可见信号 / EDGE 编号）：
- UPSTREAM_PATCHES 需求：

## 变更请求
CR-<X>-1 …

## 与 SPEC 不一致之处（附上游代码证据）

## 自检
- 非测试代码行数 vs 估算：
- try/catch 位置清单（每处说明属于哪类边界）：
- 是否修改了归属路径以外的文件：否 / 是（列出并说明）

## 需要实机验证的项（建议的 L1/L2/L3 编号）
```

## 8. 总指挥合并前检查

1. `git diff --name-only desktop/main...desktop/ws-<x>` 全部落在该 WS 名下的路径（§2.2、§2.3）。
2. `./gradlew -p desktop check` 在合并后的结果上通过；测试前后 `android/` 快照一致。
3. 逐个查看新增的 `catch`、`runCatching`、`?: return`、`orEmpty()`、`getOrNull()`、`retry`、`delay` 调用，确认每一处属于 AGENTS §3 允许的边界，或者已登记到 FALLBACKS。可以先用 `rg -n "catch|runCatching|getOrNull|orEmpty\(|retry|delay\(" <变更文件>` 列出。
4. 没有复制上游业务逻辑（新的桌面布局和呈现代码不属于复制业务）：`rg` 查找上游常量和字符串字面量是否在桌面代码中出现了副本（应该引用上游，而不是复制）。
5. 报告中的变更请求都已批复；登记表已更新；PROGRESS 记下合并结果。
