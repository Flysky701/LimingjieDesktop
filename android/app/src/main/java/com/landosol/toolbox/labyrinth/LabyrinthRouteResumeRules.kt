package com.landosol.toolbox.labyrinth

import com.landosol.toolbox.labyrinth.node.LabyrinthNodeTypes
import com.landosol.toolbox.labyrinth.node.NodeSessionState
import com.landosol.toolbox.labyrinth.vision.EntryAnchorId
import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryFrameResult
import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryPageState

/** Creating a route session on the first map glimpse does not prove opening selection is done. */
internal fun labyrinthRouteHasPassedOpening(
    state: NodeSessionState?,
    // A dispatched node move is already beyond opening, even before its destination confirms
    // the persisted cursor. This also protects a picker opened by the very first event node.
    hasNodeTransition: Boolean = false,
): Boolean = hasNodeTransition ||
    (state != null && (state.currentArea > 1 || state.visitedNodes.size > 1))

/** A real opening roster must reclaim the entry flow even after a premature map handoff. */
internal fun labyrinthShouldResumeOpeningSelection(
    entryPhaseComplete: Boolean,
    result: LabyrinthEntryFrameResult,
    /**
     * Whether the route has progressed beyond the first area's start. The opening selector, a shop 选择印记 pick and an
     * event free pick are the *same* page with the same header, so the header alone cannot tell
     * them apart -- and only the first of the three belongs to the entry planner.
     *
     * 2026-09-22 bundle 173753: a mid-route imprint picker matched this predicate, the run reset
     * itself to the entry phase, and the entry planner then stared at a picker it has no rule for
     * while nothing else could own the frame. 599 frames / 408 s, actionCount frozen at 379.
     */
    routeActive: Boolean = false,
): Boolean = entryPhaseComplete &&
    !routeActive &&
    result.observation.state == LabyrinthEntryPageState.INITIAL_CHARACTER_SELECTION &&
    !labyrinthIsRoleRewardPage(result) &&
    maxOf(
        result.observation.anchorScores[EntryAnchorId.SELECTION_HEADER],
        result.observation.anchorScores[EntryAnchorId.SELECTION_HEADER_STANDARD],
    ) >= 0.85

/**
 * The node session advances the persisted cursor to the next area's START as soon as an area Boss
 * is entered. After an app restart the game may still be on that Boss challenge/team page, so the
 * immediately preceding selected-route Boss is still the active encounter until a later node is
 * confirmed.
 */
internal fun labyrinthPersistedCurrentNode(route: LabyrinthRouteJson?): LabyrinthNodeJson? {
    route ?: return null
    val currentBlockId = route.currentBlockId ?: return null
    return route.nodes.firstOrNull { it.blockId == currentBlockId }
}

internal fun labyrinthPersistedBossNode(route: LabyrinthRouteJson?): LabyrinthNodeJson? {
    route ?: return null
    val current = labyrinthPersistedCurrentNode(route) ?: return null
    val currentIndex = route.nodes.indexOfFirst { it.blockId == current.blockId }
    if (currentIndex < 0) return null
    if (current.blockType == LabyrinthNodeTypes.BOSS) return current
    if (current.blockType != LabyrinthNodeTypes.START || currentIndex == 0) return null
    val previous = route.nodes[currentIndex - 1]
    return previous.takeIf {
        it.blockType == LabyrinthNodeTypes.BOSS && it.area + 1 == current.area
    }
}

internal fun labyrinthBossEncounterStrategy(route: LabyrinthRouteJson?): LabyrinthExEncounterStrategy? {
    val boss = labyrinthPersistedBossNode(route) ?: return null
    val unitId = LabyrinthBossCatalog.unitIdForQuest(boss.area, boss.questId)
    return LabyrinthBossEncounterCatalog.forUnitId(unitId)
}
