# 现有真实素材覆盖审计

日期：2026-10-25。只读核查主仓库上游截图、测试与资源；不访问旧项目或真实账号数据，不生成图片、不重复缩放充数。本次只追加一条有明确业务 ROI 和真值的样本；原 14 条与原匹配器保持不变，评估结果由主分支运行后记录。

## 可正式追加的样本

`android/app/src/test/resources/labyrinth/node-target-missed-20260916.jpg` 中，地图左下唯一绿色 HP 标记旁计数为 **1**。使用 `AndroidLabyrinthNodeRelicStackResolver.kt:211` 的首个 `OCR_DIGIT_RECTS`（360,970,48,48）及原 `OCR_SCALE=4`，调用现有 `node-stack` matcher。此图之前未进入清单；不重复计入相同数字/同帧的其他缩放。

追加后共 **15/80**：商店 3、详情 2、堆叠 7、角色奖励 0、事件 2、EX 1。总量仍缺 65；各类达到 8 分别缺 5/6/1/8/6/7。

## 六条商店标题只保留为转写候选

以下均来自已有截图，可目视读取，但本次不计入实体解析准确率：

| 原图（相对 `android/app/src/test/resources/`） | 顶部三项原文 | 已有证据 |
|---|---|---|
| `shop/live_shop_20260909.jpg` | 强化宝石；魔护灵装；回生杖 | `LabyrinthEntryFrameProcessorTest.kt:415-436` 对同图的原图标识别断言 ID 为 14001/25004/15001 |
| `shop/shop.png` | 贵族之刀；进化之耳环；再生之手镯 | 同测试文件 `video shop flow recognizes the page, purchase dialog, completion, and exit dialog` 对该图归类 SHOP；标题目视转写，未推断遗物 ID |

原因：`AndroidLabyrinthShopItemResolver` 通过 `LabyrinthShopCategoryResolution.resolve` 将文字送入原分类器；普通遗物只要求“文字非空且图标已识别”，实体 ID 来自图标，不来自标题。现有 `shop-imprint` matcher 只解析职能印记。把任意非空普通遗物 OCR 都算成功会稀释质量指标；另写名称匹配器又不再是原业务匹配器。后续可单独报告这些图的字符错误率，不改变当前实体指标含义。底部印记虽可见，但不在原 resolver 三个顶部标题 ROI 中，本次也未加入。

## 角色奖励与其他稀缺类别

- `vision/initial_role_reward/characters/character_{1335,1233,1256}_name.png` 三张模板可读，`LabyrinthEntryFrameProcessorTest.kt:1364-1383` 将其绑定到咲恋（新年）/涅娅/碧卡拉及对应 ID。它们是姓名裁片，仓库缺少原始完整截图、裁剪来源及真实 resolver ROI 对应关系；不按三条正式实采样本计入。即使后续补齐出处，也只有三条，仍少于八条。1256 截图用字与资源显示名不同，不能为通过 OCR 擅自改字或重写别名规则。
- `labyrinth/shop-character-joined-20260915.png`（千歌（礼服））和 `guild-bonus-joined-20260917.png`（拉比莉斯塔）属于 `CHARACTER_JOINED` 弹窗，不是三选一 `ROLE_REWARD`；不混算。`LabyrinthRoleRewardTextIdentityTest` 的合成字符串/ID 不构成截图样本。测试引用的外部 `素材/ui/黎明界进入` 原图目录在当前仓库不存在。
- 系列效果详情只有已纳入的 `series-effect-panel-20261012.jpg`；遗物获得弹窗不替代系列详情。事件无奖励/移动确认弹窗不替代事件选项。`ex/ex_monster_detail_live_20260910.jpg` 实际是战斗失败画面；`labyrinth/ex-antimatter-beast-20260911.png` 实际是助手状态日志，不能按文件名记为 EX 遭遇。
- 上游截图目录共有约 55 张图，另有连续结果帧、队伍页、对话框和诊断页。文件数不等于六类有效 OCR 样本数。现存素材不足以完成总量 80、每类 8；需补采有真实业务 ROI 与实体真值的角色奖励、系列详情、事件和 EX，堆叠仍缺至少一条。
