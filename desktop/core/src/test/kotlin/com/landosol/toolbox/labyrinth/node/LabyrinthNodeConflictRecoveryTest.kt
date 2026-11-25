package com.landosol.toolbox.labyrinth.node

import com.landosol.toolbox.labyrinth.vision.EntryPixelRect
import org.junit.Assert.assertEquals
import org.junit.Test

class LabyrinthNodeConflictRecoveryTest {
    private val rect = EntryPixelRect(800, 120, 280, 350)
    private val visible = listOf(NodeClassification(2, 1, LabyrinthNodeTypes.RELIC, 0.92, true, rect))

    @Test fun `single visible route target can recover any semantic conflict after three stable frames`() {
        for ((expected, detected) in listOf(
            LabyrinthNodeTypes.LINK to LabyrinthNodeTypes.RELIC,
            LabyrinthNodeTypes.EVENT to LabyrinthNodeTypes.NORMAL_BATTLE,
            LabyrinthNodeTypes.EX_BATTLE to LabyrinthNodeTypes.RELIC,
            LabyrinthNodeTypes.SHOP to LabyrinthNodeTypes.EVENT,
        )) {
            val recovery = LabyrinthNodeConflictRecovery()
            val conflict = NodeAction.TypeConflict(
                20603, expected, detected, rect, 0.92, 0.85, NodeTopologyBindingKind.SINGLE_TARGET,
            )
            val frame = visible.map { it.copy(blockType = detected) }
            repeat(2) {
                assertEquals(conflict, recovery.resolve("session", conflict, frame, uniqueReachableRouteTarget = true))
            }
            assertEquals(
                NodeAction.ClickNode(20603, expected, rect),
                recovery.resolve("session", conflict, frame, uniqueReachableRouteTarget = true),
            )
        }
    }

    @Test fun `ordered topology can recover target even when other active nodes are visible`() {
        val recovery = LabyrinthNodeConflictRecovery()
        val conflict = NodeAction.TypeConflict(
            20603, LabyrinthNodeTypes.EVENT, LabyrinthNodeTypes.NORMAL_BATTLE,
            rect, 0.91, 0.86, NodeTopologyBindingKind.FULL_COLUMN,
        )
        val other = NodeClassification(
            3, 1, LabyrinthNodeTypes.RELIC, 0.93, true, EntryPixelRect(1180, 470, 280, 350),
        )
        // The current crop must agree with the conflict's detected type, not a nearby/other label.
        val current = visible.map { it.copy(blockType = LabyrinthNodeTypes.NORMAL_BATTLE) }
        repeat(2) { assertEquals(conflict, recovery.resolve("session", conflict, current + other)) }
        assertEquals(
            NodeAction.ClickNode(20603, LabyrinthNodeTypes.EVENT, rect),
            recovery.resolve("session", conflict, current + other),
        )
    }

    @Test fun `single target ambiguity weak evidence and inactive crop do not authorize click`() {
        val recovery = LabyrinthNodeConflictRecovery()
        val base = NodeAction.TypeConflict(
            20603, LabyrinthNodeTypes.LINK, LabyrinthNodeTypes.RELIC,
            rect, 0.92, 0.85, NodeTopologyBindingKind.SINGLE_TARGET,
        )
        val ambiguous = visible + visible[0].copy(screenRect = rect.copy(top = 500))
        repeat(8) { assertEquals(base, recovery.resolve("a", base, ambiguous, uniqueReachableRouteTarget = true)) }
        repeat(8) {
            assertEquals(base, recovery.resolve("b", base, visible.map { it.copy(isClickable = false) }, uniqueReachableRouteTarget = true))
        }
        val weak = base.copy(confidence = 0.60, topologyConfidence = 0.10)
        repeat(8) { assertEquals(weak, recovery.resolve("c", weak, visible, uniqueReachableRouteTarget = true)) }
    }

    @Test fun `single target evidence cannot override semantics on a branching route`() {
        val recovery = LabyrinthNodeConflictRecovery()
        val conflict = NodeAction.TypeConflict(
            20603, LabyrinthNodeTypes.EVENT, LabyrinthNodeTypes.NORMAL_BATTLE,
            rect, 0.94, 0.88, NodeTopologyBindingKind.SINGLE_TARGET,
        )
        val frame = visible.map { it.copy(blockType = LabyrinthNodeTypes.NORMAL_BATTLE) }
        repeat(10) {
            assertEquals(conflict, recovery.resolve("branch", conflict, frame, uniqueReachableRouteTarget = false))
        }
    }

    @Test fun `small crop jitter preserves stability but session or missing frame resets it`() {
        val recovery = LabyrinthNodeConflictRecovery()
        val conflict = NodeAction.TypeConflict(
            20603, LabyrinthNodeTypes.LINK, LabyrinthNodeTypes.RELIC,
            rect, 0.92, 0.85, NodeTopologyBindingKind.SINGLE_TARGET,
        )
        assertEquals(conflict, recovery.resolve("a", conflict, visible, uniqueReachableRouteTarget = true))
        val shiftedRect = rect.copy(left = rect.left + 12, top = rect.top + 9)
        val shiftedConflict = conflict.copy(screenRect = shiftedRect)
        val shiftedVisible = visible.map { it.copy(screenRect = shiftedRect) }
        assertEquals(shiftedConflict, recovery.resolve("a", shiftedConflict, shiftedVisible, uniqueReachableRouteTarget = true))
        assertEquals(
            NodeAction.ClickNode(20603, LabyrinthNodeTypes.LINK, shiftedRect),
            recovery.resolve("a", shiftedConflict, shiftedVisible, uniqueReachableRouteTarget = true),
        )

        recovery.reset()
        repeat(2) {
            assertEquals(conflict, recovery.resolve("a", conflict, visible, uniqueReachableRouteTarget = true))
        }
        assertEquals(conflict, recovery.resolve("b", conflict, visible, uniqueReachableRouteTarget = true))
        recovery.resolve("b", conflict, emptyList())
        assertEquals(conflict, recovery.resolve("b", conflict, visible, uniqueReachableRouteTarget = true))
    }
}
