package com.landosol.toolbox.automation.session

import com.landosol.toolbox.automation.ScreenPoint
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionExpiryTerminatorTest {
    private val triggerEntry = ScreenPoint(1735f, 805f)
    private val returnTitle = ScreenPoint(961f, 739f)
    private var result: ClientTerminationResult? = null

    private fun terminator(
        taps: MutableList<ScreenPoint> = mutableListOf(),
        tapResult: Boolean = true,
        onTapImpl: (() -> Boolean)? = null,
        popupVisible: () -> Boolean = { true },
        titleReached: () -> Boolean = { true },
        frameSize: () -> Pair<Int, Int> = { 1920 to 1080 },
        available: () -> Boolean = { true },
        popupTimeoutMillis: Long = 50_000L,
        titleTimeoutMillis: Long = 50_000L,
        triggerAnchorPoint: () -> ScreenPoint? = { null },
        returnTitleAnchorPoint: () -> ScreenPoint? = { null },
    ) = SessionExpiryTerminator(
        onTap = { point ->
            taps += point
            onTapImpl?.invoke() ?: tapResult
        },
        triggerEntryPoint = triggerEntry,
        returnTitlePoint = returnTitle,
        popupVisible = popupVisible,
        titleReached = titleReached,
        frameSize = frameSize,
        available = available,
        popupTimeoutMillis = popupTimeoutMillis,
        titleTimeoutMillis = titleTimeoutMillis,
        entryTapDelayMillis = 0L,
        returnTapDelayMillis = 0L,
        triggerAnchorPoint = triggerAnchorPoint,
        returnTitleAnchorPoint = returnTitleAnchorPoint,
    )

    @Test
    fun `taps trigger entry then return button and terminates`() = runTest {
        val taps = mutableListOf<ScreenPoint>()
        var popupSeen = false
        var titleSeen = false
        val terminator = terminator(
            taps = taps,
            popupVisible = { popupSeen },
            titleReached = { titleSeen },
        )

        val job = launch { result = terminator.terminate() }
        kotlinx.coroutines.delay(30)
        popupSeen = true
        kotlinx.coroutines.delay(30)
        titleSeen = true
        job.join()

        assertEquals(ClientTerminationResult.TERMINATED, result)
        assertEquals(listOf(ScreenPoint(1735f, 805f), ScreenPoint(961f, 739f)), taps)
    }

    @Test
    fun `returns unsupported when frame size is invalid`() = runTest {
        val terminator = terminator(frameSize = { 0 to 0 })

        assertEquals(ClientTerminationResult.UNSUPPORTED, terminator.terminate())
    }

    @Test
    fun `returns timeout when popup never appears`() = runTest {
        val taps = mutableListOf<ScreenPoint>()
        val terminator = terminator(taps = taps, popupTimeoutMillis = 20L, popupVisible = { false })

        assertEquals(ClientTerminationResult.TIMEOUT, terminator.terminate())
        assertEquals(1, taps.size)
    }

    @Test
    fun `returns timeout when title page never reached`() = runTest {
        val taps = mutableListOf<ScreenPoint>()
        val terminator = terminator(taps = taps, titleTimeoutMillis = 20L, titleReached = { false })

        assertEquals(ClientTerminationResult.TIMEOUT, terminator.terminate())
        assertEquals(2, taps.size)
    }

    @Test
    fun `returns unsupported when trigger tap rejected`() = runTest {
        val terminator = terminator(tapResult = false)

        assertEquals(ClientTerminationResult.UNSUPPORTED, terminator.terminate())
    }

    @Test
    fun `returns timeout when return title tap rejected`() = runTest {
        var tapCount = 0
        val terminator = terminator(
            onTapImpl = { tapCount++ < 1 },
        )

        assertEquals(ClientTerminationResult.TIMEOUT, terminator.terminate())
        assertEquals(2, tapCount)
    }

    @Test
    fun `supported when availability check passes`() {
        assertTrue(terminator(available = { true }).isSupported())
        assertFalse(terminator(available = { false }).isSupported())
    }

    @Test
    fun `uses current layout anchor centers instead of reference coordinates`() = runTest {
        val taps = mutableListOf<ScreenPoint>()
        val terminator = terminator(
            taps = taps,
            frameSize = { 2780 to 1264 },
            triggerAnchorPoint = { ScreenPoint(2542.5f, 1016f) },
            returnTitleAnchorPoint = { ScreenPoint(1390f, 820f) },
        )

        assertEquals(ClientTerminationResult.TERMINATED, terminator.terminate())
        assertEquals(
            listOf(ScreenPoint(2542.5f, 1016f), ScreenPoint(1390f, 820f)),
            taps,
        )
    }
}
