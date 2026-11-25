package com.landosol.toolbox.labyrinth.node

/**
 * 黎明界节点类型常量
 *
 * 数值与游戏协议一致，不要修改；仅中文含义按实际游戏校准。
 */
object LabyrinthNodeTypes {

    const val START = 1          // 起始点
    const val NORMAL_BATTLE = 2  // 普通战斗
    const val EX_BATTLE = 3      // EX战斗
    const val LINK = 4           // 连结
    const val EVENT = 5          // 事件
    const val RELIC = 6          // 遗物
    const val SHOP = 7           // 商店
    const val BOSS = 8           // Boss
    /** 地图完成标记，仅用于视觉状态，不是可点击协议节点。 */
    const val CLEAR = -1

    /** 初始状态：尚无当前节点时的哨兵值，语义等同起始点。 */
    const val INITIAL = START

    /** 未知 / 未识别节点哨兵值，非协议数值。 */
    const val UNKNOWN = 0

    fun labelOf(blockType: Int): String =
        when (blockType) {
            START -> "起始点"
            NORMAL_BATTLE -> "普通战斗"
            EX_BATTLE -> "EX战斗"
            LINK -> "连结"
            EVENT -> "事件"
            RELIC -> "遗物"
            SHOP -> "商店"
            BOSS -> "Boss"
            CLEAR -> "CLEAR"
            else -> "未知($blockType)"
        }

    fun isClickable(type: Int): Boolean = type in setOf(
        NORMAL_BATTLE, EX_BATTLE, LINK, EVENT, RELIC, SHOP, BOSS,
    )
}
