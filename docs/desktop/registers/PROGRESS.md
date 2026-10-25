# PROGRESS.md —— 进度与测量（只有总指挥可以修改）

## 当前实施进展（2026-10-25，以本节覆盖下方准备期模板）

用户已授权完整开发和子代理协作。工作目录为 `D:\Project\LimingjieDesktop`；上游完整历史已接入，分支 desktop/main，基线 b809b196988b019571caf5679ccf8d7ebb7f072d。

- M0 完成：Windows x64（10.0.26300）；项目内 Temurin JDK 21.0.12.1+1、Gradle 8.12.1 已下载并校验；GitHub 和三个 Maven/插件仓库通过实际下载验证。未发现运行中模拟器、可用 ADB 或 Android SDK，实机级别 L0。未启用真实登录或游戏操作。
- 基线：189 个主文件、56,587 行；Android 耦合 63 个（UI 10 个）；163 个测试文件、34,502 行；资源 39,464,426 字节。去掉尾部空行的口径与方案相差 3/2 行，版本没有变化。版本 1.0.18/10018，schema v4，截图间隔 300ms。证据：`docs/desktop/evidence/m0/baseline.json`。
- M1 离线验证完成：共享主源码/测试编译、Room spike、兼容层及上游测试、签名/冲突/漂移检查通过；Android 构建待 SDK/CI。
- 验证命令：`desktop/gradlew.bat -p desktop :shared:test :shared:checkShimCollisions :shared:checkUpstreamDrift`，退出码 0。1,252 项测试中 1,206 通过、46 因上游缺少本地素材而跳过、0 失败；含 8 项新增兼容层/数据库测试。测试前后 android/ 状态与修改文件内容一致。
- Room v1–v4 迁移、事务提交/回滚、Flow 查询、外键级联通过；identityHash 与上游相同。25 个框架兼容类无运行时 JAR 同名冲突。
- M2 进行中：端口已冻结，开始设备、OCR、平台/协议实现；M3–M6 未完成，当前没有可交付 EXE/zip，不将原型或共享测试当成完整功能。
- 需求/资源审阅与装配审阅已由子代理只读完成。OCR 原始截图覆盖缺口正在核对，不能虚构准确率；主界面沿用浅色紫色原型，未把未回复记作视觉确认。
- 报告：`desktop/shared/build/reports/tests/test/index.html`，摘要将保存到 `docs/desktop/evidence/m1/`。

## 1. M0 环境探测

| 项 | 结果 | 命令 / 证据 |
|---|---|---|
| 操作系统、架构、是否 Windows | | `uname -a` / `systeminfo` |
| JDK（版本、发行商）；是否可安装 JDK 21 | | `java -version` |
| Maven Central / Google Maven / Gradle Plugin Portal 可达 | | `curl -I …` |
| GitHub（MaaTouch Release、上游数据库下载地址）可达 | | |
| Android SDK（`ANDROID_HOME`）；能否跑 `android/` 的构建 | | |
| adb 是否可用；`adb devices -l` 输出 | | |
| 模拟器类型与版本、分辨率、Android 版本 | | `getprop ro.build.version.release` 等 |
| 游戏是否安装（包名、版本） | | `pm list packages` |
| 是否可以使用子代理 / 多会话 | | |
| `LIVE_GAME_ACTIONS_ALLOWED` | | |

**实机能力级别**（ACCEPTANCE §6.1）：L? —— 理由：

## 2. 基线重测（对照 PLAN.md §2）

| 项 | 方案值（b809b196） | 实测值（commit ……） | 偏差处理 |
|---|---|---|---|
| 主代码 .kt 文件数 / 行数 | 189 / 56,590 | | |
| 不含 Android import 的文件数 | 126 | | |
| 与 Android 耦合的文件数（其中 `ui/`） | 63（10） | | |
| 测试文件数 / 行数 | 163 / 34,504 | | |
| 上游 versionName / versionCode | 1.0.18 / | | |
| assets 体积 | 约 40 MB | | |
| Room schema 版本 | 4 | | |
| `FRAME_INTERVAL_MILLIS` | 300 | | |
| 上游依赖版本（Kotlin、Compose BOM、Room、coroutines、serialization、OkHttp） | 1.9.24 / 2024.06 / 2.6.1 / 1.8.1 / 1.6.3 / 4.12.0 | | |

## 3. 里程碑

| 里程碑 | 状态（未开始 / 进行中 / 完成） | 开始 | 完成 | 备注 |
|---|---|---|---|---|
| M0 | | | | |
| M1 | | | | |
| M2 | | | | |
| M3 | | | | |
| M4 | | | | |
| M5 | | | | |
| M6 | | | | |
| M7 | | | | |

## 4. Gate 记录

| Gate | 日期 | commit | 结果 | 证据路径 | 未验证项数 |
|---|---|---|---|---|---|
| | | | | | |

## 5. 子代理派发

| WS | 轮次 | 分支 | 派发日期 | 状态 | 报告 | 合并 commit |
|---|---|---|---|---|---|---|
| | | | | | | |

## 6. 测量数据

| 指标 | 环境 | p50 | p95 | 其他 | 日期 |
|---|---|---|---|---|---|
| 截图 Raw 单帧耗时 | | | | | |
| 截图 PNG 单帧耗时 | | | | | |
| 帧间隔（10 分钟） | | | | | |
| 前台实时查询耗时 | | | | | |
| OCR 单 ROI 端到端耗时 | | | | | |
| OCR 实体解析准确率（总体 / 各 kind） | | | | | |
| 应用冷启动到主窗口 | | | | | |
| 退出耗时 | | | | | |
| 1 h soak：堆（GC 后）、线程数、RSS | | | | | |

## 7. 阻塞与待用户回答的问题（AGENTS §9）

| # | 日期 | 问题 | 推荐选项与影响 | 状态 |
|---|---|---|---|---|
| | | | | |

## 8. 代码规模自检（AGENTS §3）

| 模块 | SPEC / 卡片估算 | 实际非测试行数 | 比值 | 说明 |
|---|---|---|---|---|
| | | | | |

## 9. 界面修订简记

实施时只记录：主要页面原型位置、功能清单是否有遗漏、典型操作结果、必要的未验证项。已有记录可链接引用，不重复统计字段数量或逐项收集证据。

2026-10-25：按 D-37 修订计划，并按用户要求简化界面验收。本次仅修改文档，原型、实现与实机验证尚未开展。

## 2026-10-25 · 正式项目准备

- 用户选择新目录 `D:\WorkSpace\LimingjieDesktop`，保留旧项目 `D:\WorkSpace\LimingjiePC`。
- 新版计划、第一版主要页面原型、项目说明已整理为项目准备包。
- 原型已提供用户查看；未收到最终视觉确认。状态和验证范围见 `UI_DESIGN.md`。
- 当前对话不具备 D 盘目录写权限；此记录不表示已在目标目录落地或注册为应用项目。
- 尚未接入上游源码或构建桌面应用；不将上述整理工作标为 M0 / Gate 通过。
