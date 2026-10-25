# 黎明界助手 Windows 桌面版移植 · 执行包

本执行包供 GPT 类编码 Agent（Codex CLI / Codex Cloud 等）在 `wbero/limingjie-assistant` 仓库中执行。

**计划修订版 v2 · 2026-10-25**。核心原则：**业务能力和行为保持一致，界面按桌面使用方式重新设计。** 用户在 Windows 内完成账号、配置、启动、监控和停止，模拟器只运行游戏，无需安卓版助手。允许调整、美化功能页面，不再强制原样嵌入安卓页面。业务与底层技术路线保留，先给主要页面原型和简短功能清单，不新增验收关卡，详见 REVISION-NOTES。

本包仍为开发文档，未含可运行程序，也未把任何实施或实机验证标为完成。

## 使用方法（给人看）

1. Fork 或 clone 上游仓库，基于 `main` 新建分支 `desktop/main`。
2. 把本包内容按目录原样复制到仓库根目录：
   - `AGENTS.md` 放在仓库根目录（Codex 会自动读取）
   - `docs/desktop/` 放在仓库 `docs/` 下
3. 在仓库根目录启动 Agent，把 `docs/desktop/KICKOFF.md` 的全文作为第一条消息发给它。
4. 想让它在你的 Windows 电脑上做实机验证：先打开 MuMu 12 或雷电 9（1920×1080 横屏），确保 `adb devices` 能看到设备。
   默认只允许对游戏做 dryRun（只截图识别，不点击）；允许真实操作时，设置环境变量 `LIVE_GAME_ACTIONS_ALLOWED=1`。
5. Agent 运行在云端或没有模拟器的环境时，会准备主要页面原型并完成离线部分（G1–G5：编译、测试、打包、Windows CI 自检），并把实机验证项整理成 `docs/desktop/USER_CHECKLIST.md` 交给你；有模拟器时会自己做 L1/L2。
6. 工具支持子代理或多会话时，总指挥会在 M1 之后按 `WORKSTREAMS.md` 并行派发；不支持时按同样的卡片顺序执行，验收标准不变。
7. 需要你介入的情况只有 `AGENTS.md §9` 列出的 5 类；其余由 Agent 自主决策并记录到 `docs/desktop/registers/DECISIONS.md`。

## 包内文件

| 文件 | 作用 |
|---|---|
| `REVISION-NOTES.md` | 修订依据、产品预期、变更清单与实施边界 |
| `AGENTS.md` | 全体会话规则：范围纪律、上游修改规则、反防御性编程、安全、停机条件（优先级最高） |
| `docs/desktop/KICKOFF.md` | 总指挥启动提示词（第一条消息） |
| `docs/desktop/PLAN.md` | 总体方案：事实基线、架构、关键决策 D-01…D-27、Android→桌面映射、里程碑 M0–M7、风险 |
| `docs/desktop/specs/SPEC-00-shared-source.md` | 共享上游源码：Sync 任务、分类、垫片、同名替身、上游补丁、测试接入、漂移检查、G1 |
| `docs/desktop/specs/SPEC-10-device.md` | 设备层：adb、连接状态机、截图、MaaTouch 触控、应用控制、前台检测、uiautomator |
| `docs/desktop/specs/SPEC-20-ocr.md` | OCR：ONNX Runtime + PP-OCR，上游契约、评估集与阈值 |
| `docs/desktop/specs/SPEC-30-persistence-security.md` | 数据目录、原子写、Room、DPAPI、日志、国服数据库、防睡眠 |
| `docs/desktop/specs/SPEC-40-protocol-captcha.md` | 协议设备画像、网关、Geetest 回环 + 系统浏览器 |
| `docs/desktop/specs/SPEC-50-ui.md` | MAA 式桌面交互、页面重设计与美化、原型、真实接线、设备与环境、日志和任务控制 |
| `docs/desktop/specs/SPEC-60-packaging-ci.md` | 便携 zip、`--self-test`、Windows CI |
| `docs/desktop/EDGE_CASES.md` | 边界情况登记表（A–J 组，每条都要映射到测试、实机项或 N/A） |
| `docs/desktop/ACCEPTANCE.md` | G1–G6；界面采用简短功能清单、典型操作和少量截图；L1–L3 与最终报告 |
| `docs/desktop/WORKSTREAMS.md` | 工作流 A–H、路径归属、变更请求、子代理卡片、派发提示词与报告模板、合并检查 |
| `docs/desktop/registers/DECISIONS.md` | D-01…D-36 原方案，D-37 用户修订；执行期从 D-38 起 |
| `docs/desktop/registers/FALLBACKS.md` | 回退与重试登记（FB-01…FB-13，未登记的重试一律视为违规） |
| `docs/desktop/registers/UPSTREAM_PATCHES.md` | 对 `android/` 的修改登记（P-01…P-07 计划） |
| `docs/desktop/registers/SUBSTITUTES.md` | 同名替身、桌面适配器、框架垫片、存储映射 |
| `docs/desktop/registers/ASSEMBLY.md` | 上游装配根 → `DesktopAppGraph` 逐段对照 |
| `docs/desktop/registers/UI_PARITY.md` | 原有外壳与路由参考，以及按功能分组的桌面入口简表 |
| `docs/desktop/registers/THREADING.md` | 线程模型对照（每行一个断言测试） |
| `docs/desktop/registers/PROGRESS.md` | 环境探测、基线重测、里程碑、Gate 记录、测量数据、阻塞问题 |
| `docs/desktop/registers/KNOWN_LIMITATIONS.md` | 已知限制与 P2 清单 |
| `docs/desktop/registers/UPSTREAM_SYNC.md` | 跟进上游更新的流程 |

执行过程中 Agent 还会生成：`docs/desktop/UI_DESIGN.md` 与 `docs/desktop/design/`（简短设计说明、主要页面原型与少量截图）、`desktop/CONTRACTS.md`（M1 冻结的接口契约）、`docs/desktop/reports/`（子代理报告）、`docs/desktop/evidence/`（Gate 证据）、`docs/desktop/USER_CHECKLIST.md`（需要你在实机上验证的项目）、`docs/desktop/FINAL_REPORT.md`（最终报告）。

基线：上游 commit `b809b196`（2026-10-22，版本 1.0.18）。若执行时上游已更新，Agent 会在 M0 重新测量并更新 PLAN.md §2 的数字。
