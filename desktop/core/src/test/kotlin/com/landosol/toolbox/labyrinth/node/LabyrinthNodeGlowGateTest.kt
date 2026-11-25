package com.landosol.toolbox.labyrinth.node

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LabyrinthNodeGlowGateTest {
    /** (score, sideScore, lowerScore, rowCoverage) as logged for EX战斗#40301, bundle 142732. */
    private val litNodeSamples = listOf(
        // The three frames the old gate happened to catch — the peak of the pulse.
        listOf(0.18, 0.14, 0.21, 0.53),
        listOf(0.17, 0.12, 0.21, 0.48),
        listOf(0.17, 0.12, 0.21, 0.50),
        // The eleven it rejected. Same node, same position, same run.
        listOf(0.07, 0.06, 0.07, 0.33),
        listOf(0.06, 0.06, 0.06, 0.40),
        listOf(0.05, 0.05, 0.06, 0.28),
        listOf(0.05, 0.05, 0.06, 0.33),
        listOf(0.05, 0.05, 0.05, 0.38),
        listOf(0.05, 0.05, 0.05, 0.40),
        listOf(0.05, 0.05, 0.04, 0.28),
        listOf(0.05, 0.05, 0.05, 0.28),
        listOf(0.05, 0.05, 0.04, 0.35),
        listOf(0.05, 0.05, 0.05, 0.45),
        listOf(0.04, 0.04, 0.05, 0.33),
        // Measured directly off the downloaded frame at the node's own rect (834,612): the old
        // gate missed this one on sideScore alone, by 0.0026.
        listOf(0.0776, 0.0574, 0.0929, 0.35),
    )

    /** The unlit crops of those same frames, plus the run's cyan-lit normal-battle node. */
    private val unlitSamples = listOf(
        listOf(0.00, 0.00, 0.00, 0.00),
        listOf(0.01, 0.00, 0.01, 0.00),
        listOf(0.02, 0.00, 0.03, 0.00),
        listOf(0.0089, 0.0, 0.0157, 0.0),
    )

    @Test fun `a lit node reads as selectable at every phase of its pulse`() {
        // 2026-09-20 bundle 142732: the node was on screen and lit for ten minutes, was found on
        // every frame, and was reported selectable on 3 of 14. A tap needs three consecutive
        // selectable frames, so the run never entered it and kept nudging the map instead.
        litNodeSamples.forEachIndexed { index, (score, side, lower, rows) ->
            assertTrue(
                "sample $index $score/$side/$lower/$rows must be selectable",
                labyrinthNodePurpleActivation(score, side, lower, rows),
            )
        }
    }

    @Test fun `unlit crops stay unselectable`() {
        // The gate moved down between the two populations, not into the unlit one.
        unlitSamples.forEachIndexed { index, (score, side, lower, rows) ->
            assertFalse(
                "unlit sample $index $score/$side/$lower/$rows must not be selectable",
                labyrinthNodePurpleActivation(score, side, lower, rows),
            )
        }
    }

    @Test fun `every threshold still separates the two populations with margin`() {
        val quietest = litNodeSamples.minOf { it[0] }
        val loudestUnlit = unlitSamples.maxOf { it[0] }
        assertTrue(
            "gate $LABYRINTH_PURPLE_ACTIVE_GLOW_MIN_RATIO must sit between $loudestUnlit and $quietest",
            LABYRINTH_PURPLE_ACTIVE_GLOW_MIN_RATIO in (loudestUnlit + 0.005)..(quietest - 0.001),
        )
        // All four still have to hold together; any one of them alone must not admit a node.
        assertFalse(labyrinthNodePurpleActivation(0.9, 0.0, 0.9, 0.9))
        assertFalse(labyrinthNodePurpleActivation(0.0, 0.9, 0.9, 0.9))
        assertFalse(labyrinthNodePurpleActivation(0.9, 0.9, 0.0, 0.9))
        assertFalse(labyrinthNodePurpleActivation(0.9, 0.9, 0.9, 0.0))
    }
}
