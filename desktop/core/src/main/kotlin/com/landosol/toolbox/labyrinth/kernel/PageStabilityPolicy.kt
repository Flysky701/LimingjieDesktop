package com.landosol.toolbox.labyrinth.kernel

import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryPageState
import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryPageState.*

enum class PageStabilityMode { SINGLE_FRAME, PAGE_IDENTITY, STATIC_TARGETS }

data class PageStabilitySpec(
    val page: LabyrinthEntryPageState,
    val mode: PageStabilityMode,
    val requiredFrames: Int,
    val maxCenterDriftPx: Int? = null,
) {
    init {
        require(requiredFrames >= 1)
        require(maxCenterDriftPx == null || maxCenterDriftPx >= 0)
    }
}

object PageStabilityRegistry {
    private val specs = LabyrinthEntryPageState.entries.associateWith { page ->
        when (page) {
            GAME_LOADING_PROGRESS, PRE_HOME_DATA_LOADING, UNKNOWN ->
                PageStabilitySpec(page, PageStabilityMode.SINGLE_FRAME, 1)
            NODE_SELECTION, NODE_MAP_VIEW ->
                PageStabilitySpec(page, PageStabilityMode.STATIC_TARGETS, 3, 16)
            INITIAL_CHARACTER_SELECTION, LINK_CHOICE, RELIC_CHOICE, EVENT_CHOICE,
            SHOP, BATTLE_CHALLENGE, BATTLE_TEAM_SELECTION ->
                PageStabilitySpec(page, PageStabilityMode.STATIC_TARGETS, 2, 16)
            TITLE_WAITING_TAP, HOME_ANNOUNCEMENT, HOME, ADVENTURE, DAWN_REALM_HOME_IDLE,
            DAWN_REALM_HOME_ACTIVE, CHARACTER_JOINED, EVENT_ANIMATION, ITEM_REWARD,
            SHOP_PURCHASE_CONFIRMATION, SHOP_PURCHASE_COMPLETE, SHOP_EXIT_CONFIRMATION,
            BATTLE_IN_PROGRESS, BATTLE_FAILED, BATTLE_RESULT, RUN_CLEAR_RESULT,
            RUN_CLEAR_CONGRATULATIONS, RUN_CLEAR_CHARACTER_SUMMARY, RUN_CLEAR_REWARD_ANIMATION,
            RUN_CLEAR_CHEST_ANIMATION, RUN_CLEAR_CHEST_RESULT ->
                PageStabilitySpec(page, PageStabilityMode.PAGE_IDENTITY, 2)
        }
    }

    init { check(specs.keys == LabyrinthEntryPageState.entries.toSet()) }

    fun specFor(page: LabyrinthEntryPageState): PageStabilitySpec = specs.getValue(page)
    fun allSpecs(): Map<LabyrinthEntryPageState, PageStabilitySpec> = specs.toMap()
}
