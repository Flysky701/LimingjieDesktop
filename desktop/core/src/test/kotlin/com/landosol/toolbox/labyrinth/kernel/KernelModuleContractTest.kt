package com.landosol.toolbox.labyrinth.kernel

import com.landosol.toolbox.automation.session.SessionBlockKind
import com.landosol.toolbox.labyrinth.LabyrinthCombatKind
import com.landosol.toolbox.labyrinth.kernel.map.MapNodeTarget
import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryPageState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KernelModuleContractTest {
    @Test fun resetClearsEveryActiveDomainAndTheStackBeforePublishingTheNewTrace() {
        val lifecycle = mutableListOf<String>()
        lateinit var bundle: LabyrinthKernelBundle
        lateinit var runtime: KernelRuntime
        val idle = listOf(
            "game-event" to KernelDomain.EVENT, "recovery" to KernelDomain.RECOVERY,
            "settlement" to KernelDomain.SETTLEMENT, "reward-tail" to KernelDomain.REWARD,
            "shop" to KernelDomain.SHOP, "battle" to KernelDomain.BATTLE, "map" to KernelDomain.MAP,
        ).map { (id, domain) -> KernelModuleStatus(id, domain, false, "idle") }
        runtime = KernelRuntime(onTraceChanged = { trace ->
            assertEquals(trace, runtime.traceId)
            assertEquals(idle, bundle.moduleStatuses())
            assertEquals(0, bundle.encounterStack.depth())
            assertNull(bundle.encounterStack.lastTransition())
            lifecycle += "trace:$trace"
        })
        bundle = LabyrinthKernelBundle(runtime)
        runtime.addSink { if (it.kind == KernelEventKinds.SESSION_LIFECYCLE) lifecycle += "start:${it.traceId}" }
        runtime.resetSession("first")

        runtime.emit(KernelDomain.EVENT, KernelEventKinds.EVENT_CATALOG_MATCHED, GameEventObservedPayload("event", 1, true))
        bundle.recovery.restoreTracking(SessionBlockKind.RELOGIN_REQUIRED, 1, 0, 0, Long.MIN_VALUE)
        bundle.settlement.beginFinalSettlement(1L)
        bundle.reward.beginFrame(1, 1, 1, LabyrinthEntryPageState.RELIC_CHOICE)
        bundle.shop.onEnterShopNode()
        bundle.encounterStack.push(KernelEncounterFrame("parent", KernelEncounterKind.EVENT))
        bundle.battle.enterNestedBattle("parent", LabyrinthCombatKind.EX, 1)
        bundle.map.acceptRouteClick(MapNodeTarget(42, 1), 1L)
        assertEquals(2, bundle.encounterStack.depth())
        val active = bundle.moduleStatuses()
        assertEquals(idle.map { it.id }, active.filter { it.active }.map { it.id })
        assertEquals(listOf("observed", "blocked", "BEFORE_SCORE", "RELIC_CHOICE", "entered", "nested_battle", "transition"),
            active.map { it.phase })

        val start = runtime.resetSession("next")
        assertEquals(idle, runtime.moduleStatuses())
        assertEquals(listOf(start), runtime.log.byTraceId("next"))
        assertEquals(listOf("trace:first", "start:first", "trace:next", "start:next"), lifecycle)
    }
}
