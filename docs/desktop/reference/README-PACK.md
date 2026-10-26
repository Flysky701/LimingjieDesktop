# 原桌面移植执行包索引

原计划修订版 v2：2026-10-25；本索引核对日期：2026-10-26。

这里保存桌面移植初期的计划和执行材料。当前仓库已包含 Kotlin / Compose Desktop 实现，源码版本为 **1.0.18+desktop.7**，不再是「尚无可运行程序」的空执行包。当前入口见[项目首页](../../../README.md)、[桌面用户说明](../../../README-desktop.md)、[构建说明](../../../desktop/README.md)与[D7 交付报告](../reports/SHARED-RUN-TABS-D7.md)。

原始移植基线为上游 1.0.18（`b809b196988b019571caf5679ccf8d7ebb7f072d`）。主技术路线仍为共享 Kotlin 业务代码与桌面适配；原计划中的单设备结构已由 D7 共享数据、每设备独立运行页补充。

## 阅读方式

- 以仓库根目录 [AGENTS.md](../../../AGENTS.md) 和用户本轮指示确定执行范围。
- 本目录的 [EXECUTION-PACK-AGENTS.md](EXECUTION-PACK-AGENTS.md) 是原执行包规则副本，不覆盖当前规则；旧文中的章节号、派发安排和停机条件需结合当前约定阅读。
- [KICKOFF.md](../KICKOFF.md)、[WORKSTREAMS.md](../WORKSTREAMS.md) 是历史启动与协作方案。阅读或修改文档不自动启动后续开发、子代理、模拟器或游戏操作。
- 原环境变量或示例命令不构成本轮真实游戏操作授权。真实账号、验证码与双模拟器游戏循环仍需按实际验收范围记录。

## 设计与实现资料

以下链接均指向本仓库现有文件，不需要再复制执行包或覆盖根规则。

| 入口 | 内容与用途 |
| --- | --- |
| [修订说明](../REVISION-NOTES.md)、[总体计划](../PLAN.md) | 原移植依据、架构、里程碑和范围 |
| [SPEC-00](../specs/SPEC-00-shared-source.md) | 共享上游源码、派生、替身、测试与漂移检查 |
| [SPEC-10](../specs/SPEC-10-device.md) | ADB、截图、MaaTouch、前台与节点树 |
| [SPEC-20](../specs/SPEC-20-ocr.md) | OCR 接口、真实样本和质量门槛 |
| [SPEC-30](../specs/SPEC-30-persistence-security.md) | 数据目录、Room、DPAPI、日志与防睡眠 |
| [SPEC-40](../specs/SPEC-40-protocol-captcha.md) | 协议设备画像与人工验证码 |
| [SPEC-50](../specs/SPEC-50-ui.md) | 桌面交互与页面设计 |
| [SPEC-60](../specs/SPEC-60-packaging-ci.md) | 便携包、自检与 CI |
| [接口契约](../../../desktop/CONTRACTS.md)、[UI 设计](../UI_DESIGN.md)、[页面原型](../design/main-pages.html) | 已保存的接口与设计参考，原型使用示例数据 |
| [边界登记](../EDGE_CASES.md)、[原验收方案](../ACCEPTANCE.md) | 历史验收口径与设备场景，不要求每次局部修改全部重跑 |

## 维护与验证资料

| 入口 | 用途 |
| --- | --- |
| [决策](../registers/DECISIONS.md)、[回退](../registers/FALLBACKS.md) | 实现取舍与回退边界 |
| [上游补丁](../registers/UPSTREAM_PATCHES.md)、[上游同步](../registers/UPSTREAM_SYNC.md) | 业务复用与必要修改记录 |
| [替身](../registers/SUBSTITUTES.md)、[装配](../registers/ASSEMBLY.md)、[线程](../registers/THREADING.md) | 桌面适配技术记录 |
| [功能对照](../registers/UI_PARITY.md)、[实施进度](../registers/PROGRESS.md) | 早期接线与阶段结果 |
| [已知限制](../registers/KNOWN_LIMITATIONS.md)、[历次报告](../FINAL_REPORT.md) | 历史限制及证据；其中 D6 独立数据、单设备和旧路径描述以 D7 报告补充 |
| [D7 报告](../reports/SHARED-RUN-TABS-D7.md) | 当前共享数据与运行标签、最终定向检查及候选包记录 |
| [用户实机清单](../USER_CHECKLIST.md) | 历史设备与游戏检查步骤；D6 绑定流程以当前桌面用户说明为准 |

当前仍有 OCR 14/15（93.33%）、覆盖不足、标题页误判和真实游戏闭环未验收等限制。执行包中的目标、计划或示例命令不表示这些项目已经通过。
