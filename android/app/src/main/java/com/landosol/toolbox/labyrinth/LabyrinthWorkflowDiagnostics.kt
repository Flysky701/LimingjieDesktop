package com.landosol.toolbox.labyrinth

import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryFrameResult
import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryPageState

/** Diagnostic classification only. The frame router, never this label, selects an action owner. */
internal fun labyrinthWorkflowOwner(result: LabyrinthEntryFrameResult): String = when {
    labyrinthIsRoleRewardPage(result) -> "reward"
    result.observation.state == LabyrinthEntryPageState.ITEM_REWARD -> "reward"
    else -> when (labyrinthPageUiOwner(result.observation.state)) {
        LabyrinthPageUiOwner.NODE_SELECTION, LabyrinthPageUiOwner.NODE_MAP_VIEW -> "map"
        LabyrinthPageUiOwner.BATTLE_TEAM_SELECTION, LabyrinthPageUiOwner.BATTLE -> "battle"
        LabyrinthPageUiOwner.CHARACTER_JOINED, LabyrinthPageUiOwner.RELIC_CHOICE,
        LabyrinthPageUiOwner.LINK_CHOICE -> "reward"
        LabyrinthPageUiOwner.CHARACTER_SELECTION -> "entry"
        LabyrinthPageUiOwner.EVENT_CHOICE -> "event"
        LabyrinthPageUiOwner.SHOP -> "shop"
        LabyrinthPageUiOwner.RUN_RESULT -> "settlement"
        LabyrinthPageUiOwner.NONE -> "router"
    }
}
