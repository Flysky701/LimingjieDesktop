package com.landosol.toolbox.protocol.bilibili

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class GameProtocolCrypto(
    private val secureRandom: SecureRandom = SecureRandom(),
) {
    fun createKey(): ByteArray = ByteArray(KEY_SIZE) { HEX[secureRandom.nextInt(HEX.size)].code.toByte() }

    fun encryptRequest(plain: ByteArray, key: ByteArray): ByteArray = encrypt(plain, key)

    fun encryptViewerId(viewerId: Long, key: ByteArray): String = Base64.getEncoder().encodeToString(
        encrypt(viewerId.toString().toByteArray(Charsets.UTF_8), key),
    )

    fun decryptResponse(body: ByteArray): ByteArray {
        val decoded = Base64.getDecoder().decode(body.toString(Charsets.US_ASCII).trim())
        require(decoded.size > KEY_SIZE) { "Invalid encrypted response" }
        val key = decoded.copyOfRange(decoded.size - KEY_SIZE, decoded.size)
        return decrypt(decoded.copyOfRange(0, decoded.size - KEY_SIZE), key)
    }

    internal fun encryptResponseForTest(plain: ByteArray, key: ByteArray): ByteArray = Base64.getEncoder().encode(
        encrypt(plain, key),
    )

    internal fun decryptRequestForTest(body: ByteArray): ByteArray {
        require(body.size > KEY_SIZE) { "Invalid encrypted request" }
        val key = body.copyOfRange(body.size - KEY_SIZE, body.size)
        return decrypt(body.copyOfRange(0, body.size - KEY_SIZE), key)
    }

    private fun encrypt(plain: ByteArray, key: ByteArray): ByteArray {
        require(key.size == KEY_SIZE) { "Invalid protocol key" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(IV))
        return cipher.doFinal(plain) + key
    }

    private fun decrypt(ciphertext: ByteArray, key: ByteArray): ByteArray {
        require(key.size == KEY_SIZE) { "Invalid protocol key" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(IV))
        return cipher.doFinal(ciphertext)
    }

    private companion object {
        const val KEY_SIZE = 32
        const val TRANSFORMATION = "AES/CBC/PKCS5Padding"
        val IV = "7Fk9Lm3Np8Qr4Sv2".toByteArray(Charsets.US_ASCII)
        val HEX = "0123456789abcdef".toCharArray()
    }
}
