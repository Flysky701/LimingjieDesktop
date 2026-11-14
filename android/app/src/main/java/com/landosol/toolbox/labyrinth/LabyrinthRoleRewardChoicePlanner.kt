package com.landosol.toolbox.labyrinth

import com.landosol.toolbox.labyrinth.vision.EntryPixelRect
import com.landosol.toolbox.labyrinth.vision.EntryReferenceRect
import com.landosol.toolbox.labyrinth.vision.EntryReferenceSize
import com.landosol.toolbox.labyrinth.vision.LabyrinthBattleCharacterMatch
import com.landosol.toolbox.labyrinth.vision.LabyrinthCharacterMatch
import com.landosol.toolbox.labyrinth.vision.ReferenceFitMapper

/**
 * Role-reward cards are large, stationary portraits with a visible name row.  Do not inherit the
 * deliberately permissive full-roster icon floor here: a barely-separated icon match is useful as
 * an OCR candidate, but it is not safe enough to drive an irreversible three-choice tap by itself.
 */
internal const val ROLE_REWARD_STRONG_ICON_CONFIDENCE = 0.50
internal const val ROLE_REWARD_STRONG_ICON_RIVAL_MARGIN = 0.10

internal fun labyrinthRoleRewardIdentityIsActionSafe(match: LabyrinthCharacterMatch): Boolean =
    match.trusted && (
        match.nameAssisted ||
            (match.confidence >= ROLE_REWARD_STRONG_ICON_CONFIDENCE &&
                match.rivalMargin >= ROLE_REWARD_STRONG_ICON_RIVAL_MARGIN)
        )

/** Weak-but-trusted icon matches still deserve the page's name OCR second pass. */
internal fun labyrinthRoleRewardNeedsNameVerification(match: LabyrinthCharacterMatch): Boolean =
    !match.nameAssisted && !labyrinthRoleRewardIdentityIsActionSafe(match)

sealed interface LabyrinthRoleRewardChoiceDecision {
    data class Select(
        val characterId: String,
        val displayName: String,
        val buttonRect: EntryPixelRect,
        val explanation: List<String>,
        /** False means this is a visible recommendation only; live mode must not tap it. */
        val actionSafe: Boolean = true,
        val safetyNote: String? = null,
    ) : LabyrinthRoleRewardChoiceDecision

    data class Wait(
        val reason: String,
    ) : LabyrinthRoleRewardChoiceDecision
}

/**
 * Safety adapter between visual recognition and role scoring. Normally all three candidates are
 * scored. If exactly one portrait remains unsafe, the two independently safe cards can still be
 * compared and tapped; the unknown card is never clicked or assigned a guessed identity.
 */
class LabyrinthRoleRewardChoicePlanner(
    private val profiles: Map<String, LabyrinthRoleProfile>,
    private val roleChoicePolicy: LabyrinthRoleChoicePolicy,
) {
    init {
        require(profiles.keys.all { it.isNotBlank() })
        require(profiles.all { (id, profile) -> id == profile.characterId })
    }

    fun decide(
        candidates: List<LabyrinthCharacterMatch>,
        acquiredCharacterIds: Set<String>,
        context: LabyrinthRoleDecisionContext,
        frameWidth: Int,
        frameHeight: Int,
    ): LabyrinthRoleRewardChoiceDecision {
        val bySlot = candidates.associateBy(LabyrinthCharacterMatch::slotId)
        val ordered = SLOT_IDS.mapNotNull(bySlot::get)
        if (ordered.size != SLOT_IDS.size) {
            return LabyrinthRoleRewardChoiceDecision.Wait("角色三选一画面尚未稳定识别三个槽位")
        }
        val weak = ordered.filter {
            it.characterId.isNullOrBlank() ||
                it.displayName.isNullOrBlank() ||
                !labyrinthRoleRewardIdentityIsActionSafe(it)
        }
        if (weak.size >= 2) {
            return LabyrinthRoleRewardChoiceDecision.Wait(
                "角色头像识别未可信：${weak.joinToString { match ->
                    "${match.slotId}=${formatConfidence(match.confidence)}/差${formatConfidence(match.rivalMargin)}"
                }}",
            )
        }
        val safeCandidates = ordered.filterNot(weak::contains)
        val candidateIds = safeCandidates.map { canonicalLabyrinthRoleId(requireNotNull(it.characterId)) }
        if (candidateIds.distinct().size != safeCandidates.size) {
            return LabyrinthRoleRewardChoiceDecision.Wait("可靠候选被识别成重复角色，拒绝点击")
        }
        val canonicalAcquiredIds = acquiredCharacterIds.map(::canonicalLabyrinthRoleId).toSet()
        val knownProfiles = profiles.withKnownRoleIdentities(candidateIds + canonicalAcquiredIds,
            safeCandidates.associate {
                canonicalLabyrinthRoleId(requireNotNull(it.characterId)) to requireNotNull(it.displayName)
            })
        val incompleteIds = (candidateIds + canonicalAcquiredIds).distinct()
            .filterNot { knownProfiles.getValue(it).isStrictDecisionReady }.sorted()
        val choice = roleChoicePolicy.chooseOneRole(
            candidateIds = candidateIds,
            acquiredCharacterIds = canonicalAcquiredIds,
            profiles = knownProfiles,
            context = context,
            requireStrictProfiles = false,
        )
        return when (choice) {
            is LabyrinthOneRoleDecision.Ready -> choice.toSelection(
                candidates = safeCandidates,
                frameWidth = frameWidth,
                frameHeight = frameHeight,
                actionSafe = true,
                safetyNote = buildList {
                    weak.singleOrNull()?.let { failed ->
                        add(
                            "${failed.slotId}头像未达到安全线，已忽略该卡并在剩余2个可靠候选中选择",
                        )
                    }
                    incompleteIds.takeIf { it.isNotEmpty() }?.let {
                        add("部分角色资料不完整，按已有信息评分：${it.joinToString()}")
                    }
                }.takeIf { it.isNotEmpty() }?.joinToString("；"),
            )
            is LabyrinthOneRoleDecision.Unavailable -> LabyrinthRoleRewardChoiceDecision.Wait(choice.reason)
        }
    }

    /**
     * Some EVENT nodes open the full roster selector and allow exactly one free recruit.  The
     * screen shell is almost identical to the initial 3-character selector, so the session uses
     * route context to enter this method instead of the opening-roster policy.
     *
     * Only stable/trusted visible identities are considered.  Unlike the three-card reward page,
     * one weak sibling does not block a safely recognized candidate; this page can contain dozens
     * of cards and the action is anchored to the chosen card's own add button.
     */
    fun decideFreeVisibleRole(
        candidates: List<LabyrinthBattleCharacterMatch>,
        acquiredCharacterIds: Set<String>,
        context: LabyrinthRoleDecisionContext,
    ): LabyrinthRoleRewardChoiceDecision {
        val trusted = candidates
            .asSequence()
            .filter { match ->
                match.trusted &&
                    !match.selected &&
                    !match.characterId.isNullOrBlank() &&
                    match.characterId !in acquiredCharacterIds
            }
            .distinctBy { it.characterId }
            .toList()
        if (trusted.isEmpty()) {
            return LabyrinthRoleRewardChoiceDecision.Wait("事件自由选角：当前视口没有可靠且未获得的角色")
        }

        // A stale/partial character catalog must not make the whole page unusable.  Ignore only
        // candidates with no scoring profile and choose among the identities we can audit.
        val scorable = trusted.mapNotNull { match ->
            val profile = profiles[match.characterId] ?: return@mapNotNull null
            val score = profile.effectiveUserScore ?: return@mapNotNull null
            RankedVisibleRole(match, profile, score)
        }
        if (scorable.isEmpty()) {
            return LabyrinthRoleRewardChoiceDecision.Wait("事件自由选角：当前可靠角色均缺少评分资料")
        }
        // Rank what is on screen by the role's own score and take the top one.
        //
        // This deliberately does not run the placement optimizer the three-card reward page uses.
        // That search enumerates team combinations per candidate, and this page shows a whole
        // roster rather than three cards, so the cost scales with the page. The list also only
        // offers roles this run has not got yet, so the question here is simply "which of these
        // is best", which the role score already answers. Repeated picks re-enter with the
        // previous choice marked selected/acquired, so multi-pick events walk down this same
        // ranking in order.
        val best = scorable.maxWith(
            compareBy<RankedVisibleRole> { it.score }
                .thenByDescending { it.match.screenRect.top }
                .thenByDescending { it.match.screenRect.left },
        )
        val card = best.match.screenRect
        val addRect = EntryPixelRect(
            left = card.left + (card.width * 0.05f).toInt(),
            top = card.top + (card.height * 0.72f).toInt(),
            width = (card.width * 0.22f).toInt().coerceAtLeast(4),
            height = (card.height * 0.24f).toInt().coerceAtLeast(4),
        )
        val runnerUp = scorable.filter { it !== best }.maxByOrNull(RankedVisibleRole::score)
        return LabyrinthRoleRewardChoiceDecision.Select(
            characterId = requireNotNull(best.match.characterId),
            displayName = best.profile.displayName,
            buttonRect = addRect,
            explanation = listOfNotNull(
                "当前页最高分：${best.profile.displayName} ${"%.1f".format(best.score)}",
                runnerUp?.let { "次选${it.profile.displayName} ${"%.1f".format(it.score)}" },
                "本页可识别未获得角色${scorable.size}名",
            ),
            actionSafe = true,
            safetyNote = null,
        )
    }

    private data class RankedVisibleRole(
        val match: LabyrinthBattleCharacterMatch,
        val profile: LabyrinthRoleProfile,
        val score: Double,
    )

    private fun LabyrinthOneRoleDecision.Ready.toSelection(
        candidates: List<LabyrinthCharacterMatch>,
        frameWidth: Int,
        frameHeight: Int,
        actionSafe: Boolean,
        safetyNote: String? = null,
    ): LabyrinthRoleRewardChoiceDecision {
        val chosenMatch = candidates.firstOrNull { match ->
            match.characterId?.let(::canonicalLabyrinthRoleId) == chosen.characterId
        } ?: return LabyrinthRoleRewardChoiceDecision.Wait("推荐角色不在当前可靠候选中")
        val slotIndex = SLOT_IDS.indexOf(chosenMatch.slotId)
        if (slotIndex < 0) return LabyrinthRoleRewardChoiceDecision.Wait("推荐角色槽位无法映射到选择按钮")
        val buttonRect = ReferenceFitMapper.map(
            frameWidth = frameWidth,
            frameHeight = frameHeight,
            referenceSize = STANDARD_REFERENCE,
            referenceRect = BUTTON_RECTS[slotIndex],
        ) ?: return LabyrinthRoleRewardChoiceDecision.Wait("角色选择按钮无法映射到当前画面")
        return LabyrinthRoleRewardChoiceDecision.Select(
            characterId = chosen.characterId,
            displayName = chosen.displayName,
            buttonRect = buttonRect,
            explanation = reasons,
            actionSafe = actionSafe,
            safetyNote = safetyNote,
        )
    }

    internal companion object {
        fun selectionButtonRect(slotId: String, frameWidth: Int, frameHeight: Int): EntryPixelRect? {
            val index = SLOT_IDS.indexOf(slotId)
            if (index < 0) return null
            return ReferenceFitMapper.map(frameWidth, frameHeight, STANDARD_REFERENCE, BUTTON_RECTS[index])
        }
        private val STANDARD_REFERENCE = EntryReferenceSize(1920, 1080)
        val SLOT_IDS = listOf("role_reward_left", "role_reward_center", "role_reward_right")
        val BUTTON_RECTS = listOf(
            EntryReferenceRect(260, 810, 290, 155),
            EntryReferenceRect(815, 810, 290, 155),
            EntryReferenceRect(1370, 810, 290, 155),
        )
    }
}

private val COMBINED_MEMBER_TO_CANONICAL_ROLE = mapOf(
    "1183" to "1807",
    "1184" to "1807",
    "1204" to "1808",
    "1205" to "1808",
    "1206" to "1808",
    "1217" to "1809",
    "1218" to "1809",
    "1243" to "1810",
    "1244" to "1810",
    "1281" to "1811",
    "1282" to "1811",
)

internal fun canonicalLabyrinthRoleId(characterId: String): String =
    COMBINED_MEMBER_TO_CANONICAL_ROLE[characterId] ?: characterId

private fun formatConfidence(value: Double): String = "%.3f".format(value)
