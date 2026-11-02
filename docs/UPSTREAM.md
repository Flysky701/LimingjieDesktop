# 上游同步与共享修改

共享代码来自 `wbero/limingjie-assistant`，初始移植基线为 `b809b196988b019571caf5679ccf8d7ebb7f072d`。共享源码位于 `android/`；桌面构建的替身、排除与派生清单位于 `desktop/shared/`。

## 当前业务基线与共享修改

- **单局完成与结算衔接**：沿用上游结果页识别与结算导航，关闭最终奖励并稳定返回黎明界主页后结束单局。在此基础上修复结算阶段衔接：关闭分数页后若冷却期内再次识别到 `RESULT` 会保持重试，随后识别到宝箱动画、宝箱结果或最终道具页时即切入对应阶段（若漏帧直接看到最终道具页也直接推进），避免卡在 `SCORE_RESULT` 阶段不点击。该修复位于共享会话逻辑，对 Android 和桌面同时生效。
- **桌面分数识别隔离**：桌面端关闭主识别器内联分数 OCR，改为在结果页缓存最后两帧小区域，离开结果页后交由独立 OCR 识别；核对“分数明细”和总分一致后按单局编号补写记录。Android 保持默认内联开关。分数与耗时仅作观察记录，不参与单局完成判断。
- **桌面架构适配**：多设备隔离、同账号租约、存储及生命周期由桌面层管理；游戏点击保持 1920 × 1080 坐标。上游既有的启动时限、战斗等待时限及战败重试问题见 [已知限制与待办](KNOWN_LIMITATIONS.md#待修复与验证问题)。

## 上游同步步骤

1. 在独立分支对比目标上游提交，保留上游提交历史。
2. 复核已有共享修复与桌面替身；涉及共享业务行为变更时同步评估 Android 端影响。
3. 更新受影响的替身或派生规则，执行 `desktop\gradlew.bat -p desktop check -Pfull`；若上游新增用例失败，优先补齐适配语义。
4. 同步数据库版本、迁移 SQL、schema identity、设备画像与 UI 入口；涉及 Android 的改动使用 Android 工具链验证。
5. 在提交或 PR 中说明变更范围、验证结果与遗留问题。

## 维护边界

- 保持 `upstream-drift.properties` 的 SHA 锁，核对对应替身后才更新摘要。
- 保持动作前分辨率与实时前台校验、同账号租约、DPAPI 凭据加密及原子存储。
- 新增 Android 耦合按保留、注入缝、派生、替身、装配或排除分类更新清单，并通过全档耦合扫描校验。
- `build/generated` 由构建生成，不在桌面层复制分叉共享业务。

历史补丁与替身台账见 [UPSTREAM_PATCHES](archive/desktop/registers/UPSTREAM_PATCHES.md)、[UPSTREAM_SYNC](archive/desktop/registers/UPSTREAM_SYNC.md) 和 [SUBSTITUTES](archive/desktop/registers/SUBSTITUTES.md)。
