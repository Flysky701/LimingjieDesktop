# UPSTREAM_SYNC.md —— 跟进上游更新的流程

适用于：上游 `wbero/limingjie-assistant` 的 `main` 有新提交，需要让桌面版跟上。

## 1. 同步步骤

1. `git fetch upstream && git checkout desktop/main && git merge upstream/main`（或 rebase，按团队习惯；不要 squash 上游历史）。
2. 冲突只可能出现在 P-xx 补丁涉及的上游文件上。解决原则：**接受上游的新内容，再重新应用纯搬移**。逐条核对 `UPSTREAM_PATCHES.md`。
3. 执行 `./gradlew -p desktop checkUpstreamDrift`，逐项处理输出：

| 漂移类型 | 处理 |
|---|---|
| 新出现的 Android 耦合文件（扫描结果与排除清单不一致） | 按 SPEC-00 §4 的规则分类（K / K+P / D / S / A / X），更新清单和 SUBSTITUTES.md |
| 替身签名与上游不一致 | 按新签名修改替身（归属 WS 负责），行为变化要补测试 |
| 派生改写命中次数不符 | 检查上游的 Factory 写法是否变了；更新 `upstream-derived.txt` |
| `AppDatabase` 版本、实体或迁移变化 | 替身同步更新，迁移 SQL 逐字复制；补充迁移测试；检查 identityHash |
| 画像字段变化 | 同步画像工厂替身（WS-E），更新对照测试 |
| 国服数据库常量变化 | 同步桌面仓库 |

4. 执行 `./gradlew -p desktop check`（包括上游新增的测试）。上游新增测试失败 → 先查垫片语义，不得直接排除。
5. 检查以下文件在上游是否有变化，有变化就更新对应登记表：
   - `ui/LandosolToolboxApp.kt` → `UI_PARITY.md`（新回调、新页面、导航变化。例如上游接入会战助手，见 EDGE J-08）
   - 黎明界、账号、策略 / 编组 / 评分页面及其对话框 → 只检查本次上游改动影响的字段、操作与状态，在 UI_PARITY §8 简记并同步桌面页面；不做无关全量盘点
   - 影响操作流程或布局的新能力 → UI_DESIGN 与相关截图；沿用桌面规范，无须复制上游布局
   - `LandosolToolboxApplication.kt` → `ASSEMBLY.md`（新成员、构造参数变化）
   - `permissions/PermissionStatusReader.kt` → 环境检查页
   - `android/app/build.gradle.kts` 的 `versionName` / `versionCode` → 桌面显示版本（D-16）；重置 `<n>` 为 1
   - 上游依赖版本升级 → 评估桌面 `libs.versions.toml` 是否需要跟进
   - `README.md` 中的使用前提（模拟器设置、游戏内设置）→ `README-desktop.md` 和环境检查页的提醒清单
6. 跑 Windows CI，确认 self-test 通过；生成新的便携 zip。
7. 在 `PROGRESS.md` 记录：同步到的上游 commit、处理过的漂移项、新增或变化的补丁和替身。

## 2. 常见风险点

- 上游给被排除的文件（例如 `LandosolAccessibilityService`）加了新的纯 Kotlin 声明，并被保留代码引用 → 编译失败。处理：新增纯搬移补丁（P-xx 顺延）。
- 上游在保留文件里新增 `android.*` 用法 → 扫描会报告。优先补垫片（只补用到的成员）；不能补垫片时再考虑搬移或注入缝。
- 上游修改了线程假设（例如回调线程）→ 对照 `THREADING.md`。
- 上游修改了金标或测试资源 → 只读使用，不要在 `android/` 下重新生成。

## 3. 不要做

- 不要为了让同步更顺利而在上游代码中加入桌面分支。
- 不要复制上游文件维护业务分叉；D-37 允许的桌面自有呈现仍需共享业务入口并维护 UI_PARITY。
- 不要关闭漂移检查。
