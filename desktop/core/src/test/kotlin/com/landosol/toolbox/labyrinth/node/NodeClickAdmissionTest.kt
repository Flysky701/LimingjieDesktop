package com.landosol.toolbox.labyrinth.node

import com.landosol.toolbox.labyrinth.vision.EntryPixelRect
import com.landosol.toolbox.protocol.labyrinth.LabyrinthMapNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression evidence from the 10089 2026-11-14 node log, without a device or Bitmap. */
class NodeClickAdmissionTest {
    private val failedRect = EntryPixelRect(398, 47, 187, 233)
    private val planner = LabyrinthNodeActionPlanner()
    private val regularTypes = listOf(
        LabyrinthNodeTypes.NORMAL_BATTLE, LabyrinthNodeTypes.EX_BATTLE,
        LabyrinthNodeTypes.LINK, LabyrinthNodeTypes.EVENT, LabyrinthNodeTypes.RELIC,
        LabyrinthNodeTypes.SHOP,
    )

    @Test fun `topology mapper preserves real visual evidence separately from binding score`() {
        val middle = EntryPixelRect(1280, 322, 280, 350)
        val mapped = NodePositionMapper().mapNodesDetailed(
            classifications = listOf(
                classification(LabyrinthNodeTypes.NORMAL_BATTLE, 0.78, EntryPixelRect(1360, 70, 280, 350))
                    .copy(column = 3, row = 1, isClickable = false),
                classification(LabyrinthNodeTypes.LINK, 0.401, middle)
                    .copy(column = 3, row = 2, typeConfidence = 0.397),
                classification(LabyrinthNodeTypes.NORMAL_BATTLE, 0.69, EntryPixelRect(1360, 650, 280, 350))
                    .copy(column = 3, row = 3),
            ),
            areaNodes = listOf(
                node(10301, LabyrinthNodeTypes.LINK),
                node(10401, LabyrinthNodeTypes.NORMAL_BATTLE),
                node(10402, LabyrinthNodeTypes.NORMAL_BATTLE),
                node(10403, LabyrinthNodeTypes.NORMAL_BATTLE),
            ),
            currentColumn = 3,
            reachableNodeIds = setOf(10401L, 10402L),
        )
        val conflict = mapped.conflicts.single { it.blockId == 10402L }
        assertEquals(0.401, requireNotNull(conflict.visualConfidence), 0.000001)
        assertNotNull(conflict.topologyConfidence)
        assertTrue(requireNotNull(conflict.topologyConfidence) > 0.70)
        val mapping = mapped.mappings.single { it.blockId == 10402L }
        assertEquals(0.401, mapping.visualConfidence, 0.000001)
        assertEquals(0.397, mapping.typeConfidence, 0.000001)
    }

    @Test fun `planner carries point 94 topology and point 401 visual as distinct fields`() {
        val action = planner.planAction(conflictMatch(visual = 0.401)) as NodeAction.TypeConflict
        assertEquals(0.401, requireNotNull(action.visualConfidence), 0.000001)
        assertEquals(0.94, requireNotNull(action.topologyConfidence), 0.000001)
        assertEquals(NodeTopologyBindingKind.REACHABLE_SUBSET, action.topologyBindingKind)
        assertFalse(action.hasOrderedSiblingEvidence)
    }

    @Test fun `both logged weak EX candidates cannot recover using topology or route uniqueness`() {
        for ((score, rect) in listOf(0.40142944370313743 to failedRect,
            0.4117466361415461 to EntryPixelRect(441, 47, 186, 233))) {
            val action = planner.planAction(conflictMatch(visual = score, rect = rect))
            assertTrue(action is NodeAction.TypeConflict)
            val recovery = LabyrinthNodeConflictRecovery()
            repeat(12) {
                assertEquals(action, recovery.resolve(
                    "logged", action, listOf(classification(LabyrinthNodeTypes.NORMAL_BATTLE, score, rect)),
                    uniqueReachableRouteTarget = true,
                ))
            }
        }
    }

    @Test fun `weak visual admission applies to every regular node type not only EX`() {
        for (expected in regularTypes) {
            val detected = if (expected == LabyrinthNodeTypes.NORMAL_BATTLE) {
                LabyrinthNodeTypes.EVENT
            } else {
                LabyrinthNodeTypes.NORMAL_BATTLE
            }
            val action = planner.planAction(conflictMatch(expected, detected, 0.412))
            assertTrue("expected=$expected action=$action", action is NodeAction.TypeConflict)
            val recovery = LabyrinthNodeConflictRecovery()
            repeat(6) {
                assertEquals(action, recovery.resolve("type-$expected", action,
                    listOf(classification(detected, 0.412)), uniqueReachableRouteTarget = true))
            }
        }
    }

    @Test fun `old compatibility confidence cannot outrank the current visual crop`() {
        val action = conflict(visual = null).copy(confidence = 0.94)
        val recovery = LabyrinthNodeConflictRecovery()
        repeat(8) {
            assertEquals(action, recovery.resolve("legacy", action,
                listOf(classification(LabyrinthNodeTypes.NORMAL_BATTLE, 0.401)), true))
        }
    }

    @Test fun `strong structure with weak semantic confidence is not strong visual evidence`() {
        val action = conflict(visual = 0.94)
        val frame = listOf(classification(LabyrinthNodeTypes.NORMAL_BATTLE, 0.94).copy(typeConfidence = 0.41))
        val recovery = LabyrinthNodeConflictRecovery()
        repeat(8) { assertEquals(action, recovery.resolve("semantic", action, frame, true)) }
    }

    @Test fun `strong nearby crop cannot supply confidence to the conflicting rect`() {
        val action = conflict(visual = 0.94)
        val frame = listOf(classification(LabyrinthNodeTypes.NORMAL_BATTLE, 0.94, failedRect.copy(left = 410)))
        val recovery = LabyrinthNodeConflictRecovery()
        repeat(8) { assertEquals(action, recovery.resolve("neighbor", action, frame, true)) }
    }

    @Test fun `current type change and competing labels at one rect reset recovery`() {
        val action = conflict(visual = 0.94)
        val valid = classification(LabyrinthNodeTypes.NORMAL_BATTLE, 0.94)
        val changed = valid.copy(blockType = LabyrinthNodeTypes.RELIC)
        val recovery = LabyrinthNodeConflictRecovery()
        repeat(2) { assertEquals(action, recovery.resolve("type", action, listOf(valid), true)) }
        assertEquals(action, recovery.resolve("type", action, listOf(changed), true))
        repeat(2) { assertEquals(action, recovery.resolve("type", action, listOf(valid), true)) }
        assertEquals(action, recovery.resolve("type", action, listOf(valid, changed), true))
        assertEquals(action, recovery.resolve("type", action, listOf(valid), true))
    }

    @Test fun `Boss conflicts are rejected in both directions despite full topology and strong vision`() {
        for (regular in regularTypes) for ((expected, detected) in listOf(
            LabyrinthNodeTypes.BOSS to regular, regular to LabyrinthNodeTypes.BOSS,
        )) {
            val action = conflict(expected, detected, 0.99).copy(
                topologyConfidence = 0.99, topologyBindingKind = NodeTopologyBindingKind.FULL_COLUMN,
                hasOrderedSiblingEvidence = true,
            )
            val recovery = LabyrinthNodeConflictRecovery()
            repeat(8) {
                assertEquals(action, recovery.resolve("boss", action,
                    listOf(classification(detected, 0.99)), true))
            }
        }
    }

    @Test fun `planner keeps Boss conflict even with a high confidence full mapping`() {
        for ((expected, detected) in listOf(
            LabyrinthNodeTypes.BOSS to LabyrinthNodeTypes.SHOP,
            LabyrinthNodeTypes.SHOP to LabyrinthNodeTypes.BOSS,
        )) {
            val match = conflictMatch(expected, detected, 0.99)
            val full = match.copy(visibleNodes = match.visibleNodes.map {
                it.copy(topologyConfidence = 0.99, topologyBindingKind = NodeTopologyBindingKind.FULL_COLUMN)
            })
            assertTrue(planner.planAction(full) is NodeAction.TypeConflict)
        }
    }

    @Test fun `strong current sole reachable candidate retains bounded legacy recovery`() {
        val action = conflict(visual = 0.92).copy(topologyBindingKind = NodeTopologyBindingKind.SINGLE_TARGET)
        val frame = listOf(classification(LabyrinthNodeTypes.NORMAL_BATTLE, 0.92))
        val recovery = LabyrinthNodeConflictRecovery()
        repeat(2) { assertEquals(action, recovery.resolve("strong-single", action, frame, true)) }
        assertEquals(NodeAction.ClickNode(20503, LabyrinthNodeTypes.EX_BATTLE, failedRect),
            recovery.resolve("strong-single", action, frame, true))
    }

    @Test fun `strong full column recovery remains available with other visible nodes`() {
        val action = conflict(visual = 0.91).copy(
            topologyConfidence = 0.86, topologyBindingKind = NodeTopologyBindingKind.FULL_COLUMN,
        )
        val frame = listOf(
            classification(LabyrinthNodeTypes.NORMAL_BATTLE, 0.91),
            classification(LabyrinthNodeTypes.RELIC, 0.93, EntryPixelRect(900, 400, 187, 233)),
        )
        val recovery = LabyrinthNodeConflictRecovery()
        repeat(2) { assertEquals(action, recovery.resolve("full", action, frame)) }
        assertTrue(recovery.resolve("full", action, frame) is NodeAction.ClickNode)
    }

    @Test fun `partial and reachable subset need independent sibling evidence on branching routes`() {
        for (binding in listOf(NodeTopologyBindingKind.PARTIAL_COLUMN, NodeTopologyBindingKind.REACHABLE_SUBSET)) {
            val action = conflict(visual = 0.82).copy(topologyBindingKind = binding)
            val frame = listOf(
                classification(LabyrinthNodeTypes.NORMAL_BATTLE, 0.82),
                classification(LabyrinthNodeTypes.EVENT, 0.91, failedRect.copy(top = 350)).copy(row = 2),
            )
            val recovery = LabyrinthNodeConflictRecovery()
            repeat(8) { assertEquals(action, recovery.resolve("subset", action, frame)) }
            val supported = action.copy(hasOrderedSiblingEvidence = true)
            repeat(2) { assertEquals(supported, recovery.resolve("subset", supported, frame)) }
            assertTrue(recovery.resolve("subset", supported, frame) is NodeAction.ClickNode)
        }
    }

    @Test fun `current activation missing and nonfinite visual values never authorize recovery`() {
        val action = conflict(visual = 0.94)
        for (score in listOf(Double.NaN, Double.POSITIVE_INFINITY, -0.1, 1.1)) {
            val recovery = LabyrinthNodeConflictRecovery()
            repeat(5) { assertEquals(action, recovery.resolve("invalid", action,
                listOf(classification(LabyrinthNodeTypes.NORMAL_BATTLE, score)), true)) }
        }
        val recovery = LabyrinthNodeConflictRecovery()
        repeat(5) { assertEquals(action, recovery.resolve("inactive", action,
            listOf(classification(LabyrinthNodeTypes.NORMAL_BATTLE, 0.99).copy(isClickable = false)), true)) }
    }

    @Test fun `semantic candidate requires real visual quality and classifier activation for all types`() {
        for (type in regularTypes + LabyrinthNodeTypes.BOSS) {
            assertFalse(NodeClickAdmission.hasSemanticEvidence(classification(type, 0.404)))
            assertFalse(NodeClickAdmission.hasSemanticEvidence(classification(type, 0.91).copy(typeConfidence = 0.404)))
            assertFalse(NodeClickAdmission.hasSemanticEvidence(classification(type, 0.91).copy(isClickable = false)))
            assertTrue(NodeClickAdmission.hasSemanticEvidence(classification(type, 0.698)))
        }
        for (type in listOf(LabyrinthNodeTypes.UNKNOWN, LabyrinthNodeTypes.START, LabyrinthNodeTypes.CLEAR)) {
            assertFalse(NodeClickAdmission.hasSemanticEvidence(classification(type, 0.99)))
        }
    }

    @Test fun `weak semantic repair cannot fall through to legacy or borrow full topology`() {
        for (semantic in listOf(false, true)) {
            val weak = mapping(visual = 0.404).copy(
                routeSemanticRepair = semantic,
                topologyConfidence = if (semantic) 0.99 else null,
                topologyBindingKind = if (semantic) NodeTopologyBindingKind.FULL_COLUMN else null,
            )
            assertEquals(NodeAction.WaitForLoad, planner.planAction(match(listOf(weak))))
        }
    }

    @Test fun `point 698 EX success preserves its actual current rect and click position`() {
        val rect = EntryPixelRect(755, 59, 186, 233)
        val candidate = classification(LabyrinthNodeTypes.EX_BATTLE, 0.6978550882158924, rect).copy(
            templateId = "node.ex_battle.inactive", purpleGlowScore = 0.167,
            purpleGlowSideScore = 0.152, purpleGlowLowerScore = 0.180, purpleGlowRowCoverage = 0.556,
        )
        assertTrue(NodeClickAdmission.hasSemanticEvidence(candidate))
        val entry = mapping(visual = candidate.confidence, rect = rect).copy(routeSemanticRepair = true)
        val action = planner.planAction(match(listOf(entry)))
        assertEquals(NodeAction.ClickNode(20503, LabyrinthNodeTypes.EX_BATTLE, rect), action)
        assertEquals(848 to 134, planner.getBaseClickPosition(rect))
    }

    @Test fun `existing point 56 event semantic repair remains admissible`() {
        assertTrue(NodeClickAdmission.hasSemanticEvidence(classification(LabyrinthNodeTypes.EVENT, 0.56)))
        val target = mapping(type = LabyrinthNodeTypes.EVENT, visual = 0.56).copy(routeSemanticRepair = true)
        assertTrue(planner.planAction(match(listOf(target), LabyrinthNodeTypes.EVENT)) is NodeAction.ClickNode)
    }

    @Test fun `real mapper rejects weak sole event repair in the preexisting false-column fixture`() {
        val rect = EntryPixelRect(820, 650, 280, 350)
        val mapped = NodePositionMapper().mapNodesDetailed(
            classifications = listOf(
                classification(LabyrinthNodeTypes.EVENT, 0.404, rect).copy(column = 2),
                classification(LabyrinthNodeTypes.LINK, 0.97, EntryPixelRect(1360, 70, 280, 350))
                    .copy(column = 3, isClickable = false),
                classification(LabyrinthNodeTypes.EX_BATTLE, 0.40, EntryPixelRect(1600, 390, 280, 350))
                    .copy(column = 3, row = 2, purpleGlowScore = 0.53),
            ),
            areaNodes = listOf(
                node(30101, LabyrinthNodeTypes.START),
                node(30201, LabyrinthNodeTypes.NORMAL_BATTLE),
                node(30202, LabyrinthNodeTypes.NORMAL_BATTLE),
                node(30203, LabyrinthNodeTypes.NORMAL_BATTLE),
                node(30301, LabyrinthNodeTypes.EVENT), node(30302, LabyrinthNodeTypes.RELIC),
                node(30303, LabyrinthNodeTypes.EVENT), node(30401, LabyrinthNodeTypes.EVENT),
                node(30402, LabyrinthNodeTypes.EVENT), node(30403, LabyrinthNodeTypes.LINK),
            ),
            currentColumn = 2, reachableNodeIds = setOf(30301L), preferredNodeId = 30301L,
        )
        assertFalse(mapped.mappings.any { it.blockId == 30301L && it.routeSemanticRepair })
        val action = planner.planAction(NodeMatchResult(
            currentNodeId = 30201L, currentNodeType = LabyrinthNodeTypes.NORMAL_BATTLE,
            nextNode = node(30301, LabyrinthNodeTypes.EVENT), visibleNodes = mapped.mappings,
            conflicts = mapped.conflicts, topologyMappings = mapped.topologyMappings, isComplete = false,
        ))
        assertFalse("weak sole repair must not become a tap: $action",
            action is NodeAction.ClickNode && action.screenRect != null)
    }

    @Test fun `strong full mapping still supports noisy but geometrically ordered visual type`() {
        val source = conflictMatch(visual = 0.50)
        val full = source.copy(visibleNodes = source.visibleNodes.map {
            it.copy(topologyBindingKind = NodeTopologyBindingKind.FULL_COLUMN, topologyConfidence = 0.96)
        })
        assertTrue(planner.planAction(full) is NodeAction.ClickNode)
    }

    @Test fun `special LINK RELIC ordered sibling override and purple EX override remain intact`() {
        val source = conflictMatch(LabyrinthNodeTypes.LINK, LabyrinthNodeTypes.RELIC, 0.72)
        val sibling = mapping(type = LabyrinthNodeTypes.EVENT, visual = 0.80, rect = failedRect.copy(top = 350))
            .copy(blockId = 20502L, row = 2, detectedBlockType = LabyrinthNodeTypes.EVENT,
                topologyConfidence = 0.81, topologyBindingKind = NodeTopologyBindingKind.REACHABLE_SUBSET)
        val link = source.copy(visibleNodes = source.visibleNodes.map { it.copy(topologyConfidence = 0.82) } + sibling)
        assertTrue(planner.planAction(link) is NodeAction.ClickNode)
        val purple = conflictMatch(LabyrinthNodeTypes.EX_BATTLE, LabyrinthNodeTypes.LINK, 0.63)
        val supported = purple.copy(
            visibleNodes = purple.visibleNodes.map { it.copy(topologyConfidence = 0.84, purpleGlowScore = 0.13) },
            conflicts = purple.conflicts.map { it.copy(purpleGlowScore = 0.13) },
        )
        assertTrue(planner.planAction(supported) is NodeAction.ClickNode)
    }

    @Test fun `protected LINK RELIC correction retains historical low-score ordered target`() {
        val targetRect = EntryPixelRect(820, 90, 280, 350)
        val siblingRect = EntryPixelRect(820, 350, 280, 350)
        val wrongRect = EntryPixelRect(1360, 510, 280, 350)
        val current = node(10402, LabyrinthNodeTypes.NORMAL_BATTLE)
            .copy(nextBlockIds = listOf(10503L, 10502L))
        val target = node(10503, LabyrinthNodeTypes.LINK)
        val mapped = NodePositionMapper().mapNodesDetailed(
            classifications = listOf(
                NodeClassification(2, 1, LabyrinthNodeTypes.RELIC, 0.546, true, targetRect),
                NodeClassification(2, 2, LabyrinthNodeTypes.EVENT, 0.457, true, siblingRect),
                NodeClassification(3, 1, LabyrinthNodeTypes.LINK, 0.556, true, wrongRect),
            ),
            areaNodes = listOf(current, node(10501, LabyrinthNodeTypes.EVENT),
                node(10502, LabyrinthNodeTypes.EVENT), target, node(10601, LabyrinthNodeTypes.RELIC)),
            currentColumn = 4, reachableNodeIds = current.nextBlockIds.toSet(), preferredNodeId = target.blockId,
        )
        val targetMapping = mapped.mappings.single { it.blockId == target.blockId }
        assertEquals(targetRect, targetMapping.screenRect)
        assertEquals(NodeTopologyBindingKind.REACHABLE_SUBSET, targetMapping.topologyBindingKind)
        assertFalse(targetMapping.routeSemanticRepair)
        assertFalse(NodeClickAdmission.hasSemanticScores(targetMapping.visualConfidence, targetMapping.typeConfidence))
        val action = planner.planAction(NodeMatchResult(
            currentNodeId = current.blockId, currentNodeType = current.blockType, nextNode = target,
            visibleNodes = mapped.mappings, conflicts = mapped.conflicts,
            topologyMappings = mapped.topologyMappings, topologyAlignmentScore = mapped.topologyAlignmentScore,
            topologyObservedColumns = mapped.topologyObservedColumns, isComplete = false,
        ))
        assertEquals(NodeAction.ClickNode(target.blockId, target.blockType, targetRect), action)
    }

    @Test fun `protected correction cannot authorize another weak rect sharing the route id`() {
        val source = lowScoreProtectedLinkMatch()
        val wrong = mapping(LabyrinthNodeTypes.LINK, 0.404, failedRect.copy(left = 900)).copy(
            confidence = 0.99, topologyConfidence = 0.94,
            topologyBindingKind = NodeTopologyBindingKind.SINGLE_TARGET, routeSemanticRepair = true,
        )
        for (mappings in listOf(source.visibleNodes + wrong, listOf(wrong) + source.visibleNodes)) {
            assertEquals(NodeAction.ClickNode(20503, LabyrinthNodeTypes.LINK, failedRect),
                planner.planAction(source.copy(visibleNodes = mappings)))
        }
    }

    @Test fun `low-score LINK RELIC conflict still requires its original independent sibling`() {
        val source = lowScoreProtectedLinkMatch()
        val isolated = source.copy(visibleNodes = source.visibleNodes.take(1))
        assertTrue(planner.planAction(isolated) is NodeAction.TypeConflict)
        assertFalse(NodeClickAdmission.hasSemanticScores(0.546, 0.546))
        assertFalse(NodeClickAdmission.hasSemanticScores(0.404, 0.404))
    }

    @Test fun `protected correction does not waive active crop or finite visual evidence`() {
        val source = lowScoreProtectedLinkMatch()
        val target = source.visibleNodes.first()
        val sibling = source.visibleNodes.last()
        for (invalid in listOf(target.copy(isClickable = false), target.copy(visualConfidence = Double.NaN),
            target.copy(typeConfidence = Double.NaN))) {
            val action = planner.planAction(source.copy(visibleNodes = listOf(invalid, sibling)))
            assertFalse("protected policy must not waive final physical evidence: $action",
                action is NodeAction.ClickNode && action.screenRect != null)
        }
    }

    @Test fun `default click and retry ratios stay unchanged in observed coordinate cases`() {
        assertEquals(listOf(491 to 122, 491 to 191, 491 to 87),
            (0..2).map { planner.getBaseClickPosition(failedRect, it) })
        assertEquals(listOf(534 to 122, 534 to 191, 534 to 87),
            (0..2).map { planner.getBaseClickPosition(EntryPixelRect(441, 47, 186, 233), it) })
    }

    private fun lowScoreProtectedLinkMatch(): NodeMatchResult {
        val source = conflictMatch(LabyrinthNodeTypes.LINK, LabyrinthNodeTypes.RELIC, 0.546)
        val sibling = mapping(LabyrinthNodeTypes.EVENT, 0.457, failedRect.copy(top = 350)).copy(
            blockId = 20502, row = 2, topologyConfidence = 0.94,
            topologyBindingKind = NodeTopologyBindingKind.REACHABLE_SUBSET,
        )
        return source.copy(visibleNodes = source.visibleNodes + sibling)
    }

    private fun conflict(
        expected: Int = LabyrinthNodeTypes.EX_BATTLE,
        detected: Int = LabyrinthNodeTypes.NORMAL_BATTLE,
        visual: Double? = 0.401,
    ) = NodeAction.TypeConflict(
        blockId = 20503, expectedBlockType = expected, detectedBlockType = detected, screenRect = failedRect,
        confidence = 0.94, topologyConfidence = 0.94,
        topologyBindingKind = NodeTopologyBindingKind.REACHABLE_SUBSET, visualConfidence = visual,
    )

    private fun classification(type: Int, confidence: Double, rect: EntryPixelRect = failedRect) =
        NodeClassification(
            column = 2, row = 1, blockType = type, confidence = confidence, isClickable = true,
            screenRect = rect, cyanGlowScore = 0.20, cyanGlowRowCoverage = 0.50,
        )

    private fun mapping(
        type: Int = LabyrinthNodeTypes.EX_BATTLE, visual: Double = 0.698, rect: EntryPixelRect = failedRect,
    ) = NodePositionMapping(
        blockId = 20503, blockType = type, column = 5, row = 3, confidence = visual, screenRect = rect,
        isClickable = true, detectedBlockType = type, visualConfidence = visual, typeConfidence = visual,
    )

    private fun match(
        mappings: List<NodePositionMapping>, type: Int = LabyrinthNodeTypes.EX_BATTLE,
    ) = NodeMatchResult(
        currentNodeId = 20402, currentNodeType = LabyrinthNodeTypes.RELIC, nextNode = node(20503, type),
        visibleNodes = mappings, isComplete = false,
    )

    private fun conflictMatch(
        expected: Int = LabyrinthNodeTypes.EX_BATTLE, detected: Int = LabyrinthNodeTypes.NORMAL_BATTLE,
        visual: Double = 0.401, rect: EntryPixelRect = failedRect,
    ): NodeMatchResult = match(listOf(
        mapping(expected, visual, rect).copy(confidence = 0.94, detectedBlockType = detected,
            topologyConfidence = 0.94, topologyBindingKind = NodeTopologyBindingKind.REACHABLE_SUBSET),
    ), expected).copy(conflicts = listOf(NodePositionConflict(
        blockId = 20503, expectedBlockType = expected, detectedBlockType = detected, visualColumn = 2,
        visualRow = 1, confidence = 0.94, screenRect = rect, sourceClickable = true,
        visualConfidence = visual, topologyConfidence = 0.94,
    )))

    private fun node(id: Long, type: Int) = LabyrinthMapNode(
        area = (id / 10000).toInt(), column = (id / 100 % 100).toInt(), row = (id % 100).toInt(),
        blockId = id, blockType = type, questId = null, nextBlockIds = emptyList(), isAreaLastPoint = false,
    )
}
