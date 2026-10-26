package com.landosol.toolbox.labyrinth

import com.landosol.toolbox.labyrinth.vision.LabyrinthCharacterMatch

/** Captured before dispatch, so the callback cannot borrow a different frame's chosen role. */
internal data class LabyrinthRoleRewardTapContext(
    val decision: LabyrinthRoleRewardChoiceDecision.Select?,
    val accountId: Long?,
    val recognizedCharacter: LabyrinthCharacterMatch?,
)
