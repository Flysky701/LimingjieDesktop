package com.landosol.toolbox.labyrinth

import com.landosol.toolbox.automation.AutomationBackendResult
import com.landosol.toolbox.automation.SessionBoundActionExecutor
import com.landosol.toolbox.automation.accessibility.GAME_NOT_FOREGROUND_REASON
import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryFrameResult
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Batch 2 only: round ownership, retry accounting and the actual session publication gates. */
class Batch2RoleRewardRoundTest {
    private val first = linkedMapOf("left" to "r1", "center" to "r2")
    private val next = linkedMapOf("left" to "r3", "center" to "r4")

    private fun finishedRound(): LabyrinthRoleRewardRound = LabyrinthRoleRewardRound().apply {
        val token = requireNotNull(submit("r1", first, 1_000L))
        assertTrue(inputFinished(token, 1_100L))
    }

    @Test fun `submission requires a trusted target and locks the whole round`() {
        val round = LabyrinthRoleRewardRound()
        assertNull(round.submit("unknown", first, 1_000L))
        assertFalse(round.blocksPlanning())
        val token = requireNotNull(round.submit("r1", first, 1_000L))
        assertTrue(round.blocksPlanning())
        assertTrue(round.blocksDispatch())
        assertEquals("r1", round.selectedId())
        assertNull(round.submit("r1", first, 1_001L))
        assertNull(round.submit("r2", first, 1_001L))
        assertTrue(round.inputFinished(token, 1_100L))
        assertNull(round.submit("r2", first, 10_000L))
        assertEquals("INPUT_FINISHED", round.snapshot().phase)
    }

    @Test fun `dispatch and frames captured before input finished cannot establish a boundary`() {
        val round = LabyrinthRoleRewardRound()
        val token = requireNotNull(round.submit("r1", first, 1_000L))
        assertFalse(round.observe(true, next, 1_400L))
        assertFalse(round.observe(true, next, 1_700L))
        assertTrue(round.inputFinished(token, 2_000L))
        assertFalse(round.observe(true, next, 1_700L))
        assertFalse(round.observe(true, next, 2_000L))
        assertEquals(0, round.snapshot().newRoundEvidenceFrames)
        assertTrue(round.blocksPlanning())
    }

    @Test fun `unknown siblings becoming known or known siblings disappearing do not unlock`() {
        val round = finishedRound()
        val sameWithNewSibling = first + ("right" to "r5")
        listOf(sameWithNewSibling, mapOf("center" to "r2", "right" to "r5"), first).forEachIndexed { i, slots ->
            assertFalse(round.observe(true, slots, 2_000L + i * 600L))
            assertFalse(round.observe(true, slots, 2_300L + i * 600L))
        }
        assertEquals(0, round.snapshot().newRoundEvidenceFrames)
        assertTrue(round.blocksDispatch())
        assertEquals("r1", round.selectedId())
    }

    @Test fun `a boundary needs two unique trusted roles and disappearance of the selected role`() {
        val round = finishedRound()
        val invalid = listOf(mapOf("left" to "r3"), mapOf("left" to "r3", "center" to "r3"),
            mapOf("left" to "r3", "center" to "r1"))
        invalid.forEachIndexed { i, slots ->
            assertFalse(round.observe(true, slots, 2_000L + i * 600L))
            assertFalse(round.observe(true, slots, 2_300L + i * 600L))
        }
        assertTrue(round.blocksPlanning())
        assertEquals(0, round.snapshot().newRoundEvidenceFrames)
    }

    @Test fun `two consistent fresh frames spanning 300 milliseconds open exactly one new round`() {
        val round = finishedRound()
        val epoch = round.epoch()
        assertFalse(round.observe(true, next, 2_000L))
        assertFalse(round.observe(true, next, 2_299L))
        assertTrue(round.observe(true, next, 2_300L))
        assertEquals(epoch + 1, round.epoch())
        assertFalse(round.blocksPlanning())
        assertFalse(round.blocksDispatch())
        assertNull(round.selectedId())
        assertEquals(0, round.snapshot().newRoundEvidenceFrames)
        assertFalse(round.observe(true, next, 2_600L))
        assertEquals(epoch + 1, round.epoch())
        assertNotNull(round.submit("r3", next, 2_600L))
    }

    @Test fun `duplicate and out of order frames do not count as independent evidence`() {
        val round = finishedRound()
        assertFalse(round.observe(true, next, 2_000L))
        assertFalse(round.observe(true, next, 2_000L))
        assertFalse(round.observe(true, next, 1_900L))
        assertEquals(1, round.snapshot().newRoundEvidenceFrames)
        assertTrue(round.observe(true, next, 2_300L))
    }

    @Test fun `inconsistent candidates or a non reward frame restart boundary evidence`() {
        val round = finishedRound()
        assertFalse(round.observe(true, next, 2_000L))
        assertFalse(round.observe(true, next + ("right" to "r5"), 2_300L))
        assertEquals(1, round.snapshot().newRoundEvidenceFrames)
        assertFalse(round.observe(false, next, 2_600L))
        assertEquals(0, round.snapshot().newRoundEvidenceFrames)
        assertFalse(round.observe(true, next, 2_900L))
        assertTrue(round.observe(true, next, 3_200L))
    }

    @Test fun `failure permits only the same target and old retry tokens cannot complete it`() {
        val round = LabyrinthRoleRewardRound()
        val original = requireNotNull(round.submit("r1", first, 1_000L))
        val beforeFailure = round.epoch()
        assertTrue(round.notExecuted(original, 1_100L))
        assertEquals(beforeFailure + 1, round.epoch())
        assertTrue(round.blocksPlanning())
        assertFalse(round.blocksDispatch())
        assertTrue(round.retryReady())
        assertNull(round.submit("r2", first, 2_000L))
        assertNull(round.submit("r1", next, 2_000L))
        val retry = requireNotNull(round.submit("r1", first, 2_000L))
        assertNotEquals(original, retry)
        val snapshot = round.snapshot()
        assertFalse(round.inputFinished(original, 2_100L))
        assertFalse(round.notExecuted(original, 2_100L))
        assertEquals(snapshot, round.snapshot())
        assertTrue(round.inputFinished(retry, 2_100L))
        assertTrue(round.blocksDispatch())
    }

    @Test fun `reset invalidates the old token even after another target is submitted`() {
        val round = finishedRound()
        val old = requireNotNull(round.snapshot().token)
        val epoch = round.epoch()
        round.reset()
        assertEquals(epoch + 1, round.epoch())
        val fresh = requireNotNull(round.submit("r3", next, 2_000L))
        val snapshot = round.snapshot()
        assertNotEquals(old, fresh)
        assertFalse(round.inputFinished(old, 2_100L))
        assertFalse(round.notExecuted(old, 2_100L))
        assertEquals(snapshot, round.snapshot())
    }

    @Test fun `decision publication requires session running unpaused matching generations and no submission`() {
        fun allowed(same: Boolean = true, running: Boolean = true, paused: Boolean = false,
                    generation: Long = 7L, round: Long = 3L, submitted: Boolean = false) =
            labyrinthRoleDecisionCanPublish(same, running, paused, 7L, generation, 3L, round, submitted)
        assertTrue(allowed())
        assertFalse(allowed(same = false))
        assertFalse(allowed(running = false))
        assertFalse(allowed(paused = true))
        assertFalse(allowed(generation = 8L))
        assertFalse(allowed(round = 4L))
        assertFalse(allowed(submitted = true))
    }

    @Test fun `session does not replan or tap when an unknown sibling resolves after execution`() = runBlocking {
        val f = Batch2SessionFixture()
        try {
            f.start()
            val probe = installPlanner(f)
            f.choose(f.roleFrame(listOf("r1", "r2", null)), "r1", 1_000L)
            f.awaitActions()
            val epoch = f.round().epoch()
            val snapshot = f.session.state.value.joinedCharacters
            val reads = probe.lookups
            f.feed(f.roleFrame(listOf("r1", "r2", "r5")), 2_000L)
            f.awaitActions()
            f.feed(f.roleFrame(listOf("r1", "r2", "r5")), 2_300L)
            f.awaitActions()
            f.feed(f.roleFrame(listOf(null, "r2", "r5")), 2_600L)
            f.awaitActions()
            assertEquals(reads, probe.lookups)
            assertEquals(1, f.calls.get())
            assertEquals(1, f.session.state.value.actionCount)
            assertEquals(epoch, f.round().epoch())
            assertTrue(f.round().blocksDispatch())
            assertEquals("r1", selected(f).characterId)
            assertEquals(snapshot, f.session.state.value.joinedCharacters)
            assertEquals(listOf("r1"), snapshot.map { it.characterId })
        } finally { f.awaitActions(); f.close() }
    }

    @Test fun `two trusted new round frames enable the real session to select again`() = runBlocking {
        val f = Batch2SessionFixture()
        try {
            f.start()
            val probe = installPlanner(f)
            f.choose(f.roleFrame(listOf("r1", "r2", null)), "r1", 1_000L)
            f.awaitActions()
            val oldEpoch = f.round().epoch()
            val newFrame = f.roleFrame(listOf("r3", "r4", null))
            f.feed(newFrame, 2_000L)
            f.awaitActions()
            assertEquals(1, f.calls.get())
            assertEquals(0, probe.lookups)
            assertEquals(1, f.round().snapshot().newRoundEvidenceFrames)
            f.feed(newFrame, 2_300L)
            f.awaitActions()
            assertEquals(oldEpoch + 1, f.round().epoch())
            assertTrue(probe.lookups > 0)
            assertTrue(selected(f).characterId in setOf("r3", "r4"))
            // The new round is open, but the original 1500 ms action cooldown still applies.
            assertFalse(f.round().blocksPlanning())
            assertEquals(1, f.calls.get())
            assertEquals(1, f.session.state.value.actionCount)
            f.feed(newFrame, 2_600L)
            f.awaitActions()
            assertEquals(2, f.calls.get())
            assertEquals(2, f.session.state.value.actionCount)
            val acquired = f.session.state.value.joinedCharacters.map { it.characterId }
            assertEquals(2, acquired.size)
            assertEquals(acquired.size, acquired.toSet().size)
            assertTrue("r1" in acquired)
            f.feed(newFrame, 2_900L)
            f.awaitActions()
            assertEquals(2, f.calls.get())
        } finally { f.awaitActions(); f.close() }
    }

    @Test fun `session retries failures at the original target without booking failed or duplicate acquisitions`() = runBlocking {
        val f = Batch2SessionFixture()
        try {
            f.start()
            val probe = installPlanner(f)
            f.outcome.set(AutomationBackendResult.Rejected(GAME_NOT_FOREGROUND_REASON))
            f.choose(f.roleFrame(listOf("r1", "r2", null)), "r1", 1_000L)
            f.awaitActions()
            val oldToken = requireNotNull(f.round().snapshot().token)
            assertTrue(f.round().retryReady())
            assertTrue(f.session.state.value.joinedCharacters.isEmpty())
            assertEquals(0, f.session.state.value.actionCount)
            assertTrue((f.get("pendingAcquiredCharacterIds") as Set<*>).isEmpty())
            // Recognition may move the SAME role to a different slot during a retry.
            val retryFrame = f.roleFrame(listOf("r2", "r1", null))
            f.feed(retryFrame, 2_000L)
            f.awaitActions()
            assertEquals(1, f.calls.get())
            assertEquals("r1", selected(f).characterId)
            assertEquals(LabyrinthRoleRewardChoicePlanner.selectionButtonRect("role_reward_center", 1920, 1080),
                selected(f).buttonRect)
            assertTrue(f.round().retryReady())
            assertTrue(f.session.state.value.joinedCharacters.isEmpty())
            assertEquals(0, probe.lookups)
            // Retry only after the original 1500 ms action interval has elapsed.
            f.feed(retryFrame, 2_500L)
            f.awaitActions()
            assertEquals(2, f.calls.get())
            assertTrue(f.round().retryReady())
            assertTrue(f.session.state.value.joinedCharacters.isEmpty())
            assertEquals(0, f.session.state.value.actionCount)
            assertEquals(0, probe.lookups)
            f.outcome.set(AutomationBackendResult.Completed)
            f.feed(retryFrame, 4_000L)
            f.awaitActions()
            assertEquals(3, f.calls.get())
            assertEquals(1, f.session.state.value.actionCount)
            assertEquals(listOf("r1"), f.session.state.value.joinedCharacters.map { it.characterId })
            assertFalse(f.round().inputFinished(oldToken, 4_100L))
            assertFalse(f.round().notExecuted(oldToken, 4_100L))
            f.feed(retryFrame, 5_500L)
            f.awaitActions()
            assertEquals(3, f.calls.get())
            assertEquals(1, f.session.state.value.joinedCharacters.size)
        } finally { f.awaitActions(); f.close() }
    }

    @Test fun `paused session refuses planning and role dispatch then resume accepts a fresh choice`() = runBlocking {
        val f = Batch2SessionFixture()
        try {
            f.start()
            val frame = f.roleFrame(listOf("r1", "r2", null))
            val id = f.id
            assertTrue(f.session.setPaused(true))
            assertEquals(false, f.invoke("observeRoleRewardRound", id, frame, 1_000L))
            assertEquals(false, f.invoke("refreshRoleRewardChoiceDecision", id, frame, 1920, 1080))
            dispatchChoice(f, frame, "r1", 1_000L)
            f.awaitActions()
            assertEquals(0, f.calls.get())
            assertFalse(f.round().blocksPlanning())
            assertTrue(f.session.setPaused(false))
            f.choose(frame, "r1", 2_000L)
            f.awaitActions()
            assertEquals(1, f.calls.get())
        } finally { f.awaitActions(); f.close() }
    }

    @Test fun `actual executed callback from an invalidated round cannot publish an acquisition`() = runBlocking {
        val f = Batch2SessionFixture()
        try {
            f.start()
            // Invalidate while the genuine executor is returning, before the session consumes its receipt.
            f.set("actionExecutor", SessionBoundActionExecutor(f.manager) {
                f.calls.incrementAndGet()
                f.invoke("clearRoleRewardBatchTracking")
                AutomationBackendResult.Completed
            })
            f.choose(f.roleFrame(listOf("r1", "r2", null)), "r1", 1_000L)
            f.awaitActions()
            assertEquals(1, f.calls.get())
            assertFalse(f.round().blocksPlanning())
            assertTrue("stale round receipt must not enter the acquired ledger", f.session.state.value.joinedCharacters.isEmpty())
            assertTrue((f.get("pendingAcquiredCharacterIds") as Set<*>).isEmpty())
            assertNull(f.get("pendingAcquiredCharacterId"))
            assertFalse(f.get("roleRewardChoiceCommitted") as Boolean)
            assertEquals(0, f.session.state.value.actionCount)
        } finally { f.awaitActions(); f.close() }
    }

    @Test fun `computation started before pause and resume cannot overwrite the resumed preview`() = runBlocking {
        val f = Batch2SessionFixture()
        try {
            f.start()
            val probe = installPlanner(f)
            val marker = LabyrinthRoleRewardChoiceDecision.Wait("fresh resumed preview")
            probe.onLookup = {
                runBlocking {
                    assertTrue(f.session.setPaused(true))
                    assertTrue(f.session.setPaused(false))
                }
                f.state { it.copy(roleRewardChoiceDecision = marker, message = "resumed") }
            }
            assertEquals(false, refresh(f, f.roleFrame(listOf("r1", "r2", null))))
            assertTrue(probe.lookups > 0)
            assertEquals(marker, f.session.state.value.roleRewardChoiceDecision)
            assertEquals("resumed", f.session.state.value.message)
            assertNull(f.get("roleRewardDecisionCacheKey"))
            assertEquals(1L, (f.get("discardedRoleDecisions") as AtomicLong).get())
            assertEquals(0, f.calls.get())
        } finally { f.awaitActions(); f.close() }
    }

    @Test fun `computation finishing after stop cannot publish a preview or cache`() = runBlocking {
        val f = Batch2SessionFixture()
        try {
            f.start()
            val probe = installPlanner(f)
            probe.onLookup = { runBlocking { assertTrue(f.session.stop("stop during choice calculation")) } }
            assertEquals(false, refresh(f, f.roleFrame(listOf("r1", "r2", null))))
            assertFalse(f.session.state.value.running)
            assertEquals("stop during choice calculation", f.session.state.value.message)
            assertNull(f.session.state.value.roleRewardChoiceDecision)
            assertNull(f.get("roleRewardDecisionCacheKey"))
            assertEquals(1L, (f.get("discardedRoleDecisions") as AtomicLong).get())
            assertEquals(0, f.calls.get())
        } finally { f.awaitActions(); f.close() }
    }

    @Test fun `computation from an old round cannot overwrite a newer round preview`() = runBlocking {
        val f = Batch2SessionFixture()
        try {
            f.start()
            val probe = installPlanner(f)
            val marker = LabyrinthRoleRewardChoiceDecision.Wait("new round preview")
            probe.onLookup = {
                f.invoke("clearRoleRewardBatchTracking")
                f.state { it.copy(roleRewardChoiceDecision = marker) }
            }
            assertEquals(false, refresh(f, f.roleFrame(listOf("r1", "r2", null))))
            assertEquals(marker, f.session.state.value.roleRewardChoiceDecision)
            assertNull(f.get("roleRewardDecisionCacheKey"))
            assertEquals(1L, (f.get("discardedRoleDecisions") as AtomicLong).get())
            assertEquals(0, f.calls.get())
        } finally { f.awaitActions(); f.close() }
    }

    @Test fun `calculation completing after another choice is dispatched cannot replace its locked target`() = runBlocking {
        val f = Batch2SessionFixture()
        try {
            f.start()
            val probe = installPlanner(f)
            val frame = f.roleFrame(listOf("r1", "r2", null))
            probe.onLookup = {
                f.choose(frame, "r1", 1_000L)
                runBlocking { f.awaitActions() }
            }
            assertEquals(false, refresh(f, frame))
            assertEquals("r1", selected(f).characterId)
            assertEquals("r1", f.round().selectedId())
            assertTrue(f.round().blocksDispatch())
            assertEquals(1, f.calls.get())
            assertEquals(listOf("r1"), f.session.state.value.joinedCharacters.map { it.characterId })
            assertNull(f.get("roleRewardDecisionCacheKey"))
            assertEquals(1L, (f.get("discardedRoleDecisions") as AtomicLong).get())
        } finally { f.awaitActions(); f.close() }
    }

    private fun selected(f: Batch2SessionFixture) =
        f.session.state.value.roleRewardChoiceDecision as LabyrinthRoleRewardChoiceDecision.Select

    private fun refresh(f: Batch2SessionFixture, frame: LabyrinthEntryFrameResult): Any? =
        f.invoke("refreshRoleRewardChoiceDecision", f.id, frame, 1920, 1080)

    private fun dispatchChoice(f: Batch2SessionFixture, frame: LabyrinthEntryFrameResult, selected: String, at: Long) {
        f.clock.set(at)
        val slot = frame.characterMatches.first { it.characterId == selected }.slotId
        val rect = requireNotNull(LabyrinthRoleRewardChoicePlanner.selectionButtonRect(slot, 1920, 1080))
        f.state { it.copy(lastResult = frame,
            roleRewardChoiceDecision = LabyrinthRoleRewardChoiceDecision.Select(selected, selected, rect, emptyList())) }
        f.invoke("dispatchPostEntryTap", f.id, LabyrinthPostEntryActionKind.SELECT_ROLE_REWARD,
            "choose $selected", rect, at, labyrinthRoleRewardSelectionSignature(frame), selected,
            null, null, null, false, emptyList<String>())
    }

    /** A one-shot hook at the start of real planning, outside the production publication lock. */
    private class ProfileProbe(private val delegate: Map<String, LabyrinthRoleProfile>) : Map<String, LabyrinthRoleProfile> by delegate {
        var lookups = 0
        var onLookup: (() -> Unit)? = null
        override fun containsKey(key: String): Boolean {
            lookups++
            val callback = onLookup
            onLookup = null
            callback?.invoke()
            return delegate.containsKey(key)
        }
    }

    private fun installPlanner(f: Batch2SessionFixture): ProfileProbe {
        // The fixture has passed entry, but the real feed path also requires configured route
        // dependencies to delegate to handlePostEntryPage. These are never used by reward frames.
        val routeLoader: suspend (Long?) -> LabyrinthRouteJson? = { null }
        val nodeTemplateLoader: () -> com.landosol.toolbox.labyrinth.node.NodeTemplateSet = {
            error("role round tests do not load map templates")
        }
        f.set("routeLoader", routeLoader)
        f.set("nodeTemplateLoader", nodeTemplateLoader)
        val probe = ProfileProbe(f.ids.mapIndexed { i, id ->
            id to LabyrinthRoleProfile(characterId = id, displayName = id,
                roleClass = if (i == 0) "掩护者" else "攻击者", attribute = LabyrinthCharacterAttribute.LIGHT,
                damageType = LabyrinthRoleDamageType.PHYSICAL, position = i + 1,
                userScore = 80.0, userRatingCount = 5, physicalDamagePotential = 80.0,
                magicDamagePotential = 0.0, functions = LabyrinthRoleFunctions(reliableVanguard = if (i == 0) 100.0 else 0.0))
        }.toMap())
        val scoring = LabyrinthTeamScoringConfig(
            attributeDamageBonus = mapOf(1 to 0.0, 2 to 0.08, 3 to 0.16, 4 to 0.25, 5 to 0.75))
        val optimizer = LabyrinthTeamOptimizer(LabyrinthTeamScorer(scoring))
        val policy = LabyrinthRoleChoicePolicy(optimizer)
        f.set("roleRewardChoicePlanner", LabyrinthRoleRewardChoicePlanner(probe, policy))
        return probe
    }
}
