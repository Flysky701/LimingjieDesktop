# 原桌面移植执行包索引

原计划修订版 v2，2026-10-25。

这里保存桌面移植初期的计划与执行材料。当前使用入口见[项目首页](../../../README.md)、[使用指南](../../../README-desktop.md)、[构建说明](../../../desktop/README.md)与[D7 记录](../reports/SHARED-RUN-TABS-D7.md)。

原始移植基线为上游 1.0.18（`b809b196988b019571caf5679ccf8d7ebb7f072d`）。主技术路线仍为共享 Kotlin 业务代码与桌面适配；原计划中的单设备结构已由 D7 共享数据、每设备独立运行页补充。

执行约定见根目录 [AGENTS.md](../../../AGENTS.md)。[原规则副本](EXECUTION-PACK-AGENTS.md)、[启动方案](../KICKOFF.md)与[协作方案](../WORKSTREAMS.md)保留作历史参考。

## 设计与实现资料

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
| [边界登记](../EDGE_CASES.md)、[原验收方案](../ACCEPTANCE.md) | 历史验收口径与设备场景 |

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

识别样本与历次结果见 [OCR 评估](../../../desktop/ocr/evaluation/README.md)。
