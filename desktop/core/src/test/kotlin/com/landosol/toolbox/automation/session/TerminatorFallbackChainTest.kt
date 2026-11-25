package com.landosol.toolbox.automation.session

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminatorFallbackChainTest {
    private class FakeTerminator(
        override val kind: ClientTerminationKind,
        private val supported: Boolean,
        private val result: ClientTerminationResult? = null,
        val calls: MutableList<Unit> = mutableListOf(),
    ) : GameClientTerminator {
        override fun isSupported(): Boolean = supported
        override suspend fun terminate(): ClientTerminationResult {
            calls += Unit
            return result ?: ClientTerminationResult.TERMINATED
        }
    }

    @Test
    fun `skips unsupported candidates and uses first supported`() = runTest {
        val skipped = FakeTerminator(ClientTerminationKind.FORCE_STOP, supported = false)
        val recents = FakeTerminator(
            ClientTerminationKind.RECENTS_SWIPE,
            supported = true,
            result = ClientTerminationResult.TERMINATED,
        )
        val chain = TerminatorFallbackChain(listOf(skipped, recents))

        val result = chain.terminate()

        assertEquals(ClientTerminationResult.TERMINATED, result)
        assertTrue(skipped.calls.isEmpty())
        assertEquals(1, recents.calls.size)
        assertEquals(ClientTerminationKind.RECENTS_SWIPE, chain.kind)
    }

    @Test
    fun `falls through to next candidate on timeout or unsupported`() = runTest {
        val first = FakeTerminator(
            ClientTerminationKind.RECENTS_SWIPE,
            supported = true,
            result = ClientTerminationResult.TIMEOUT,
        )
        val second = FakeTerminator(
            ClientTerminationKind.GRACEFUL_LOGOUT,
            supported = true,
            result = ClientTerminationResult.TERMINATED,
        )
        val chain = TerminatorFallbackChain(listOf(first, second))

        assertEquals(ClientTerminationResult.TERMINATED, chain.terminate())
        assertEquals(1, first.calls.size)
        assertEquals(1, second.calls.size)
    }

    @Test
    fun `user rejection stops the chain`() = runTest {
        val first = FakeTerminator(
            ClientTerminationKind.RECENTS_SWIPE,
            supported = true,
            result = ClientTerminationResult.REJECTED,
        )
        val second = FakeTerminator(
            ClientTerminationKind.USER_ASSIST,
            supported = true,
            result = ClientTerminationResult.TERMINATED,
        )
        val chain = TerminatorFallbackChain(listOf(first, second))

        assertEquals(ClientTerminationResult.REJECTED, chain.terminate())
        assertTrue(second.calls.isEmpty())
    }

    @Test
    fun `already gone short-circuits without trying the rest`() = runTest {
        val first = FakeTerminator(
            ClientTerminationKind.RECENTS_SWIPE,
            supported = true,
            result = ClientTerminationResult.ALREADY_GONE,
        )
        val second = FakeTerminator(
            ClientTerminationKind.USER_ASSIST,
            supported = true,
        )
        val chain = TerminatorFallbackChain(listOf(first, second))

        assertEquals(ClientTerminationResult.ALREADY_GONE, chain.terminate())
        assertTrue(second.calls.isEmpty())
    }

    @Test
    fun `no supported candidate reports unsupported`() = runTest {
        val chain = TerminatorFallbackChain(
            listOf(
                FakeTerminator(ClientTerminationKind.FORCE_STOP, supported = false),
                FakeTerminator(ClientTerminationKind.RECENTS_SWIPE, supported = false),
            ),
        )

        assertEquals(ClientTerminationResult.UNSUPPORTED, chain.terminate())
        assertTrue(!chain.isSupported())
    }
}
