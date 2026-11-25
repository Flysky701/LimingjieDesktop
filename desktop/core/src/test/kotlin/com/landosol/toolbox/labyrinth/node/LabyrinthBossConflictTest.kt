package com.landosol.toolbox.labyrinth.node

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LabyrinthBossConflictTest {
    @Test fun `the boss platform is never waived as template noise`() {
        // 2026-09-19 bundle 222537: the route wanted 商店#30601 and the only crop on screen was the
        // area's Boss platform, bound FULL_COLUMN at 0.99 because a one-node logical column matches
        // a single detection trivially. The ordered-topology override waived the conflict, so the
        // run tapped the Boss platform and waited for a movement dialog that never came.
        assertTrue(labyrinthBossSemanticConflict(LabyrinthNodeTypes.SHOP, LabyrinthNodeTypes.BOSS))
        assertTrue(labyrinthBossSemanticConflict(LabyrinthNodeTypes.BOSS, LabyrinthNodeTypes.SHOP))
        assertTrue(labyrinthBossSemanticConflict(LabyrinthNodeTypes.NORMAL_BATTLE, LabyrinthNodeTypes.BOSS))
    }

    @Test fun `the pairs the override exists for are untouched`() {
        // LINK/RELIC and the purple EX read are genuine template noise between regular node icons.
        assertFalse(labyrinthBossSemanticConflict(LabyrinthNodeTypes.LINK, LabyrinthNodeTypes.RELIC))
        assertFalse(labyrinthBossSemanticConflict(LabyrinthNodeTypes.EX_BATTLE, LabyrinthNodeTypes.LINK))
        assertFalse(labyrinthBossSemanticConflict(LabyrinthNodeTypes.SHOP, LabyrinthNodeTypes.EVENT))
        // A Boss that reads as a Boss is not a conflict at all.
        assertFalse(labyrinthBossSemanticConflict(LabyrinthNodeTypes.BOSS, LabyrinthNodeTypes.BOSS))
    }
}
