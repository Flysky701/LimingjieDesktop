package com.landosol.toolbox.labyrinth.vision

import android.graphics.Bitmap
import com.landosol.toolbox.automation.AutomationSessionId
import com.landosol.toolbox.automation.overlay.AutomationOverlayPresentation
import com.landosol.toolbox.clanbattle.recognition.PixelImage
import com.landosol.toolbox.labyrinth.LabyrinthEntryRecognitionSessionState
import com.landosol.toolbox.labyrinth.LabyrinthEntryRecognitionStatus
import com.landosol.toolbox.labyrinth.LabyrinthFrameTrace
import com.landosol.toolbox.labyrinth.debug.LabyrinthDebugDashboardServer
import com.landosol.toolbox.labyrinth.node.LabyrinthNodeActionPlanner
import com.landosol.toolbox.labyrinth.node.LabyrinthNodeConflictRecovery
import com.landosol.toolbox.labyrinth.node.LabyrinthNodeTypes
import com.landosol.toolbox.labyrinth.node.NodeAction
import com.landosol.toolbox.labyrinth.node.NodeClassification
import com.landosol.toolbox.labyrinth.node.NodeColumnKey
import com.landosol.toolbox.labyrinth.node.NodeMatchResult
import com.landosol.toolbox.labyrinth.node.NodePositionConflict
import com.landosol.toolbox.labyrinth.node.NodePositionMapper
import com.landosol.toolbox.labyrinth.node.NodePositionMapping
import com.landosol.toolbox.labyrinth.node.NodeTopologyBindingKind
import com.landosol.toolbox.labyrinth.node.NodeTopologyMapper
import com.landosol.toolbox.protocol.labyrinth.LabyrinthMapNode
import java.io.ByteArrayInputStream
import java.net.Socket
import java.util.zip.ZipInputStream
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VisionRedundancyHardeningTest {

    @Test
    fun `failure and end confirmation detectors cannot override high confidence unrelated or session pages`() {
        val failureDetector = LabyrinthBattleFailureDetector()
        // Construct a synthetic frame that satisfies BattleFailureDetector TWO_BUTTON thresholds
        // and BattleEndConfirmationDetector RETREAT_CONFIRM thresholds simultaneously.
        val pixels = IntArray(1920 * 1080) { 0xff101010.toInt() }
        fun fill(left: Int, top: Int, w: Int, h: Int, color: Int) {
            for (y in top until top + h) for (x in left until left + w) pixels[y * 1920 + x] = color
        }
        fill(740, 40, 450, 130, 0xff2878ff.toInt())
        fill(1600, 55, 220, 45, 0xfff0f0f0.toInt())
        fill(1480, 965, 260, 55, 0xff2878ff.toInt())
        fill(560, 278, 800, 30, 0xff2878ff.toInt())
        fill(580, 720, 180, 40, 0xfff0f0f0.toInt())
        fill(850, 720, 75, 40, 0xfff0f0f0.toInt())
        fill(1150, 720, 200, 40, 0xff2878ff.toInt())
        val synthetic = PixelImage(1920, 1080, pixels)
        val endDetector = LabyrinthBattleEndConfirmationDetector()

        // High-confidence BATTLE_RESULT blocks battleFailure override.
        val battleResultObs = LabyrinthEntryPageObservation(
            state = LabyrinthEntryPageState.BATTLE_RESULT,
            confidence = 0.91,
            stateScores = mapOf(LabyrinthEntryPageState.BATTLE_RESULT to 0.91),
            anchorScores = LabyrinthAnchorScores(mapOf(EntryAnchorId.BATTLE_RESULT_NEXT_BUTTON to 0.91)),
        )
        val (failure1, end1) = resolveLabyrinthBattleFailureAndEndConfirmation(
            synthetic, battleResultObs, battleResultObs.anchorScores, failureDetector, endDetector,
        )
        assertNull(failure1)
        assertNull(end1)

        // Session error dialog suppresses battleEndConfirmation even when page is UNKNOWN.
        val sessionObs = LabyrinthEntryPageObservation(
            state = LabyrinthEntryPageState.UNKNOWN,
            confidence = 0.0,
            stateScores = emptyMap(),
            anchorScores = LabyrinthAnchorScores(mapOf(EntryAnchorId.SESSION_ERROR_TITLE to 0.88)),
        )
        val (_, end2) = resolveLabyrinthBattleFailureAndEndConfirmation(
            synthetic, sessionObs, sessionObs.anchorScores, failureDetector, endDetector,
        )
        assertNull(end2)
    }

    @Test
    fun `classifier scopes LINK_CHOICE tie break bonus and allows ITEM_REWARD over BATTLE_RESULT and SHOP`() {
        val classifier = LabyrinthEntryPageClassifier()
        // LINK_CHOICE slightly below SHOP in base text anchors must not overtake SHOP just via cardSignature.
        val shopWins = classifier.classify(
            LabyrinthAnchorScores(
                mapOf(
                    EntryAnchorId.LINK_CHOICE_TITLE to 0.72,
                    EntryAnchorId.LINK_CHOICE_INSTRUCTION to 0.72,
                    EntryAnchorId.LINK_CHOICE_REMAINING to 0.72,
                    EntryAnchorId.LINK_CHOICE_CARD_SIGNATURE to 0.95,
                    EntryAnchorId.SHOP_TITLE to 0.85,
                    EntryAnchorId.SHOP_INSTRUCTION to 0.85,
                    EntryAnchorId.SHOP_CLOSE to 0.85,
                ),
            ),
        )
        assertEquals(LabyrinthEntryPageState.SHOP, shopWins.state)

        // Strong ITEM_REWARD modal owns frame even over BATTLE_RESULT or SHOP.
        val rewardOverBattle = classifier.classify(
            LabyrinthAnchorScores(
                mapOf(
                    EntryAnchorId.ITEM_REWARD_TITLE to 0.92,
                    EntryAnchorId.ITEM_REWARD_INSTRUCTION to 0.90,
                    EntryAnchorId.ITEM_REWARD_CLOSE to 0.94,
                    EntryAnchorId.BATTLE_RESULT_NEXT_BUTTON to 0.95,
                ),
            ),
        )
        assertEquals(LabyrinthEntryPageState.ITEM_REWARD, rewardOverBattle.state)
    }

    @Test
    fun `single-node FULL_COLUMN never waives TYPE_CONFLICT in planner or recovery`() {
        val rect = EntryPixelRect(900, 350, 280, 350)
        val target = LabyrinthMapNode(
            area = 3, column = 6, row = 1, blockId = 30601L, blockType = LabyrinthNodeTypes.SHOP,
            questId = null, nextBlockIds = emptyList(), isAreaLastPoint = false,
        )
        val match = NodeMatchResult(
            currentNodeId = 30501L,
            currentNodeType = LabyrinthNodeTypes.NORMAL_BATTLE,
            nextNode = target,
            visibleNodes = listOf(
                NodePositionMapping(
                    blockId = 30601L,
                    blockType = LabyrinthNodeTypes.SHOP,
                    column = 6,
                    row = 1,
                    confidence = 0.98,
                    screenRect = rect,
                    isClickable = true,
                    detectedBlockType = LabyrinthNodeTypes.LINK,
                    topologyConfidence = 0.98,
                    topologyBindingKind = NodeTopologyBindingKind.FULL_COLUMN,
                    visualConfidence = 0.85,
                    typeConfidence = 0.85,
                ),
            ),
            conflicts = listOf(
                NodePositionConflict(
                    blockId = 30601L,
                    expectedBlockType = LabyrinthNodeTypes.SHOP,
                    detectedBlockType = LabyrinthNodeTypes.LINK,
                    visualColumn = 2,
                    visualRow = 1,
                    confidence = 0.98,
                    screenRect = rect,
                    sourceClickable = true,
                    visualConfidence = 0.85,
                    topologyConfidence = 0.98,
                ),
            ),
            isComplete = false,
            columnNodeCounts = mapOf(NodeColumnKey(3, 6) to 1),
        )
        val action = LabyrinthNodeActionPlanner().planAction(match)
        assertTrue("Expected TypeConflict for 1-node FULL_COLUMN, got $action", action is NodeAction.TypeConflict)
        val conflict = action as NodeAction.TypeConflict
        assertEquals(1, conflict.columnNodeCount)

        val recovery = LabyrinthNodeConflictRecovery()
        val visible = listOf(NodeClassification(2, 1, LabyrinthNodeTypes.LINK, 0.85, true, rect))
        repeat(5) {
            assertTrue(
                recovery.resolve("s", conflict, visible, uniqueReachableRouteTarget = false) is NodeAction.TypeConflict,
            )
        }
    }

    @Test
    fun `currentColumn rect is protected from semantic repair hijacking and 1-of-3 Y-band check rejects row mismatch`() {
        val currentRect = EntryPixelRect(400, 350, 280, 350)
        val futureWrongTypeRect = EntryPixelRect(900, 350, 280, 350)
        val nodes = listOf(
            LabyrinthMapNode(1, 2, 1, 10201L, LabyrinthNodeTypes.NORMAL_BATTLE, null, listOf(10301L, 10302L), false),
            LabyrinthMapNode(1, 3, 2, 10302L, LabyrinthNodeTypes.RELIC, null, emptyList(), false),
            LabyrinthMapNode(1, 3, 1, 10301L, LabyrinthNodeTypes.EVENT, null, emptyList(), false),
        )
        // Column 1 detection (currentRect) is misclassified as EVENT and aligns to currentNode 10201 (col 2);
        // Column 2 (future col 3) has both slots detected as RELIC, leaving unique active EVENT = currentRect.
        // Semantic repair for 10301 (EVENT) must NOT hijack currentRect belonging to currentColumn 10201!
        val result = NodePositionMapper().mapNodesDetailed(
            classifications = listOf(
                NodeClassification(1, 1, LabyrinthNodeTypes.EVENT, 0.90, true, currentRect),
                NodeClassification(2, 1, LabyrinthNodeTypes.RELIC, 0.88, true, EntryPixelRect(900, 90, 280, 350)),
                NodeClassification(2, 2, LabyrinthNodeTypes.RELIC, 0.88, true, futureWrongTypeRect),
            ),
            areaNodes = nodes,
            currentColumn = 2,
            reachableNodeIds = setOf(10301L, 10302L),
            preferredNodeId = 10301L,
        )
        assertFalse(
            "Expected currentColumn rect $currentRect not to be hijacked by 10301, mappings=${result.mappings}",
            result.mappings.any { it.blockId == 10301L && it.screenRect == currentRect },
        )

        // 1-of-3 REACHABLE_SUBSET: top target (10403) must not bind to an extreme bottom detection.
        val threeNodes = listOf(
            LabyrinthMapNode(1, 3, 1, 10301L, LabyrinthNodeTypes.NORMAL_BATTLE, null, listOf(10403L), false),
            LabyrinthMapNode(1, 4, 3, 10403L, LabyrinthNodeTypes.EVENT, null, emptyList(), false),
            LabyrinthMapNode(1, 4, 2, 10402L, LabyrinthNodeTypes.RELIC, null, emptyList(), false),
            LabyrinthMapNode(1, 4, 1, 10401L, LabyrinthNodeTypes.LINK, null, emptyList(), false),
        )
        val bottomRect = EntryPixelRect(1000, 620, 280, 350)
        val topo = NodeTopologyMapper().map(
            classifications = listOf(
                NodeClassification(1, 1, LabyrinthNodeTypes.NORMAL_BATTLE, 0.95, true, EntryPixelRect(450, 340, 280, 350)),
                NodeClassification(2, 1, LabyrinthNodeTypes.EVENT, 0.95, true, bottomRect),
            ),
            areaNodes = threeNodes,
            currentColumn = 3,
            reachableNodeIds = setOf(10403L),
            preferredNodeId = 10403L,
        )
        assertTrue(topo.mappings.none { it.blockId == 10403L && it.bindingKind == NodeTopologyBindingKind.REACHABLE_SUBSET })
    }

    @Test
    fun `refineMemberGeometry clamps near top and left frame edges without throwing`() {
        val matcher = LabyrinthCharacterIconMatcher()
        val frame = PixelImage(200, 200, IntArray(200 * 200) { 0xff334455.toInt() })
        val edgeRect = EntryPixelRect(0, 0, 90, 90)
        val initial = LabyrinthCharacterIconMatch(
            characterId = null, displayName = null, iconVariant = null,
            confidence = 0.2, rivalConfidence = 0.1, rivalMargin = 0.1, trusted = false,
            suspectedCharacterId = "1001", suspectedDisplayName = "测试",
        )
        val refined = matcher.refineMemberGeometry(frame, edgeRect, initial, force = true)
        assertEquals(initial, refined)
    }

    @Test
    fun `shop resolvers handle transient null OCR without EXHAUSTED and require dual-variant coin agreement`() {
        val shopFrame = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888)
        // Transient null OCR must keep shop item in READING (never EXHAUSTED).
        val itemResolver = AndroidLabyrinthShopItemResolver { _, cb -> cb(null) }
        val baseShop = LabyrinthShopObservation(
            items = listOf(
                LabyrinthShopItemMatch(
                    slotId = "shop_item_1",
                    screenRect = EntryPixelRect(100, 200, 400, 220),
                    buyButtonRect = EntryPixelRect(150, 350, 200, 60),
                    buyButtonScore = 0.95,
                    status = LabyrinthShopItemStatus.AVAILABLE,
                    titleRect = EntryPixelRect(125, 230, 390, 55),
                ),
            ),
        )
        val frameResult = LabyrinthEntryFrameResult(
            observation = LabyrinthEntryPageObservation(
                LabyrinthEntryPageState.SHOP, 0.95,
                mapOf(LabyrinthEntryPageState.SHOP to 0.95), LabyrinthAnchorScores(emptyMap()),
            ),
            matchedFeatures = emptyList(),
            elapsedMillis = 1L,
            shopObservation = baseShop,
        )
        var resolved = frameResult
        repeat(5) {
            resolved = itemResolver.resolve(shopFrame, resolved)
        }
        assertEquals(LabyrinthShopCategoryState.READING, resolved.shopObservation!!.items.single().categoryState)

        // Coin resolver uses distinct scales; disagreeing s2 ("1234") vs s3 ("5678") must stay unconfirmed until tie-break agrees.
        var now = 1_000L
        val scalesSeen = mutableListOf<Int>()
        val responses = ArrayDeque(listOf("1234", "5678", "5678"))
        val coinResolver = AndroidLabyrinthShopCoinResolver(
            submitTextRead = { bmp, cb ->
                scalesSeen += bmp.width
                cb(responses.removeFirst())
            },
            clock = { now },
        )
        val r1 = coinResolver.resolve(shopFrame, frameResult).shopObservation!!
        assertFalse(r1.coinBalanceConfirmed)
        now += 500L
        val r2 = coinResolver.resolve(shopFrame, frameResult).shopObservation!!
        assertFalse(r2.coinBalanceConfirmed)
        now += 500L
        val r3 = coinResolver.resolve(shopFrame, frameResult).shopObservation!!
        assertFalse(r3.coinBalanceConfirmed)
        now += 500L
        val r4 = coinResolver.resolve(shopFrame, frameResult).shopObservation!!
        assertTrue(r4.coinBalanceConfirmed)
        assertEquals(5678, r4.coinBalance)
        assertTrue("Expected distinct crop widths across variants, got $scalesSeen", scalesSeen.toSet().size >= 2)
    }

    @Test
    fun `debug dashboard captures lossless PNG incidents and includes expanded diagnostic fields in logs zip`() {
        val server = LabyrinthDebugDashboardServer(port = 0, allowAddressReuse = false)
        server.attachOcrHealthSource { mapOf("status" to "Ready", "nullFailures" to 1L) }
        server.start()
        try {
            val port = requireNotNull(server.boundPort)
            val bitmap = Bitmap.createBitmap(64, 36, Bitmap.Config.ARGB_8888)
            val state = LabyrinthEntryRecognitionSessionState(
                status = LabyrinthEntryRecognitionStatus.ERROR,
                message = "动作超时",
                frameTrace = LabyrinthFrameTrace(actionRejectReason = "node-type-conflict:30601"),
            )
            val result = LabyrinthEntryFrameResult(
                observation = LabyrinthEntryPageObservation(
                    LabyrinthEntryPageState.SHOP, 0.95, emptyMap(), LabyrinthAnchorScores(emptyMap()),
                ),
                matchedFeatures = emptyList(),
                elapsedMillis = 5L,
                shopObservation = LabyrinthShopObservation(
                    items = emptyList(),
                    coinBalance = 4321,
                    coinBalanceConfirmed = true,
                ),
                battleFailure = LabyrinthBattleFailureObservation(
                    confidence = 0.85,
                    endButtonRect = EntryPixelRect(10, 10, 20, 20),
                    retryButtonRect = EntryPixelRect(30, 10, 20, 20),
                ),
                exEncounter = LabyrinthExEncounterObservation(
                    encounterId = "ex_test",
                    encounterName = "测试头目",
                    trusted = true,
                ),
            )
            server.publish(
                bitmap, state, result,
                AutomationOverlayPresentation(
                    sessionId = AutomationSessionId(1L),
                    title = "t",
                    status = "err",
                    dryRun = false,
                ),
            )

            val stateJson = Socket("127.0.0.1", port).use { s ->
                s.getOutputStream().write("GET /api/state HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n".toByteArray())
                val raw = s.getInputStream().readBytes()
                val text = String(raw, Charsets.UTF_8)
                JSONObject(text.substringAfter("\r\n\r\n"))
            }
            assertEquals(4321, stateJson.getJSONObject("shop").getInt("coinBalance"))
            assertTrue(stateJson.getJSONObject("shop").getBoolean("coinBalanceConfirmed"))
            assertEquals("ex_test", stateJson.getJSONObject("exEncounter").getString("encounterId"))
            assertEquals("Ready", stateJson.getJSONObject("ocrHealth").getString("status"))
            assertTrue(stateJson.getInt("incidentCount") >= 1)
            assertTrue(stateJson.has("modals"))

            val zipBytes = Socket("127.0.0.1", port).use { s ->
                s.getOutputStream().write("GET /logs.zip HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n".toByteArray())
                val raw = s.getInputStream().readBytes()
                val headerEnd = raw.indices.first { i ->
                    i + 3 < raw.size && raw[i] == 13.toByte() && raw[i + 1] == 10.toByte() &&
                        raw[i + 2] == 13.toByte() && raw[i + 3] == 10.toByte()
                } + 4
                raw.copyOfRange(headerEnd, raw.size)
            }
            val names = mutableListOf<String>()
            ZipInputStream(ByteArrayInputStream(zipBytes)).use { zin ->
                while (true) {
                    val entry = zin.nextEntry ?: break
                    names += entry.name
                    if (entry.name.endsWith(".png")) {
                        val header = zin.readNBytes(8)
                        assertEquals(0x89.toByte(), header[0])
                        assertEquals('P'.code.toByte(), header[1])
                    }
                }
            }
            assertTrue("Expected lossless PNG incident in zip, got $names", names.any { it.startsWith("incidents/") && it.endsWith(".png") })
            assertTrue("Expected incident metadata json in zip, got $names", names.any { it.startsWith("incidents/") && it.endsWith(".json") })
        } finally {
            server.close()
        }
    }
}
