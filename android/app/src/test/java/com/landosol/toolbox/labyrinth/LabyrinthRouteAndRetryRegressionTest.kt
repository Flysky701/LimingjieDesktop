package com.landosol.toolbox.labyrinth

import com.landosol.toolbox.automation.AutomationBackendResult
import com.landosol.toolbox.automation.AutomationSessionId
import com.landosol.toolbox.automation.AutomationSessionManager
import com.landosol.toolbox.automation.SessionBoundActionExecutor
import com.landosol.toolbox.labyrinth.batch.LabyrinthRunTerminalEvent
import com.landosol.toolbox.labyrinth.concurrent.LabyrinthFourThreadRuntime
import com.landosol.toolbox.labyrinth.node.LabyrinthNodeTypes
import com.landosol.toolbox.labyrinth.node.NodeTemplateSet
import com.landosol.toolbox.labyrinth.vision.EntryAnchorId
import com.landosol.toolbox.labyrinth.vision.EntryAnchorMatch
import com.landosol.toolbox.labyrinth.vision.EntryPixelRect
import com.landosol.toolbox.labyrinth.vision.LabyrinthAnchorScores
import com.landosol.toolbox.labyrinth.vision.LabyrinthBattleElementFilter
import com.landosol.toolbox.labyrinth.vision.LabyrinthBattleFailureObservation
import com.landosol.toolbox.labyrinth.vision.LabyrinthBattleScrollbarObservation
import com.landosol.toolbox.labyrinth.vision.LabyrinthBattleTeamObservation
import com.landosol.toolbox.labyrinth.vision.LabyrinthBattleTeamRecognitionState
import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryFrameResult
import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryPageObservation
import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryPageState
import com.landosol.toolbox.labyrinth.vision.LabyrinthNodeMoveConfirmationObservation
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LabyrinthRouteAndRetryRegressionTest {
    @Test
    fun `map navigation and move confirmation reset post-entry unknown timer before next transition`() = runBlocking {
        val harness = Harness(roleCount = 5)
        try {
            harness.start(runId = "run-fix05")
            harness.set("activeNodeType", LabyrinthNodeTypes.NORMAL_BATTLE)

            // Earlier UNKNOWN frame arms postEntryUnknownSince at 1_000 ms.
            harness.feed(frame(LabyrinthEntryPageState.UNKNOWN), at = 1_000L)
            assertEquals(1_000L, harness.get("postEntryUnknownSince"))

            // Map exploration and move confirmation take ~30 s on separate branches.
            harness.feed(frame(LabyrinthEntryPageState.NODE_SELECTION), at = 10_000L)
            assertEquals(Long.MIN_VALUE, harness.get("postEntryUnknownSince"))
            harness.feed(frame(LabyrinthEntryPageState.NODE_SELECTION), at = 25_000L)

            // Re-arming UNKNOWN briefly before a dimmed move-confirmation modal also clears on the modal.
            harness.feed(frame(LabyrinthEntryPageState.UNKNOWN), at = 29_000L)
            assertEquals(29_000L, harness.get("postEntryUnknownSince"))
            harness.feed(
                frame(
                    LabyrinthEntryPageState.UNKNOWN,
                    moveConfirmation = LabyrinthNodeMoveConfirmationObservation(
                        confidence = 0.90,
                        confirmButtonRect = EntryPixelRect(975, 690, 415, 105),
                        cancelButtonRect = EntryPixelRect(545, 690, 415, 105),
                    ),
                ),
                at = 30_500L,
            )
            assertEquals(Long.MIN_VALUE, harness.get("postEntryUnknownSince"))

            // New post-confirmation transition 1.8 s later (31.3 s after 1_000 ms) must not abort.
            harness.feed(frame(LabyrinthEntryPageState.UNKNOWN), at = 32_300L)
            harness.awaitActions()
            assertEquals(LabyrinthEntryRecognitionStatus.RUNNING, harness.session.state.value.status)

            // Continuous UNKNOWN past 30 s from the new transition start still triggers safety stop.
            harness.feed(frame(LabyrinthEntryPageState.UNKNOWN), at = 62_400L)
            harness.awaitStopped()
            assertTrue(
                harness.session.state.value.message.orEmpty().contains("路线阶段未知页面超过限定时间"),
            )
        } finally {
            harness.close()
        }
    }

    @Test
    fun `residual battle failure frames after second retry do not count as third defeat before new battle`() = runBlocking {
        val harness = Harness(roleCount = 7, rerollAfterThreeFailures = true)
        try {
            harness.start(runId = "run-fix01")
            harness.enterExCombat()

            // Attempt 1 fails -> taps 重新挑战 (#1).
            harness.seedCurrentTeam(listOf("r1", "r2", "r3", "r4", "r5"))
            harness.feed(failureFrame(), at = 1_000L)
            harness.feed(failureFrame(), at = 1_300L)
            harness.awaitActions()
            assertEquals(1, harness.get("battleRetryCount"))
            assertEquals(true, harness.get("awaitingRetryBattleAfterFailure"))

            // Attempt 2 enters battle -> clears awaitingRetryBattleAfterFailure, then fails -> taps 重新挑战 (#2).
            harness.feed(frame(LabyrinthEntryPageState.BATTLE_IN_PROGRESS), at = 3_000L)
            assertEquals(false, harness.get("awaitingRetryBattleAfterFailure"))
            harness.seedCurrentTeam(listOf("r1", "r2", "r3", "r4", "r6"))
            harness.feed(failureFrame(), at = 5_000L)
            harness.feed(failureFrame(), at = 5_300L)
            harness.awaitActions()
            assertEquals(2, harness.get("battleRetryCount"))
            assertEquals(true, harness.get("awaitingRetryBattleAfterFailure"))

            // Residual failure frames (both inside cooldown and after cooldown if first tap faded slowly)
            // must neither terminate as 3rd defeat nor increment battleRetryCount past 2.
            harness.feed(failureFrame(), at = 5_600L)
            harness.feed(failureFrame(), at = 5_900L)
            harness.feed(failureFrame(), at = 7_000L)
            harness.awaitActions()
            assertEquals(LabyrinthEntryRecognitionStatus.RUNNING, harness.session.state.value.status)
            assertEquals(2, harness.get("battleRetryCount"))
            assertTrue(harness.terminalEvents.isEmpty())

            // Attempt 3 genuinely enters combat and then fails -> now ends at 3rd defeat without re-tapping retry.
            harness.feed(frame(LabyrinthEntryPageState.BATTLE_IN_PROGRESS), at = 8_500L)
            assertEquals(false, harness.get("awaitingRetryBattleAfterFailure"))
            val tapsBeforeThirdFailure = harness.executedActions.get()
            harness.seedCurrentTeam(listOf("r1", "r2", "r3", "r4", "r7"))
            harness.feed(failureFrame(), at = 10_000L)
            harness.feed(failureFrame(), at = 10_300L)
            harness.awaitStopped()

            assertEquals(tapsBeforeThirdFailure, harness.executedActions.get())
            assertTrue(harness.session.state.value.message.orEmpty().contains("EX战连续3次挑战失败"))
            harness.awaitTerminalEvent()
            assertEquals(listOf(LabyrinthRunTerminalEvent.FailedMaxRetry("run-fix01")), harness.terminalEvents.toList())
        } finally {
            harness.close()
        }
    }

    @Test
    fun `short rosters of three and four roles hand off to batch on exhausted retry without stalling`() = runBlocking {
        for (count in listOf(3, 4)) {
            val batchHarness = Harness(roleCount = count, rerollAfterThreeFailures = false)
            try {
                batchHarness.start(runId = "batch-$count")
                batchHarness.enterExCombat()
                batchHarness.seedCurrentTeam((1..count).map { "r$it" })

                batchHarness.feed(failureFrame(), at = 1_000L)
                batchHarness.feed(failureFrame(), at = 1_300L)
                batchHarness.awaitStopped()

                assertEquals(0, batchHarness.executedActions.get())
                batchHarness.awaitTerminalEvent()
                assertEquals(
                    listOf(LabyrinthRunTerminalEvent.FailedMaxRetry("batch-$count")),
                    batchHarness.terminalEvents.toList(),
                )
            } finally {
                batchHarness.close()
            }

            // Standalone non-reroll run stops cleanly on the failure page without clicking 结束 or 重新挑战.
            val standaloneHarness = Harness(roleCount = count, rerollAfterThreeFailures = false)
            try {
                standaloneHarness.start(runId = null)
                standaloneHarness.enterExCombat()
                standaloneHarness.seedCurrentTeam((1..count).map { "r$it" })

                standaloneHarness.feed(failureFrame(), at = 1_000L)
                standaloneHarness.feed(failureFrame(), at = 1_300L)
                standaloneHarness.awaitStopped()

                assertEquals(0, standaloneHarness.executedActions.get())
                assertTrue(standaloneHarness.session.state.value.message.orEmpty().contains("保留在失败页"))
            } finally {
                standaloneHarness.close()
            }
        }
    }

    @Test
    fun `team selection page with zero or exhausted roles finishes instead of stalling for two minutes`() = runBlocking {
        // 0 roles in a batch run -> hands off as FailedMaxRetry after stable frames on BATTLE_TEAM_SELECTION.
        val emptyBatch = Harness(roleCount = 0, rerollAfterThreeFailures = false)
        try {
            emptyBatch.start(runId = "batch-empty")
            emptyBatch.enterExCombat()

            emptyBatch.feed(teamSelectionFrame(), at = 1_000L)
            emptyBatch.feed(teamSelectionFrame(), at = 1_400L)
            emptyBatch.awaitStopped()

            emptyBatch.awaitTerminalEvent()
            assertEquals(
                listOf(LabyrinthRunTerminalEvent.FailedMaxRetry("batch-empty")),
                emptyBatch.terminalEvents.toList(),
            )
        } finally {
            emptyBatch.close()
        }

        // 0 roles in an interactive standalone run -> stops cleanly without auto-reroll.
        val emptyStandalone = Harness(roleCount = 0, rerollAfterThreeFailures = true)
        try {
            emptyStandalone.start(runId = null)
            emptyStandalone.enterExCombat()

            emptyStandalone.feed(teamSelectionFrame(), at = 1_000L)
            emptyStandalone.feed(teamSelectionFrame(), at = 1_400L)
            emptyStandalone.awaitStopped()

            assertFalse(emptyStandalone.rerollRequested.get())
            assertTrue(emptyStandalone.session.state.value.message.orEmpty().contains("没有已获得的可用角色"))
        } finally {
            emptyStandalone.close()
        }
    }

    @Test
    fun `balanced mode large roster battle failure offloads retry feasibility off T2 and retries`() = runBlocking {
        val harness = Harness(roleCount = 14, rerollAfterThreeFailures = true, balancedMode = true)
        try {
            harness.start(runId = "run-balanced-t2-retry")
            harness.enterExCombat(area = 3)
            harness.seedCurrentTeam(listOf("r1", "r2", "r3", "r4", "r5"))

            harness.feed(failureFrame(), at = 1_000L)
            harness.feed(failureFrame(), at = 1_300L)
            assertEquals(LabyrinthEntryRecognitionStatus.RUNNING, harness.session.state.value.status)
            assertEquals(0, harness.executedActions.get())

            harness.awaitBattleRetryPlanningIdle()
            val readyAt = harness.battleRetryPlanningReadyAt()
            harness.feed(failureFrame(), at = maxOf(1_600L, readyAt + 100L))
            harness.awaitActions()

            assertEquals(LabyrinthEntryRecognitionStatus.RUNNING, harness.session.state.value.status)
            assertEquals(1, harness.executedActions.get())
            assertEquals(1, harness.get("battleRetryCount"))
            assertEquals(true, harness.get("awaitingRetryBattleAfterFailure"))
        } finally {
            harness.close()
        }
    }

    @Test
    fun `balanced mode battle failure hands off when async retry feasibility is unavailable`() = runBlocking {
        // Subcase 1: 14-role pool (>= PARALLEL_MIN_COMBINATIONS) where parallel search finds no legal retry team.
        val largePool = Harness(
            roleCount = 14,
            rerollAfterThreeFailures = false,
            balancedMode = true,
            frontlineSurvivalGateEnabled = true,
            backmostOnlyVanguard = true,
        )
        try {
            largePool.start(runId = "run-balanced-t2-unavail-14")
            largePool.enterExCombat(area = 3)
            largePool.seedCurrentTeam(listOf("r1", "r2", "r3", "r4", "r5"))

            largePool.feed(failureFrame(), at = 1_000L)
            largePool.feed(failureFrame(), at = 1_300L)
            largePool.awaitBattleRetryPlanningIdle()
            val readyAt = largePool.battleRetryPlanningReadyAt()
            largePool.feed(failureFrame(), at = maxOf(1_600L, readyAt + 100L))
            largePool.awaitStopped()
            largePool.awaitTerminalEvent()

            assertEquals(0, largePool.executedActions.get())
            assertEquals(
                listOf(LabyrinthRunTerminalEvent.FailedMaxRetry("run-balanced-t2-unavail-14")),
                largePool.terminalEvents.toList(),
            )
            assertTrue(largePool.session.state.value.message.orEmpty().contains("无可替换的未失败重试阵容"))
        } finally {
            largePool.close()
        }

        // Subcase 2: short 4-role pool exhausted in balanced mode still hands off to batch immediately.
        val shortPool = Harness(
            roleCount = 4,
            rerollAfterThreeFailures = false,
            balancedMode = true,
        )
        try {
            shortPool.start(runId = "run-balanced-t2-unavail-4")
            shortPool.enterExCombat(area = 1)
            shortPool.seedCurrentTeam(listOf("r1", "r2", "r3", "r4"))

            shortPool.feed(failureFrame(), at = 1_000L)
            shortPool.feed(failureFrame(), at = 1_300L)
            shortPool.awaitBattleRetryPlanningIdle()
            val readyAt = shortPool.battleRetryPlanningReadyAt()
            shortPool.feed(failureFrame(), at = maxOf(1_600L, readyAt + 100L))
            shortPool.awaitStopped()
            shortPool.awaitTerminalEvent()

            assertEquals(0, shortPool.executedActions.get())
            assertEquals(
                listOf(LabyrinthRunTerminalEvent.FailedMaxRetry("run-balanced-t2-unavail-4")),
                shortPool.terminalEvents.toList(),
            )
        } finally {
            shortPool.close()
        }
    }

    private class Harness(
        private val roleCount: Int,
        private val rerollAfterThreeFailures: Boolean = true,
        private val balancedMode: Boolean = false,
        private val frontlineSurvivalGateEnabled: Boolean = false,
        private val backmostOnlyVanguard: Boolean = false,
    ) {
        val manager = AutomationSessionManager()
        val clock = AtomicLong(0L)
        val executedActions = AtomicInteger(0)
        val rerollRequested = AtomicBoolean(false)
        val terminalEvents = CopyOnWriteArrayList<LabyrinthRunTerminalEvent>()
        private val allProfiles = (1..maxOf(7, roleCount)).associate { i ->
            val id = "r$i"
            val isVanguard = if (backmostOnlyVanguard) i == roleCount else i == 1
            id to LabyrinthRoleProfile(
                characterId = id,
                displayName = "角色$i",
                roleClass = if (isVanguard) "掩护者" else "攻击者",
                attribute = LabyrinthCharacterAttribute.LIGHT,
                damageType = LabyrinthRoleDamageType.PHYSICAL,
                position = if (backmostOnlyVanguard && isVanguard) 999 else i,
                userScore = 90.0 - i,
                userRatingCount = 5,
                physicalDamagePotential = 80.0,
                magicDamagePotential = 0.0,
                functions = LabyrinthRoleFunctions(reliableVanguard = if (isVanguard) 100.0 else 0.0),
            )
        }
        private val planner = LabyrinthBattleTeamRecommendationPlanner(
            profiles = allProfiles,
            teamPlanSearcher = LabyrinthTeamPlanSearcher(
                LabyrinthTeamOptimizer(
                    LabyrinthTeamScorer(
                        LabyrinthTeamScoringConfig(
                            attributeDamageBonus = mapOf(1 to 0.0, 2 to 0.08, 3 to 0.16, 4 to 0.25, 5 to 0.75),
                        ),
                    ),
                    searchThreads = if (balancedMode) maxOf(2, LabyrinthFourThreadRuntime.cpuWorkerBudget) else 1,
                ),
                LabyrinthTeamPlanSearchConfig(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0),
            ),
        )
        val session = LabyrinthEntryRecognitionSession(
            sessionManager = manager,
            captureActive = { true },
            processorFactory = { { error("direct frame feed only") } },
            actionExecutor = SessionBoundActionExecutor(manager) {
                executedActions.incrementAndGet()
                AutomationBackendResult.Completed
            },
            actionsAvailable = { true },
            clock = { clock.get() },
            battleTeamRecommendationPlanner = planner,
            battleTeamSelectionPlanner = LabyrinthBattleTeamSelectionPlanner(allProfiles, minimumCharacterConfidence = 0.0),
            routeLoader = { testRoute },
            routeExecutionGate = { LabyrinthExecutionGateResult.Allowed(testRoute, "test route admitted") },
            nodeTemplateLoader = { NodeTemplateSet(emptyMap()) },
            rerollRequester = {
                rerollRequested.set(true)
                true
            },
            runTerminalListener = { event -> terminalEvents += event },
            fastProcessorFactory = if (balancedMode) ({ { error("direct frame feed only") } }) else null,
        )

        suspend fun start(runId: String?) {
            assertTrue(session.startAutomation(accountId = 1L, runId = runId) is LabyrinthEntryRecognitionStartResult.Started)
            suspend fun configure() {
                set("entryPhaseComplete", true)
                (get("nodeInitStarted") as AtomicBoolean).set(true)
                set("rerollAfterThreeBattleFailures", rerollAfterThreeFailures)
                if (frontlineSurvivalGateEnabled) {
                    set("battleStrategySettingsSnapshot", LabyrinthStrategySettings(frontlineSurvivalGateEnabled = true))
                }
                state { current ->
                    current.copy(
                        joinedCharacters = (1..roleCount).map { i ->
                            LabyrinthJoinedCharacter("r$i", "角色$i", i - 1, 0.95, 0L, 0L)
                        },
                    )
                }
            }
            if (balancedMode) withContext(LabyrinthFourThreadRuntime.controlDispatcher) { configure() } else configure()
        }

        suspend fun enterExCombat(area: Int? = null) {
            fun configure() {
                if (area != null) set("activeNodeArea", area)
                set("combatContext", LabyrinthCombatContext(LabyrinthCombatKind.EX))
                val stageField = session.javaClass.getDeclaredField("effectiveCharacterScanStage").apply { isAccessible = true }
                val completeStage = stageField.type.enumConstants.first { (it as Enum<*>).name == "COMPLETE" }
                stageField.set(session, completeStage)
                val stampMethod = session.javaClass.getDeclaredMethod("currentEffectiveScanRosterStamp").apply { isAccessible = true }
                set("effectiveCharacterScanRosterStamp", stampMethod.invoke(session))
            }
            if (balancedMode) withContext(LabyrinthFourThreadRuntime.controlDispatcher) { configure() } else configure()
        }

        @Suppress("UNCHECKED_CAST")
        fun seedCurrentTeam(ids: List<String>) {
            val sigs = get("currentBattleTeamSignatures") as MutableSet<String>
            sigs.clear()
            sigs += labyrinthBattleTeamSignature(ids)
        }

        suspend fun close() {
            session.stop("test cleanup")
        }

        fun get(name: String): Any? = session.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(session)

        fun set(name: String, value: Any?) {
            session.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(session, value)
        }

        @Suppress("UNCHECKED_CAST")
        fun state(update: (LabyrinthEntryRecognitionSessionState) -> LabyrinthEntryRecognitionSessionState) {
            val flow = get("_state") as MutableStateFlow<LabyrinthEntryRecognitionSessionState>
            flow.value = update(flow.value)
        }

        suspend fun feed(result: LabyrinthEntryFrameResult, at: Long) {
            val id = session.state.value.sessionId ?: return
            suspend fun deliver() {
                clock.set(at)
                if (balancedMode) {
                    set("balancedDetailCapturedAt", at)
                    set("balancedDetailWidth", 1920)
                    set("balancedDetailHeight", 1080)
                    set("balancedDetailEpoch", get("balancedSceneEpoch"))
                }
                suspendCoroutine<Unit> { continuation ->
                    val method = session.javaClass.declaredMethods.first {
                        it.name.startsWith("processFrameResult") && it.parameterCount == 6
                    }.apply { isAccessible = true }
                    val rawArgs = arrayOf<Any?>(id, result, 1920, 1080, at, continuation)
                    val adapted = method.parameterTypes.mapIndexed { index, type ->
                        val arg = rawArgs[index]
                        when {
                            type.name == "long" -> (arg as? AutomationSessionId)?.value ?: (arg as Number).toLong()
                            type.name == "int" -> (arg as Number).toInt()
                            else -> arg
                        }
                    }.toTypedArray()
                    try {
                        if (method.invoke(session, *adapted) !== COROUTINE_SUSPENDED) continuation.resume(Unit)
                    } catch (e: InvocationTargetException) {
                        continuation.resumeWithException(e.targetException)
                    }
                }
            }
            if (balancedMode) withContext(LabyrinthFourThreadRuntime.controlDispatcher) { deliver() } else deliver()
        }

        suspend fun awaitBattleRetryPlanningIdle() = withTimeout(3_000L) {
            while (withContext(LabyrinthFourThreadRuntime.controlDispatcher) {
                (get("battleRetryPlanning") as LabyrinthBalancedPlanningSlot<*, *>).busy
            }) {
                delay(5L)
            }
        }

        suspend fun battleRetryPlanningReadyAt(): Long = withContext(LabyrinthFourThreadRuntime.controlDispatcher) {
            (get("battleRetryPlanning") as LabyrinthBalancedPlanningSlot<*, *>).readyAt
        }

        suspend fun awaitActions() {
            val inFlight = get("actionInFlight") as AtomicBoolean
            withTimeout(3_000L) { while (inFlight.get()) delay(5L) }
        }

        suspend fun awaitStopped() {
            withTimeout(3_000L) {
                while (session.state.value.status != LabyrinthEntryRecognitionStatus.STOPPED) delay(5L)
            }
        }

        suspend fun awaitTerminalEvent() {
            withTimeout(3_000L) {
                while (terminalEvents.isEmpty()) delay(5L)
            }
        }
    }

    private companion object {
        val testRoute = LabyrinthRouteJson(
            enterId = 1L,
            guildId = 8,
            difficulty = 2,
            attempt = 1,
            rerollUntilFound = false,
            perfectStart = false,
            thirdBlockChoice = "ANY",
            area3BossIds = emptyList(),
            area5BossIds = emptyList(),
            nodes = listOf(
                LabyrinthNodeJson(1, 1, 1, 10101L, LabyrinthNodeTypes.START, null, listOf(10201L), false),
                LabyrinthNodeJson(1, 2, 1, 10201L, LabyrinthNodeTypes.NORMAL_BATTLE, null, emptyList(), true),
            ),
            currentBlockId = 10101L,
        )

        fun frame(
            state: LabyrinthEntryPageState,
            moveConfirmation: LabyrinthNodeMoveConfirmationObservation? = null,
        ) = LabyrinthEntryFrameResult(
            observation = LabyrinthEntryPageObservation(state, 1.0, emptyMap(), LabyrinthAnchorScores(emptyMap())),
            matchedFeatures = emptyList(),
            elapsedMillis = 0L,
            nodeMoveConfirmation = moveConfirmation,
            frameWidth = 1920,
            frameHeight = 1080,
        )

        fun failureFrame() = LabyrinthEntryFrameResult(
            observation = LabyrinthEntryPageObservation(
                LabyrinthEntryPageState.BATTLE_FAILED,
                0.95,
                emptyMap(),
                LabyrinthAnchorScores(emptyMap()),
            ),
            matchedFeatures = emptyList(),
            elapsedMillis = 0L,
            battleFailure = LabyrinthBattleFailureObservation(
                confidence = 0.92,
                endButtonRect = EntryPixelRect(945, 935, 430, 110),
                retryButtonRect = EntryPixelRect(1405, 935, 430, 110),
            ),
            frameWidth = 1920,
            frameHeight = 1080,
        )

        fun teamSelectionFrame() = LabyrinthEntryFrameResult(
            observation = LabyrinthEntryPageObservation(
                LabyrinthEntryPageState.BATTLE_TEAM_SELECTION,
                0.95,
                emptyMap(),
                LabyrinthAnchorScores(emptyMap()),
            ),
            matchedFeatures = emptyList(),
            elapsedMillis = 0L,
            anchorMatches = mapOf(
                EntryAnchorId.BATTLE_TEAM_START_BUTTON to EntryAnchorMatch(
                    0.92,
                    EntryPixelRect(1565, 850, 270, 110),
                ),
            ),
            battleTeamSelection = LabyrinthBattleTeamObservation(
                currentFilter = LabyrinthBattleElementFilter.ALL,
                filters = emptyList(),
                visibleCharacters = emptyList(),
                selectedCharacters = emptyList(),
                scrollbar = LabyrinthBattleScrollbarObservation(
                    EntryPixelRect(1825, 377, 28, 349),
                    null,
                    false,
                    false,
                    0.0,
                ),
                recognitionState = LabyrinthBattleTeamRecognitionState.STABLE,
                viewportRevision = 1L,
            ),
            frameWidth = 1920,
            frameHeight = 1080,
        )
    }
}
