package com.landosol.toolbox.labyrinth

import com.landosol.toolbox.automation.AutomationSessionId
import com.landosol.toolbox.automation.AutomationAction
import com.landosol.toolbox.automation.ScreenPoint
import com.landosol.toolbox.labyrinth.vision.EntryPixelRect
import com.landosol.toolbox.labyrinth.vision.LABYRINTH_BATTLE_CHARACTER_SAFE_CONFIDENCE
import com.landosol.toolbox.labyrinth.vision.LabyrinthBattleCharacterMatch
import com.landosol.toolbox.labyrinth.vision.LabyrinthBattleElementFilter
import com.landosol.toolbox.labyrinth.vision.LabyrinthBattleFilterObservation
import com.landosol.toolbox.labyrinth.vision.LabyrinthBattleTeamObservation
import com.landosol.toolbox.labyrinth.vision.LabyrinthBattleTeamRecognitionState

enum class LabyrinthBattleTeamSelectionTargetKind {
    SELECT,
    DESELECT,
}

/** A recognized coordinate that a later execution stage may use. This class never executes it. */
data class LabyrinthBattleTeamSelectionTarget(
    val kind: LabyrinthBattleTeamSelectionTargetKind,
    val characterId: String,
    val displayName: String,
    val slotId: String,
    val screenRect: EntryPixelRect,
    val confidence: Double,
)

internal data class LabyrinthBossEditorPreparationDecision(
    val stage: LabyrinthBossEditorPreparationStage,
    val step: LabyrinthBattleTeamExecutionStep? = null,
)

/**
 * Clears inherited Boss teams before any recommendation is allowed to run.
 *
 * The game keeps Boss-editor teams between encounters. A role still parked on another tab cannot
 * be selected on the current tab, which used to deadlock later Bosses. Walk team 1 -> 2 -> 3,
 * remove every visible current-member card on each tab, then return to team 1. The bottom current-
 * member strip is authoritative and does not require a trusted character identity.
 */
internal fun labyrinthBossEditorPreparationDecision(
    observation: LabyrinthBattleTeamObservation,
    stage: LabyrinthBossEditorPreparationStage,
): LabyrinthBossEditorPreparationDecision {
    if (stage == LabyrinthBossEditorPreparationStage.INACTIVE ||
        stage == LabyrinthBossEditorPreparationStage.COMPLETE ||
        observation.recognitionState != LabyrinthBattleTeamRecognitionState.STABLE
    ) {
        return LabyrinthBossEditorPreparationDecision(stage)
    }
    val currentIndex = observation.bossTeamIndex
        ?: return LabyrinthBossEditorPreparationDecision(stage)

    fun tap(rect: EntryPixelRect) = AutomationAction.Tap(
        ScreenPoint(rect.left + rect.width / 2f, rect.top + rect.height / 2f),
    )

    fun switchTo(teamIndex: Int, nextStage: LabyrinthBossEditorPreparationStage) :
        LabyrinthBossEditorPreparationDecision {
        val tab = observation.bossTeamTabs.getOrNull(teamIndex - 1)
            ?: return LabyrinthBossEditorPreparationDecision(stage)
        return LabyrinthBossEditorPreparationDecision(
            stage = nextStage,
            step = LabyrinthBattleTeamExecutionStep(
                kind = LabyrinthBattleTeamExecutionKind.RESET_BOSS_SWITCH_TEAM,
                key = "boss-reset-tab:$currentIndex->$teamIndex:${observation.viewportRevision}",
                label = "Boss预处理：切换队伍$teamIndex",
                action = tap(tab),
            ),
        )
    }

    val targetIndex = when (stage) {
        LabyrinthBossEditorPreparationStage.TEAM_1 -> 1
        LabyrinthBossEditorPreparationStage.TEAM_2 -> 2
        LabyrinthBossEditorPreparationStage.TEAM_3 -> 3
        LabyrinthBossEditorPreparationStage.RETURN_TEAM_1 -> 1
        else -> return LabyrinthBossEditorPreparationDecision(stage)
    }
    if (currentIndex != targetIndex) {
        return switchTo(targetIndex, stage)
    }

    if (stage == LabyrinthBossEditorPreparationStage.RETURN_TEAM_1) {
        return LabyrinthBossEditorPreparationDecision(LabyrinthBossEditorPreparationStage.COMPLETE)
    }

    observation.selectedCharacters.firstOrNull()?.let { selected ->
        // Current-member slots are compacted left after every deselection.  That means a
        // successful tap on current_member_1 is commonly followed by the next member also
        // occupying current_member_1, while the roster viewport revision stays unchanged.
        // Include the authoritative member count so every successful removal produces a new
        // execution key; otherwise the executor mistakes the next member for a retry of the
        // previous toggle and eventually reports "no visual feedback".
        val selectedMemberCount = observation.selectedCharacters.size
        return LabyrinthBossEditorPreparationDecision(
            stage = stage,
            step = LabyrinthBattleTeamExecutionStep(
                kind = LabyrinthBattleTeamExecutionKind.RESET_BOSS_DESELECT_CHARACTER,
                key = "boss-reset-deselect:$targetIndex:$selectedMemberCount:${selected.slotId}:${observation.viewportRevision}",
                label = "Boss预处理：清空队伍${targetIndex}成员",
                action = tap(selected.screenRect),
            ),
        )
    }

    return when (stage) {
        LabyrinthBossEditorPreparationStage.TEAM_1 ->
            switchTo(2, LabyrinthBossEditorPreparationStage.TEAM_2)
        LabyrinthBossEditorPreparationStage.TEAM_2 ->
            switchTo(3, LabyrinthBossEditorPreparationStage.TEAM_3)
        LabyrinthBossEditorPreparationStage.TEAM_3 ->
            switchTo(1, LabyrinthBossEditorPreparationStage.RETURN_TEAM_1)
        else -> LabyrinthBossEditorPreparationDecision(stage)
    }
}

/** Diagnostic-only match which deliberately did not become an executable target. */
data class LabyrinthBattleTeamUnsafeMatch(
    val kind: LabyrinthBattleTeamSelectionTargetKind,
    val characterId: String,
    val displayName: String,
    val slotId: String,
    val screenRect: EntryPixelRect,
    val confidence: Double,
    val reason: String,
)

/**
 * Immutable dry-run diff between one recommendation and one stable team-editor viewport.
 * [isStillValid] is the future execution hand-off gate: a plan cannot survive a session,
 * recommendation, selected-team, filter, or viewport change.
 */
data class LabyrinthBattleTeamSelectionPlan(
    val sessionId: AutomationSessionId,
    val recommendedIds: List<String>,
    val recommendedNames: Map<String, String>,
    val currentlySelectedIds: List<String>,
    val currentlySelectedNames: Map<String, String>,
    val alreadyCorrectIds: List<String>,
    val needSelectIds: List<String>,
    val needDeselectIds: List<String>,
    val visibleSelectTargets: List<LabyrinthBattleTeamSelectionTarget>,
    val visibleDeselectTargets: List<LabyrinthBattleTeamSelectionTarget>,
    val unsafeVisibleMatches: List<LabyrinthBattleTeamUnsafeMatch>,
    val notCurrentlyVisibleIds: List<String>,
    val currentFilter: LabyrinthBattleElementFilter,
    val recommendedNextFilter: LabyrinthBattleElementFilter?,
    val recommendedFilterTarget: LabyrinthBattleFilterObservation?,
    /**
     * Attribute tabs that can still contain [notCurrentlyVisibleIds], most promising first and
     * always ending with the全部 tab. A single-attribute tab usually holds one or two rows, so
     * scanning there removes the multi-row ambiguity of the全部 list.
     */
    val missingTargetFilters: List<LabyrinthBattleElementFilter>,
    val scrollRequired: Boolean,
    val viewportRevision: Long,
    val readyToExecute: Boolean,
    val blockedReason: String?,
    val teamReady: Boolean,
    val minimumCharacterConfidence: Double,
    /**
     * Ordinary battles may keep the team the game already has: no member is added or removed, so
     * the plan collapses straight to "start battle". Defaults to false so every other caller
     * keeps the full diff behaviour.
     */
    val holdCurrentTeam: Boolean = false,
) {
    val dryRun: Boolean = false

    fun isStillValid(
        currentSessionId: AutomationSessionId,
        recommendation: LabyrinthBattleTeamRecommendation,
        observation: LabyrinthBattleTeamObservation,
    ): Boolean {
        if (!readyToExecute || currentSessionId != sessionId) return false
        if (observation.recognitionState != LabyrinthBattleTeamRecognitionState.STABLE) return false
        if (observation.viewportRevision != viewportRevision || observation.currentFilter != currentFilter) return false
        // When the team is deliberately held, [recommendedIds] mirrors the currently selected
        // members instead of the planner output, so comparing it against the recommendation would
        // fail every frame. The viewport, filter and selected-team checks still apply, so a team
        // change or page change invalidates the plan as before.
        if (!holdCurrentTeam) {
            val currentRecommendationIds = recommendation.members
                .map { canonicalLabyrinthRoleId(it.characterId) }
            if (currentRecommendationIds != recommendedIds) return false
        }
        val selectedIds = observation.effectiveSelectedCharacters().map { match ->
            match.characterId?.let(::canonicalLabyrinthRoleId) ?: return false
        }
        if (selectedIds != currentlySelectedIds) return false
        return (visibleSelectTargets + visibleDeselectTargets).all { target ->
            val source = when (target.kind) {
                LabyrinthBattleTeamSelectionTargetKind.SELECT -> observation.visibleCharacters
                // The bottom current-member strip is the authoritative team state and is directly
                // tappable.  Do not require the same character to also be visible in the roster:
                // doing so makes a one-for-one replacement scan unrelated element tabs merely to
                // remove an already-selected member.
                LabyrinthBattleTeamSelectionTargetKind.DESELECT -> observation.effectiveSelectedCharacters()
            }
            source.any { match ->
                match.slotId == target.slotId &&
                    match.characterId?.let(::canonicalLabyrinthRoleId) == target.characterId &&
                    match.screenRect == target.screenRect &&
                    match.confidence >= minimumCharacterConfidence
            }
        }
    }

    fun overlayLines(): List<String> = listOf(
        "自动编组：保持${alreadyCorrectIds.size} · 取消${needDeselectIds.size} · 选择${needSelectIds.size}",
        when {
            teamReady && holdCurrentTeam -> "普通战：保留当前队伍，直接开始战斗"
            teamReady -> "第一队已与推荐一致；下一步开始战斗"
            visibleSelectTargets.isNotEmpty() || visibleDeselectTargets.isNotEmpty() ->
                "计划点击：" + (visibleDeselectTargets + visibleSelectTargets).joinToString("、") {
                    (if (it.kind == LabyrinthBattleTeamSelectionTargetKind.SELECT) "选" else "取消") + it.displayName
                }
            else -> "当前无安全可见点击目标"
        },
        "下一筛选：${recommendedNextFilter?.label ?: "无"} · 滚动：${if (scrollRequired) "是" else "否"} · " +
            (blockedReason?.let { "阻塞：$it" } ?: "计划安全条件满足"),
    )

    /**
     * Running overlay is intentionally tiny so it does not cover the battle-team UI.
     * The full plan remains available through [overlayLines] while paused and [logText] in logs.
     */
    fun compactOverlayLines(): List<String> = listOf(
        "编组：命中${alreadyCorrectIds.size}/${recommendedIds.size} · 取消${needDeselectIds.size} · 待选${needSelectIds.size}",
        when {
            teamReady -> "下一步：第一队已就绪，开始战斗"
            visibleDeselectTargets.isNotEmpty() ->
                "下一步：取消 ${visibleDeselectTargets.first().displayName}"
            visibleSelectTargets.isNotEmpty() ->
                "下一步：选择 ${visibleSelectTargets.first().displayName}"
            recommendedNextFilter != null -> "下一步：切换 ${recommendedNextFilter.label}"
            scrollRequired -> "下一步：滚动寻找 ${notCurrentlyVisibleIds.size} 人"
            blockedReason != null -> "阻塞：${blockedReason.take(28)}"
            else -> "下一步：等待稳定识别"
        },
    )

    fun logText(): String = buildString {
        appendLine("推荐第一队：")
        recommendedIds.forEach { id -> appendLine("$id ${recommendedNames[id] ?: id}") }
        appendLine("当前已选：${describe(currentlySelectedIds, currentlySelectedNames)}")
        appendLine("保持：${describe(alreadyCorrectIds, recommendedNames)}")
        appendLine("计划取消：${describe(needDeselectIds, currentlySelectedNames)}")
        appendLine("计划选择：${describe(needSelectIds, recommendedNames)}")
        appendLine("当前可见计划取消：${describeTargets(visibleDeselectTargets)}")
        appendLine("当前可见计划选择：${describeTargets(visibleSelectTargets)}")
        if (unsafeVisibleMatches.isNotEmpty()) {
            appendLine(
                "低置信度疑似目标：" + unsafeVisibleMatches.joinToString("；") {
                    "${it.characterId} ${it.displayName} @${it.slotId} confidence=${formatSelectionConfidence(it.confidence)} ${it.reason}"
                },
            )
        }
        appendLine("尚未找到：${describe(notCurrentlyVisibleIds, recommendedNames)}")
        appendLine("当前筛选：${currentFilter.label}")
        appendLine("建议下一筛选：${recommendedNextFilter?.label ?: "无"}")
        appendLine(
            "尚未找到目标可用筛选：" +
                missingTargetFilters.joinToString("、", transform = LabyrinthBattleElementFilter::label)
                    .ifBlank { "无" },
        )
        appendLine("需要滚动：${if (scrollRequired) "是" else "否"}")
        appendLine("viewportRevision：$viewportRevision")
        append(
            when {
                teamReady && holdCurrentTeam -> "状态：普通战保留当前队伍，可开始战斗"
                teamReady -> "状态：第一队编组已与推荐一致，可开始战斗"
                readyToExecute -> "状态：自动编组计划安全条件满足"
                else -> "状态：BLOCKED，${blockedReason ?: "未知原因"}；不执行点击"
            },
        )
    }

    private fun describe(ids: List<String>, names: Map<String, String>): String =
        ids.joinToString(" / ") { id -> "$id ${names[id] ?: id}" }.ifBlank { "无" }

    private fun describeTargets(targets: List<LabyrinthBattleTeamSelectionTarget>): String =
        targets.joinToString("；") {
            "${it.characterId} ${it.displayName} @${it.slotId} " +
                "rect=${it.screenRect.left},${it.screenRect.top},${it.screenRect.width},${it.screenRect.height} " +
                "confidence=${formatSelectionConfidence(it.confidence)}"
        }.ifBlank { "无" }
}

enum class LabyrinthBattleTeamExecutionKind {
    DESELECT_CHARACTER,
    SELECT_CHARACTER,
    SELECT_FILTER,
    SCROLL_CHARACTERS,
    START_BATTLE,
    NEXT_BOSS_TEAM,
    RESET_BOSS_DESELECT_CHARACTER,
    RESET_BOSS_SWITCH_TEAM,
}

enum class LabyrinthBossEditorPreparationStage {
    INACTIVE,
    TEAM_1,
    TEAM_2,
    TEAM_3,
    RETURN_TEAM_1,
    COMPLETE,
}

internal data class LabyrinthBossTeamAdvanceDisposition(
    val committedIds: List<String> = emptyList(),
    val preparedSingleTeamIds: List<String> = emptyList(),
)

internal fun labyrinthBossTeamAdvanceDisposition(
    mode: LabyrinthBossTeamMode,
    confirmedTeamIds: List<String>,
): LabyrinthBossTeamAdvanceDisposition {
    val ids = confirmedTeamIds.distinct()
    return when (mode) {
        LabyrinthBossTeamMode.MULTI_TEAM -> LabyrinthBossTeamAdvanceDisposition(committedIds = ids)
        LabyrinthBossTeamMode.SINGLE_TEAM -> LabyrinthBossTeamAdvanceDisposition(preparedSingleTeamIds = ids)
    }
}

private fun LabyrinthBattleTeamObservation.effectiveSelectedCharacters(): List<LabyrinthBattleCharacterMatch> =
    selectedCharacters.distinctBy { match ->
        match.characterId?.let(::canonicalLabyrinthRoleId) ?: "slot:${match.slotId}"
    }

data class LabyrinthBattleTeamExecutionStep(
    val kind: LabyrinthBattleTeamExecutionKind,
    val key: String,
    val label: String,
    val action: AutomationAction,
    val confirmedTeamIds: List<String> = emptyList(),
    val scrollDirection: LabyrinthBattleRosterScrollDirection? = null,
    val scrollOriginPosition: Double? = null,
)

/** Converts one still-valid visual plan into exactly one guarded input action. */
fun labyrinthBattleTeamExecutionStep(
    plan: LabyrinthBattleTeamSelectionPlan,
    sessionId: AutomationSessionId,
    recommendation: LabyrinthBattleTeamRecommendation,
    observation: LabyrinthBattleTeamObservation,
    startButtonRect: EntryPixelRect?,
    frameWidth: Int,
    frameHeight: Int,
    scrollDirection: LabyrinthBattleRosterScrollDirection = LabyrinthBattleRosterScrollDirection.NEXT_PAGE,
): LabyrinthBattleTeamExecutionStep? {
    if (frameWidth <= 0 || frameHeight <= 0) return null
    if (!plan.isStillValid(sessionId, recommendation, observation)) return null

    fun tap(rect: EntryPixelRect): AutomationAction.Tap = AutomationAction.Tap(
        ScreenPoint(
            x = rect.left + rect.width / 2f,
            y = rect.top + rect.height / 2f,
        ),
    )

    if (plan.teamReady) {
        val bossTeam = observation.bossTeamIndex
        if (bossTeam != null && bossTeam < 3) {
            val tab = observation.bossTeamTabs.getOrNull(bossTeam) ?: return null
            return LabyrinthBattleTeamExecutionStep(
                kind = LabyrinthBattleTeamExecutionKind.NEXT_BOSS_TEAM,
                key = "boss-next:$bossTeam:${plan.currentlySelectedIds.joinToString(",")}",
                label = "Boss第${bossTeam}队编组完成，切换队伍${bossTeam + 1}",
                action = tap(tab),
                confirmedTeamIds = plan.recommendedIds,
            )
        }
        val rect = startButtonRect ?: return null
        return LabyrinthBattleTeamExecutionStep(
            kind = LabyrinthBattleTeamExecutionKind.START_BATTLE,
            key = "start:${plan.currentlySelectedIds.joinToString(",")}",
            label = "编组完成，开始战斗",
            action = tap(rect),
            confirmedTeamIds = plan.recommendedIds,
        )
    }
    plan.visibleDeselectTargets.firstOrNull()?.let { target ->
        return LabyrinthBattleTeamExecutionStep(
            kind = LabyrinthBattleTeamExecutionKind.DESELECT_CHARACTER,
            key = "deselect:${target.characterId}:${target.slotId}:${plan.viewportRevision}",
            label = "取消编组：${target.displayName}",
            action = tap(target.screenRect),
        )
    }
    plan.visibleSelectTargets.firstOrNull()?.let { target ->
        return LabyrinthBattleTeamExecutionStep(
            kind = LabyrinthBattleTeamExecutionKind.SELECT_CHARACTER,
            key = "select:${target.characterId}:${target.slotId}:${plan.viewportRevision}",
            label = "加入编组：${target.displayName}",
            action = tap(target.screenRect),
        )
    }
    plan.recommendedFilterTarget
        ?.takeIf { it.filter != observation.currentFilter }
        ?.let { target ->
            return LabyrinthBattleTeamExecutionStep(
                kind = LabyrinthBattleTeamExecutionKind.SELECT_FILTER,
                key = "filter:${target.filter.name}:${plan.viewportRevision}",
                label = "切换编组筛选：${target.filter.label}",
                action = tap(target.screenRect),
            )
        }
    if (plan.scrollRequired) {
        return labyrinthBattleRosterScrollStep(
            observation = observation,
            frameWidth = frameWidth,
            frameHeight = frameHeight,
            direction = scrollDirection,
        )
    }
    return null
}

private const val BATTLE_TEAM_SCROLL_DURATION_MILLIS = 360L

/**
 * After the current tab was scanned to its end without finding every missing role, pick the next
 * tab to search: the missing roles' own attribute tabs first, 全部 last, skipping the current tab
 * and tabs already exhausted for this missing-role set. Returns null when every candidate tab has
 * been scanned or its button is not visible.
 */
internal fun labyrinthBattleRosterFilterRecoveryTarget(
    plan: LabyrinthBattleTeamSelectionPlan,
    observation: LabyrinthBattleTeamObservation,
    exhaustedFilters: Set<LabyrinthBattleElementFilter>,
): LabyrinthBattleFilterObservation? = plan.missingTargetFilters
    .asSequence()
    .filterNot { it == observation.currentFilter || it in exhaustedFilters }
    .mapNotNull { candidate -> observation.filters.firstOrNull { it.filter == candidate } }
    .firstOrNull()

internal fun labyrinthBattleRosterFilterRecoveryStep(
    plan: LabyrinthBattleTeamSelectionPlan,
    observation: LabyrinthBattleTeamObservation,
    exhaustedFilters: Set<LabyrinthBattleElementFilter>,
): LabyrinthBattleTeamExecutionStep? {
    val target = labyrinthBattleRosterFilterRecoveryTarget(plan, observation, exhaustedFilters) ?: return null
    val missing = plan.notCurrentlyVisibleIds.joinToString("、") {
        plan.recommendedNames[it] ?: plan.currentlySelectedNames[it] ?: it
    }
    return LabyrinthBattleTeamExecutionStep(
        kind = LabyrinthBattleTeamExecutionKind.SELECT_FILTER,
        key = "filter-recovery:${target.filter.name}:${observation.viewportRevision}",
        label = "${observation.currentFilter.label}筛选已扫到底仍未找到$missing；切换${target.filter.label}继续查找",
        action = AutomationAction.Tap(
            ScreenPoint(
                target.screenRect.left + target.screenRect.width / 2f,
                target.screenRect.top + target.screenRect.height / 2f,
            ),
        ),
    )
}

internal fun labyrinthBattleRosterScrollStep(
    observation: LabyrinthBattleTeamObservation,
    frameWidth: Int,
    frameHeight: Int,
    direction: LabyrinthBattleRosterScrollDirection,
    keyPrefix: String = "scroll",
    labelPrefix: String? = null,
): LabyrinthBattleTeamExecutionStep? {
    if (frameWidth <= 0 || frameHeight <= 0 ||
        observation.recognitionState != LabyrinthBattleTeamRecognitionState.STABLE ||
        observation.currentFilter == LabyrinthBattleElementFilter.UNKNOWN
    ) return null
    // Keep at least one card-height of overlap, particularly in the short Boss viewport.
    // Returning to the top reverses the same in-roster gesture; neither end crosses tabs.
    val upper = ScreenPoint(frameWidth * 0.75f, frameHeight * if (observation.bossTeamIndex != null) 0.50f else 0.43f)
    val lower = ScreenPoint(frameWidth * 0.75f, frameHeight * 0.67f)
    val toTop = direction == LabyrinthBattleRosterScrollDirection.TO_TOP
    val defaultLabel = if (toTop) "下滑角色列表，返回顶部查找" else "上滑角色列表，查找下方角色"
    return LabyrinthBattleTeamExecutionStep(
        kind = LabyrinthBattleTeamExecutionKind.SCROLL_CHARACTERS,
        key = "$keyPrefix:${direction.name}:${observation.currentFilter.name}:${observation.viewportRevision}",
        label = labelPrefix?.let { prefix ->
            "$prefix：${if (toTop) "返回顶部" else "继续向下"}"
        } ?: defaultLabel,
        action = AutomationAction.Swipe(
            start = if (toTop) upper else lower,
            end = if (toTop) lower else upper,
            durationMillis = BATTLE_TEAM_SCROLL_DURATION_MILLIS,
        ),
        scrollDirection = direction,
        scrollOriginPosition = observation.scrollbar.position.takeIf { it.isFinite() && it in 0.0..1.0 } ?: 0.5,
    )
}

/**
 * Multi-team mode may intentionally stop at one or two safe teams. The remaining Boss tabs must
 * stay empty; only team 3 exposes Start, so walk empty tabs without asking the team recommender to
 * invent an unsafe extra formation.
 */
internal fun labyrinthBossPartialMultiTeamExecutionStep(
    observation: LabyrinthBattleTeamObservation,
    completedTeamCount: Int,
    startButtonRect: EntryPixelRect?,
): LabyrinthBattleTeamExecutionStep? {
    if (completedTeamCount !in 1..2 ||
        observation.recognitionState != LabyrinthBattleTeamRecognitionState.STABLE ||
        observation.selectedCharacters.isNotEmpty()
    ) return null
    val index = observation.bossTeamIndex ?: return null
    if (index <= completedTeamCount || index !in 2..3) return null
    val rect = (if (index < 3) observation.bossTeamTabs.getOrNull(2) else startButtonRect)
        ?: return null
    val start = index == 3
    return LabyrinthBattleTeamExecutionStep(
        kind = if (start) LabyrinthBattleTeamExecutionKind.START_BATTLE else LabyrinthBattleTeamExecutionKind.NEXT_BOSS_TEAM,
        key = "boss-partial:$completedTeamCount:$index:${observation.viewportRevision}",
        label = if (start) {
            "Boss仅编组${completedTeamCount}队，其余留空，开始挑战"
        } else {
            "Boss仅编组${completedTeamCount}队，队伍${index}留空，切换队伍3"
        },
        action = AutomationAction.Tap(ScreenPoint(rect.left + rect.width / 2f, rect.top + rect.height / 2f)),
        confirmedTeamIds = emptyList(),
    )
}

/** Single-team first attempt: teams two/three may be empty; only the third tab owns Start. */
internal fun labyrinthBossSingleTeamExecutionStep(
    observation: LabyrinthBattleTeamObservation,
    confirmedFirstTeamIds: List<String>,
    startButtonRect: EntryPixelRect?,
): LabyrinthBattleTeamExecutionStep? {
    if (confirmedFirstTeamIds.size !in 1..5 || confirmedFirstTeamIds.distinct().size != confirmedFirstTeamIds.size ||
        observation.recognitionState != LabyrinthBattleTeamRecognitionState.STABLE ||
        observation.selectedCharacters.isNotEmpty()) return null
    val index = observation.bossTeamIndex ?: return null
    val rect = when (index) {
        2 -> observation.bossTeamTabs.getOrNull(2)
        3 -> startButtonRect
        else -> null
    } ?: return null
    return LabyrinthBattleTeamExecutionStep(
        kind = if (index == 2) LabyrinthBattleTeamExecutionKind.NEXT_BOSS_TEAM else LabyrinthBattleTeamExecutionKind.START_BATTLE,
        key = "boss-single:$index:${confirmedFirstTeamIds.joinToString(",")}",
        label = if (index == 2) "第二队留空，切换第三队" else "第一队已确认，开始Boss单队首战",
        action = AutomationAction.Tap(ScreenPoint(rect.left + rect.width / 2f, rect.top + rect.height / 2f)),
        confirmedTeamIds = confirmedFirstTeamIds,
    )
}

/** Only the saved opt-in flag may request the ordinary-battle lineup shortcut. */
internal fun labyrinthRequestsCurrentNormalBattleTeam(
    settings: LabyrinthStrategySettings,
    kind: LabyrinthCombatKind?,
    retryCount: Int,
): Boolean = settings.normalBattleSkipTeamSelectionEnabled &&
    kind == LabyrinthCombatKind.NORMAL && retryCount == 0

/**
 * Whether an ordinary battle keeps the team the game already shows instead of re-selecting the
 * recommendation. Requires a full five-slot strip with every slot identified: a short strip needs
 * filling, and an unidentified slot could be anyone.
 */
internal fun labyrinthHoldsCurrentTeam(
    requested: Boolean,
    selectedSlotCount: Int,
    identifiedSelectedCount: Int,
): Boolean = requested && selectedSlotCount >= LABYRINTH_TEAM_SIZE && identifiedSelectedCount == selectedSlotCount

private const val LABYRINTH_TEAM_SIZE = 5

class LabyrinthBattleTeamSelectionPlanner(
    private val profiles: Map<String, LabyrinthRoleProfile>,
    private val minimumCharacterConfidence: Double = LABYRINTH_BATTLE_CHARACTER_SAFE_CONFIDENCE,
) {
    init {
        require(minimumCharacterConfidence in 0.0..1.0)
    }

    /**
     * @param exhaustedFilters tabs already scanned to their end for this recommendation without
     * finding the still-missing roles. They are skipped when choosing the next tab so the search
     * walks each attribute tab once and then 全部, instead of bouncing back to a covered tab.
     */
    fun plan(
        sessionId: AutomationSessionId,
        recommendation: LabyrinthBattleTeamRecommendation,
        observation: LabyrinthBattleTeamObservation,
        exhaustedFilters: Set<LabyrinthBattleElementFilter> = emptySet(),
        holdCurrentTeamRequested: Boolean = false,
    ): LabyrinthBattleTeamSelectionPlan {
        // The current-member strip is authoritative. Favourite stars and bright portrait art can
        // resemble the large yellow roster checkmark, so roster-card marker guesses must never
        // create or remove team membership by themselves.
        val selectedMatches = observation.effectiveSelectedCharacters()
        val selectedIds = selectedMatches.mapNotNull { it.characterId?.let(::canonicalLabyrinthRoleId) }
        val selectedNames = selectedMatches.mapNotNull { match ->
            match.characterId?.let(::canonicalLabyrinthRoleId)?.let { id ->
                id to (match.displayName ?: profiles[id]?.displayName ?: id)
            }
        }.toMap()
        // Ordinary battle shortcut: treat the current members as the recommendation so the diff
        // is empty and the plan goes straight to "start battle" — but only when the team is
        // already full (the game caps a team at 5) and every member of the strip is identified.
        // A partial or unreadable strip still goes through the normal diff, so a battle can never
        // start on an unknown or short team.
        val holdCurrentTeam = labyrinthHoldsCurrentTeam(
            requested = holdCurrentTeamRequested,
            selectedSlotCount = selectedMatches.size,
            identifiedSelectedCount = selectedIds.size,
        )
        val recommendedIds = if (holdCurrentTeam) {
            selectedIds
        } else {
            recommendation.members.map { canonicalLabyrinthRoleId(it.characterId) }
        }
        val recommendedNames = if (holdCurrentTeam) {
            selectedNames
        } else {
            recommendation.members.associate { member ->
                canonicalLabyrinthRoleId(member.characterId) to member.displayName
            }
        }
        val selectedSet = selectedIds.toSet()
        val recommendedSet = recommendedIds.toSet()
        val alreadyCorrectIds = recommendedIds.filter(selectedSet::contains)
        val needSelectIds = recommendedIds.filterNot(selectedSet::contains)
        val needDeselectIds = selectedIds.distinct().filterNot(recommendedSet::contains)

        val visibleByCanonicalId = observation.visibleCharacters
            .mapNotNull { match -> match.characterId?.let(::canonicalLabyrinthRoleId)?.let { it to match } }
            .groupBy(Pair<String, LabyrinthBattleCharacterMatch>::first)
            .mapValues { (_, values) -> values.map(Pair<String, LabyrinthBattleCharacterMatch>::second) }
        val visibleSelectTargets = mutableListOf<LabyrinthBattleTeamSelectionTarget>()
        val visibleDeselectTargets = mutableListOf<LabyrinthBattleTeamSelectionTarget>()
        val unsafeMatches = mutableListOf<LabyrinthBattleTeamUnsafeMatch>()
        val notCurrentlyVisibleIds = mutableListOf<String>()

        needSelectIds.forEach { id ->
            val candidates = visibleByCanonicalId[id].orEmpty()
            val best = candidates.maxByOrNull(LabyrinthBattleCharacterMatch::confidence)
            when {
                best == null -> notCurrentlyVisibleIds += id
                best.confidence < minimumCharacterConfidence -> unsafeMatches += best.toUnsafeMatch(
                    kind = LabyrinthBattleTeamSelectionTargetKind.SELECT,
                    canonicalId = id,
                    fallbackName = recommendedNames[id] ?: id,
                    reason = "低于安全阈值${formatSelectionConfidence(minimumCharacterConfidence)}",
                )
                else -> visibleSelectTargets += best.toSelectionTarget(
                    kind = LabyrinthBattleTeamSelectionTargetKind.SELECT,
                    canonicalId = id,
                    fallbackName = recommendedNames[id] ?: id,
                )
            }
        }
        val selectedByCanonicalId = selectedMatches
            .mapNotNull { match -> match.characterId?.let(::canonicalLabyrinthRoleId)?.let { it to match } }
            .groupBy(Pair<String, LabyrinthBattleCharacterMatch>::first)
            .mapValues { (_, values) -> values.map(Pair<String, LabyrinthBattleCharacterMatch>::second) }
        needDeselectIds.forEach { id ->
            val best = selectedByCanonicalId[id].orEmpty().maxByOrNull(LabyrinthBattleCharacterMatch::confidence)
            when {
                // Membership came from this same authoritative strip, so a missing executable
                // match is unsafe rather than a reason to search the scrollable roster.
                best == null -> Unit
                best.confidence < minimumCharacterConfidence -> unsafeMatches += best.toUnsafeMatch(
                    kind = LabyrinthBattleTeamSelectionTargetKind.DESELECT,
                    canonicalId = id,
                    fallbackName = selectedNames[id] ?: id,
                    reason = "低于安全阈值${formatSelectionConfidence(minimumCharacterConfidence)}",
                )
                else -> visibleDeselectTargets += best.toSelectionTarget(
                    kind = LabyrinthBattleTeamSelectionTargetKind.DESELECT,
                    canonicalId = id,
                    fallbackName = selectedNames[id] ?: id,
                )
            }
        }

        val missingTargetFilters = missingTargetFilters(notCurrentlyVisibleIds)
        val recommendedNextFilter = recommendNextFilter(
            // Element filters and roster scrolling exist only to find characters that still need
            // to be added. Characters that need removal are handled from the current-member strip.
            needSelectIds = needSelectIds,
            visibleSelectIds = visibleSelectTargets.map(LabyrinthBattleTeamSelectionTarget::characterId).toSet(),
            notCurrentlyVisibleIds = notCurrentlyVisibleIds,
            missingTargetFilters = missingTargetFilters,
            exhaustedFilters = exhaustedFilters,
            observation = observation,
        )
        val recommendedFilterTarget = recommendedNextFilter
            ?.takeIf { it != observation.currentFilter }
            ?.let { target -> observation.filters.firstOrNull { it.filter == target } }
        // A confirmed roster page is scroll-capable even when the blue scrollbar is not detected.
        // Treat the scrollbar as boundary evidence, not as permission to issue the gesture.
        val scrollRequired = recommendedNextFilter == observation.currentFilter &&
            notCurrentlyVisibleIds.any { id -> currentFilterMayContain(observation.currentFilter, id) }

        val blockReasons = buildList {
            if (observation.recognitionState == LabyrinthBattleTeamRecognitionState.WAITING_FOR_STABILITY) {
                add("角色列表变化中，等待稳定")
            }
            if (recommendedIds.size !in 1..5 || recommendedIds.distinct().size != recommendedIds.size) {
                add("推荐角色canonical后不是1至5个不同角色")
            }
            selectedMatches.filter { it.characterId == null }.takeIf { it.isNotEmpty() }?.let { unknown ->
                val slots = unknown.joinToString("、") { "成员" + it.slotId.substringAfterLast('_') }
                add("下方当前成员头像未确认（$slots），请检查识别框是否贴合头像")
            }
            if (selectedIds.size != selectedIds.distinct().size) add("下方当前成员canonical后存在重复")
            if (selectedMatches.size > 5) add("下方当前成员超过5人")
            selectedMatches.filter { it.characterId != null && it.confidence < minimumCharacterConfidence }
                .takeIf(List<LabyrinthBattleCharacterMatch>::isNotEmpty)
                ?.let { add("下方当前成员存在低置信度识别") }
            if (observation.currentFilter == LabyrinthBattleElementFilter.UNKNOWN) add("当前属性筛选识别不明")
            if (unsafeMatches.isNotEmpty()) add("目标角色存在低置信度视觉识别，不生成可执行点击目标")
            if (recommendedNextFilter != null &&
                recommendedNextFilter != observation.currentFilter &&
                recommendedFilterTarget == null
            ) {
                add("未识别到建议筛选${recommendedNextFilter.label}的按钮位置")
            }
            if (recommendedNextFilter == LabyrinthBattleElementFilter.EFFECTIVE_EFFECT) {
                add("普通首战禁止规划有效效果筛选")
            }
            if (needSelectIds.isNotEmpty() &&
                visibleSelectTargets.isEmpty() &&
                !scrollRequired &&
                (recommendedNextFilter == null || recommendedNextFilter == observation.currentFilter)
            ) {
                add("当前筛选下未找到目标角色，且没有安全的筛选或滚动步骤")
            }
        }.distinct()
        val readyToExecute = blockReasons.isEmpty()
        val teamReady = readyToExecute && needSelectIds.isEmpty() && needDeselectIds.isEmpty()
        return LabyrinthBattleTeamSelectionPlan(
            sessionId = sessionId,
            recommendedIds = recommendedIds,
            recommendedNames = recommendedNames,
            currentlySelectedIds = selectedIds,
            currentlySelectedNames = selectedNames,
            alreadyCorrectIds = alreadyCorrectIds,
            needSelectIds = needSelectIds,
            needDeselectIds = needDeselectIds,
            visibleSelectTargets = visibleSelectTargets,
            visibleDeselectTargets = visibleDeselectTargets,
            unsafeVisibleMatches = unsafeMatches,
            notCurrentlyVisibleIds = notCurrentlyVisibleIds,
            currentFilter = observation.currentFilter,
            recommendedNextFilter = recommendedNextFilter,
            recommendedFilterTarget = recommendedFilterTarget,
            missingTargetFilters = missingTargetFilters,
            scrollRequired = scrollRequired,
            viewportRevision = observation.viewportRevision,
            readyToExecute = readyToExecute,
            blockedReason = blockReasons.joinToString("；").ifBlank { null },
            teamReady = teamReady,
            minimumCharacterConfidence = minimumCharacterConfidence,
            holdCurrentTeam = holdCurrentTeam,
        )
    }

    private fun recommendNextFilter(
        needSelectIds: List<String>,
        visibleSelectIds: Set<String>,
        notCurrentlyVisibleIds: List<String>,
        missingTargetFilters: List<LabyrinthBattleElementFilter>,
        exhaustedFilters: Set<LabyrinthBattleElementFilter>,
        observation: LabyrinthBattleTeamObservation,
    ): LabyrinthBattleElementFilter? {
        if (needSelectIds.isEmpty()) return null
        val current = observation.currentFilter
        if (current !in setOf(LabyrinthBattleElementFilter.UNKNOWN, LabyrinthBattleElementFilter.EFFECTIVE_EFFECT) &&
            visibleSelectIds.isNotEmpty()
        ) {
            return current
        }
        // Prefer grouping the search by the attribute of the roles that still have to be found.
        // A single-attribute tab is usually one or two rows, so it removes the multi-row ambiguity
        // of the 全部 list; only an unknown attribute forces 全部.
        val searchIds = notCurrentlyVisibleIds.ifEmpty { needSelectIds }
        if (searchIds.any { profiles[it]?.attribute == null }) return LabyrinthBattleElementFilter.ALL
        val candidates = if (notCurrentlyVisibleIds.isNotEmpty()) {
            missingTargetFilters
        } else {
            missingTargetFilters(needSelectIds)
        }
        // Stay on the current attribute tab while it can still hold a missing role and has not
        // already been scanned to its end; otherwise take the next unexhausted candidate, ending
        // with 全部. When every candidate is exhausted, fall back to 全部 so the caller can report
        // the full scan instead of deadlocking on an empty choice.
        val next = when {
            current in ELEMENT_FILTERS && current in candidates && current !in exhaustedFilters -> current
            else -> candidates.firstOrNull { it !in exhaustedFilters } ?: LabyrinthBattleElementFilter.ALL
        }
        return next.takeUnless { it == LabyrinthBattleElementFilter.EFFECTIVE_EFFECT }
            ?: LabyrinthBattleElementFilter.ALL
    }

    /**
     * Ordered recovery tabs for roles that were not found on the current tab:每个缺失角色的属性页
     * first (most missing roles first), then全部 as the last resort.
     */
    private fun missingTargetFilters(notCurrentlyVisibleIds: List<String>): List<LabyrinthBattleElementFilter> {
        val attributeTabs = notCurrentlyVisibleIds
            .mapNotNull { id -> profiles[id]?.attribute?.toBattleFilter() }
            .filter { it in ELEMENT_FILTERS }
            .groupingBy { it }
            .eachCount()
            .entries
            .sortedWith(
                compareByDescending<Map.Entry<LabyrinthBattleElementFilter, Int>> { it.value }
                    .thenBy { it.key.ordinal },
            )
            .map { it.key }
        return (attributeTabs + LabyrinthBattleElementFilter.ALL).distinct()
    }

    private fun currentFilterMayContain(filter: LabyrinthBattleElementFilter, characterId: String): Boolean = when (filter) {
        LabyrinthBattleElementFilter.ALL -> true
        LabyrinthBattleElementFilter.EFFECTIVE_EFFECT,
        LabyrinthBattleElementFilter.UNKNOWN,
        -> false
        else -> profiles[characterId]?.attribute?.toBattleFilter() == filter
    }

    private fun LabyrinthCharacterAttribute.toBattleFilter(): LabyrinthBattleElementFilter = when (this) {
        LabyrinthCharacterAttribute.FIRE -> LabyrinthBattleElementFilter.FIRE
        LabyrinthCharacterAttribute.WATER -> LabyrinthBattleElementFilter.WATER
        LabyrinthCharacterAttribute.WIND -> LabyrinthBattleElementFilter.WIND
        LabyrinthCharacterAttribute.LIGHT -> LabyrinthBattleElementFilter.LIGHT
        LabyrinthCharacterAttribute.DARK -> LabyrinthBattleElementFilter.DARK
        LabyrinthCharacterAttribute.UNKNOWN -> LabyrinthBattleElementFilter.ALL
    }

    private fun LabyrinthBattleCharacterMatch.toSelectionTarget(
        kind: LabyrinthBattleTeamSelectionTargetKind,
        canonicalId: String,
        fallbackName: String,
    ) = LabyrinthBattleTeamSelectionTarget(
        kind = kind,
        characterId = canonicalId,
        displayName = displayName ?: fallbackName,
        slotId = slotId,
        screenRect = screenRect,
        confidence = confidence,
    )

    private fun LabyrinthBattleCharacterMatch.toUnsafeMatch(
        kind: LabyrinthBattleTeamSelectionTargetKind,
        canonicalId: String,
        fallbackName: String,
        reason: String,
    ) = LabyrinthBattleTeamUnsafeMatch(
        kind = kind,
        characterId = canonicalId,
        displayName = displayName ?: fallbackName,
        slotId = slotId,
        screenRect = screenRect,
        confidence = confidence,
        reason = reason,
    )

    private companion object {
        val ELEMENT_FILTERS = setOf(
            LabyrinthBattleElementFilter.FIRE,
            LabyrinthBattleElementFilter.WATER,
            LabyrinthBattleElementFilter.WIND,
            LabyrinthBattleElementFilter.LIGHT,
            LabyrinthBattleElementFilter.DARK,
        )
    }
}

private fun formatSelectionConfidence(value: Double): String = "%.3f".format(value)
