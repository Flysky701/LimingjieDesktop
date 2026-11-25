package com.landosol.toolbox.labyrinth.kernel

import com.landosol.toolbox.automation.*
import com.landosol.toolbox.gamedata.*
import com.landosol.toolbox.labyrinth.*
import com.landosol.toolbox.labyrinth.debug.LabyrinthDebugDashboardServer
import com.landosol.toolbox.labyrinth.kernel.KernelEventKinds as K
import com.landosol.toolbox.labyrinth.kernel.event.*
import com.landosol.toolbox.labyrinth.vision.*
import java.net.URI
import java.util.zip.ZipInputStream
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class EventDomainKernelReplayTest {
    /** Enter at the same accepted-detail boundary as a capture worker, with an in-memory backend. */
    private class Replay {
        private val manager = AutomationSessionManager()
        val taps = mutableListOf<AutomationAction>()
        val session = LabyrinthEntryRecognitionSession(manager, captureActive = { true },
            processorFactory = { { error("replay supplies measured frames") } }, eventChoicePlanner = eventPlanner(),
            actionExecutor = SessionBoundActionExecutor(manager) { taps += it; AutomationBackendResult.Completed },
            actionScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
        val log get() = session.kernelRuntime.log
        fun set(name: String, value: Any?) = session.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(session, value)
        suspend fun start() {
            assertTrue(session.startAutomation() is LabyrinthEntryRecognitionStartResult.Started)
            set("actionPlanner", null)
            set("entryPhaseComplete", true)
        }

        suspend fun feed(obs: LabyrinthEventChoiceObservation, postEntry: Boolean = false) {
            val page = LabyrinthEntryPageState.EVENT_CHOICE
            val frame = LabyrinthEntryFrameResult(LabyrinthEntryPageObservation(page, 1.0, mapOf(page to 1.0),
                LabyrinthAnchorScores(emptyMap())), matchedFeatures = emptyList(), elapsedMillis = 0,
                frameWidth = 1280, frameHeight = 720, eventChoiceSelection = obs)
            val at = System.currentTimeMillis()
            val id = requireNotNull(session.state.value.sessionId).value
            val method = session.javaClass.declaredMethods.single { it.name.startsWith("processFrameResult") &&
                '$' !in it.name && it.parameterCount == 6 }.apply { isAccessible = true }
            suspendCoroutineUninterceptedOrReturn<Unit> { continuation ->
                method.invoke(session, id, frame, 1280, 720, at, continuation)
            }
            if (postEntry) {
                session.javaClass.declaredMethods.single { it.name.startsWith("handlePostEntryPage") &&
                    '$' !in it.name && it.parameterCount == 5 }.apply { isAccessible = true }
                    .invoke(session, id, frame, 1280, 720, at)
            }
        }
    }

    @Test fun framesToHookToExecutorAndRejectedRetry() = runBlocking {
        val replay = Replay()
        try {
            replay.start()
            val session = replay.session
            val observation = observed(realEvents().single { it.id == "1108" })
            session.registerKernelHook(hook("right", interests = setOf(K.EVENT_DECISION_PROPOSED)) {
                KernelHookVerdict.OverrideEventChoice("11081")
            })
            replay.feed(observation.copy(trusted = false), true)
            assertTrue(replay.taps.isEmpty())
            replay.feed(observation.copy(choices = observation.choices.map {
                if (it.choice.slot == 2) it.copy(enabledConfidence = 0.0) else it
            }), true)
            assertFalse((session.state.value.eventChoiceDecision as LabyrinthEventChoiceDecision.Select).actionSafe)
            assertTrue(replay.taps.isEmpty())
            replay.feed(observation, true)
            assertEquals(1, replay.taps.size)
            val receipt = replay.log.filter(kind = K.ACTION_RECEIPT_OBSERVED).last()
            assertEquals("11081", (receipt.payload as ActionReceiptPayload).choiceId)
            val chain = replay.log.causalChain(receipt.eventSeq)
            val expected = listOf(K.FRAME_OBSERVED, K.EVENT_CATALOG_MATCHED, K.EVENT_RULE_EVALUATED, K.EVENT_DECISION_PROPOSED,
                K.HOOK_INVOKED, K.PERMIT_ISSUED, K.ACTION_DISPATCHED, K.ACTION_RECEIPT_OBSERVED)
            assertEquals(expected, chain.map { it.kind }.filter { it in expected }.distinct())
            assertTrue(chain.dropWhile { it.kind != K.FRAME_OBSERVED }.all { it.frameSeq == receipt.frameSeq })
            replay.set("eventChoiceCommittedAt", System.currentTimeMillis() - 8_000)
            replay.set("lastPostEntryActionAt", Long.MIN_VALUE)
            replay.set("lastPostEntryActionLandedAt", Long.MIN_VALUE)
            replay.feed(observation, true)
            assertTrue(replay.log.filter(reasonCode = "PAGE_UNCHANGED").isNotEmpty())
            assertEquals(1, replay.taps.size)
            session.unregisterKernelHook("right")
            replay.feed(observation, true)
            assertEquals(2, replay.taps.size)
            assertEquals("11082", (replay.log.filter(kind = K.ACTION_DISPATCHED).last().payload as ActionDispatchedPayload).choiceId)
            assertTrue(replay.log.filter(kind = K.PERMIT_REVOKED).isNotEmpty())
        } finally { replay.session.stop("offline replay complete") }
        Unit
    }

    @Test fun unknownPolicyAndDashboardExportReadTheSessionLog() = runBlocking {
        val replay = Replay()
        val server = LabyrinthDebugDashboardServer(port = 0, processLogSource = { ByteArray(0) })
        try {
            replay.start()
            val session = replay.session
            val choice = EventChoiceResource("gift", "领取", 1, "免费领取金币×300")
            val obs = observed(EventResource(id = "ocr/test", name = "新事件", choices = listOf(choice)), known = false)
            replay.feed(obs)
            assertTrue(session.state.value.eventChoiceDecision is LabyrinthEventChoiceDecision.Wait)
            session.updateEventExtensionPack(KernelEventExtensionPack(unknownEventPolicy = UnknownGameEventPolicy.SAFE_PARSE_OR_WAIT))
            replay.feed(obs)
            assertTrue((session.state.value.eventChoiceDecision as LabyrinthEventChoiceDecision.Select).actionSafe)
            replay.feed(observed(obs.event.copy(choices = listOf(choice.copy(description = "消耗200金币 + 金币×300"))), known = false))
            assertTrue(session.state.value.eventChoiceDecision is LabyrinthEventChoiceDecision.Wait)
            assertEquals(3, replay.log.filter(kind = K.EVENT_UNKNOWN_OBSERVED).size)
            server.start()
            fun url(path: String) = URI("http://127.0.0.1:" + server.boundPort + path).toURL()
            fun archive(): Map<String,String> = ZipInputStream(url("/logs.zip").openStream()).use { zip ->
                buildMap { while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.name == "kernel/events.ndjson") put(entry.name, zip.readBytes().decodeToString())
                } }
            }
            assertTrue(archive().isEmpty())
            server.attachKernelEventSource(replay.log)
            val snapshot = Json.decodeFromString<KernelLogSnapshot>(url("/api/kernel/events").readText())
            assertEquals(replay.log.snapshot(), snapshot)
            val connection = url("/api/kernel/events?format=ndjson").openConnection()
            assertEquals("application/x-ndjson; charset=utf-8", connection.contentType)
            val ndjson = connection.getInputStream().bufferedReader().use { it.readText() }
            assertEquals(replay.log.toNdjson(), ndjson)
            assertEquals(ndjson, archive()["kernel/events.ndjson"])
        } finally { server.close(); replay.session.stop("offline replay complete") }
        Unit
    }
}
