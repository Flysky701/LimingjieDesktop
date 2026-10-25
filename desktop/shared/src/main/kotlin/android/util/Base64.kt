package android.util

object Base64 {
    const val DEFAULT = 0
    const val NO_PADDING = 1
    const val NO_WRAP = 2
    const val CRLF = 4
    const val URL_SAFE = 8
    fun decode(value: String, flags: Int): ByteArray =
        (if (flags and URL_SAFE != 0) java.util.Base64.getUrlDecoder() else java.util.Base64.getDecoder())
            .decode(value.filterNot { it.isWhitespace() })
    fun decode(value: ByteArray, flags: Int): ByteArray = decode(value.toString(Charsets.US_ASCII), flags)
    fun encodeToString(value: ByteArray, flags: Int): String {
        var encoder = if (flags and URL_SAFE != 0) java.util.Base64.getUrlEncoder() else java.util.Base64.getEncoder()
        if (flags and NO_PADDING != 0) encoder = encoder.withoutPadding()
        val encoded = encoder.encodeToString(value)
        if (flags and NO_WRAP != 0 || encoded.isEmpty()) return encoded
        val separator = if (flags and CRLF != 0) "\r\n" else "\n"
        return encoded.chunked(76).joinToString(separator, postfix = separator)
    }
    fun encode(value: ByteArray, flags: Int): ByteArray = encodeToString(value, flags).toByteArray(Charsets.US_ASCII)
}
