package com.landosol.toolbox.labyrinth.kernel

import com.landosol.toolbox.labyrinth.LabyrinthEntryRecognitionSession
import com.landosol.toolbox.labyrinth.vision.AndroidLabyrinthEventChoiceResolver
import com.landosol.toolbox.labyrinth.vision.EntryPixelRect
import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryPageState
import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryPageState.*
import java.io.IOException
import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class KernelContractsAndJournalTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun decisionContractsAndWireCompatibility() {
        for (category in KernelErrorCategory.entries) {
            val error = KernelError("missing", category, "frame", "no target")
            assertEquals(error, Json.decodeFromString<KernelError>(Json.encodeToString(error)))
        }
        val error = KernelError("missing", KernelErrorCategory.EVIDENCE, "frame")
        val decisions = listOf(KernelDecision.Wait("pending"), KernelDecision.Act("tap", "select", "one", EntryPixelRect(1,2,3,4)),
            KernelDecision.Complete("one", "done"), KernelDecision.FinishRun(true, "cleared"), KernelDecision.NeedEvidence(error, "read"))
        assertEquals(listOf("pending", "select", "done", "cleared", "missing"), decisions.map {
            when (it) {
                is KernelDecision.Wait -> it.reason
                is KernelDecision.Act -> it.label
                is KernelDecision.Complete -> it.summary
                is KernelDecision.FinishRun -> it.reason
                is KernelDecision.NeedEvidence -> it.error.code
            }
        })
        assertEquals(StateReducedPayload("HOME"), Json.decodeFromString<StateReducedPayload>("""{"pageState":"HOME"}"""))
        assertNull(Json.decodeFromString<PolicyRuleEvaluatedPayload>("""{"ruleId":null,"action":"Wait"}""").ruleProvenance)
    }

    @Test fun allPagesHaveTheExpectedStability() {
        assertEquals(LabyrinthEntryPageState.entries.toSet(), PageStabilityRegistry.allSpecs().keys)
        fun constant(owner: Class<*>, name: String) = owner.getDeclaredField(name).apply { isAccessible = true }.getInt(null)
        val session = LabyrinthEntryRecognitionSession::class.java
        assertEquals(constant(session, "NODE_CLICK_STABLE_FRAMES"), PageStabilityRegistry.specFor(NODE_SELECTION).requiredFrames)
        assertEquals(constant(session, "POST_ENTRY_STABLE_FRAMES"), PageStabilityRegistry.specFor(SHOP).requiredFrames)
        assertEquals(constant(AndroidLabyrinthEventChoiceResolver::class.java, "REQUIRED_STABLE_FRAMES"),
            PageStabilityRegistry.specFor(EVENT_CHOICE).requiredFrames)
        for (page in LabyrinthEntryPageState.entries) {
            val spec = PageStabilityRegistry.specFor(page)
            val expected = when (page) {
                GAME_LOADING_PROGRESS, PRE_HOME_DATA_LOADING, UNKNOWN -> PageStabilityMode.SINGLE_FRAME to 1
                NODE_SELECTION, NODE_MAP_VIEW -> PageStabilityMode.STATIC_TARGETS to 3
                INITIAL_CHARACTER_SELECTION, LINK_CHOICE, RELIC_CHOICE, EVENT_CHOICE, SHOP,
                BATTLE_CHALLENGE, BATTLE_TEAM_SELECTION -> PageStabilityMode.STATIC_TARGETS to 2
                else -> PageStabilityMode.PAGE_IDENTITY to 2
            }
            assertEquals(page, spec.page)
            assertEquals(expected, spec.mode to spec.requiredFrames)
            assertEquals(spec.mode == PageStabilityMode.STATIC_TARGETS, spec.maxCenterDriftPx != null)
        }
        assertThrows(IllegalArgumentException::class.java) { PageStabilitySpec(HOME, PageStabilityMode.PAGE_IDENTITY, 0) }
        assertThrows(IllegalArgumentException::class.java) { PageStabilitySpec(SHOP, PageStabilityMode.STATIC_TARGETS, 2, -1) }
    }

    @Test fun stackTransitionsStayInLogOrderUnderContention() {
        val runtime = KernelRuntime()
        val stack = KernelEncounterStack(runtime)
        val root = stack.push(KernelEncounterFrame("root", KernelEncounterKind.ENTRY))
        val pool = Executors.newFixedThreadPool(4)
        try {
            pool.invokeAll((1..24).map { Callable { stack.push(KernelEncounterFrame("node-$it", KernelEncounterKind.EVENT)) } })
                .forEach { it.get() }
        } finally { pool.shutdownNow() }
        val frames = stack.snapshot()
        assertEquals(25, stack.depth())
        assertEquals(frames.last(), stack.peek())
        assertThrows(IllegalArgumentException::class.java) { stack.push(root) }
        assertNull(stack.popExpected(root.id))
        assertEquals(frames.last(), stack.popExpected(frames.last().id))
        assertEquals(frames[23], stack.pop("done"))
        assertEquals(frames.take(23).reversed(), stack.clear())
        assertNull(stack.peek())
        assertNull(stack.pop())
        val transitions = runtime.log.filter(domain = KernelDomain.RUNTIME, kind = KernelEventKinds.STATE_REDUCED)
            .map { it.payload as StateReducedPayload }
        assertEquals(50, transitions.size)
        for ((index, frame) in frames.withIndex()) {
            assertEquals(frames.getOrNull(index - 1)?.id, frame.parentId)
            val pushed = transitions[index]
            val popped = transitions[transitions.lastIndex - index]
            assertEquals(frame.id, pushed.encounterId)
            assertEquals(frame.parentId, pushed.parentId)
            assertEquals(index + 1, pushed.depth)
            assertEquals("push", pushed.detail["op"])
            assertEquals(frame.kind.name, pushed.detail["kind"])
            assertEquals(frame.id, popped.encounterId)
            assertEquals(frame.parentId, popped.parentId)
            assertEquals(index, popped.depth)
            assertEquals("pop", popped.detail["op"])
        }
        assertEquals("done", transitions[26].detail["reason"])
    }

    @Test fun tracesHaveIndependentCountsAndCanBeReopened() {
        val dir = temporary.newFolder()
        val runtime = KernelRuntime()
        val traces = listOf("first", "../bad:name", "x".repeat(140) + "a", "x".repeat(140) + "b", "con")
        val paths = traces.map { KernelJournalSink.sessionDirFor(dir, it) }
        assertEquals(paths.size, paths.distinct().size)
        assertTrue(paths.all { it.canonicalFile.parentFile == dir.canonicalFile && it.name.length < 128 })
        KernelJournalSink(dir).use { sink ->
            runtime.addSink(sink)
            for (trace in traces) {
                runtime.resetSession(trace)
                runtime.emit(KernelDomain.ENTRY, "state", StateReducedPayload("HOME"))
            }
            runtime.removeSink(sink)
        }
        KernelJournalSink(dir).use { sink ->
            runtime.addSink(sink)
            runtime.emit(KernelDomain.RUNTIME, "last", StateReducedPayload("END"))
            runtime.removeSink(sink)
        }
        for ((index, trace) in traces.withIndex()) {
            val loaded = KernelJournalReader.verifyAndLoad(paths[index])
            val expected = runtime.log.byTraceId(trace)
            assertEquals(expected, loaded.events)
            assertEquals(expected.size.toLong(), loaded.snapshot.eventCount)
            assertEquals(loaded.snapshot.eventCount, loaded.snapshot.revision)
            assertEquals(2 * index + 1L, loaded.events.first().eventSeq)
            assertEquals(expected.last().eventSeq, loaded.snapshot.lastEventSeq)
        }
    }

    @Test fun readerIdentifiesCorruptSnapshotsAndLines() {
        val root = temporary.newFolder()
        val runtime = KernelRuntime()
        KernelJournalSink(root).use { sink ->
            runtime.addSink(sink)
            runtime.resetSession("test")
            runtime.emit(KernelDomain.RUNTIME, "next", StateReducedPayload("HOME"))
            runtime.removeSink(sink)
        }
        val dir = KernelJournalSink.sessionDirFor(root, "test")
        val snapshotFile = dir.resolve("snapshot.json")
        val eventsFile = dir.resolve("events.ndjson")
        val snapshot = KernelJournalReader.verifyAndLoad(dir).snapshot
        val first = runtime.log.recent().first()
        val line = Json.encodeToString<KernelEvent>(first)
        val good = eventsFile.readText()
        fun rejected(message: String) {
            val error = assertThrows(IllegalArgumentException::class.java) { KernelJournalReader.verifyAndLoad(dir) }
            assertTrue(error.message, error.message.orEmpty().contains(message))
        }
        for ((bad, message) in listOf(good.trimEnd() to "newline", good + "\n" to "Blank",
                "{bad}\n" to "JSON", "$line\n$line\n" to "eventSeq", "$line\n" to "eventCount",
                Json.encodeToString<KernelEvent>(first.copy(traceId = "other")) + "\n" to "traceId",
                Json.encodeToString<KernelEvent>(first.copy(eventSeq = 0)) + "\n" to "eventSeq")) {
            eventsFile.writeText(bad)
            rejected(message)
        }
        eventsFile.writeText(good)
        for ((bad, message) in listOf(snapshot.copy(revision = 3) to "revision",
                snapshot.copy(schemaVersion = 2) to "schemaVersion", snapshot.copy(lastEventSeq = 9) to "lastEventSeq")) {
            snapshotFile.writeText(Json.encodeToString(bad))
            rejected(message)
        }
    }

    @Test fun failedSinkDoesNotBlockTheRingOrOtherSinksAndCancellationEscapes() {
        val runtime = KernelRuntime()
        val bad = KernelEventSink { throw IOException("disk full") }
        val received = mutableListOf<KernelEvent>()
        val good = KernelEventSink { received.add(it) }
        runtime.addSink(bad)
        runtime.log.addSink(good)
        val first = runtime.resetSession()
        assertEquals(listOf(first), received)
        assertEquals(1L, runtime.log.sinkFailureCount)
        runtime.log.removeSink(bad)
        runtime.removeSink(good)
        runtime.resetSession()
        assertEquals(1, received.size)
        assertEquals(2, runtime.log.recent().size)
        runtime.addSink(KernelEventSink { throw CancellationException() })
        assertThrows(CancellationException::class.java) { runtime.resetSession() }
    }
}
