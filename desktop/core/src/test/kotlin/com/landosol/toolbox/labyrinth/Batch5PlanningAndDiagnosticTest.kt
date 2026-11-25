package com.landosol.toolbox.labyrinth

import com.landosol.toolbox.automation.capture.RefCountedFrameLease
import com.landosol.toolbox.labyrinth.concurrent.LabyrinthFourThreadRuntime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class Batch5PlanningAndDiagnosticTest {
    private val control = LabyrinthFourThreadRuntime.controlDispatcher
    private val scope = CoroutineScope(SupervisorJob() + control)
    private suspend fun awaitCondition(test: () -> Boolean) = withTimeout(4_000) {
        while (!withContext(control) { test() }) delay(2)
    }

    @Test fun `long CPU leaves control counter moving and running cancel does not start next body early`() = runBlocking {
        val entered = CountDownLatch(1)
        val exit = CountDownLatch(1)
        val nextBodies = AtomicInteger()
        val ticks = AtomicInteger()
        val slot = LabyrinthBalancedPlanningSlot<String, String>(scope, { 1000L }) {
            assertTrue(LabyrinthFourThreadRuntime.isControlThread())
        }
        try {
            withContext(control) { assertNull(slot.request("old") { entered.countDown(); exit.await(); "old-result" }) }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            repeat(12) { withContext(control) { ticks.incrementAndGet() } }
            assertEquals(12, ticks.get())
            withContext(control) {
                assertNull(slot.request("new") { nextBodies.incrementAndGet(); "new-result" })
                assertTrue(slot.busy)
            }
            assertEquals("cancelled future is not quiescence", 0, nextBodies.get())
            exit.countDown()
            awaitCondition { !slot.busy }
            withContext(control) {
                assertEquals("new-result", slot.request("new") { error("must use ready") })
                assertEquals(1L, slot.discarded)
            }
            assertEquals(1, nextBodies.get())
        } finally {
            exit.countDown()
            withContext(control) { slot.invalidate() }
            awaitCondition { !slot.busy }
        }
    }

    @Test fun `failure clears single flight and same semantic key retries instead of poisoning cache`() = runBlocking {
        val attempts = AtomicInteger()
        val slot = LabyrinthBalancedPlanningSlot<String, String>(scope, { 1000L }, {})
        try {
            withContext(control) { slot.request("same") { attempts.incrementAndGet(); error("transient") } }
            awaitCondition { !slot.busy }
            withContext(control) { assertNull(slot.request("same") { attempts.incrementAndGet(); "ok" }) }
            awaitCondition { !slot.busy }
            withContext(control) { assertEquals("ok", slot.request("same") { error("cached") }) }
            assertEquals(2, attempts.get())
        } finally { withContext(control) { slot.invalidate() }; awaitCondition { !slot.busy } }
    }

    @Test fun `pause invalidation discards old completion and cannot clear resumed new slot`() = runBlocking {
        val entered = CountDownLatch(1); val exit = CountDownLatch(1)
        val slot = LabyrinthBalancedPlanningSlot<String, String>(scope, { 1000L }, {})
        try {
            withContext(control) { slot.request("before-pause") { entered.countDown(); exit.await(); "old" } }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            withContext(control) {
                slot.invalidate()
                slot.request("after-resume") { "fresh" }
            }
            exit.countDown(); awaitCondition { !slot.busy }
            withContext(control) { assertEquals("fresh", slot.request("after-resume") { error("not cached") }) }
        } finally { exit.countDown(); withContext(control) { slot.invalidate() }; awaitCondition { !slot.busy } }
    }

    @Test fun `diagnostic lane active plus latest pending releases running lease only after exit`() = runBlocking {
        val entered = CountDownLatch(1); val exit = CountDownLatch(1)
        val disposed = AtomicInteger(); val invoked = AtomicInteger()
        val lane = LabyrinthBalancedDiagnosticLane(scope) { assertTrue(LabyrinthFourThreadRuntime.isControlThread()) }
        val active = RefCountedFrameLease.create("active") { disposed.incrementAndGet() }
        try {
            withContext(control) { lane.offer({ active.close() }) { entered.countDown(); exit.await(); active.value; invoked.incrementAndGet() } }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            withContext(control) {
                repeat(20) { index ->
                    val pending = RefCountedFrameLease.create("pending-$index") { disposed.incrementAndGet() }
                    lane.offer({ pending.close() }) { pending.value; invoked.incrementAndGet() }
                }
                assertTrue(lane.busy); assertTrue(lane.hasPending)
                assertEquals(19L, lane.replacements)
                assertEquals(19, disposed.get())
                lane.clear()
                assertTrue(lane.busy)
                assertFalse(active.isClosed)
                assertEquals(20, disposed.get())
            }
            exit.countDown(); awaitCondition { !lane.busy }
            assertTrue(active.isClosed)
            assertEquals(21, disposed.get())
            assertEquals(1, invoked.get())
        } finally { exit.countDown(); withContext(control) { lane.clear() }; awaitCondition { !lane.busy } }
    }

    @Test fun `qualification order roster context round and planner identity are semantic key inputs`() {
        fun planner() = LabyrinthRoleRewardChoicePlanner(emptyMap(), LabyrinthRoleChoicePolicy(
            LabyrinthTeamOptimizer(LabyrinthTeamScorer(LabyrinthTeamScoringConfig(attributeDamageBonus = (1..5).associateWith { 0.0 })))))
        val p = planner()
        val two = listOf(LabyrinthBalancedRoleCandidateKey("left", "r1", "一", true),
            LabyrinthBalancedRoleCandidateKey("center", "r2", "二", true),
            LabyrinthBalancedRoleCandidateKey("right", "r3", "三", false))
        val key = LabyrinthBalancedRolePlanningKey("session", 1, 2, 3, p, two, listOf("r5", "r4"),
            LabyrinthRoleDecisionContext(4))
        assertEquals(key, key.copy(candidates = two.toList()))
        assertNotEquals(key, key.copy(candidates = two.dropLast(1) + two.last().copy(qualified = true)))
        assertNotEquals(key, key.copy(candidates = two.reversed()))
        assertNotEquals(key, key.copy(acquiredIds = key.acquiredIds.reversed()))
        assertNotEquals(key, key.copy(context = key.context.copy(defenseMarkStacks = 5)))
        assertNotEquals(key, key.copy(round = 3))
        assertNotEquals(key, key.copy(generation = 2))
        assertNotEquals(key, key.copy(planner = planner()))
    }
}
