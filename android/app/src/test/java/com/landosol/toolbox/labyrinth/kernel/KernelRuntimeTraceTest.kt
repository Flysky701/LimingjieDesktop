package com.landosol.toolbox.labyrinth.kernel

import com.landosol.toolbox.labyrinth.kernel.KernelEventKinds as K
import java.util.concurrent.Executors
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

internal fun hook(name: String, order: Int = 0, interests: Set<String> = emptySet(),
                  body: (KernelEvent) -> KernelHookVerdict) = object : KernelEventHook {
    override val id = name
    override val priority = order
    override val kinds = interests
    override fun handle(event: KernelEvent, ctx: KernelRuntime) = body(event)
}

class KernelRuntimeTraceTest {
    private val sample = CustomPayload(mapOf("text" to "two\nlines"))
    @Test fun ringEvictionAndMalformedCycleTerminate() {
        val runtime = KernelRuntime({ 5L }, 3)
        val root = runtime.resetSession("first")
        val a = runtime.emit(KernelDomain.EVENT, "a", sample, root)
        val b = runtime.emit(KernelDomain.EVENT, "b", sample, a)
        val c = runtime.emit(KernelDomain.EVENT, "c", sample, b)
        assertEquals(listOf(a,b,c), runtime.log.causalChain(c.eventSeq))
        assertEquals(listOf(b,c), runtime.log.descendants(a.eventSeq))
        assertEquals(listOf(b,c), runtime.log.filter(domain = KernelDomain.EVENT, traceId = "first", sinceSeq = a.eventSeq))
        assertEquals(4L, runtime.log.summary().total)
        assertEquals(3, runtime.log.summary().byDomain[KernelDomain.EVENT])
        assertEquals(listOf(a,b,c), runtime.log.toNdjson().lineSequence().map { Json.decodeFromString<KernelEvent>(it) }.toList())
        val malformed = KernelEventLog()
        malformed.append(a.copy(causeEventSeq = b.eventSeq))
        malformed.append(b)
        assertEquals(2, malformed.causalChain(a.eventSeq).size)
    }

    @Test fun nestedHooksAreOrderedAuditedAndBounded() {
        val runtime = KernelRuntime()
        val order = mutableListOf<String>()
        val k = setOf(K.EVENT_DECISION_PROPOSED)
        runtime.registerHook(hook("b", 2, k) { order += "b"; KernelHookVerdict.OverrideEventChoice("R") })
        runtime.registerHook(hook("a", 2, k) { order += "a"; KernelHookVerdict.Emit("child", sample) })
        runtime.registerHook(hook("stop", 0, k) { order += "stop"; KernelHookVerdict.Veto("blocked") })
        fun propose() = runtime.emit(KernelDomain.EVENT, K.EVENT_DECISION_PROPOSED, sample)
        val root = propose()
        assertEquals(listOf("a", "b"), order)
        assertEquals(KernelHookVerdict.OverrideEventChoice("R"), runtime.latestIntervention?.verdict)
        assertEquals(listOf(K.HOOK_INVOKED, "child", K.HOOK_INVOKED), runtime.log.descendants(root.eventSeq).map { it.kind })
        runtime.unregisterHook("b")
        propose()
        assertEquals(KernelHookVerdict.Veto("blocked"), runtime.latestIntervention?.verdict)
        runtime.registerHook(hook("failure", 9, k) { error("broken") })
        propose()
        assertTrue((runtime.latestIntervention?.verdict as KernelHookVerdict.Veto).reason.contains("HOOK_FAILED"))
        runtime.registerHook(hook("loop", interests = setOf("loop")) { KernelHookVerdict.Emit("loop", sample) })
        val loop = runtime.emit(KernelDomain.CUSTOM_HOOK, "loop", sample)
        assertEquals(KernelRuntime.MAX_REENTRANCY_DEPTH, runtime.log.filter(kind = "loop").size)
        assertEquals("DEPTH_LIMIT", runtime.log.descendants(loop.eventSeq).last().reasonCode)
        runtime.resetSession("next")
        assertNull(runtime.latestIntervention)
        assertEquals(1, runtime.log.byTraceId("next").size)
    }

    @Test fun moduleRegistrationResetAndRemoval() {
        val runtime = KernelRuntime()
        val calls = mutableListOf<String>()
        runtime.registerModule(object : KernelDomainModule {
            override val id = "observer"
            override val domain = KernelDomain.EVENT
            override fun onKernelEvent(event: KernelEvent, ctx: KernelRuntime) { calls += event.kind }
            override fun reset(traceId: String) { calls += traceId }
        })
        runtime.resetSession("one")
        runtime.emit(KernelDomain.MAP, "map", sample)
        runtime.emit(KernelDomain.EVENT, "event", sample)
        runtime.unregisterModule("observer")
        runtime.resetSession("two")
        runtime.emit(KernelDomain.EVENT, "later", sample)
        assertEquals(listOf("one", "event"), calls)
    }

    @Test fun concurrentEmissionAndPermitRevisions() {
        val runtime = KernelRuntime()
        val pool = Executors.newFixedThreadPool(3)
        try {
            val jobs = (1..60).map { pool.submit { runtime.emit(KernelDomain.EVENT, "parallel", sample) } }
            jobs.forEach { it.get() }
        } finally { pool.shutdown() }
        assertEquals((1L..60L).toList(), runtime.log.recent().map { it.eventSeq })
        assertTrue(runtime.log.recent().all { it.causeEventSeq == null })
        val permit = KernelActionPermit<String>(10) { issued, key, rev, reason ->
            runtime.emit(KernelDomain.EVENT, if (issued) K.PERMIT_ISSUED else K.PERMIT_REVOKED, PermitTransitionPayload(key, rev, reason))
        }
        val first = permit.issue("a", 100)
        assertTrue(first.isCurrent(110))
        assertFalse(first.isCurrent(111))
        val second = permit.issue("b", 105)
        assertFalse(first.isCurrent(106))
        permit.revoke()
        assertFalse(second.isCurrent(106))
        assertEquals(listOf(K.PERMIT_ISSUED,K.PERMIT_REVOKED,K.PERMIT_ISSUED,K.PERMIT_REVOKED),
            runtime.log.recent(4).map { it.kind })
    }
}
