# 上游同步与共享修改

共享代码来自 `wbero/limingjie-assistant`，初始移植基线为 `b809b196988b019571caf5679ccf8d7ebb7f072d`。当前共享源码在 `android/`；桌面构建的替身、排除与派生清单在 `desktop/shared/`。

## 更新步骤

1. 在隔离分支比较目标上游提交，保留上游历史；不要自动合并、推送或改动用户工作区。
2. 逐项复核已有共享修复和替身。业务修复必须明确说明 Android 影响，不冒称纯搬移或默认行为不变。
3. 更新受影响的替身/派生规则，运行 `desktop\gradlew.bat -p desktop check -Pfull`。上游用例失败先检查适配语义，不直接排除。
4. 同步数据库版本、迁移 SQL、schema identity、设备画像与 UI 所需入口。Android 有关改动另用 Android 工具链验证；不能用桌面编译冒充 Android 验证。
5. 将变更范围、测试和遗留问题写入提交或 PR；发布前分别确认 CI、打包与实机状态。

## 不可放宽的边界

- 保留 `upstream-drift.properties` 的 SHA 锁；只有人工核对对应替身后才能更新摘要。
- 保留原始识别帧、动作前分辨率与前台校验、账号租约、凭据隐私及原子存储。
- 新 Android 耦合按保留/注入缝/派生/替身/装配/排除分类，准确更新清单；扫描放全档，不能通过漏登记或改规则掩盖变化。
- 不手改 `build/generated`，不复制共享业务形成桌面分叉；测试不回写上游源码和 fixture。

历史修改登记与适配说明完整保留在 [UPSTREAM_PATCHES](archive/desktop/registers/UPSTREAM_PATCHES.md)、[UPSTREAM_SYNC](archive/desktop/registers/UPSTREAM_SYNC.md) 和 [SUBSTITUTES](archive/desktop/registers/SUBSTITUTES.md)，其中阶段要求及旧成绩仅供追溯，不自动成为当前任务授权或验收。
