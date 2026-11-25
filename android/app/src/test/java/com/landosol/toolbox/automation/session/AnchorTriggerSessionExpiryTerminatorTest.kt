package com.landosol.toolbox.automation.session

import com.landosol.toolbox.automation.ScreenPoint
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnchorTriggerSessionExpiryTerminatorTest {
    private val adventureTab = ScreenPoint(172f, 1007f)
    private val retry = ScreenPoint(1620f, 990f)
    private val returnTitle = ScreenPoint(961f, 739f)
    private var result: ClientTerminationResult? = null

    private fun terminator(
        taps: MutableList<ScreenPoint> = mutableListOf(),
        triggerPoint: () -> ScreenPoint? = { adventureTab },
        popupVisible: () -> Boolean = { true },
        titleReached: () -> Boolean = { true },
        synchronizedWithoutExpiry: () -> Boolean = { false },
        frameSize: () -> Pair<Int, Int> = { 1920 to 1080 },
        popupTimeoutMillis: Long = 300L,
        titleTimeoutMillis: Long = 50_000L,
        maxTriggerTaps: Int = 3,
    ) = AnchorTriggerSessionExpiryTerminator(
        onTap = { point -> taps += point; true },
        triggerPoint = triggerPoint,
        returnTitlePoint = returnTitle,
        popupVisible = popupVisible,
        titleReached = titleReached,
        synchronizedWithoutExpiry = synchronizedWithoutExpiry,
        frameSize = frameSize,
        popupTimeoutMillis = popupTimeoutMillis,
        popupAfterTapTimeoutMillis = popupTimeoutMillis / maxTriggerTaps,
        titleTimeoutMillis = titleTimeoutMillis,
        triggerTapDelayMillis = 0L,
        returnTapDelayMillis = 0L,
        maxTriggerTaps = maxTriggerTaps,
    )

    @Test
    fun `taps the recognised home tab then returns to title`() = runTest {
        val taps = mutableListOf<ScreenPoint>()
        var popupSeen = false
        var titleSeen = false
        val terminator = terminator(taps = taps, popupVisible = { popupSeen }, titleReached = { titleSeen })

        val job = launch { result = terminator.terminate() }
        kotlinx.coroutines.delay(30)
        popupSeen = true
        kotlinx.coroutines.delay(30)
        titleSeen = true
        job.join()

        assertEquals(ClientTerminationResult.TERMINATED, result)
        assertEquals(listOf(adventureTab, returnTitle), taps)
    }

    @Test
    fun `never taps while no trigger is recognised`() = runTest {
        // 2026-09-17: the old fixed home-tab point landed on whatever page was showing.
        val taps = mutableListOf<ScreenPoint>()
        val terminator = terminator(
            taps = taps,
            triggerPoint = { null },
            popupVisible = { false },
            popupTimeoutMillis = 90L,
        )

        assertEquals(ClientTerminationResult.TIMEOUT, terminator.terminate())
        assertEquals(emptyList<ScreenPoint>(), taps)
        assertEquals("trigger-not-recognised-after-0-taps", terminator.lastStep)
    }

    @Test
    fun `waits for the trigger to appear before tapping it`() = runTest {
        val taps = mutableListOf<ScreenPoint>()
        var onHome = false
        var popupSeen = false
        val terminator = terminator(
            taps = taps,
            triggerPoint = { adventureTab.takeIf { onHome } },
            popupVisible = { popupSeen },
            popupTimeoutMillis = 3_000L,
        )

        val job = launch { result = terminator.terminate() }
        kotlinx.coroutines.delay(20)
        assertEquals(emptyList<ScreenPoint>(), taps)
        onHome = true
        kotlinx.coroutines.delay(600)
        popupSeen = true
        job.join()

        assertEquals(ClientTerminationResult.TERMINATED, result)
        assertEquals(listOf(adventureTab, returnTitle), taps)
    }

    @Test
    fun `battle failure page walks the three-step retreat chain before the popup appears`() = runTest {
        val end = ScreenPoint(1160f, 990f)
        val retreatButton = ScreenPoint(962f, 745f)
        val confirm = ScreenPoint(1252f, 745f)
        val taps = mutableListOf<ScreenPoint>()
        var popupSeen = false
        // Each tap advances the dialog; the trigger is re-read from the "current frame".
        val terminator = terminator(
            taps = taps,
            triggerPoint = { listOf(end, retreatButton, confirm).getOrNull(taps.size) },
            popupVisible = { popupSeen },
            popupTimeoutMillis = 900L,
            maxTriggerTaps = 6,
        )

        val job = launch { result = terminator.terminate() }
        kotlinx.coroutines.delay(30)
        while (taps.size < 3) kotlinx.coroutines.delay(10)
        popupSeen = true
        job.join()

        assertEquals(ClientTerminationResult.TERMINATED, result)
        assertEquals(listOf(end, retreatButton, confirm, returnTitle), taps)
    }

    @Test
    fun `trigger is re-read before every tap so a page change switches target`() = runTest {
        val taps = mutableListOf<ScreenPoint>()
        var onFailurePage = true
        val terminator = terminator(
            taps = taps,
            triggerPoint = { if (onFailurePage) retry else adventureTab },
            popupVisible = { false },
            popupTimeoutMillis = 60L,
            maxTriggerTaps = 2,
        )

        val job = launch { result = terminator.terminate() }
        kotlinx.coroutines.delay(10)
        onFailurePage = false
        job.join()

        assertEquals(ClientTerminationResult.TIMEOUT, result)
        assertEquals(listOf(retry, adventureTab), taps)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `a newly recognised retreat confirmation interrupts the popup wait`() = runTest {
        val retreat = ScreenPoint(1200f, 960f)
        val confirm = ScreenPoint(1180f, 740f)
        val triggers = listOf(retreat, confirm)
        val taps = mutableListOf<ScreenPoint>()
        val popupAfterTapTimeoutMillis = 12_000L
        val terminator = AnchorTriggerSessionExpiryTerminator(
            onTap = { taps.add(it) },
            triggerPoint = { triggers.getOrNull(taps.size) },
            returnTitlePoint = returnTitle,
            popupVisible = { taps.size == triggers.size },
            titleReached = { taps.lastOrNull() == returnTitle },
            frameSize = { 1920 to 1080 },
            popupAfterTapTimeoutMillis = popupAfterTapTimeoutMillis,
            triggerTapDelayMillis = 0L,
            returnTapDelayMillis = 0L,
            clock = { testScheduler.currentTime },
        )

        assertEquals(ClientTerminationResult.TERMINATED, terminator.terminate())
        assertEquals(listOf(retreat, confirm, returnTitle), taps)
        assertTrue(testScheduler.currentTime < popupAfterTapTimeoutMillis)
    }

    @Test
    fun `skips the trigger tap when the expiry popup is already showing`() = runTest {
        val taps = mutableListOf<ScreenPoint>()
        val terminator = terminator(taps = taps, popupVisible = { true }, titleReached = { true })

        assertEquals(ClientTerminationResult.TERMINATED, terminator.terminate())
        assertEquals(listOf(returnTitle), taps)
    }

    @Test
    fun `accepts successful home navigation when the client was already synchronized`() = runTest {
        val taps = mutableListOf<ScreenPoint>()
        var synchronized = false
        val terminator = terminator(
            taps = taps,
            popupVisible = { false },
            synchronizedWithoutExpiry = { synchronized },
            popupTimeoutMillis = 3_000L,
        )

        val job = launch { result = terminator.terminate() }
        while (taps.isEmpty()) kotlinx.coroutines.delay(10)
        synchronized = true
        job.join()

        assertEquals(ClientTerminationResult.ALREADY_GONE, result)
        assertEquals(listOf(adventureTab), taps)
        assertEquals("client-already-synchronized", terminator.lastStep)
    }

    @Test
    fun `expiry popup wins if home chrome is also recognizable behind it`() = runTest {
        val taps = mutableListOf<ScreenPoint>()
        val terminator = terminator(
            taps = taps,
            popupVisible = { true },
            synchronizedWithoutExpiry = { true },
            titleReached = { true },
        )

        assertEquals(ClientTerminationResult.TERMINATED, terminator.terminate())
        assertEquals(listOf(returnTitle), taps)
    }

    @Test
    fun `retries the trigger a bounded number of times then times out`() = runTest {
        val taps = mutableListOf<ScreenPoint>()
        val terminator = terminator(taps = taps, popupVisible = { false }, popupTimeoutMillis = 60L, maxTriggerTaps = 3)

        assertEquals(ClientTerminationResult.TIMEOUT, terminator.terminate())
        assertEquals(3, taps.size)
        assertEquals(setOf(adventureTab), taps.toSet())
    }

    @Test
    fun `returns unsupported when no frame has been seen`() = runTest {
        val terminator = AnchorTriggerSessionExpiryTerminator(
            onTap = { true },
            triggerPoint = { adventureTab },
            returnTitlePoint = returnTitle,
            popupVisible = { true },
            titleReached = { true },
            frameSize = { 0 to 0 },
            frameReadyTimeoutMillis = 20L,
        )
        assertEquals(ClientTerminationResult.UNSUPPORTED, terminator.terminate())
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `pauses without tapping or spending timeout budget and resumes cleanly`() = runTest {
        val taps = mutableListOf<ScreenPoint>()
        var paused = true
        var popupSeen = false
        var titleSeen = false
        val terminator = AnchorTriggerSessionExpiryTerminator(
            onTap = { point -> taps += point; true },
            triggerPoint = { adventureTab },
            returnTitlePoint = returnTitle,
            popupVisible = { popupSeen },
            titleReached = { titleSeen },
            frameSize = { 1920 to 1080 },
            popupTimeoutMillis = 900L,
            popupAfterTapTimeoutMillis = 300L,
            titleTimeoutMillis = 5_000L,
            triggerTapDelayMillis = 100L,
            returnTapDelayMillis = 100L,
            maxTriggerTaps = 3,
            isPaused = { paused },
            clock = { testScheduler.currentTime },
        )

        val job = launch { result = terminator.terminate() }
        advanceTimeBy(2_500L)
        assertEquals(emptyList<ScreenPoint>(), taps)
        assertFalse(job.isCompleted)

        paused = false
        while (taps.isEmpty()) advanceTimeBy(100L)
        popupSeen = true
        while (taps.size < 2) advanceTimeBy(100L)
        titleSeen = true
        advanceUntilIdle()
        job.join()

        assertEquals(ClientTerminationResult.TERMINATED, result)
        assertEquals(listOf(adventureTab, returnTitle), taps)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `cancels immediately during pause with zero taps`() = runTest {
        val taps = mutableListOf<ScreenPoint>()
        var cancelled = false
        val terminator = AnchorTriggerSessionExpiryTerminator(
            onTap = { point -> taps += point; true },
            triggerPoint = { adventureTab },
            returnTitlePoint = returnTitle,
            popupVisible = { false },
            titleReached = { false },
            frameSize = { 1920 to 1080 },
            popupTimeoutMillis = 900L,
            maxTriggerTaps = 3,
            isPaused = { true },
            isCancelled = { cancelled },
            clock = { testScheduler.currentTime },
        )

        val job = launch { result = terminator.terminate() }
        advanceTimeBy(1_500L)
        assertEquals(emptyList<ScreenPoint>(), taps)
        assertFalse(job.isCompleted)

        cancelled = true
        advanceUntilIdle()
        job.join()

        assertEquals(ClientTerminationResult.REJECTED, result)
        assertEquals(emptyList<ScreenPoint>(), taps)
    }
}
