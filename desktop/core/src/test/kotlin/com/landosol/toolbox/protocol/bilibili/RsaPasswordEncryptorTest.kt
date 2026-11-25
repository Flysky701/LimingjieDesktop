package com.landosol.toolbox.protocol.bilibili

import java.security.KeyPairGenerator
import java.util.Base64
import javax.crypto.Cipher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class RsaPasswordEncryptorTest {
    @Test
    fun `encrypts hash and password with PKCS1 v1_5`() {
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val publicPem = "-----BEGIN PUBLIC KEY-----\n" +
            Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(keyPair.public.encoded) +
            "\n-----END PUBLIC KEY-----"

        val encrypted = RsaPasswordEncryptor().encrypt(
            serverHash = "hash-prefix",
            password = "local-secret",
            publicKeyPem = publicPem,
        )

        assertFalse(encrypted.contains("local-secret"))
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.DECRYPT_MODE, keyPair.private)
        val decrypted = String(cipher.doFinal(Base64.getDecoder().decode(encrypted)), Charsets.UTF_8)
        assertEquals("hash-prefixlocal-secret", decrypted)
    }
}
