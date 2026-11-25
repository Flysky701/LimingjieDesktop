package com.landosol.toolbox.protocol.bilibili

import java.security.MessageDigest

class BilibiliSdkSigner(
    private val secret: String,
) {
    fun sign(fields: Map<String, String>): String {
        val payload = fields
            .filterKeys { it != SIGN_FIELD }
            .toSortedMap()
            .values
            .joinToString(separator = "") + secret
        return MessageDigest.getInstance("MD5")
            .digest(payload.toByteArray(Charsets.UTF_8))
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    fun withSignature(
        fields: LinkedHashMap<String, String>,
        epochSeconds: Long,
    ): LinkedHashMap<String, String> = LinkedHashMap(fields).apply {
        remove(SIGN_FIELD)
        this["timestamp"] = epochSeconds.toString()
        this["client_timestamp"] = epochSeconds.toString()
        this[SIGN_FIELD] = sign(this)
    }

    private companion object {
        const val SIGN_FIELD = "sign"
    }
}
