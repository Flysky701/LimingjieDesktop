package com.landosol.toolbox.labyrinth

import com.landosol.toolbox.labyrinth.vision.LABYRINTH_BATTLE_CHARACTER_SAFE_CONFIDENCE
import com.landosol.toolbox.labyrinth.vision.LabyrinthBattleTeamObservation

internal data class LabyrinthEffectiveScanFrameEvidence(
    val confirmedCharacterIds: Set<String>,
    val unsafeVisibleCharacters: List<com.landosol.toolbox.labyrinth.vision.LabyrinthBattleCharacterMatch>,
    val toleratedSelectedDimmedCount: Int,
)

/**
 * Selected roster cards are rendered dimmed by the game. Their portrait match can therefore fall
 * below the normal safety threshold even though the yellow checkmark and the authoritative current
 * member strip prove that this is an already-selected card. Such a dimmed card must not deadlock
 * the official "有效效果" scan. We only assign an identity when its id/suspected id reconciles to
 * a member that is independently present in the bottom strip; otherwise it is tolerated but not
 * added to the effective-role set.
 */
internal fun labyrinthEffectiveScanFrameEvidence(
    observation: com.landosol.toolbox.labyrinth.vision.LabyrinthBattleTeamObservation,
): LabyrinthEffectiveScanFrameEvidence {
    val selectedMemberIds = observation.selectedCharacters.mapNotNull { match ->
        match.characterId?.let(::canonicalLabyrinthRoleId)
    }.toSet()
    val hasAuthoritativeSelectedMembers = observation.selectedCharacters.isNotEmpty()
    val confirmed = linkedSetOf<String>()
    val unsafe = mutableListOf<com.landosol.toolbox.labyrinth.vision.LabyrinthBattleCharacterMatch>()
    var toleratedSelectedDimmedCount = 0

    observation.visibleCharacters.forEach { match ->
        val id = match.characterId?.let(::canonicalLabyrinthRoleId)
        val safelyIdentified = id != null && match.trusted &&
            match.confidence >= LABYRINTH_BATTLE_CHARACTER_SAFE_CONFIDENCE
        if (safelyIdentified) {
            confirmed += requireNotNull(id)
            return@forEach
        }
        if (match.selected && hasAuthoritativeSelectedMembers) {
            toleratedSelectedDimmedCount++
            val reconciledId = sequenceOf(match.characterId, match.suspectedCharacterId)
                .filterNotNull()
                .map(::canonicalLabyrinthRoleId)
                .firstOrNull { it in selectedMemberIds }
            if (reconciledId != null) confirmed += reconciledId
            return@forEach
        }

        unsafe += match
    }

    return LabyrinthEffectiveScanFrameEvidence(
        confirmedCharacterIds = confirmed,
        unsafeVisibleCharacters = unsafe,
        toleratedSelectedDimmedCount = toleratedSelectedDimmedCount,
    )
}

internal enum class LabyrinthEffectiveCharacterScanStage {
    IDLE,
    SWITCH_TO_EFFECTIVE,
    SCANNING,
    RETURN_TO_ALL,
    COMPLETE,
}
