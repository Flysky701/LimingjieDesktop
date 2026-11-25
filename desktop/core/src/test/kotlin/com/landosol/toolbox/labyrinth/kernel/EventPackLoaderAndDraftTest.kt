package com.landosol.toolbox.labyrinth.kernel

import com.landosol.toolbox.automation.AutomationSessionManager
import com.landosol.toolbox.automation.AutomationSessionId
import com.landosol.toolbox.automation.SessionBoundActionExecutor
import com.landosol.toolbox.automation.overlay.AutomationOverlayCoordinator
import com.landosol.toolbox.automation.overlay.AutomationOverlayHost
import com.landosol.toolbox.gamedata.EventChoiceResource
import com.landosol.toolbox.gamedata.EventResource
import com.landosol.toolbox.labyrinth.*
import com.landosol.toolbox.labyrinth.kernel.event.*
import com.landosol.toolbox.labyrinth.vision.*
import java.io.File
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class EventPackLoaderAndDraftTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun write(dir: File, name: String, pack: EventPackDocument) =
        dir.resolve("$name.json").writeText(EventPackCodec.encode(pack))

    private fun catalog(id: String) = EventPackDocument(
        manifest = EventPackManifest(id, kind = EventPackKind.CATALOG),
        overlay = GameEventOverlayDocument(events = listOf(GameEventDefinition(id, "礼物", choices = listOf(
            GameEventChoiceSpec("$id/1", 1, "领取", "金币×200")))), aliases = mapOf("新礼物" to id)),
    )

    @Test fun layersMergeInOrderAndQuarantineWholeFiles() {
        val dir = temporary.newFolder()
        val base = realEvents()
        val repo = EventPackRepository(dir) { base }
        assertEquals(CompiledEventPackSet(), repo.loadCompiled())
        val first = catalog("x")
        val policy = BuiltinEventPackPresets.dailySafePolicyPack().copy(
            manifest = EventPackManifest("p", version = "2", kind = EventPackKind.POLICY, order = 10))
        val bundle = EventPackDocument(manifest = EventPackManifest("z"), unknownEventPolicy = UnknownGameEventPolicy.FIRST_FREE_ENABLED,
            rules = listOf(GameEventRule("z-rule", 3, GameEventCondition.EventIdIn(setOf("extra")), GameEventRuleAction.SelectBySlot(1))),
            overlay = GameEventOverlayDocument(aliases = mapOf("又一礼物" to "x")))
        write(dir, "z-file", first)
        write(dir, "b-policy", policy)
        write(dir, "a-bundle", bundle)
        write(dir, "disabled", policy.copy(manifest = policy.manifest.copy(enabled = false)))

        val corrupt = mapOf("bad" to "{", "schema" to EventPackCodec.encode(first).replaceFirst("\"schemaVersion\":1", "\"schemaVersion\":9"),
            "catalog-with-rules" to EventPackCodec.encode(policy).replace("\"POLICY\"", "\"CATALOG\""),
            "policy-with-overlay" to EventPackCodec.encode(first).replace("\"CATALOG\"", "\"POLICY\""))
        for ((name, data) in corrupt) dir.resolve("$name.json").writeText(data)
        write(dir, "zz-duplicate-id", policy.copy(rules = emptyList()))
        fun conflict(id: String, overlay: GameEventOverlayDocument, rules: List<GameEventRule> = emptyList()) =
            EventPackDocument(manifest = EventPackManifest(id, order = 200), overlay = overlay, rules = rules,
                unknownEventPolicy = UnknownGameEventPolicy.FIRST_ENABLED_SLOT)
        write(dir, "rule", conflict("rule", catalog("lost-rule").overlay.copy(aliases = emptyMap()), policy.rules.take(1)))
        write(dir, "event", conflict("event", first.overlay.copy(aliases = emptyMap())))
        write(dir, "alias", conflict("alias", catalog("lost-alias").overlay))
        write(dir, "bad-target", conflict("bad-target", GameEventOverlayDocument(aliases = mapOf("gone" to "absent"))))
        val compiled = repo.loadCompiled()
        assertEquals(listOf("p", "x", "z"), compiled.loadedManifests.map { it.packId })
        assertEquals((corrupt.keys + listOf("zz-duplicate-id", "rule", "event", "alias", "bad-target"))
            .map { "$it.json" }.toSet(), compiled.quarantinedErrors.keys)
        assertEquals(policy.rules + bundle.rules, compiled.extensionPack.rules)
        assertEquals("p@2#" + policy.rules.first().id, compiled.ruleProvenance[policy.rules.first().id])
        assertEquals(bundle.unknownEventPolicy, compiled.extensionPack.unknownEventPolicy)
        val catalog = GameEventCatalogRegistry(base).apply { addOverlay(compiled.extensionPack.overlay) }
        assertEquals(22, base.size)
        assertEquals(23, catalog.toEventResources().size)
        base.forEach { assertEquals(it, catalog.byId(it.id)) }
        assertEquals("x", catalog.resolveRecognitionAlias("又一礼物")?.id)
        assertEquals("x", catalog.resolveRecognitionAlias("新礼物")?.id)
        assertThrows(IllegalArgumentException::class.java) { policy.copy(rules = policy.rules + policy.rules.first()) }
        assertThrows(IllegalArgumentException::class.java) { policy.copy(rules = listOf(policy.rules.first().copy(id = "builtin/x"))) }
    }

    @Test fun presetsChooseRealOptionsAndPreserveRuleSources() {
        val fixtures = realEvents().associateBy { it.id }
        val daily = BuiltinEventPackPresets.dailySafePolicyPack()
        val greedy = BuiltinEventPackPresets.highscoreGreedyPolicyPack()
        for (pack in listOf(daily, greedy)) {
            assertEquals(pack, EventPackCodec.decode(EventPackCodec.encode(pack, true)))
            assertEquals(EventPackKind.POLICY, pack.manifest.kind)
            val dir = temporary.newFolder()
            write(dir, "preset", pack)
            val compiled = EventPackRepository(dir).loadCompiled()
            val planner = eventPlanner().withExtensionPack(compiled.extensionPack, compiled.ruleProvenance)
            fun select(id: String, balance: Int? = null, rejected: Set<String> = emptySet()): LabyrinthEventChoiceDecision.Select {
                val event = if (id == "arena") LabyrinthArenaEvent.event else fixtures.getValue(id)
                val evaluation = planner.evaluate(observed(event), emptySet(), LabyrinthRoleDecisionContext(0),
                    emptyMap(), rejected, balance)
                val decision = evaluation.decision as LabyrinthEventChoiceDecision.Select
                assertNotNull(evaluation.ruleProvenance)
                assertEquals(compiled.ruleProvenance.getValue(requireNotNull(decision.policyRuleId)), evaluation.ruleProvenance)
                assertEquals(evaluation.ruleProvenance, decision.ruleProvenance)
                return decision
            }
            assertEquals("11073", select("1107").choiceId)
            if (pack == daily) {
                assertEquals(UnknownGameEventPolicy.WAIT, compiled.extensionPack.unknownEventPolicy)
                assertEquals("11073", select("1107", 3000).choiceId)
                for (id in listOf("2315", "3315", "2316", "3316")) assertEquals(id + "2", select(id).choiceId)
                assertEquals("一试身手吧！", select("arena").label)
            } else {
                assertEquals(UnknownGameEventPolicy.SAFE_PARSE_OR_WAIT, compiled.extensionPack.unknownEventPolicy)
                assertEquals("11073", select("1107", 2499).choiceId)
                assertEquals("11071", select("1107", 2500).choiceId)
                assertEquals("11073", select("1107", 3000, setOf("11071")).choiceId)
                for (id in listOf("2315", "3315")) assertEquals(id + "1", select(id).choiceId)
                assertEquals("与强敌战斗真是令人兴奋！", select("arena").label)
            }
        }
        val builtin = eventPlanner().evaluate(observed(fixtures.getValue("1107")), emptySet(), LabyrinthRoleDecisionContext(0), emptyMap())
        assertEquals("builtin#builtin/fixed/1107", builtin.ruleProvenance)
        assertEquals(builtin.ruleProvenance, (builtin.decision as LabyrinthEventChoiceDecision.Select).ruleProvenance)
        val utility = eventPlanner().evaluate(observed(fixtures.getValue("1108")), emptySet(), LabyrinthRoleDecisionContext(0), emptyMap())
        assertNull(utility.ruleProvenance)
        assertNull((utility.decision as LabyrinthEventChoiceDecision.Select).ruleProvenance)
    }

    // Use the accepted-detail boundary so the regular session, planner and event domain participate.
    private suspend fun feed(session: LabyrinthEntryRecognitionSession, observation: LabyrinthEventChoiceObservation) {
        val page = LabyrinthEntryPageState.EVENT_CHOICE
        val frame = LabyrinthEntryFrameResult(LabyrinthEntryPageObservation(page, 1.0, mapOf(page to 1.0),
            LabyrinthAnchorScores(emptyMap())), matchedFeatures = emptyList(), elapsedMillis = 0,
            frameWidth = 1280, frameHeight = 720, eventChoiceSelection = observation)
        val method = session.javaClass.declaredMethods.single { it.name.startsWith("processFrameResult") &&
            '$' !in it.name && it.parameterCount == 6 }.apply { isAccessible = true }
        suspendCoroutineUninterceptedOrReturn<Unit> { continuation ->
            method.invoke(session, requireNotNull(session.state.value.sessionId).value, frame, 1280, 720,
                System.currentTimeMillis(), continuation)
        }
    }

    @Test fun sessionFreezesDiskConfigurationAndJournalsThroughStop() = runBlocking {
        val packs = temporary.newFolder()
        val journals = temporary.newFolder()
        val daily = BuiltinEventPackPresets.dailySafePolicyPack()
        val greedy = BuiltinEventPackPresets.highscoreGreedyPolicyPack()
        write(packs, "policy", daily)
        var reads = 0
        val session = LabyrinthEntryRecognitionSession(AutomationSessionManager(), captureActive = { true },
            processorFactory = { { error("offline replay") } }, eventChoicePlanner = eventPlanner(),
            eventPackProvider = { reads++; EventPackRepository(packs).loadCompiled() }, journalDirectoryProvider = { journals })
        val event = realEvents().single { it.id == "2315" }
        val log = session.kernelRuntime.log
        try {
            for ((index, pack) in listOf(daily, greedy).withIndex()) {
                assertTrue(session.start() is LabyrinthEntryRecognitionStartResult.Started)
                assertEquals(index + 1, reads)
                val trace = session.kernelRuntime.traceId
                val dir = KernelJournalSink.sessionDirFor(journals, trace)
                assertEquals("start", (KernelJournalReader.verifyAndLoad(dir).events.first().payload as SessionLifecyclePayload).phase)
                if (index == 0) {
                    write(packs, "policy", greedy)
                    write(packs, "catalog", catalog("extra"))
                }
                assertEquals(index == 1, session.eventCatalogFor(realEvents()).byId("extra") != null)
                assertEquals(pack.unknownEventPolicy, session.currentEventPackSet.extensionPack.unknownEventPolicy)
                feed(session, observed(event))
                val choice = if (index == 0) "23152" else "23151"
                val selected = session.state.value.eventChoiceDecision as LabyrinthEventChoiceDecision.Select
                assertEquals(choice, selected.choiceId)
                val provenance = "${pack.manifest.packId}@1.0.0#select/$choice"
                assertEquals(provenance, selected.ruleProvenance)
                val rule = log.filter(kind = KernelEventKinds.EVENT_RULE_EVALUATED, traceId = trace).last()
                assertEquals(provenance, (rule.payload as PolicyRuleEvaluatedPayload).ruleProvenance)

                session.encounterStack.push(KernelEncounterFrame("current", KernelEncounterKind.EVENT))
                assertTrue(session.stop())
                assertEquals(0, session.encounterStack.depth())
                val stopped = KernelJournalReader.verifyAndLoad(dir)
                assertEquals(log.byTraceId(trace), stopped.events)
                assertEquals("stop", (stopped.events.last().payload as SessionLifecyclePayload).phase)
                session.kernelRuntime.emit(KernelDomain.RUNTIME, "after_stop", StateReducedPayload("IDLE"))
                assertEquals(stopped, KernelJournalReader.verifyAndLoad(dir))
                assertEquals(0L, log.sinkFailureCount)
                assertTrue(dir.renameTo(journals.resolve("closed-$index")))
            }
        } finally { session.stop() }
        Unit
    }

    @Test fun failedStartupClosesTheJournalAfterRecordingRollback() = runBlocking {
        for (reason in listOf("overlay_unavailable", "launcher_unavailable")) {
            val manager = AutomationSessionManager()
            val journals = temporary.newFolder()
            lateinit var session: LabyrinthEntryRecognitionSession
            val overlay = AutomationOverlayCoordinator(object : AutomationOverlayHost {
                override fun show(sessionId: AutomationSessionId) {
                    session.encounterStack.push(KernelEncounterFrame("entry", KernelEncounterKind.ENTRY))
                    if (reason == "overlay_unavailable") error("test overlay unavailable")
                }
                override fun hide(sessionId: AutomationSessionId) = Unit
            })
            session = LabyrinthEntryRecognitionSession(manager, captureActive = { true },
                processorFactory = { { error("offline replay") } }, overlayCoordinator = overlay,
                actionExecutor = SessionBoundActionExecutor(manager) { error("no actions expected") },
                gameLauncher = { false }, journalDirectoryProvider = { journals })
            try {
                assertTrue(session.startAutomation() is LabyrinthEntryRecognitionStartResult.Blocked)
                assertNull(manager.current())
                assertEquals(0, session.encounterStack.depth())
                val dir = KernelJournalSink.sessionDirFor(journals, session.kernelRuntime.traceId)
                val journal = KernelJournalReader.verifyAndLoad(dir)
                assertEquals("start", (journal.events.first().payload as SessionLifecyclePayload).phase)
                assertEquals("stop", (journal.events.last().payload as SessionLifecyclePayload).phase)
                assertEquals(reason, journal.events.last().summary)
                val popped = journal.events.map { it.payload }.filterIsInstance<StateReducedPayload>().last()
                assertEquals("pop", popped.detail["op"])
                assertEquals(reason, popped.detail["reason"])
                session.kernelRuntime.emit(KernelDomain.RUNTIME, "after_failure", StateReducedPayload("IDLE"))
                assertEquals(journal, KernelJournalReader.verifyAndLoad(dir))
                assertEquals(0L, session.kernelRuntime.log.sinkFailureCount)
            } finally { session.stop() }
        }
        Unit
    }

    @Test fun draftMergesRepeatedSessionObservationsByTitleAndSlot() = runBlocking {
        val session = LabyrinthEntryRecognitionSession(AutomationSessionManager(), captureActive = { true },
            processorFactory = { { error("offline replay") } }, eventChoicePlanner = eventPlanner())
        try {
            assertTrue(session.start() is LabyrinthEntryRecognitionStartResult.Started)
            for ((index, title) in listOf("新 礼物！", "新礼物!", "新礼物!").withIndex()) {
                val id = "ocr/2/$index"
                val event = EventResource(id = id, name = title, choices = listOf(
                    EventChoiceResource("$id/1", "一", 1, if (index == 0) "金币×200" else ""),
                    EventChoiceResource("$id/2", "二", 2, if (index == 1) "金币×300" else "")))
                feed(session, observed(event, known = false).copy(rawText = "$title\n第${index}帧"))
            }
            val log = session.kernelRuntime.log
            assertEquals(3, log.filter(kind = KernelEventKinds.EVENT_UNKNOWN_OBSERVED).size)
            val dir = temporary.newFolder()
            dir.resolve("draft.json").writeText(UnknownEventDraftExporter.exportCatalogDraftJson(log))
            val pack = EventPackRepository(dir).loadCompiled()
            assertTrue(pack.quarantinedErrors.isEmpty())
            assertEquals(EventPackKind.CATALOG, pack.loadedManifests.single().kind)
            val registry = GameEventCatalogRegistry(realEvents()).apply { addOverlay(pack.extensionPack.overlay) }
            val discovered = requireNotNull(registry.resolveRecognitionAlias("新 礼物！"))
            assertEquals(discovered, registry.resolveRecognitionAlias("新礼物!"))
            assertTrue(discovered.name.isNotBlank())
            assertEquals(23, registry.toEventResources().size)
            assertEquals(listOf(1, 2), discovered.choices.map { it.slot })
            assertEquals(listOf("金币×200", "金币×300"), discovered.choices.map { it.description })
            for (choice in discovered.choices) {
                assertEquals(GameEventEffectParser.parse(choice.description).primitives, registry.effects(discovered.id, choice.id))
                assertTrue(registry.effects(discovered.id, choice.id).any { it is GameEventEffectPrimitive.AlphaCoin })
            }
        } finally { session.stop() }
        Unit
    }
}
