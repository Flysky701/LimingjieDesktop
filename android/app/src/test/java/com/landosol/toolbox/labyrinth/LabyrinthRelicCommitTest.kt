package com.landosol.toolbox.labyrinth

import com.landosol.toolbox.automation.*
import com.landosol.toolbox.automation.accessibility.GAME_NOT_FOREGROUND_REASON
import com.landosol.toolbox.labyrinth.vision.*
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** Exercises the real session commit boundary without screenshots or Android OCR. */
class LabyrinthRelicCommitTest {
    private val mark = LabyrinthRelicMark.DEFENSE
    private val rect = EntryPixelRect(0, 0, 100, 100)
    private fun relic(id: String = "a") = LabyrinthRelicMatch(
        "choice", id, id, "守备", "2", "", 0.99, rect, iconRect = rect,
    )

    @Test fun `confirmed purchase survives unknown frames and completion replay adds nothing`() = runBlocking {
        val harness = Harness()
        try {
            harness.start()
            val candidate = relic()
            harness.plan(candidate)
            for (time in listOf(1_000L, 2_000L)) {
                harness.frame(LabyrinthEntryPageState.SHOP_PURCHASE_CONFIRMATION, time,
                    LabyrinthShopObservation(emptyList(), LabyrinthShopDialogState.PURCHASE_CONFIRMATION,
                        purchaseCandidate = candidate))
            }
            assertEquals(candidate, harness.get("pendingShopPurchase"))
            harness.dispatch(LabyrinthPostEntryActionKind.SHOP_CONFIRM_RELIC, candidate.relicId)
            harness.awaitAction()
            harness.frame(LabyrinthEntryPageState.UNKNOWN, 2_100)
            assertEquals(candidate, harness.get("pendingShopPurchase"))
            val complete = LabyrinthShopObservation(emptyList(), LabyrinthShopDialogState.PURCHASE_COMPLETE)
            harness.frame(LabyrinthEntryPageState.SHOP_PURCHASE_COMPLETE, 3_000, complete)
            harness.frame(LabyrinthEntryPageState.SHOP_PURCHASE_COMPLETE, 4_000, complete)
            assertEquals(listOf("a"), harness.store.snapshot.observedRelics.map { it.relicId })
            assertEquals(11, harness.ledger.stacks[mark])
            assertEquals(harness.store.snapshot.relicStacks, harness.ledger.records)
        } finally { harness.close() }
    }

    @Test fun `cancel arriving during suspended commit cannot resurrect a prepared acquisition`() = runBlocking {
        val action = CompletableDeferred<AutomationBackendResult>()
        val enteredAction = CompletableDeferred<Unit>()
        val harness = Harness { enteredAction.complete(Unit); action.await() }
        try {
            harness.start()
            harness.plan(relic())
            harness.dispatch(LabyrinthPostEntryActionKind.SHOP_BUY_RELIC, "a")
            withTimeout(5_000) { enteredAction.await() }
            val writeEntered = CompletableDeferred<Unit>()
            val releaseWrite = CompletableDeferred<Unit>()
            harness.store.beforeWrite = { writeEntered.complete(Unit); releaseWrite.await() }
            val staged = harness.ledger.fork()
            val writing = async { harness.persist(harness.result(LabyrinthEntryPageState.UNKNOWN), 2_000, staged) }
            writeEntered.await()
            action.complete(AutomationBackendResult.Rejected(GAME_NOT_FOREGROUND_REASON))
            harness.awaitAction()
            assertTrue(harness.session.state.value.running)
            assertNull(harness.get("plannedShopPurchaseRelicId"))
            releaseWrite.complete(Unit)
            assertTrue(writing.await())
            assertFalse(harness.ledger.hasAcquisition("a"))
            harness.ledger.observe(mark, 12, LabyrinthRelicStackSource.CHOICE, "slot", 1)
            harness.ledger.observe(mark, 12, LabyrinthRelicStackSource.CHOICE, "slot", 2)
            assertEquals(12, harness.ledger.stacks[mark])
        } finally { harness.close() }
    }

    @Test fun `stopping while store is suspended cannot publish the old frame`() = runBlocking {
        val harness = Harness()
        try {
            harness.start()
            val writeEntered = CompletableDeferred<Unit>()
            val releaseWrite = CompletableDeferred<Unit>()
            harness.store.beforeWrite = { writeEntered.complete(Unit); releaseWrite.await() }
            val staged = harness.ledger.fork()
            staged.prepare("a", mark, 2, 9)
            staged.acquired("a", 1_000)
            val writing = async { harness.persist(harness.result(LabyrinthEntryPageState.UNKNOWN), 2_000, staged) }
            writeEntered.await()
            harness.session.stop()
            releaseWrite.complete(Unit)
            assertFalse(writing.await())
            assertFalse(harness.session.state.value.running)
            assertTrue(harness.ledger.stacks.isEmpty())
        } finally { harness.close() }
    }

    @Test fun `database receipt rather than rejected staged totals is published`() = runBlocking {
        val harness = Harness()
        try {
            harness.start()
            val staged = harness.ledger.fork()
            staged.prepare("a", mark, 2, 9)
            staged.acquired("a", 1_000)
            val durable = mapOf(mark to LabyrinthRelicStackRecord(9, LabyrinthRelicStackSource.DETAIL))
            harness.store.stackReceipt = durable
            assertTrue(harness.persist(harness.result(LabyrinthEntryPageState.UNKNOWN), 2_000, staged))
            assertEquals(durable, harness.ledger.records)
        } finally { harness.close() }
    }

    @Test fun `new run rejects a late successful role action from the previous generation`() = runBlocking {
        val action = CompletableDeferred<AutomationBackendResult>()
        val enteredAction = CompletableDeferred<Unit>()
        val harness = Harness { enteredAction.complete(Unit); action.await() }
        try {
            harness.start()
            harness.dispatch(LabyrinthPostEntryActionKind.SELECT_ROLE_REWARD)
            withTimeout(5_000) { enteredAction.await() }
            harness.frame(LabyrinthEntryPageState.INITIAL_CHARACTER_SELECTION, 2_000)
            val writes = harness.store.writes
            action.complete(AutomationBackendResult.Completed)
            harness.awaitAction()
            assertEquals(writes, harness.store.writes)
            assertFalse(harness.session.skipRoleRewardJoinedRecognition)
            assertTrue(harness.store.snapshot.joinedCharacters.isEmpty())
        } finally { harness.close() }
    }

    @Test fun `restart on a relic effect receipt preserves generation and does not reapply it`() = runBlocking {
        val harness = Harness(route = openingRelicRoute(guildId = 5))
        try {
            harness.start()
            harness.frame(LabyrinthEntryPageState.INITIAL_CHARACTER_SELECTION, 2_000)
            harness.frame(LabyrinthEntryPageState.CHARACTER_JOINED, 2_100)
            val random = relic("11001").copy(attribute = "加速", attributeBonus = "1")
            harness.reward(LabyrinthRelicRewardKind.RELIC_EFFECT, listOf(random), 2_200)
            harness.reward(LabyrinthRelicRewardKind.RELIC_EFFECT, listOf(random), 2_300)
            val saved = harness.store.snapshot
            assertEquals(3, saved.observedRelics.size)
            harness.close()
            harness.now = 3_000
            harness.start()
            harness.reward(LabyrinthRelicRewardKind.RELIC_EFFECT, listOf(random), 3_100)
            harness.reward(LabyrinthRelicRewardKind.RELIC_EFFECT, listOf(random), 3_200)
            harness.reward(LabyrinthRelicRewardKind.RELIC_EFFECT, listOf(random), 3_300)
            assertEquals(saved.startedAt, harness.store.snapshot.startedAt)
            assertEquals(saved.openingRelicReceipt, harness.store.snapshot.openingRelicReceipt)
            assertEquals(saved.observedRelics, harness.store.snapshot.observedRelics)
            assertEquals(2, harness.ledger.stacks[LabyrinthRelicMark.ACCELERATION])
        } finally { harness.close() }
    }

    @Test fun `ordinary item popup and unconfirmed or unknown title never reset or record rewards`() = runBlocking {
        val harness = Harness()
        try {
            harness.start()
            val startedAt = harness.store.snapshot.startedAt
            harness.frame(LabyrinthEntryPageState.ITEM_REWARD, 2_000)
            assertTrue(harness.reward(LabyrinthRelicRewardKind.RELIC_EFFECT, listOf(relic()), 2_100, confirmed = false))
            assertTrue(harness.reward(LabyrinthRelicRewardKind.RELIC_EFFECT, listOf(relic()), 2_200, confirmed = false))
            assertTrue(harness.reward(null, listOf(relic()), 2_300))
            assertTrue(harness.reward(null, listOf(relic()), 2_400))
            assertEquals(startedAt, harness.store.snapshot.startedAt)
            assertNull(harness.store.snapshot.openingRelicReceipt)
            assertFalse(harness.get("openingRelicBaselineKnown") as Boolean)
            assertTrue(harness.store.snapshot.observedRelics.isEmpty())
        } finally { harness.close() }
    }

    @Test fun `effect receipt closes only after complete stable rows and committed identities`() = runBlocking {
        val harness = Harness(route = openingRelicRoute(nodes = emptySet()))
        try {
            harness.start()
            harness.frame(LabyrinthEntryPageState.INITIAL_CHARACTER_SELECTION, 1_500)
            harness.frame(LabyrinthEntryPageState.CHARACTER_JOINED, 1_600)
            val first = relic("40006").copy(slotId = "first", attributeBonus = "1")
            val second = relic("50006").copy(slotId = "second", attributeBonus = "1")
            assertTrue(harness.reward(LabyrinthRelicRewardKind.RELIC_EFFECT, listOf(first, second), 2_000))
            assertTrue(harness.reward(LabyrinthRelicRewardKind.RELIC_EFFECT, listOf(first), 2_100))
            assertTrue(harness.reward(LabyrinthRelicRewardKind.RELIC_EFFECT, listOf(first), 2_200))
            assertEquals(listOf("40003"), harness.store.snapshot.observedRelics.map { it.relicId })
            assertTrue(harness.reward(LabyrinthRelicRewardKind.RELIC_EFFECT, listOf(first, second), 2_300))
            assertFalse(harness.reward(LabyrinthRelicRewardKind.RELIC_EFFECT, listOf(first, second), 2_400))
            assertEquals(3, harness.store.snapshot.observedRelics.size)
            assertEquals(2, harness.ledger.stacks[mark])
            val mistaken = second.copy(relicId = first.relicId, displayName = first.displayName)
            assertTrue(harness.reward(LabyrinthRelicRewardKind.RELIC_EFFECT, listOf(first, mistaken), 2_500))
            assertTrue(harness.reward(LabyrinthRelicRewardKind.RELIC_EFFECT, listOf(first, second), 2_600))
            assertFalse(harness.reward(LabyrinthRelicRewardKind.RELIC_EFFECT, listOf(first, second), 2_700))
            assertFalse(harness.reward(LabyrinthRelicRewardKind.RELIC_EFFECT, listOf(first, second), 2_800))
            assertEquals(3, harness.store.snapshot.observedRelics.size)
            assertEquals(2, harness.ledger.stacks[mark])
        } finally { harness.close() }
    }

    @Test fun `special relic is recorded without a mark while incomplete receipt remains held`() = runBlocking {
        val harness = Harness()
        try {
            harness.start()
            val special = relic("50011").copy(attribute = "特殊", attributeBonus = "0")
            harness.reward(LabyrinthRelicRewardKind.RELIC_EFFECT, listOf(special), 2_000, complete = false)
            harness.reward(LabyrinthRelicRewardKind.RELIC_EFFECT, listOf(special), 2_100, complete = false)
            assertTrue(harness.store.snapshot.observedRelics.isEmpty())
            harness.reward(LabyrinthRelicRewardKind.RELIC_EFFECT, listOf(special), 2_200)
            harness.reward(LabyrinthRelicRewardKind.RELIC_EFFECT, listOf(special), 2_300)
            assertEquals(listOf("50011"), harness.store.snapshot.observedRelics.map { it.relicId })
            assertTrue(harness.ledger.stacks.isEmpty())
        } finally { harness.close() }
    }

    @Test fun `entry close planner receives consecutive ready frames after durable effect receipt`() = runBlocking {
        val harness = Harness(route = openingRelicRoute(nodes = emptySet()))
        try {
            harness.start()
            harness.frame(LabyrinthEntryPageState.INITIAL_CHARACTER_SELECTION, 1_500)
            harness.frame(LabyrinthEntryPageState.CHARACTER_JOINED, 1_600)
            val item = relic("11001").copy(attributeBonus = "1")
            val planner = LabyrinthEntryActionPlanner()
            assertTrue(harness.reward(LabyrinthRelicRewardKind.RELIC_EFFECT, listOf(item), 2_000))
            assertFalse(harness.reward(LabyrinthRelicRewardKind.RELIC_EFFECT, listOf(item), 2_100))
            assertTrue(planner.decide(LabyrinthEntryPageState.ITEM_REWARD, 1_280, 720, 2_100) is LabyrinthEntryActionDecision.Wait)
            assertFalse(harness.reward(LabyrinthRelicRewardKind.RELIC_EFFECT, listOf(item), 2_200))
            val close = planner.decide(LabyrinthEntryPageState.ITEM_REWARD, 1_280, 720, 2_200)
            assertEquals(LabyrinthEntryActionKind.CLOSE_ITEM_REWARD, (close as LabyrinthEntryActionDecision.Execute).kind)
            assertEquals(2, harness.store.snapshot.observedRelics.size)
        } finally { harness.close() }
    }

    @Test fun `Mercurius coin-only opening receipt closes without adding relics`() = runBlocking {
        val harness = Harness(route = openingRelicRoute(guildId = 9))
        try {
            harness.start()
            harness.frame(LabyrinthEntryPageState.INITIAL_CHARACTER_SELECTION, 1_500)
            harness.frame(LabyrinthEntryPageState.CHARACTER_JOINED, 1_600)
            val saved = harness.store.snapshot
            assertEquals(listOf("40009", "50009"), saved.observedRelics.map { it.relicId })
            val critical = LabyrinthRelicMark.CRITICAL
            assertEquals(setOf(critical), harness.ledger.verificationMarks)
            harness.ledger.observe(critical, 2, LabyrinthRelicStackSource.MAP, "map", 1, 1_700)
            harness.ledger.observe(critical, 2, LabyrinthRelicStackSource.MAP, "map", 2, 1_800)
            assertFalse(harness.ledger.peekWaitingForMap(1_800))
            val evidenceVersion = harness.get("relicEvidenceVersion")
            val slots = setOf("reward_6")
            val kind = LabyrinthRelicRewardKind.RELIC_EFFECT
            assertTrue(harness.reward(kind, emptyList(), 2_000, complete = false, slots = slots))
            assertEquals("正在确认奖励内容，暂不关闭弹窗", harness.session.state.value.message)
            assertTrue(harness.reward(kind, emptyList(), 2_100, confirmed = false, slots = slots))
            assertEquals("正在确认奖励内容，暂不关闭弹窗", harness.session.state.value.message)
            assertTrue(harness.reward(kind, emptyList(), 2_200, slots = slots))
            assertFalse(harness.reward(kind, emptyList(), 2_300, slots = slots))
            val planner = LabyrinthEntryActionPlanner()
            assertTrue(planner.decide(LabyrinthEntryPageState.ITEM_REWARD, 1_280, 720, 2_300) is LabyrinthEntryActionDecision.Wait)
            assertFalse(harness.reward(kind, emptyList(), 2_400, slots = slots))
            val close = planner.decide(LabyrinthEntryPageState.ITEM_REWARD, 1_280, 720, 2_400)
            assertEquals(LabyrinthEntryActionKind.CLOSE_ITEM_REWARD, (close as LabyrinthEntryActionDecision.Execute).kind)
            assertEquals(saved.startedAt, harness.store.snapshot.startedAt)
            assertEquals(saved.observedRelics, harness.store.snapshot.observedRelics)
            assertEquals(saved.relicStacks, harness.store.snapshot.relicStacks)
            assertEquals(saved.relicStacks, harness.ledger.records)
            assertEquals(evidenceVersion, harness.get("relicEvidenceVersion"))
            assertTrue(harness.ledger.verificationMarks.isEmpty())
            assertFalse(harness.ledger.peekWaitingForMap(2_400))
        } finally { harness.close() }
    }

    @Test fun `fixed guild grants are recorded only at the initial character selection`() = runBlocking {
        val newGuildMarks = mapOf(
            6 to LabyrinthRelicMark.ENHANCEMENT, 7 to LabyrinthRelicMark.ACCELERATION,
            8 to LabyrinthRelicMark.DEFENSE, 9 to LabyrinthRelicMark.CRITICAL,
            10 to LabyrinthRelicMark.DEBUFF,
        )
        for (guild in 1..10) for (unlocked in listOf(false, true)) {
            val harness = Harness(route = openingRelicRoute(guild, if (unlocked) setOf(31) else emptySet()))
            try {
                harness.start()
                harness.frame(LabyrinthEntryPageState.DAWN_REALM_HOME_ACTIVE, 1_500)
                assertTrue(harness.store.snapshot.observedRelics.isEmpty())
                harness.frame(LabyrinthEntryPageState.INITIAL_CHARACTER_SELECTION, 2_000)
                val ids = listOfNotNull((40_000 + guild).toString(), (50_000 + guild).toString().takeIf { unlocked })
                assertEquals("guild $guild", ids, harness.store.snapshot.observedRelics.map { it.relicId })
                val startedAt = harness.store.snapshot.startedAt
                harness.frame(LabyrinthEntryPageState.INITIAL_CHARACTER_SELECTION, 2_100)
                harness.frame(LabyrinthEntryPageState.CHARACTER_JOINED, 2_200)
                harness.frame(LabyrinthEntryPageState.NODE_SELECTION, 2_300)
                assertEquals(startedAt, harness.store.snapshot.startedAt)
                assertEquals(ids, harness.store.snapshot.observedRelics.map { it.relicId })
                assertEquals(ids.size, harness.ledger.stacks.values.sum())
                newGuildMarks[guild]?.let { expectedMark ->
                    assertEquals("guild $guild", mapOf(expectedMark to ids.size), harness.ledger.stacks)
                }
                assertTrue(harness.get("openingRelicBaselineKnown") as Boolean)
                assertEquals(LabyrinthOpeningRelicReceipt(42, true), harness.store.snapshot.openingRelicReceipt)
                assertEquals(harness.ledger.records, harness.store.snapshot.relicStacks)
            } finally { harness.close() }
        }
    }

    @Test fun `unknown enhancement status records only base relic and leaves totals correctable`() = runBlocking {
        val harness = Harness(route = openingRelicRoute(nodes = null))
        try {
            harness.start()
            harness.frame(LabyrinthEntryPageState.INITIAL_CHARACTER_SELECTION, 2_000)
            assertEquals(listOf("40003"), harness.store.snapshot.observedRelics.map { it.relicId })
            assertFalse(harness.get("openingRelicBaselineKnown") as Boolean)
            val acceleration = LabyrinthRelicMark.ACCELERATION
            harness.ledger.observe(acceleration, 2, LabyrinthRelicStackSource.MAP, "map", 1, 2_100)
            harness.ledger.observe(acceleration, 2, LabyrinthRelicStackSource.MAP, "map", 2, 2_200)
            assertEquals(2, harness.ledger.stacks[acceleration])
        } finally { harness.close() }
    }

    @Test fun `restart during initial selection preserves grants only for the same enter id`() = runBlocking {
        val route = openingRelicRoute()
        val harness = Harness(route = route)
        try {
            harness.start()
            harness.frame(LabyrinthEntryPageState.INITIAL_CHARACTER_SELECTION, 2_000)
            val started = harness.store.snapshot.startedAt
            harness.close()
            harness.now = 2_500
            harness.start()
            harness.frame(LabyrinthEntryPageState.INITIAL_CHARACTER_SELECTION, 2_600)
            assertEquals(started, harness.store.snapshot.startedAt)
            assertEquals(listOf("40003", "50003"), harness.store.snapshot.observedRelics.map { it.relicId })
            harness.close()
            harness.route = openingRelicRoute(guildId = 2, nodes = emptySet(), enterId = 43)
            harness.now = 3_000
            harness.start()
            harness.frame(LabyrinthEntryPageState.INITIAL_CHARACTER_SELECTION, 3_100)
            assertTrue(harness.store.snapshot.startedAt > started)
            assertEquals(listOf("40002"), harness.store.snapshot.observedRelics.map { it.relicId })
        } finally { harness.close() }
    }

    @Test fun `fixed baseline and a later same-series random effect use unique history not an updated map`() = runBlocking {
        val harness = Harness(route = openingRelicRoute(guildId = 5))
        try {
            harness.start()
            harness.frame(LabyrinthEntryPageState.INITIAL_CHARACTER_SELECTION, 2_000)
            harness.frame(LabyrinthEntryPageState.CHARACTER_JOINED, 2_100)
            harness.frame(LabyrinthEntryPageState.NODE_SELECTION, 2_200)
            val started = harness.store.snapshot.startedAt
            harness.close()
            harness.now = 2_500
            harness.start()
            harness.frame(LabyrinthEntryPageState.NODE_SELECTION, 2_600)
            assertEquals(started, harness.store.snapshot.startedAt)
            assertTrue(harness.get("openingRelicBaselineKnown") as Boolean)
            val acceleration = LabyrinthRelicMark.ACCELERATION
            harness.ledger.acceptCommitted(harness.ledger.records + (acceleration to LabyrinthRelicStackRecord(2, LabyrinthRelicStackSource.MAP)))
            val random = relic("11001").copy(slotId = "reward_1", attribute = "加速", attributeBonus = "1")
            harness.reward(LabyrinthRelicRewardKind.RELIC_EFFECT, listOf(random), 2_700)
            harness.reward(LabyrinthRelicRewardKind.RELIC_EFFECT, listOf(random), 2_800)
            harness.reward(LabyrinthRelicRewardKind.RELIC_EFFECT, listOf(random), 2_900)
            assertEquals(setOf("40005", "50005", "11001"), harness.store.snapshot.observedRelics.map { it.relicId }.toSet())
            assertEquals(2, harness.ledger.stacks[acceleration])
            assertEquals(1, harness.ledger.stacks[LabyrinthRelicMark.DEBUFF])
        } finally { harness.close() }
    }

    @Test fun `partial same-run receipt can add the unlocked second relic after refreshing route`() = runBlocking {
        val harness = Harness(route = openingRelicRoute(nodes = null))
        try {
            harness.start()
            harness.frame(LabyrinthEntryPageState.INITIAL_CHARACTER_SELECTION, 2_000)
            val started = harness.store.snapshot.startedAt
            assertEquals(LabyrinthOpeningRelicReceipt(42, false), harness.store.snapshot.openingRelicReceipt)
            harness.close()
            harness.route = openingRelicRoute(nodes = setOf(31))
            harness.now = 2_500
            harness.start()
            assertFalse(harness.get("openingRelicBaselineKnown") as Boolean)
            harness.frame(LabyrinthEntryPageState.INITIAL_CHARACTER_SELECTION, 2_600)
            assertEquals(started, harness.store.snapshot.startedAt)
            assertEquals(listOf("40003", "50003"), harness.store.snapshot.observedRelics.map { it.relicId })
            assertEquals(LabyrinthOpeningRelicReceipt(42, true), harness.store.snapshot.openingRelicReceipt)
            assertEquals(2, harness.ledger.stacks.values.sum())
        } finally { harness.close() }
    }

    @Test fun `legacy or mismatched route guild never invents fixed relic identities`() = runBlocking {
        val routes = listOf(openingRelicRoute().copy(openingRelics = null),
            openingRelicRoute().copy(guildId = 1))
        for (route in routes) {
            val harness = Harness(route = route)
            try {
                harness.start()
                harness.frame(LabyrinthEntryPageState.INITIAL_CHARACTER_SELECTION, 2_000)
                assertTrue(harness.store.snapshot.observedRelics.isEmpty())
                assertFalse(harness.get("openingRelicBaselineKnown") as Boolean)
            } finally { harness.close() }
        }
    }

    private inner class Harness(var route: LabyrinthRouteJson? = null,
        backend: suspend () -> AutomationBackendResult = { AutomationBackendResult.Completed }) {
        val manager = AutomationSessionManager()
        val store = MemoryStore()
        var now = 1_000L
        private var sessionId = 0L
        private val savedRouteLoader: (suspend (Long?) -> LabyrinthRouteJson?)? =
            if (route != null) { _ -> route } else null
        val session = LabyrinthEntryRecognitionSession(
            sessionManager = manager, captureActive = { true },
            processorFactory = { { error("no bitmap processing in commit tests") } },
            actionExecutor = SessionBoundActionExecutor(manager) { backend() },
            actionsAvailable = { true }, gameLauncher = { true }, runStateStore = store, clock = { now },
            openingRelicCatalog = LabyrinthOpeningRelicCatalog(openingRelicResources()),
            routeLoader = savedRouteLoader,
            nodeTemplateLoader = if (route != null) ({ com.landosol.toolbox.labyrinth.node.NodeTemplateSet(emptyMap()) }) else null,
            routeExecutionGate = { LabyrinthExecutionGateResult.Allowed(requireNotNull(route), "test route") },
        )
        val ledger get() = get("relicStackLedger") as LabyrinthRelicStackLedger
        suspend fun start() {
            val started = session.startAutomation()
            assertTrue(started is LabyrinthEntryRecognitionStartResult.Started)
            sessionId = (started as LabyrinthEntryRecognitionStartResult.Started).sessionId.value
        }
        suspend fun close() { session.stop() }
        fun get(name: String): Any? = field(name).get(session)
        fun set(name: String, value: Any?) { field(name).set(session, value) }
        private fun field(name: String) = session.javaClass.getDeclaredField(name).apply { isAccessible = true }
        private fun method(name: String, parameterCount: Int? = null) = session.javaClass.declaredMethods.single {
            (it.name == name || it.name.startsWith("$name-")) && !it.name.endsWith("\$default") &&
                (parameterCount == null || it.parameterCount == parameterCount)
        }.apply { isAccessible = true }
        fun plan(candidate: LabyrinthRelicMatch) {
            ledger.prepare(requireNotNull(candidate.relicId), mark, 2, 9)
            set("plannedShopPurchaseRelicId", candidate.relicId)
            set("plannedShopPurchaseStartedAt", now)
        }
        suspend fun dispatch(kind: LabyrinthPostEntryActionKind, relicId: String? = null) {
            val selectedRole = if (kind == LabyrinthPostEntryActionKind.SELECT_ROLE_REWARD) "1002" else null
            val rewardFrame = selectedRole?.let { Batch2SessionFixture().roleFrame(listOf(it, "1003", "1004")) }
            if (selectedRole != null) {
                @Suppress("UNCHECKED_CAST")
                val state = get("_state") as kotlinx.coroutines.flow.MutableStateFlow<LabyrinthEntryRecognitionSessionState>
                state.value = state.value.copy(lastResult = rewardFrame,
                    roleRewardChoiceDecision = LabyrinthRoleRewardChoiceDecision.Select(
                        selectedRole, "千歌（礼服）", rect, emptyList()))
            }
            method("dispatchPostEntryTap", 12).invoke(session, sessionId, kind, "test action", rect,
                now, rewardFrame?.let(::labyrinthRoleRewardSelectionSignature), selectedRole, relicId,
                null, null, false, emptyList<String>())
        }
        suspend fun awaitAction() = withTimeout(5_000) {
            while ((get("actionInFlight") as AtomicBoolean).get()) delay(1)
        }
        fun result(page: LabyrinthEntryPageState, shop: LabyrinthShopObservation? = null) = LabyrinthEntryFrameResult(
            LabyrinthEntryPageObservation(page, 1.0, emptyMap(), LabyrinthAnchorScores(emptyMap())),
            emptyList(), 0, shopObservation = shop, frameWidth = 1_920, frameHeight = 1_080,
        )
        suspend fun frame(page: LabyrinthEntryPageState, time: Long, shop: LabyrinthShopObservation? = null) {
            now = time
            val result = result(page, shop)
            method("invalidatePreviousPageState").invoke(session, page, false)
            method("observeRelicIdentities").invoke(session, result, time)
            assertTrue(persist(result, time, ledger.fork()))
            method("holdRelicRewardReceipt").invoke(session, sessionId, result, time)
        }
        suspend fun reward(kind: LabyrinthRelicRewardKind?, relics: List<LabyrinthRelicMatch>, time: Long, confirmed: Boolean = true,
            complete: Boolean = true, slots: Set<String> = relics.map { it.slotId }.toSet()): Boolean {
            now = time
            val result = result(LabyrinthEntryPageState.ITEM_REWARD).copy(
                relicRewardObservation = LabyrinthRelicRewardObservation(kind, confirmed, relics, time, complete, slots),
                relicEvidenceVersion = get("relicEvidenceVersion") as Long)
            method("invalidatePreviousPageState").invoke(session, LabyrinthEntryPageState.ITEM_REWARD, false)
            method("observeRelicIdentities").invoke(session, result, time)
            assertTrue(persist(result, time, ledger.fork()))
            return method("holdRelicRewardReceipt").invoke(session, sessionId, result, time) as Boolean
        }
        suspend fun persist(result: LabyrinthEntryFrameResult, time: Long, staged: LabyrinthRelicStackLedger): Boolean {
            return suspendCoroutine { continuation: Continuation<Boolean> ->
                try {
                    val value = method("persistObservation").invoke(session, sessionId,
                        result, time, staged, continuation)
                    if (value !== COROUTINE_SUSPENDED) continuation.resume(value as Boolean)
                } catch (failure: InvocationTargetException) {
                    continuation.resumeWithException(failure.targetException)
                }
            }
        }
    }

    private class MemoryStore : LabyrinthRunStateStore {
        var writes = 0
        var beforeWrite: suspend () -> Unit = {}
        var stackReceipt: Map<LabyrinthRelicMark, LabyrinthRelicStackRecord>? = null
        var snapshot = LabyrinthRunSnapshot("test", null, "ACTIVE", null, 0, emptyList(), 0, startedAt = 100)
        override suspend fun begin(accountId: Long?, nowMillis: Long) = snapshot
        override suspend fun startNew(accountId: Long?, nowMillis: Long): LabyrinthRunSnapshot {
            snapshot = snapshot.copy(startedAt = maxOf(snapshot.startedAt + 1, nowMillis),
                observedRelics = emptyList(), joinedCharacters = emptyList(), relicStacks = emptyMap(), openingRelicReceipt = null)
            return snapshot
        }
        override suspend fun load(accountId: Long?) = snapshot
        override suspend fun recordPage(accountId: Long?, page: String, characters: List<LabyrinthCharacterMatch>,
            nowMillis: Long, relics: List<LabyrinthRelicMatch>, replaceCharacters: Boolean,
            expectedStartedAt: Long?, relicStacks: Map<LabyrinthRelicMark, LabyrinthRelicStackRecord>?,
            openingRelicReceipt: LabyrinthOpeningRelicReceipt?): LabyrinthRunSnapshot {
            beforeWrite()
            check(expectedStartedAt == snapshot.startedAt)
            writes++
            snapshot = snapshot.copy(currentPage = page,
                joinedCharacters = LabyrinthRosterMerger.merge(snapshot.joinedCharacters, characters, nowMillis),
                observedRelics = LabyrinthRelicMerger.merge(snapshot.observedRelics, relics, nowMillis),
                relicStacks = stackReceipt ?: (snapshot.relicStacks + relicStacks.orEmpty()),
                openingRelicReceipt = openingRelicReceipt?.let {
                    check(snapshot.openingRelicReceipt == null || snapshot.openingRelicReceipt?.enterId == it.enterId)
                    it.copy(complete = it.complete || snapshot.openingRelicReceipt?.complete == true)
                } ?: snapshot.openingRelicReceipt)
            return snapshot
        }
    }
}
