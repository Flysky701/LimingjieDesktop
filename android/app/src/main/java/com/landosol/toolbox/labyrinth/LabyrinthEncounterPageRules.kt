package com.landosol.toolbox.labyrinth

import com.landosol.toolbox.labyrinth.vision.EntryPixelRect
import com.landosol.toolbox.labyrinth.vision.EntryReferenceRect
import com.landosol.toolbox.labyrinth.vision.EntryReferenceSize
import com.landosol.toolbox.labyrinth.vision.LabyrinthBattleElementFilter
import com.landosol.toolbox.labyrinth.vision.LabyrinthBattleTeamObservation
import com.landosol.toolbox.labyrinth.vision.LabyrinthBattleTeamRecognitionState
import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryPageState
import com.landosol.toolbox.labyrinth.vision.ReferenceFitMapper

/**
 * Direct single-target EX name OCR is intentionally given a short chance first.  If it still
 * cannot identify the encounter, opening the fixed monster Details button is a non-destructive
 * way to move identity OCR onto the much more stable detail-modal name row.  Multi-monster and
 * special-dual EX use their own structurally detected info buttons and never enter this path.
 */
/**
 * The EX identity probe (name OCR, then the bounded 详情 fallback) is budgeted per *visit* to the
 * challenge page, not per run. 2026-09-17 live (bundle 152039): the EX challenge page was first
 * seen at 15:13:58, the battle was lost, and 重新挑战 brought the page back at 15:16:05; the
 * probe clock still held the first visit, so the 10 s budget was already spent and the run
 * stopped 0.4 s later with "未建立可信身份" before OCR could read 好朋友X even once.
 * UNKNOWN frames (animations, the 详情 modal) never count as leaving the page.
 */
internal fun labyrinthExIdentityProbeRestarts(
    lastKnownPage: LabyrinthEntryPageState?,
    page: LabyrinthEntryPageState,
    encounterResolved: Boolean,
): Boolean =
    page == LabyrinthEntryPageState.BATTLE_CHALLENGE &&
        !encounterResolved &&
        lastKnownPage != null &&
        lastKnownPage != LabyrinthEntryPageState.BATTLE_CHALLENGE

internal fun labyrinthSingleExDetailProbeRect(
    pageState: LabyrinthEntryPageState,
    isEx: Boolean,
    encounterResolved: Boolean,
    multiMonsterLikely: Boolean,
    specialDualLikely: Boolean,
    elapsedMillis: Long,
    stableFrames: Int,
    attempts: Int,
    frameWidth: Int,
    frameHeight: Int,
    minimumWaitMillis: Long = 3_000L,
    minimumStableFrames: Int = 2,
    maxAttempts: Int = 2,
): EntryPixelRect? {
    if (pageState != LabyrinthEntryPageState.BATTLE_CHALLENGE || !isEx || encounterResolved) return null
    if (multiMonsterLikely || specialDualLikely) return null
    if (elapsedMillis < minimumWaitMillis || stableFrames < minimumStableFrames || attempts >= maxAttempts) return null
    if (frameWidth <= 0 || frameHeight <= 0) return null
    return ReferenceFitMapper.map(
        frameWidth = frameWidth,
        frameHeight = frameHeight,
        referenceSize = EntryReferenceSize(1920, 1080),
        referenceRect = EntryReferenceRect(1395, 590, 155, 88),
    )
}

/**
 * Final safety gate for the challenge button.
 *
 * Challenge-title OCR is asynchronous. Without this gate an EVENT -> EX transition can expose a
 * BATTLE_CHALLENGE frame before "极难" has returned from OCR, allowing the generic challenge
 * branch to tap first. Boss nodes are already semantically identified by the route. Every other
 * challenge must either be positively classified as a non-EX title, or have a concrete EX guide
 * resolved. An automation-owned EX details probe may also explicitly approve a guide-less
 * fallback after it has closed the modal; this remains distinct from a generic unknown page.
 */
internal fun labyrinthBattleChallengeCanAutoStart(
    combatContext: LabyrinthCombatContext?,
    challengeDifficultyResolved: Boolean,
    extremeChallenge: Boolean,
    exEncounterResolved: Boolean,
    exGuideFallbackApproved: Boolean = false,
): Boolean = when {
    combatContext?.kind == LabyrinthCombatKind.BOSS -> true
    combatContext?.kind == LabyrinthCombatKind.EX || extremeChallenge ->
        exEncounterResolved || exGuideFallbackApproved
    !challengeDifficultyResolved -> false
    else -> true
}

/**
 * The 有效效果 filter can list nobody: the roster area shows 「未搜索到该角色。」 and no cards.
 * 2026-09-18: the scan waited forever for cards/scroll feedback on that page. An empty filtered
 * roster is a complete answer (zero effective roles), so the scan must finish, not wait. Both
 * the notice template and the absence of cards are required so a slow-loading list is not
 * mistaken for an empty one.
 */
internal fun labyrinthEffectiveFilterIsEmpty(
    observation: LabyrinthBattleTeamObservation,
    emptyNoticeScore: Double,
    minimumNoticeScore: Double = 0.80,
): Boolean =
    observation.currentFilter == LabyrinthBattleElementFilter.EFFECTIVE_EFFECT &&
        observation.recognitionState == LabyrinthBattleTeamRecognitionState.STABLE &&
        observation.visibleCharacters.isEmpty() &&
        emptyNoticeScore >= minimumNoticeScore
