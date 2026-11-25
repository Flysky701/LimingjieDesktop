package com.landosol.toolbox.protocol.bilibili

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class BilibiliSdkSignerTest {
    @Test
    fun `sign concatenates values by sorted key and excludes existing sign`() {
        val signer = BilibiliSdkSigner(secret = "fixture-secret")

        val result = signer.sign(
            linkedMapOf(
                "zeta" to "last",
                "alpha" to "first",
                "sign" to "must-not-be-included",
                "middle" to "中间",
            ),
        )

        assertEquals("43dc7aa1d53bb6a778aadd2f72072002", result)
    }

    @Test
    fun `signed fields receive one timestamp pair and one signature`() {
        val signer = BilibiliSdkSigner(secret = "fixture-secret")

        val result = signer.withSignature(linkedMapOf("user_id" to "tester"), epochSeconds = 1234L)

        assertEquals("1234", result["timestamp"])
        assertEquals("1234", result["client_timestamp"])
        assertFalse(result["sign"].isNullOrBlank())
        assertEquals(4, result.size)
    }
}
