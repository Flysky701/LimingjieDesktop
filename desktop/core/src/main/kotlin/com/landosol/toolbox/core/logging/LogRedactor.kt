package com.landosol.toolbox.core.logging

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

object LogRedactor {
    private const val REDACTED = "<redacted>"
    private const val SECRET_KEYS = "password|passwd|access[_-]?key|access[_-]?token|refresh[_-]?token|" +
        "token|login[_-]?id|session[_-]?key|secret"
    private const val QUOTED = """"(?:[^"\\]|\\[\s\S])*+"|'(?:[^'\\]|\\[\s\S])*+'"""
    private const val UNQUOTED = """[^\s,;}&]+"""
    private val secretKey = Regex("$SECRET_KEYS|authorization|cookie", RegexOption.IGNORE_CASE)
    private val credentials = Regex(
        listOf(
            assignment("authorization", """Digest[ \t]+[^\r\n]+|(?:(?:Bearer|Basic|Negotiate|NTLM|Token)[ \t]+)?$UNQUOTED"""),
            assignment("cookie", """$UNQUOTED(?:;[ \t]*[^\s,;=}&]+=$UNQUOTED)*+"""),
            assignment(SECRET_KEYS, UNQUOTED),
        ).joinToString("|"),
        RegexOption.IGNORE_CASE,
    )
    private val encrypted = Regex("v1:[A-Za-z0-9+/=]{16,}")

    fun redact(text: String): String {
        val safe = credentials.replace(text) { match ->
            val (prefix, value) = match.groupValues.drop(1).filter(String::isNotEmpty)
            val quote = value.first().takeIf { it == value.last() && (it == '"' || it == '\'') }
            prefix + if (quote == null) REDACTED else "$quote$REDACTED$quote"
        }
        return encrypted.replace(safe, "<encrypted-redacted>")
    }

    internal fun redactJson(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> JsonObject(element.entries.associate { (key, value) ->
            redact(key) to when {
                secretKey.matches(key) && value is JsonPrimitive && value.isString -> JsonPrimitive(REDACTED)
                else -> redactJson(value)
            }
        })
        is JsonArray -> JsonArray(element.map(::redactJson))
        is JsonPrimitive -> if (element.isString) JsonPrimitive(redact(element.content)) else element
    }

    private fun assignment(keys: String, unquoted: String): String =
        """(["']?(?:$keys)["']?\s*[:=]\s*)($QUOTED|$unquoted)"""
}
