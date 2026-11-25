# 上游同步与共享修改

共享代码源于 `wbero/limingjie-assistant`（初始基线 `b809b196988b019571caf5679ccf8d7ebb7f072d`），现与 [SatenRuiko10032/limingjie-assistant](https://github.com/SatenRuiko10032/limingjie-assistant) 保持同步。共享源码位于 `android/`，桌面派生与替身清单位于 `desktop/shared/`。跨仓演进里程碑见 [统一路线图](ROADMAP.md)。

## 当前业务基线与修改边界

- **上游业务基线**：已合入 `limingjie-assistant-copy`（`143d2b2115eeb2410cc0baf63334cb193b1fdd6d`）及上游 **PR #29**（3/4/5 区商店重构、三档前排生存模式、EX/遗物模板、事件复用与 1088 恢复），完整覆盖十公会、开局候选池与赠送角色、自定义事件/路线与预览、选人确认、遗物唯一持有与层数、双战斗开关（前排生存门槛 / 普战跳编队）。
- **共享运行时与桌面修复**：在 `android/` 共享层合入结算宝箱推进衔接、当前帧地图点击证据、已识别路线清空未知计时、入口慢加载 90s 分阶段预算与 `READY_FOR_NEXT_ROUND` 透传、HUD 进展感知战斗等待上限（5–14 分钟）、单队 1–5 人统一重试及失败评估线程卸载；同时作用于桌面与 Android（待实机清单见 [已知限制与待办](KNOWN_LIMITATIONS.md#待修复与验证问题)）。
- **桌面端独占适配**：保持多 JVM 隔离、同账号租约、横屏参考映射（支持 1080p 与 720p 开放测试）、独立分数 OCR 隔离、直接像素视图与预编译角色样本、300 ms 默认节拍，以及策略库原子保存/撤销、旧桌面分享码与 `LABY1.` 双向兼容；Android 保持默认内联分数开关与自身界面布局。
- **平台边界**：桌面主题、主导航、存储权威与设备通道由 `desktop/` 装配；新接入的 NN 战斗估值与 Path B 规划通过 `android/` 纯 Kotlin SPI 解耦，Android 保持零 ONNX 依赖。

## 上游同步步骤

1. 在独立分支对比目标上游提交，保留上游提交历史。
2. 复核已有共享修复与桌面替身；涉及共享业务变更时同步评估 Android 端影响。
3. 更新受影响替身或派生规则并运行 `desktop\gradlew.bat -p desktop check -Pfull`；上游新增用例失败时优先补齐适配语义。
4. 同步数据库版本、迁移 SQL、schema identity、设备画像与 UI 入口；涉及 Android 的改动使用 Android 工具链验证。

## 维护边界

- 保持 `upstream-drift.properties` 的 SHA 锁，核对替身后才更新摘要；`build/generated` 由构建生成，不在桌面层复制分叉共享业务。
- 保持动作前分辨率与实时前台校验、同账号租约、DPAPI 加密及原子存储；新增 Android 耦合按清单分类并通过耦合扫描校验。
- 历史台账归档于 [archive/desktop/registers/](archive/desktop/registers/UPSTREAM_PATCHES.md)。
