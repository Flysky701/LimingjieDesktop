package com.landosol.toolbox.protocol.bilibili

import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class GameProtocolCryptoTest {
    private val crypto = GameProtocolCrypto()
    private val key = "0123456789abcdef0123456789abcdef".toByteArray(Charsets.US_ASCII)

    @Test
    fun `request appends key and decrypts to original payload`() {
        val plain = "protocol-payload".toByteArray()

        val encrypted = crypto.encryptRequest(plain, key)

        assertArrayEquals(key, encrypted.copyOfRange(encrypted.size - key.size, encrypted.size))
        assertArrayEquals(plain, crypto.decryptRequestForTest(encrypted))
    }

    @Test
    fun `response base64 envelope decrypts to original payload`() {
        val plain = "response-payload".toByteArray()

        val response = crypto.encryptResponseForTest(plain, key)

        assertArrayEquals(plain, crypto.decryptResponse(response))
    }

    @Test
    fun `viewer id uses same encrypted envelope format`() {
        val encoded = crypto.encryptViewerId(123456L, key)

        assertEquals("123456", crypto.decryptRequestForTest(Base64.getDecoder().decode(encoded)).toString(Charsets.UTF_8))
    }
}
