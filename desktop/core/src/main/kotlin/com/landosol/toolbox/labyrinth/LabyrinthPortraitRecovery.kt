package com.landosol.toolbox.labyrinth

import com.landosol.toolbox.labyrinth.vision.EntryAnchorId
import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryFrameResult

/**
 * A visible battle menu proves a battle is on screen without classifying the whole frame as a
 * battle. Used to veto blind post-entry taps over an in-progress fight. The state-dependent
 * \"自动\" control is deliberately not used as recognition evidence.
 */
internal fun labyrinthHasBattleControls(result: LabyrinthEntryFrameResult): Boolean =
    BATTLE_CONTROL_ANCHORS.any {
        result.observation.anchorScores[it] >= 0.45 ||
            (result.anchorMatches[it]?.score ?: 0.0) >= 0.45
    }

private val BATTLE_CONTROL_ANCHORS = listOf(
    EntryAnchorId.BATTLE_IN_PROGRESS_MENU_BUTTON,
)
