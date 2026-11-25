package com.landosol.toolbox.labyrinth.kernel

import com.landosol.toolbox.automation.AutomationBackendResult
import com.landosol.toolbox.automation.AutomationSessionManager
import com.landosol.toolbox.automation.SessionBoundActionExecutor
import com.landosol.toolbox.gamedata.EventChoiceResource
import com.landosol.toolbox.gamedata.EventResource
import com.landosol.toolbox.labyrinth.LabyrinthEntryRecognitionSession
import com.landosol.toolbox.labyrinth.LabyrinthEntryRecognitionStartResult
import com.landosol.toolbox.labyrinth.LabyrinthExecutionGateResult
import com.landosol.toolbox.labyrinth.LabyrinthNodeJson
import com.landosol.toolbox.labyrinth.LabyrinthRouteJson
import com.landosol.toolbox.labyrinth.kernel.KernelEventKinds as K
import com.landosol.toolbox.labyrinth.kernel.event.*
import com.landosol.toolbox.labyrinth.node.LabyrinthNodeSession
import com.landosol.toolbox.labyrinth.node.LabyrinthNodeTypes
import com.landosol.toolbox.labyrinth.node.NodeClassification
import com.landosol.toolbox.labyrinth.node.NodeFrameEvidenceSource
import com.landosol.toolbox.labyrinth.node.NodeTemplateSet
import com.landosol.toolbox.labyrinth.vision.*
import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryPageState as Page
import java.lang.reflect.InvocationTargetException
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FullLabyrinthKernelFlowTest {
    @get:Rule val temporary = TemporaryFolder()

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun routeToFinalHomeWithNestedArenaAndJournal() = runTest {
        val packs = temporary.newFolder("event-packs")
        val journal = temporary.newFolder("journal")
        val eventId = "replay-arena"
        val choiceId = "replay-fight"
        val entry = GameEventDefinition(eventId, "回放竞技场", familyId = "arena", choices = listOf(
            GameEventChoiceSpec(choiceId, 1, "接受挑战", "进行普通战斗"),
        ))
        val pack = EventPackDocument(
            manifest = EventPackManifest("replay"),
            overlay = GameEventOverlayDocument(events = listOf(entry), aliases = mapOf("竞技回放" to eventId)),
            rules = listOf(GameEventRule("accept-arena", 250, GameEventCondition.EventIdIn(setOf(eventId)),
                GameEventRuleAction.SelectByChoiceId(choiceId))),
        )
        packs.resolve("arena.json").writeText(EventPackCodec.encode(pack))

        val base = emptyList<EventResource>()
        val route = route()
        val nodes = LabyrinthNodeSession()
        val templates = NodeTemplateSet(emptyMap())
        val manager = AutomationSessionManager()
        var at = 1_000L
        var inputs = 0
        val session = LabyrinthEntryRecognitionSession(
            manager, captureActive = { true }, processorFactory = { { error("replay supplies measured frames") } },
            clock = { at }, actionScope = this, eventChoicePlanner = eventPlanner(),
            actionExecutor = SessionBoundActionExecutor(manager) { inputs++; AutomationBackendResult.Completed },
            routeLoader = { route }, routeExecutionGate = { LabyrinthExecutionGateResult.Allowed(route, "offline") },
            nodeTemplateLoader = { templates }, nodeSessionFactory = { nodes },
            eventPackProvider = { EventPackRepository(packs) { base }.loadCompiled() }, journalDirectoryProvider = { journal },
        )
        val runtime = session.kernelRuntime
        val stack = session.encounterStack
        val processFrame = session.javaClass.declaredMethods.single {
            it.name.startsWith("processFrameResult-") && it.parameterCount == 6
        }.apply { isAccessible = true }
        fun seed(name: String, value: Any?) {
            session.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(session, value)
        }
        suspend fun feed(result: LabyrinthEntryFrameResult, count: Int = 1) {
            repeat(count) {
                at += 2_000L
                val id = requireNotNull(session.state.value.sessionId).value
                suspendCoroutineUninterceptedOrReturn<Unit> { continuation ->
                    try {
                        processFrame.invoke(session, id, result, WIDTH, HEIGHT, at, continuation)
                    } catch (failure: InvocationTargetException) {
                        throw failure.targetException
                    }
                }
                runCurrent()
            }
        }
        fun receipt(domain: KernelDomain, action: String): KernelEvent =
            requireNotNull(runtime.log.filter(domain, K.ACTION_RECEIPT_OBSERVED).lastOrNull {
                (it.payload as? ActionReceiptPayload)?.let { p -> p.choiceId == action && p.verdict == "Executed" } == true
            }) {
                "Missing ${domain.name}/$action at $at: ${session.state.value.message}, " +
                    "inputs=$inputs route=${nodes.getState().currentNodeId} " +
                    "frame=${session.state.value.frameTrace}; " +
                    runtime.log.recent(12).joinToString { "${it.domain}/${it.kind}/${it.reasonCode}" }
            }
        val trace: String
        try {
            assertTrue(session.startAutomation(accountId = 1L) is LabyrinthEntryRecognitionStartResult.Started)
            trace = runtime.traceId
            nodes.initialize(route.toFullMap(), route.toLabyrinthRoute().nodes, templates, route.currentBlockId)
            seed("nodeSession", nodes)
            seed("routeNodeCount", route.nodes.size)
            seed("entryPhaseComplete", true)
            runCurrent()

            // Channel ownership preempts route actions even though there is a recognizable map behind it.
            seed("channelPromptVisibleUntil", at + 2_001L)
            feed(mapFrame(2, LabyrinthNodeTypes.EVENT))
            assertEquals(0, inputs)
            assertTrue(runtime.log.filter(KernelDomain.RECOVERY, reasonCode = "channel_prompt").isNotEmpty())

            feed(mapFrame(2, LabyrinthNodeTypes.EVENT), 4)
            val click = receipt(KernelDomain.MAP, "NODE_CLICK")
            feed(moveDialog(), 2)
            val move = receipt(KernelDomain.MAP, "MOVE_CONFIRM")
            assertTrue(runtime.log.causalChain(move.eventSeq).contains(click))

            val catalog = session.eventCatalogFor(base)
            val known = requireNotNull(catalog.resolveRecognitionAlias("竞技回放"))
            feed(frame(Page.EVENT_CHOICE).copy(eventChoiceSelection = observed(known)), 2)
            val parent = requireNotNull(stack.peek())
            assertEquals(KernelEncounterKind.EVENT, parent.kind)
            val eventReceipt = receipt(KernelDomain.EVENT, choiceId)
            val eventChain = runtime.log.causalChain(eventReceipt.eventSeq)
            assertTrue(eventChain.contains(move))
            val policy = eventChain.mapNotNull { it.payload as? PolicyRuleEvaluatedPayload }.single()
            assertEquals("accept-arena", policy.ruleId)
            assertTrue(requireNotNull(policy.ruleProvenance).startsWith("replay@"))

            val challenge = frame(Page.BATTLE_CHALLENGE).copy(
                exEncounter = LabyrinthExEncounterObservation(challengeDifficultyResolved = true),
                anchorMatches = mapOf(EntryAnchorId.BATTLE_CHALLENGE_BUTTON to ANCHOR),
            )
            feed(challenge, 2)
            val child = requireNotNull(stack.peek())
            assertEquals(KernelEncounterKind.BATTLE, child.kind)
            assertEquals(parent.id, child.parentId)
            val push = requireNotNull(stack.lastTransition())
            assertEquals(eventReceipt.eventSeq, push.causeEventSeq)
            val battleTap = receipt(KernelDomain.BATTLE, "BATTLE_START_CHALLENGE")
            assertTrue(runtime.log.causalChain(battleTap.eventSeq).containsAll(listOf(eventReceipt, push)))
            feed(frame(Page.BATTLE_IN_PROGRESS))
            feed(frame(Page.BATTLE_RESULT).copy(
                anchorMatches = mapOf(EntryAnchorId.BATTLE_RESULT_NEXT_BUTTON to ANCHOR),
            ), 2)
            val battleResult = receipt(KernelDomain.BATTLE, "BATTLE_RESULT_NEXT")
            feed(frame(Page.LINK_CHOICE).copy(linkChoiceSelection = LabyrinthLinkChoiceObservation(listOf(
                LabyrinthLinkChoiceMatch("first", LabyrinthLinkElement.FIRE, 1.0, BUTTON, BUTTON, BUTTON, 1),
            ))), 2)
            assertEquals(listOf(parent), stack.snapshot())
            val reward = receipt(KernelDomain.REWARD, "SELECT_LINK_IMPRINT")
            val exit = runtime.log.filter(KernelDomain.RUNTIME, K.STATE_REDUCED).single {
                val state = it.payload as StateReducedPayload
                state.encounterId == child.id && state.detail["op"] == "pop"
            }
            assertTrue(runtime.log.causalChain(exit.eventSeq).contains(battleResult))

            feed(mapFrame(3, LabyrinthNodeTypes.SHOP), 3)
            feed(moveDialog(), 2)
            val imprint = LabyrinthShopItemMatch("imprint", BUTTON, BUTTON, 0.9,
                status = LabyrinthShopItemStatus.AVAILABLE, kind = LabyrinthShopItemKind.ROLE_IMPRINT,
                roleImprintLabel = "全体选择印记", choiceRoleImprint = true)
            feed(frame(Page.SHOP).copy(
                shopObservation = LabyrinthShopObservation(listOf(imprint), LabyrinthShopDialogState.NONE),
                anchorMatches = mapOf(EntryAnchorId.SHOP_REFRESH_BUTTON to ANCHOR),
            ), 2)
            val purchase = receipt(KernelDomain.SHOP, "SHOP_BUY_IMPRINT")
            feed(frame(Page.SHOP_PURCHASE_CONFIRMATION).copy(
                anchorMatches = mapOf(EntryAnchorId.SHOP_EXIT_CONFIRM_BUTTON to ANCHOR),
                shopObservation = LabyrinthShopObservation(emptyList(), LabyrinthShopDialogState.PURCHASE_CONFIRMATION),
            ), 2)
            val confirmation = receipt(KernelDomain.SHOP, "SHOP_CONFIRM_IMPRINT")
            assertTrue(runtime.log.causalChain(confirmation.eventSeq).contains(purchase))
            assertTrue(runtime.log.causalChain(purchase.eventSeq).contains(reward))
            feed(frame(Page.SHOP_PURCHASE_COMPLETE).copy(shopObservation =
                LabyrinthShopObservation(emptyList(), LabyrinthShopDialogState.PURCHASE_COMPLETE)), 2)
            receipt(KernelDomain.SHOP, "SHOP_CLOSE_PURCHASE_COMPLETE")

            val unknown = EventResource("ocr/replay", "待复核的旅人", choices = listOf(
                EventChoiceResource("ocr/replay/1", "领取", 1, "免费领取金币×300"),
            ))
            val unknownBefore = inputs
            feed(frame(Page.EVENT_CHOICE).copy(eventChoiceSelection = observed(unknown, known = false)
                .copy(rawText = "待复核的旅人\n免费领取金币×300")))
            assertEquals(unknownBefore, inputs)
            val draftFile = packs.resolve("draft-review.json")
            draftFile.writeText(UnknownEventDraftExporter.exportCatalogDraftJson(runtime.log))
            val draft = EventPackCodec.decode(draftFile.readText())
            val draftEntry = draft.overlay.events.single()
            assertEquals(EventPackKind.CATALOG, draft.manifest.kind)
            assertEquals(draftEntry.id, draft.overlay.aliases["待复核的旅人"])
            assertEquals("免费领取金币×300", draftEntry.choices.single().description)
            assertTrue(draftEntry.choices.single().effects.isNotEmpty())
            assertTrue(EventPackRepository(packs) { base }.loadCompiled().quarantinedErrors.isEmpty())
            assertNull(session.eventCatalogFor(base).byId(draftEntry.id))

            feed(frame(Page.RUN_CLEAR_RESULT), 2)
            receipt(KernelDomain.SETTLEMENT, "CLOSE_FINAL_SCORE")
            feed(frame(Page.ITEM_REWARD), 2)
            receipt(KernelDomain.SETTLEMENT, "CLOSE_FINAL_ITEM")
            feed(frame(Page.DAWN_REALM_HOME_IDLE), 2)
            assertNotNull(session.state.value.runSummary)
            assertTrue(runtime.log.filter(KernelDomain.SETTLEMENT, reasonCode = "run_cleared").isNotEmpty())
        } finally {
            session.stop("offline replay")
        }

        assertEquals(0, stack.depth())
        val loaded = KernelJournalReader.verifyAndLoad(KernelJournalSink.sessionDirFor(journal, trace))
        assertEquals(runtime.log.byTraceId(trace), loaded.events)
        assertEquals("stop", (loaded.events.last().payload as SessionLifecyclePayload).phase)
        val indexed = loaded.events.associateBy { it.eventSeq }
        assertTrue(loaded.events.all { event ->
            event.traceId == trace && (event.causeEventSeq?.let { cause ->
                cause < event.eventSeq && indexed[cause]?.traceId == trace
            } ?: true)
        })
    }

    private companion object {
        const val WIDTH = 1280
        const val HEIGHT = 720
        val BUTTON = EntryPixelRect(850, 510, 100, 45)
        val ANCHOR = EntryAnchorMatch(1.0, BUTTON)

        fun frame(page: Page) = LabyrinthEntryFrameResult(
            LabyrinthEntryPageObservation(page, 1.0, emptyMap(), LabyrinthAnchorScores(emptyMap())),
            matchedFeatures = emptyList(), elapsedMillis = 0, frameWidth = WIDTH, frameHeight = HEIGHT,
        )

        fun mapFrame(column: Int, type: Int) = frame(Page.NODE_SELECTION).copy(
            nodeClassifications = listOf(NodeClassification(column, 1, type, 0.95,
                isClickable = true, screenRect = EntryPixelRect(540, 140, 186, 233), cyanGlowScore = 0.4,
                cyanGlowRowCoverage = 0.8, colorRoiScore = 0.95, typeConfidence = 0.95)),
            nodeSearchWindowCount = 1, nodeSearchMode = "full", nodeEvidenceSource = NodeFrameEvidenceSource.SEARCH,
            nodeViewportSignature = "fixture-$column",
        )

        fun moveDialog() = frame(Page.NODE_SELECTION).copy(
            nodeMoveConfirmation = LabyrinthNodeMoveConfirmationObservation(1.0, BUTTON, BUTTON.copy(left = 650)),
        )

        fun route(): LabyrinthRouteJson {
            val nodes = listOf(LabyrinthNodeTypes.START, LabyrinthNodeTypes.EVENT, LabyrinthNodeTypes.SHOP)
                .mapIndexed { index, type ->
                    val id = 10101L + index * 100L
                    LabyrinthNodeJson(1, index + 1, 1, id, type, null,
                        if (index < 2) listOf(id + 100L) else emptyList(), false)
                }
            return LabyrinthRouteJson(1, 1, 1, 1, false, false, "fixture", emptyList(), emptyList(),
                nodes, allNodes = nodes, currentBlockId = nodes.first().blockId)
        }
    }
}
