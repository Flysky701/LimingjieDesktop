# ACCEPTANCE.md —— 验收标准（G1–G6、实机清单、最终报告模板）

## 0. 规则

- 每个条目都要有**证据**：命令 + 退出码，或报告文件路径（`build/reports/…`、`docs/desktop/evidence/<gate>/…`）。没有证据的条目一律不算通过。
- 状态只有四种：`通过`、`失败`、`未验证（原因）`、`不适用（理由）`。不允许写“基本通过”“应该可以”。
- 执行环境做不到的条目标成 `未验证`，同时写进最终报告的「需要用户验证」一节和 `docs/desktop/USER_CHECKLIST.md`（§6.5）。不要用模拟手段代替实机，然后标成通过。
- Gate 结果写进 `registers/PROGRESS.md`，格式：`G<n> | 日期 | commit | 通过/失败 | 证据路径 | 未验证项数`。
- Gate 失败时先修，不能跳过。确实修不了的，登记到 `KNOWN_LIMITATIONS.md`（影响、规避方法、计划）。只有总指挥能决定“带着限制通过”，并且要在最终报告里写明。

## 0.1 本次界面修订的验证原则

不新增独立 Gate。主要页面原型用于展示形态，简短功能清单用于防止遗漏；在原有 G4 中用几条典型操作确认真实功能。已有测试或一次操作记录能覆盖的直接引用，不为表格重复验证，不要求逐字段证明、全页面截图或另建测试框架。

## 1. G1 —— 共享源码可编译（M1）

以 SPEC-00 §13 为准，另加：

| # | 检查 | 证据 |
|---|---|---|
| G1-1 | `desktop/gradlew -p desktop :shared:compileKotlin :shared:compileTestKotlin` 通过，没有新增 `@Suppress` 来压制上游代码的错误 | 构建日志 |
| G1-2 | `checkShimCollisions`、`checkSubstituteSignatures`、`checkUpstreamDrift` 通过；派生改写命中次数等于期望值 | 任务输出 |
| G1-3 | `:shared:test` 中上游 JVM 单测全部通过；排除的测试逐条登记在 `upstream-test-excludes.txt`，且理由都是“测试对象本身被排除” | `build/reports/tests/` + 清单 |
| G1-4 | 测试前后 `android/` 快照一致（SPEC-00 §11）；金标文件在测试前存在 | 任务输出 |
| G1-5 | Room：identityHash 与 `android/app/schemas/…/4.json` 一致；上游 androidTest 中数据库相关测试的桌面移植版通过 | 测试报告 |
| G1-6 | 每个垫片都有单测，覆盖 SPEC-00 §6.2 列出的语义要点（例如 Bitmap 的 `recycle`、`createBitmap` 全尺寸返回同一实例、Base64 flags、prefs 的 `commit` 返回值） | 测试报告 |
| G1-7 | `desktop/CONTRACTS.md` 已冻结：`DesktopBindings`、`:device` 公共 API、`OcrEngine`、存储接口、`DeviceIdentitySnapshot` 的签名和 KDoc | 文件 + commit |
| G1-8 | `UPSTREAM_PATCHES.md` 中每个 P-xx 都写明：Android 端编译和单测结果（本地或 CI 的 `android-verify` job） | 登记表 |
| G1-9 | `registers/` 全部建立；`SUBSTITUTES.md` 中每个替身桩都标明归属 WS，未实现的方法统一为 `TODO("WS-x")` | 文件 |

## 2. G2-x —— 各工作流完成（M2）

详细交付物见 `WORKSTREAMS.md`。共同要求：本 WS 的单测全部通过；EDGE_CASES 中归属本 WS 的条目都映射到测试名、实机清单项或 `N/A + 理由`；没有修改所属路径之外的文件；报告按 WORKSTREAMS §7 模板提交。

| Gate | 关键判据 |
|---|---|
| G2-B 设备 | SPEC-10 §11 的必测项全部存在且通过；`FakeAdb` 覆盖 Android 9 / 12 两套 fixture；≥ 64 MB 输出不死锁；MaaTouch 手势逐字节比对；所有错误文案和 SPEC 一致 |
| G2-C OCR | SPEC-20 §6 单测通过；`:ocr:evaluate` 实体解析准确率 ≥ 95%，且每个 `kind` ≥ 85%；评估集 ≥ 80 条（不足时在 PROGRESS 中写明缺口和补齐计划） |
| G2-D 持久化与安全 | SPEC-30 §10 全部通过；DPAPI 测试在 Windows CI 上实际运行（不是 skipped） |
| G2-E 协议与验证码 | SPEC-40 §6 全部通过；画像字段对照表与上游工厂逐字段一致 |
| G2-F UI 骨架 | 主要页面原型可查看；桌面导航、页面骨架、任务栏和托盘能显示与切换；保留原功能入口，正式运行路径不使用假实现 |
| G2-G 打包与 CI 骨架 | `desktop-ci.yml` 三个 job 在当前分支上跑通（此时允许 self-test 只包含已实现的检查）；`packagePortableZip` 产出 zip |

## 3. G3 —— 装配与假设备端到端（M3）

WS-H 用 `FakeDevice`（基于 `FakeAdb` + 按脚本喂帧的截图器 + 记录动作的触控端）驱动**真实的 `DesktopAppGraph`**。除设备 I/O 和网络之外不替换任何组件。

| # | 场景 | 通过判据 |
|---|---|---|
| G3-1 | 连接：发现 → Verifying → 测速 → Ready | 状态序列与 SPEC-10 §3 一致；上游 `CaptureStateRegistry` 进入运行状态；帧经 `CaptureFrameBus` 到达上游订阅者 |
| G3-2 | 黎明界画面自动化回放（dryRun = false，假设备） | 用上游 `src/test/resources` 的截图按脚本喂帧，派发的动作序列（类型、坐标、时长）与脚本中的期望序列逐项相等。期望序列来源要写明：上游测试断言或上游源码中的坐标常量（文件:行） |
| G3-3 | 同一回放，dryRun = true | 不派发任何触控动作；识别结果和覆盖框照常产生 |
| G3-4 | 暂停 / 继续 / 停止 | 暂停期间零动作；继续后从上游的继续点恢复；停止后截图线程、协程、触控 actor 全部结束（用线程和协程计数断言） |
| G3-5 | 前台不是游戏 | 动作返回 `Rejected(GAME_NOT_FOREGROUND_REASON)`；任务按上游路径停止；UI 文案三要素齐全（AGENTS §4） |
| G3-6 | 运行中断线：连续 5 次截图失败 | 任务停止且不续跑；连接进入 Reconnecting；恢复后 Ready，任务仍为已停止 |
| G3-7 | 刷开局流程（协议层使用 MockWebServer 或上游测试已有的假网关） | 上游 `LabyrinthRerollWorkflow` 在桌面装配下跑完一轮：检查点写入 prefs 垫片，重启装配后能读回；“刷到出”判定逻辑与上游测试期望一致 |
| G3-8 | 应用退出 | 回环服务、诊断面板端口、数据库连接、MaaTouch 进程（`FakeAdb` 记录到 kill 命令）、防睡眠状态全部释放；退出耗时 ≤ 5 s |
| G3-9 | 日志包 | 文件清单与上游一致（`logs/logcat.txt` 内容来自桌面日志）；对包内全部文本扫描 fixture 中的假密钥、假 token、假账号，必须零命中 |
| G3-10 | 装配对照 | `registers/ASSEMBLY.md`：上游 `LandosolToolboxApplication` 的每个字段或初始化步骤都对应到 `DesktopAppGraph` 的一项，或写明 `不适用 + 理由`；覆盖率 100% |
| G3-11 | 线程 | `registers/THREADING.md` 每一行都有对应的断言测试（例如 OCR 回调在 Main 上执行、截图在专用线程上执行） |

## 4. G4 —— 桌面功能与界面检查（M4）

以“功能完整、典型流程可用、主要布局正常”为准，复用已有证据，不重复跑已覆盖的检查。

| # | 检查 | 简要记录 |
|---|---|---|
| G4-1 | 按 UI_PARITY 功能清单确认桌面入口可达，原功能和已有确认未遗漏；页面调用原业务状态与动作 | 清单备注，已有测试结果可共用 |
| G4-2 | 走通选择 / 编辑账号、修改配置并保存或取消、任务启动与支持的控制、未连接等异常提示；只验证本次影响的流程 | 一次操作记录或已有相关测试，注明离线 / 实机 |
| G4-3 | 主任务页和一个复杂表单在默认与最小窗口下可读，主要操作可达；字体、配色和控件风格统一，鼠标、滚轮和焦点正常 | 少量代表截图与简短结论 |
| G4-4 | 高 DPI 在已有测试或实机条件下抽查；无法验证时注明，不为界面改版单独搭建环境或跑全组合 | 可共用 L1 截图；没有条件则记未验证 |

原型不能替代真实接线验证，假设备不能证明游戏流程已实机通过。真实登录与游戏仍按 G6 的实际条件记录；不为界面美化扩大实机测试范围。

## 5. G5 —— 打包与 CI（M5）

以 SPEC-60 §6 为准，另加：

| # | 检查 | 证据 |
|---|---|---|
| G5-1 | `--self-test` 在 Windows CI 上全部 `passed`（`tls` 在无网络时允许 `skipped`，但必须写明原因） | 报告 JSON |
| G5-2 | 解压到 `测试 目录`（中文 + 空格）后 self-test 通过 | CI 日志 |
| G5-3 | zip 内没有开发期遗留物：`*.pdb`、测试资源、`build/` 临时文件、真实数据目录 | 文件清单 diff |
| G5-4 | `THIRD_PARTY_NOTICES` 覆盖每个运行时依赖（JRE、Compose、Room/SQLite、ONNX Runtime、PP-OCR 模型、MaaTouch、JNA、OkHttp 等），包含许可证、来源、版本和 SHA-256 | 文件 |
| G5-5 | 版本号显示为 `<上游 versionName>+desktop.<n>`，与 zip 文件名、关于页、日志包一致 | 截图 + 文件名 |

## 6. G6 —— 实机验收（M6）

### 6.1 能力级别（M0 时确定，写进 PROGRESS）

| 级别 | 条件 | 本节要做的 |
|---|---|---|
| L0 | 没有模拟器（例如云端 Linux 容器） | 完成 G1–G5；产出 `USER_CHECKLIST.md`；G6 全部为 `未验证` |
| L1 | Windows + 模拟器，没有游戏 | §6.2 |
| L2 | 模拟器里装了游戏，可以 dryRun | §6.2 + §6.3 |
| L3 | 用户在场，可以协助 | §6.4（每项都需要用户明确同意） |

### 6.2 L1：无游戏（设备层的真实性验证）

| # | 项目 | 判据 / 记录 |
|---|---|---|
| L1-01 | MuMu 12、雷电 9 各自被自动发现并连接到 Ready；「自定义 ADB 地址」手动连接成功 | 截图 + 日志 |
| L1-02 | Raw / PNG 各 5 帧的测速结果 | 记录 p50 / p95；p95 > 600 ms 时环境检查出现警告 |
| L1-03 | 连续截图 10 分钟 | 帧间隔 p50 ≈ 300 ms；无内存持续增长（堆使用量在 GC 后的斜率 ≈ 0） |
| L1-04 | 用真实 fixture 替换合成 fixture | `SOURCES.md` 中的「合成」条目全部替换为实采（Android 9 和 12 至少各一套） |
| L1-05 | MaaTouch 在系统「设置」应用上测试：Tap 打开某个子页；Swipe 滚动列表；hold > 0 的 Swipe；Back 返回 | 每个动作执行前后用 `uiautomator dump` 核对效果；坐标就是节点 bounds 的中心 |
| L1-06 | 旋转：在横屏和竖屏下各做一次 Tap | 横屏正确；竖屏时按 SPEC-10 §5 的尺寸校验规则拒绝或执行，与规格一致 |
| L1-07 | 兼容模式：Tap 和 hold = 0 的 Swipe 正常；hold > 0 被拒绝，文案正确 | 截图 |
| L1-08 | 应用控制：以「设置」为对象测试 `resolve-activity` → `am start` → `force-stop`（`pidof` 变为空） | 日志 |
| L1-09 | 前台检测：实时查询的 p50 / p95 写入 PROGRESS（p95 > 150 ms 时按 SPEC-10 §7 处理）；切换应用后缓存在 ≤ 1 s 内更新 | 数据 |
| L1-10 | uiautomator：在「设置」页 dump 成功；连续 dump 20 次时出现 `could not get idle state` 的次数，以及重试是否成功 | 数据 |
| L1-11 | 断线：关闭模拟器 → Lost → 12 次重连失败 → Failed，文案正确；重新打开模拟器后手动重连成功 | 截图 + 日志 |
| L1-12 | adb 冲突：PATH 中有另一个版本的 adb 时，按 D-18 选择 adb，不出现反复杀 server 的循环（R-12） | 日志 |
| L1-13 | 模拟器窗口最小化、Windows 锁屏两种情况下的截图表现 | 记录是否黑帧；黑帧持续 10 s 时 UI 警告出现 |
| L1-14 | Windows 防火墙：启动回环服务和诊断面板时不弹窗（因为只绑定 127.0.0.1） | 截图或说明 |
| L1-15 | 防睡眠：任务运行时 `powercfg /requests` 中能看到本进程；任务结束后消失 | 命令输出 |
| L1-16 | 单实例、托盘、关闭窗口的三种分支、中文用户名或中文路径下的数据目录 | 截图 |
| L1-17 | 系统缩放 125% / 150%（对应 G4-4） | 截图 |

### 6.3 L2：游戏 dryRun（只截图识别，不派发点击）

| # | 项目 | 判据 / 记录 |
|---|---|---|
| L2-01 | 通过助手启动游戏；前台检测识别出游戏包；版本和签名 MD5 与上游 `GameClientResolver` 的期望一致 | 日志 |
| L2-02 | 在黎明界的各类画面（入口、地图节点、编组、战斗、遗物、商店、事件、结算）停留，dryRun 会话的识别结果与人工判断一致 | 每类画面截图 + 识别日志 |
| L2-03 | 预览中的覆盖框（`AutomationOverlayPresentation.boxes`）位置与画面内容吻合 | 截图 |
| L2-04 | 通过诊断面板的逐帧存档采集 OCR 样本，补充评估集，使每个 `kind` ≥ 8 条实采样本；重跑 `:ocr:evaluate`，仍需满足 G2-C 的阈值 | 评估报告 |
| L2-05 | 1 小时 dryRun soak：无崩溃；堆内存在 GC 后无持续增长；线程数稳定；帧间隔 p95 稳定。8 小时 soak 视环境条件选做 | 监控数据（JFR 或定时采样） |
| L2-06 | 国服数据库更新：真实下载、校验、安装；数据库被占用时进入暂存，重启后安装（FB-08） | 日志 |
| L2-07 | 诊断面板在 `127.0.0.1:8765` 可以打开，实时数据刷新；日志包生成并通过脱敏扫描 | 截图 + 扫描结果 |
| L2-08 | 游戏内「播放角色加入动画」开启时的表现与上游一致（上游 README 要求关闭） | 记录 |

### 6.4 L3：需要用户协助（每项都要先得到用户同意；真实操作要求 `LIVE_GAME_ACTIONS_ALLOWED=1`）

| # | 项目 | 判据 |
|---|---|---|
| L3-01 | B 服账号密码登录，并在系统浏览器中完成 Geetest | 登录成功。如果 Geetest 拒绝 `127.0.0.1` 来源，按 R-02 启用 KCEF 备选方案，并在 DECISIONS 登记 |
| L3-02 | 渠道服（雷电 9 + 小米 / 华为 / vivo 中至少一个）uid / access_key 登录 | 登录成功 |
| L3-03 | 渠道 SDK 页面：uiautomator 读取节点并点击（`ChannelPromptScreen`） | 行为与上游无障碍实现一致 |
| L3-04 | 硬关闭（`am force-stop`）之后，渠道 SDK 的登录状态没有被清掉 | 重新启动游戏后不需要重新登录渠道账号；如果被清掉，登记限制并评估改用 `pressHome` + `am kill` |
| L3-05 | 完整刷开局一次（非 dryRun） | 与 Android 版在同一账号上的行为一致：“刷到出”命中、首轮复用、检查点 |
| L3-06 | 完整画面自动化一轮（非 dryRun） | 从入口到结算全程无人工干预；遇到异常时按上游路径停止 |
| L3-07 | 竖屏 1080×1920 的模拟器配置 | 与上游的“统一横屏捕获”行为一致 |
| L3-08 | 干净的 Windows（没有装过 Java）双击启动（对应 SPEC-60 G5-4） | 主窗口出现 |
| L3-09 | Windows Defender / SmartScreen 的提示 | 记录实际文案；把用户需要的操作写进 README-desktop |

### 6.5 `USER_CHECKLIST.md`

凡是 `未验证` 的 L1–L3 条目，都要生成到 `docs/desktop/USER_CHECKLIST.md`，并随发行包附带一份 `用户验收清单.md`。每一项包含：前置条件、可直接复制的操作步骤（包括 PowerShell 命令）、预期结果、出现问题时要附上的材料（日志包路径、截图）。语言面向普通用户，不出现内部编号以外的术语。

## 7. 最终报告模板（`docs/desktop/FINAL_REPORT.md`）

```markdown
# 黎明界助手桌面版 —— 最终报告

## 1. 结论
- 版本：<upstream versionName>+desktop.<n>（上游 commit <sha>）
- 发行包：<路径 / CI artifact>，SHA-256 <…>
- Gate：G1 <结果> G2 <结果> G3 <结果> G4 <结果> G5 <结果> G6 <通过 / 部分（L?）/ 未验证>
- 一句话：<用户现在能做什么；还需要用户验证什么>

## 2. 功能对齐
| 上游功能 | 桌面入口 / 呈现变化 | 桌面状态 | 证据 |
（取自 UI_PARITY.md 汇总；会战助手：上游未接入 UI，桌面同样不接入）

## 3. 验证情况
### 3.1 自动化（测试数量、上游测试通过数 / 排除数、OCR 准确率、self-test）
### 3.2 实机（能力级别；L1/L2/L3 每项的结果和数据）
### 3.3 需要用户验证（链接到 USER_CHECKLIST.md）
### 3.4 界面修订（主要页面原型、少量代表截图、功能简表与典型操作结论；可引用已有记录，无需重复报告）

## 4. 与上游的差异
- 上游补丁 P-xx 列表（全部为纯搬移或注入缝）
- 派生改写列表
- 行为差异（每条附 DECISIONS 编号）

## 5. 已知限制（KNOWN_LIMITATIONS.md 摘要）

## 6. 回退路径的使用情况（FALLBACKS.md：每条是否在测试 / 实机中被触发）

## 7. 上游同步
- 如何同步（UPSTREAM_SYNC.md）
- 最可能出问题的地方

## 8. 统计
- 新增代码行数（按模块）、与 SPEC 估算的对比（超过 1.5 倍的要解释）
- 测试数量、CI 运行时间
```
