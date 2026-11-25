package com.landosol.toolbox.protocol.bilibili

import java.security.KeyFactory
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher

class RsaPasswordEncryptor {
    fun encrypt(
        serverHash: String,
        password: String,
        publicKeyPem: String,
    ): String {
        val der = publicKeyPem
            .replace("-----BEGIN PUBLIC KEY-----", "")
            .replace("-----END PUBLIC KEY-----", "")
            .filterNot(Char::isWhitespace)
            .let(Base64.getDecoder()::decode)
        val publicKey = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(der))
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, publicKey)
        val plaintext = (serverHash + password).toByteArray(Charsets.UTF_8)
        return Base64.getEncoder().encodeToString(cipher.doFinal(plaintext))
    }
}
