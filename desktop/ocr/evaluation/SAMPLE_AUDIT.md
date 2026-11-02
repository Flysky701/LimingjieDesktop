# 真实素材覆盖盘点

本文说明 `manifest.json` 现有 15 条真实 ROI 样本的来源，以及仓库内其余测试截图未直接计入实体评估清单的原因。

## 已纳入的扩展样本

- `android/app/src/test/resources/labyrinth/node-target-missed-20260916.jpg`：取地图左下绿色 HP 标记旁计数 **1**，使用 `AndroidLabyrinthNodeRelicStackResolver.kt` 的首个 `OCR_DIGIT_RECTS` `(360,970,48,48)` 与 `OCR_SCALE=4`，对应 `node-stack` 匹配器。
- 纳入后合计 **15 条**（商店 3、详情 2、堆叠 7、角色奖励 0、事件 2、EX 1）。

## 未计入实体准确率清单的现有素材

### 1. 普通遗物商店标题（6 条）

| 原图（相对 `android/app/src/test/resources/`） | 顶部三项标题 | 说明 |
| --- | --- | --- |
| `shop/live_shop_20260909.jpg` | 强化宝石；魔护灵装；回生杖 | 实体 ID（14001/25004/15001）由图标模板识别决定 |
| `shop/shop.png` | 贵族之刀；进化之耳环；再生之手镯 | 仅归类为 `SHOP` 页面，未绑定遗物 ID 真值 |

`AndroidLabyrinthShopItemResolver` 对普通遗物仅要求标题 OCR 非空且图标已识别，只有职能印记通过 `shop-imprint` 文本匹配器解析实体。因此普通遗物标题仅可作为转写参考，不计入 `shop-imprint` 实体解析指标。

### 2. 角色奖励与其他类别

- **角色姓名裁片**：`vision/initial_role_reward/characters/character_{1335,1233,1256}_name.png` 为局部姓名模板，缺少原始整帧截图与 resolver ROI 映射（且 1256 截图字形与标准显示名存在差异），暂不作为整帧实采样本。
- **角色加入弹窗**：`labyrinth/shop-character-joined-20260915.png` 与 `guild-bonus-joined-20260917.png` 属于 `CHARACTER_JOINED` 页面，而非三选一 `ROLE_REWARD`。
- **其他非对应页面**：`ex/ex_monster_detail_live_20260910.jpg` 实为战斗失败页，`labyrinth/ex-antimatter-beast-20260911.png` 实为助手日志界面，均非 EX 遭遇详情页。

后续补充样本需采集包含完整游戏画面、对应 resolver ROI 及明确实体真值的角色奖励、系列详情、事件、EX 与地图堆叠截图。
