# “角色加入奖励”三选一模板

本目录保存初始角色三选一的页面锚点与历史名称素材。角色身份采用名称 OCR 优先、头像回退。素材来源与页面特征：

- 截图：`通关视频/分析输出/ADB只读回归/当前识别状态_现场.png`
- 页面标题：`角色加入奖励！`
- 页面提示：`邀请选择的角色成为伙伴。`
- 当前状态：`还剩1次`，下方有三个`选择`按钮

| 文件 | 用途 | 原图裁剪区域（x,y,w,h） |
| --- | --- | --- |
| `initial_role_reward_title.png` | 页面标题 | 680,35,580,120 |
| `initial_role_reward_instruction.png` | 页面说明 | 680,150,570,75 |
| `initial_role_reward_select_button.png` | 三个候选角色共用的选择按钮 | 260,810,290,155 |
| `initial_role_reward_remaining_once.png` | `还剩1次`示例，仅作辅助素材 | 840,215,240,60 |

角色头像和角色名称不作为页面锚点，因为三名候选角色会变化。识别使用标题和页面说明作为主锚点，选择按钮作为辅助锚点。

## 角色身份识别

`AndroidLabyrinthRoleRewardNameResolver` 先读取三名候选名称，通过统一名称表解析；文字证据不足时，调用 `icons.json` 和 `LabyrinthCharacterIconMatcher` 做头像回退及名称辅助。页面提供左、中、右三个候选的名称与头像 ROI，确认身份后选择。

历史名称回归素材：

- `characters/character_1335_name.png`：咲恋(新年)，角色 ID `1335`
- `characters/character_1233_name.png`：涅娅，角色 ID `1233`
- `characters/character_1256_name.png`：碧卡拉，角色 ID `1256`

名称和头像证据不足时等待下一帧。桌面自动执行使用 1920×1080 横屏，样本统计见 `desktop/ocr/evaluation/`。
