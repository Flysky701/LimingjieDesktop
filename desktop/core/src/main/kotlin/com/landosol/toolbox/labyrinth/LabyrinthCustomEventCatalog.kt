package com.landosol.toolbox.labyrinth

/** 展示名称和描述以 0.6.2 原文为基础，斗技场奖励按用户截图显示四区数量；slot 使用 ord，不按 choice_id 排序。 */
data class LabyrinthCustomEventChoice(
    val name: String,
    val description: String,
    val slot: Int,
    /** 与 events.placeholder.json 的分支名称逐字匹配；与展示名称不同时单独填写。 */
    val runtimeName: String = name,
)

/** id 标识独立卡片；runtimeName 用于持久覆盖和运行匹配，不以 id 代替事件名。 */
data class LabyrinthCustomEventCard(
    val id: String,
    val name: String,
    val choices: List<LabyrinthCustomEventChoice>,
    /** 默认选择的 ord；目录仅包含可自定义事件。 */
    val defaultSlot: Int,
    /** 与 events.placeholder.json 的事件名称逐字匹配；与展示名称不同时单独填写。 */
    val runtimeName: String = name,
)

/** UI 与运行侧共享的完整目录；不依赖设置类，避免默认值初始化循环。 */
object LabyrinthCustomEventCatalog {
    /** 8 张可自定义事件卡片；四区斗技场统一为 arena，描述列出四区奖励数量。 */
    val cards: List<LabyrinthCustomEventCard> = listOf(
        LabyrinthCustomEventCard(
            id = "arena",
            name = "发现了魔物们聚集的斗技场！",
            choices = listOf(
                LabyrinthCustomEventChoice(
                    name = "一试身手吧！",
                    description = "向战斗格子（普通难度）的 敌人发起挑战 ＋阿尔法金币×200/300/400/500",
                    slot = 1,
                ),
                LabyrinthCustomEventChoice(
                    name = "与强敌战斗真是令人兴奋！",
                    description = "向战斗格子（极难）的 敌人发起挑战 ＋阿尔法金币×300/450/600/750",
                    slot = 2,
                ),
            ),
            // 1105 / 1109 / 1113 / 1117 共用事件名和分支名，一条覆盖适用于四区。
            defaultSlot = 2,
        ),
        LabyrinthCustomEventCard(
            id = "1108",
            name = "在地下城迷路了！要走哪边？",
            choices = listOf(
                LabyrinthCustomEventChoice(
                    name = "难道不是左边吗？",
                    description = "阿尔法金币×1000",
                    slot = 1,
                ),
                LabyrinthCustomEventChoice(
                    name = "当然是右边啊！",
                    description = "50％概率获得★1迷宫遗物×1 50％概率获得随机职能的 随机印记×2",
                    slot = 2,
                ),
            ),
            defaultSlot = 2,
        ),
        LabyrinthCustomEventCard(
            id = "1111",
            name = "能听到从箱子里传出的声音……",
            choices = listOf(
                LabyrinthCustomEventChoice(
                    name = "弟弟 这边这边♪",
                    description = "阿尔法金币×2000",
                    slot = 1,
                ),
                LabyrinthCustomEventChoice(
                    name = "哥哥 在这边哦♪",
                    description = "★1～3迷宫遗物×１",
                    slot = 2,
                ),
            ),
            defaultSlot = 1,
        ),
        LabyrinthCustomEventCard(
            id = "1112",
            name = "活动会场出现了魔物！",
            choices = listOf(
                LabyrinthCustomEventChoice(
                    name = "见识下御久间流的 剑术吧！",
                    description = "阿尔法金币×2000",
                    slot = 1,
                ),
                LabyrinthCustomEventChoice(
                    name = "这里就 交给我们吧！",
                    description = "纯成为伙伴 ＋全体随机印记×2～4",
                    slot = 2,
                ),
            ),
            defaultSlot = 2,
        ),
        LabyrinthCustomEventCard(
            id = "2315",
            name = "料理完成了！不过食材还有剩余哦！",
            choices = listOf(
                LabyrinthCustomEventChoice(
                    name = "这样应该就 足够了吧？",
                    description = "随机职能的 随机印记×2",
                    slot = 1,
                ),
                LabyrinthCustomEventChoice(
                    name = "我还没 吃够呢☆",
                    description = "50％概率进入下一个事件",
                    slot = 2,
                ),
            ),
            defaultSlot = 1,
        ),
        LabyrinthCustomEventCard(
            id = "3315",
            name = "完美！不过食材还有很多哦！",
            choices = listOf(
                LabyrinthCustomEventChoice(
                    name = "这么多 根本吃不完吧！",
                    description = "随机职能的 选择印记×2",
                    slot = 1,
                ),
                LabyrinthCustomEventChoice(
                    name = "诶嘿嘿～ 再一下就好……",
                    description = "50％概率获得全体选择印记×２",
                    slot = 2,
                ),
            ),
            defaultSlot = 1,
        ),
        LabyrinthCustomEventCard(
            id = "2316",
            name = "再较量一次！",
            choices = listOf(
                LabyrinthCustomEventChoice(
                    name = "到这里就行了……",
                    description = "挑战结束",
                    slot = 1,
                ),
                LabyrinthCustomEventChoice(
                    name = "今天真是走运！",
                    description = "50％概率获得阿尔法金币×4000 并进入下一个事件",
                    slot = 2,
                ),
            ),
            defaultSlot = 2,
        ),
        LabyrinthCustomEventCard(
            id = "3316",
            name = "最后一发！",
            choices = listOf(
                LabyrinthCustomEventChoice(
                    name = "差不多该放弃了……",
                    description = "挑战结束",
                    slot = 1,
                ),
                LabyrinthCustomEventChoice(
                    name = "今天的我 才不会输！",
                    description = "50％概率获得阿尔法金币×8000",
                    slot = 2,
                ),
            ),
            defaultSlot = 2,
        ),
    )

    /** 八个支持自定义的运行事件名，不包含公会或石板。 */
    val supportedNames: Set<String> = cards.map { it.runtimeName }.toSet()

    /** 八个运行事件名的默认分支，按 defaultSlot 匹配 ord。 */
    val defaultChoiceOverrides: Map<String, String> = cards.associate { card ->
        card.runtimeName to card.choices.single { it.slot == card.defaultSlot }.runtimeName
    }
}
