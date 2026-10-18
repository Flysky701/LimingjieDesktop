package com.landosol.toolbox.automation.session

import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryPageState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GameClientHardCloserTest {
    private val game = "com.bilibili.priconne.m4399"
    private val launcher = "com.android.launcher3"

    @Test
    fun `a foreground game is sent home before its processes are killed`() = runTest {
        var foreground: String? = game
        val events = mutableListOf<String>()
        val closer = GameClientHardCloser(
            foregroundPackage = { foreground },
            pressHome = {
                events += "home"
                foreground = launcher
                true
            },
            killBackgroundProcesses = { events += "kill $it" },
            canKillOtherApps = true,
            clock = { currentTime },
        )

        assertEquals(GameClientHardCloseResult.KillRequested, closer.close(game))
        assertEquals(listOf("home", "kill $game"), events)
    }

    @Test
    fun `a game already in the background is killed without pressing home`() = runTest {
        val events = mutableListOf<String>()
        val closer = GameClientHardCloser(
            foregroundPackage = { "com.landosol.toolbox" },
            pressHome = { events += "home"; true },
            killBackgroundProcesses = { events += "kill $it" },
            canKillOtherApps = true,
            clock = { currentTime },
        )

        assertEquals(GameClientHardCloseResult.KillRequested, closer.close(game))
        assertEquals(listOf("kill $game"), events)
    }

    @Test
    fun `the kill waits until the game has actually left the foreground`() = runTest {
        var foreground: String? = game
        var killedWhileForeground = false
        val closer = GameClientHardCloser(
            foregroundPackage = { foreground },
            pressHome = { true },
            killBackgroundProcesses = { killedWhileForeground = foreground == game },
            canKillOtherApps = true,
            clock = { currentTime },
            pollMillis = 100L,
        )
        // The launcher only takes over 1.2 s after the home key.
        backgroundScope.launchLater(1_200L) { foreground = launcher }

        assertEquals(GameClientHardCloseResult.KillRequested, closer.close(game))
        assertTrue(!killedWhileForeground)
        assertTrue(currentTime >= 1_200L)
    }

    @Test
    fun `a game that never leaves the foreground is not killed and fails within the timeout`() = runTest {
        var killed = false
        val closer = GameClientHardCloser(
            foregroundPackage = { game },
            pressHome = { true },
            killBackgroundProcesses = { killed = true },
            canKillOtherApps = true,
            clock = { currentTime },
            leaveForegroundTimeoutMillis = 3_000L,
        )

        val result = closer.close(game)

        assertTrue(result is GameClientHardCloseResult.Failed)
        assertTrue(!killed)
        assertTrue(currentTime in 3_000L..3_500L)
    }

    @Test
    fun `a refused home key fails without killing`() = runTest {
        var killed = false
        val closer = GameClientHardCloser(
            foregroundPackage = { game },
            pressHome = { false },
            killBackgroundProcesses = { killed = true },
            canKillOtherApps = true,
            clock = { currentTime },
        )

        assertTrue(closer.close(game) is GameClientHardCloseResult.Failed)
        assertTrue(!killed)
    }

    @Test
    fun `a kill that throws is reported instead of claiming the game closed`() = runTest {
        val closer = GameClientHardCloser(
            foregroundPackage = { launcher },
            pressHome = { true },
            killBackgroundProcesses = { throw SecurityException("no permission") },
            canKillOtherApps = true,
            clock = { currentTime },
        )

        val result = closer.close(game)

        assertTrue(result is GameClientHardCloseResult.Failed)
        assertTrue((result as GameClientHardCloseResult.Failed).reason.contains("no permission"))
    }

    @Test
    fun `android 14 refuses up front without leaving the game or calling the kill`() = runTest {
        val events = mutableListOf<String>()
        val closer = GameClientHardCloser(
            foregroundPackage = { game },
            pressHome = { events += "home"; true },
            killBackgroundProcesses = { events += "kill $it" },
            canKillOtherApps = false,
            clock = { currentTime },
        )

        val result = closer.close(game)

        assertEquals(GameClientHardCloseResult.Failed(GameClientHardCloser.ANDROID_14_REASON), result)
        assertTrue(events.isEmpty())
    }

    @Test
    fun `a kill that returns normally while the process lives on is caught at the cold start`() = runTest {
        // killBackgroundProcesses returns Unit whether or not the process died. The system here
        // quietly kept the game alive, so the relaunch brings back its old home page.
        val closer = GameClientHardCloser(
            foregroundPackage = { launcher },
            pressHome = { true },
            killBackgroundProcesses = { },
            canKillOtherApps = true,
            clock = { currentTime },
        )
        val ledger = GameClientHardCloseLedger()
        val gate = ColdStartTitleGate()

        assertEquals(GameClientHardCloseResult.KillRequested, closer.close(game))
        ledger.issue(accountId = 7L, packageName = game)
        assertTrue(ledger.consume(7L, game))

        assertEquals(ColdStartVerdict.PENDING, gate.observe(LabyrinthEntryPageState.HOME))
        assertEquals(ColdStartVerdict.CLIENT_SURVIVED, gate.observe(LabyrinthEntryPageState.HOME))
    }

    @Test
    fun `a real cold start passes the title gate`() {
        val gate = ColdStartTitleGate()

        assertEquals(ColdStartVerdict.PENDING, gate.observe(LabyrinthEntryPageState.UNKNOWN))
        assertEquals(ColdStartVerdict.PENDING, gate.observe(LabyrinthEntryPageState.GAME_LOADING_PROGRESS))
        assertEquals(ColdStartVerdict.CONFIRMED, gate.observe(LabyrinthEntryPageState.TITLE_WAITING_TAP))
        // After the title, the normal entry flow owns every later page.
        assertEquals(ColdStartVerdict.CONFIRMED, gate.observe(LabyrinthEntryPageState.HOME))
        assertTrue(gate.titleSeen)
    }

    @Test
    fun `a survivor already on the labyrinth home cannot complete the reset`() {
        val gate = ColdStartTitleGate()

        gate.observe(LabyrinthEntryPageState.DAWN_REALM_HOME_IDLE)

        assertEquals(ColdStartVerdict.CLIENT_SURVIVED, gate.observe(LabyrinthEntryPageState.DAWN_REALM_HOME_IDLE))
        assertTrue(!gate.titleSeen)
    }

    @Test
    fun `one stray in-game frame between loading frames does not stop the reset`() {
        val gate = ColdStartTitleGate()

        assertEquals(ColdStartVerdict.PENDING, gate.observe(LabyrinthEntryPageState.HOME))
        assertEquals(ColdStartVerdict.PENDING, gate.observe(LabyrinthEntryPageState.UNKNOWN))
        assertEquals(ColdStartVerdict.PENDING, gate.observe(LabyrinthEntryPageState.HOME))
        assertEquals(ColdStartVerdict.CONFIRMED, gate.observe(LabyrinthEntryPageState.TITLE_WAITING_TAP))
    }

    @Test
    fun `a ticket only counts for the account and client it was issued for`() {
        val ledger = GameClientHardCloseLedger()

        ledger.issue(accountId = 1L, packageName = game)
        assertTrue(!ledger.consume(2L, game))
        // Consuming spends the ticket even on a mismatch.
        assertTrue(!ledger.consume(1L, game))

        ledger.issue(accountId = 1L, packageName = game)
        assertTrue(!ledger.consume(1L, "com.bilibili.priconne.mi"))
        assertTrue(!ledger.consume(1L, game))

        ledger.issue(accountId = 1L, packageName = game)
        assertTrue(!ledger.consume(null, game))
    }

    @Test
    fun `a failed channel reroll does not leak its hard close into the next channel`() {
        // 4399 batch: hard close succeeds, the reroll then fails and the batch halts without
        // syncing the client, so the ticket is never consumed.
        val ledger = GameClientHardCloseLedger()
        ledger.issue(accountId = 1L, packageName = game)

        // The user switches to a 小米 account and starts a new batch. Its reroll clears the ledger
        // before anything else (and 小米 is not hard-closed, so nothing new is issued).
        ledger.clear()

        assertTrue(!ledger.consume(2L, "com.bilibili.priconne.mi"))
    }

    @Test
    fun `even without the clear a stale ticket is refused for another account`() {
        val ledger = GameClientHardCloseLedger()
        ledger.issue(accountId = 1L, packageName = game)

        assertTrue(!ledger.consume(2L, "com.bilibili.priconne.mi"))
    }

    private fun CoroutineScope.launchLater(delayMillis: Long, block: () -> Unit) {
        launch {
            delay(delayMillis)
            block()
        }
    }
}
