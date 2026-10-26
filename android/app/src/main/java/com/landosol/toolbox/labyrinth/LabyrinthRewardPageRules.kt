package com.landosol.toolbox.labyrinth

import com.landosol.toolbox.labyrinth.node.LabyrinthNodeTypes
import com.landosol.toolbox.labyrinth.vision.EntryAnchorId
import com.landosol.toolbox.labyrinth.vision.EntryPixelRect
import com.landosol.toolbox.labyrinth.vision.LabyrinthBattleCharacterMatch
import com.landosol.toolbox.labyrinth.vision.LabyrinthCharacterMatch
import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryFrameResult
import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryPageState
import com.landosol.toolbox.labyrinth.vision.labyrinthRoleRewardOwnsInitialSelection

internal fun labyrinthIsRoleRewardPage(
    result: LabyrinthEntryFrameResult,
    minimumAnchorScore: Double = ROLE_PAGE_ANCHOR_MIN_SCORE,
): Boolean = result.observation.state == LabyrinthEntryPageState.INITIAL_CHARACTER_SELECTION &&
    labyrinthRoleRewardOwnsInitialSelection(
        anchorScores = result.observation.anchorScores,
        minimumScore = minimumAnchorScore,
    )

/**
 * A role-reward batch can present several selection pages back-to-back without changing the
 * page enum. Use the candidate identities, not the page state, to tell one selection round from
 * the next. Scores are intentionally excluded so recognition jitter cannot create a fake round.
 */
internal fun labyrinthRoleRewardSelectionSignature(
    result: LabyrinthEntryFrameResult,
): String? {
    if (!labyrinthIsRoleRewardPage(result)) return null
    if (result.characterMatches.isEmpty()) return null
    return result.characterMatches
        .sortedBy { it.slotId }
        .joinToString("|") { match ->
            val identity = match.characterId ?: match.suspectedCharacterId ?: "?"
            "${match.slotId}:$identity"
        }
}

/**
 * Boss/event settlements may first show several role-choice pages and only afterwards show all
 * corresponding CHARACTER_JOINED popups. Keep that whole sequence as one reward batch. An
 * explicit non-reward page is the terminal boundary; UNKNOWN is allowed only inside the already
 * armed batch so transient portrait/transition frames can still be advanced safely.
 */
internal fun labyrinthKeepsRoleRewardBatchOnPage(
    pageState: LabyrinthEntryPageState,
    isRoleRewardPage: Boolean,
): Boolean = when (pageState) {
    LabyrinthEntryPageState.INITIAL_CHARACTER_SELECTION -> isRoleRewardPage
    LabyrinthEntryPageState.UNKNOWN,
    LabyrinthEntryPageState.EVENT_ANIMATION,
    LabyrinthEntryPageState.CHARACTER_JOINED,
    LabyrinthEntryPageState.ITEM_REWARD,
    -> true
    else -> false
}

internal fun labyrinthResumedRunRewardPageOwnsRoute(
    result: LabyrinthEntryFrameResult,
): Boolean = when (result.observation.state) {
    LabyrinthEntryPageState.CHARACTER_JOINED -> true
    LabyrinthEntryPageState.INITIAL_CHARACTER_SELECTION -> labyrinthIsRoleRewardPage(result)
    else -> false
}

/**
 * Returns only positive, trusted ownership evidence. Absence from a later viewport is never
 * represented here and therefore can never delete a previously known role.
 */
internal fun labyrinthRosterReconciliationMatches(
    result: LabyrinthEntryFrameResult,
): List<LabyrinthCharacterMatch> = when (result.observation.state) {
    LabyrinthEntryPageState.CHARACTER_JOINED -> result.characterMatches
        .filter { it.trusted && it.characterId != null && it.displayName != null }

    // The battle editor is an observation of the already-owned roster, not an acquisition
    // event. Appending its transient icon guesses permanently polluted the saved roster whenever
    // a seasonal variant briefly matched the wrong candidate.
    LabyrinthEntryPageState.BATTLE_TEAM_SELECTION -> emptyList()

    LabyrinthEntryPageState.INITIAL_CHARACTER_SELECTION -> {
        if (labyrinthIsRoleRewardPage(result) || !openingRosterIsConfirmed(result)) {
            emptyList()
        } else {
            result.openingCharacterMatches
                .filter {
                    it.selected && it.trusted && it.characterId != null && it.displayName != null
                }
                .map(::toRosterMatch)
                .distinctBy { match -> canonicalLabyrinthRoleId(requireNotNull(match.characterId)) }
                .takeIf { it.size == REQUIRED_OPENING_ROSTER_SIZE }
                .orEmpty()
        }
    }

    else -> emptyList()
}
    .distinctBy { match -> canonicalLabyrinthRoleId(requireNotNull(match.characterId)) }

/**
 * Roster evidence for the guild's opening grant. The confirmed three picks replace the roster;
 * the granted character rides along so the run never plans without it. Only the guild is
 * needed: the grant is unconditional and its 角色加入 popup may not be recognised.
 */
internal fun labyrinthGuildGrantedRosterMatches(
    guildId: Int?,
    frameRect: EntryPixelRect,
): List<LabyrinthCharacterMatch> = LabyrinthOpeningRosterCatalog.grantedCharactersFor(guildId).map { granted ->
    LabyrinthCharacterMatch(
        slotId = "guild_grant_${granted.characterId}",
        characterId = granted.characterId,
        displayName = granted.displayName,
        confidence = 1.0,
        screenRect = frameRect,
        trusted = true,
    )
}

internal fun labyrinthRosterReplacesExisting(
    result: LabyrinthEntryFrameResult,
    matches: List<LabyrinthCharacterMatch>,
): Boolean = result.observation.state == LabyrinthEntryPageState.INITIAL_CHARACTER_SELECTION &&
    matches.size == REQUIRED_OPENING_ROSTER_SIZE

private fun openingRosterIsConfirmed(result: LabyrinthEntryFrameResult): Boolean {
    val scores = result.observation.anchorScores
    val selectionComplete = maxOf(
        scores[EntryAnchorId.SELECTION_COUNT_COMPLETE],
        scores[EntryAnchorId.SELECTION_COUNT_COMPLETE_STANDARD],
    )
    val inviteEnabled = maxOf(
        scores[EntryAnchorId.INVITE_ENABLED],
        scores[EntryAnchorId.INVITE_ENABLED_STANDARD],
    )
    return minOf(selectionComplete, inviteEnabled) >= ROLE_PAGE_ANCHOR_MIN_SCORE
}

private fun toRosterMatch(match: LabyrinthBattleCharacterMatch) = LabyrinthCharacterMatch(
    slotId = match.slotId,
    characterId = match.characterId,
    displayName = match.displayName,
    confidence = match.confidence,
    screenRect = match.screenRect,
    iconVariant = match.iconVariant,
    trusted = match.trusted,
    rivalMargin = match.rivalMargin,
)

private const val ROLE_PAGE_ANCHOR_MIN_SCORE = 0.45

private const val REQUIRED_OPENING_ROSTER_SIZE = 3

/**
 * Whether a committed selection tap on a single-pick choice page has waited long enough to be
 * considered lost.
 *
 * This is [labyrinthEventChoiceCommitExpired] generalised to the remaining page that holds a
 * "committed" latch, so a tap the game never accepted cannot pin the run forever. Same reasoning:
 * a real selection leaves the page within a second or two, so a page that is still the same choice
 * after this long means the tap never landed. Retrying is safe because these pages accept a single
 * selection per round — if the tap did land and the page merely lagged, the page has changed by the
 * time the retry is planned.
 *
 * Contrast the 2026-10-12 bundles: a RELIC_CHOICE page sat motionless for 120 s with actionCount
 * frozen and was only ended by the page-stall watchdog, because the relic latch cleared on a page
 * change alone and the page never changed.
 */
internal fun labyrinthSelectionCommitExpired(
    committedAtMillis: Long,
    nowMillis: Long,
    retryAfterMillis: Long = SELECTION_COMMIT_RETRY_MILLIS,
): Boolean = committedAtMillis != Long.MIN_VALUE &&
    (nowMillis - committedAtMillis).coerceAtLeast(0L) >= retryAfterMillis

internal const val SELECTION_COMMIT_RETRY_MILLIS = 6_000L

/**
 * A number of reward flows show only a tappable character portrait between two recognized
 * pages. The portrait itself is intentionally allowed to be UNKNOWN, but only when the
 * previous page proves that a reward/animation chain is in progress.
 */
internal fun shouldArmCharacterAcquisitionFallback(
    previousPage: LabyrinthEntryPageState?,
    currentPage: LabyrinthEntryPageState,
    activeNodeType: Int? = null,
): Boolean {
    if (currentPage != LabyrinthEntryPageState.UNKNOWN) return false
    return when (previousPage) {
        LabyrinthEntryPageState.INITIAL_CHARACTER_SELECTION,
        LabyrinthEntryPageState.LINK_CHOICE,
        LabyrinthEntryPageState.EVENT_CHOICE,
        LabyrinthEntryPageState.EVENT_ANIMATION,
        -> true

        // Link rewards show an ITEM_REWARD popup containing the three granted characters and
        // then enter the portrait sequence. Normal/EX/Boss rewards are followed by a role
        // selection. A relic or plain event item reward can instead return directly to the map,
        // so those node types must not arm the fallback here.
        LabyrinthEntryPageState.ITEM_REWARD,
        LabyrinthEntryPageState.BATTLE_RESULT,
        -> activeNodeType in setOf(
            LabyrinthNodeTypes.LINK,
            LabyrinthNodeTypes.NORMAL_BATTLE,
            LabyrinthNodeTypes.EX_BATTLE,
            LabyrinthNodeTypes.BOSS,
        )

        else -> false
    }
}
