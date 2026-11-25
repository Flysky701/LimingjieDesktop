package com.landosol.toolbox.protocol.bilibili

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GameProtocolFixtureTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `decoded contract fixtures contain required fields and placeholder identifiers`() {
        val server = fixture("game_server_index.json")
        val maintenance = fixture("game_maintenance.json")
        val login = fixture("game_sdk_login_success.json")
        val load = fixture("game_load_index_success.json")

        assertTrue(server["data"]!!.jsonObject["server"]!!.jsonArray.isNotEmpty())
        assertTrue(maintenance["data"]!!.jsonObject["required_manifest_ver"]!!.jsonPrimitive.content.isNotBlank())
        assertFalse(login["data"]!!.jsonObject["is_risk"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(
            "FixturePlayer",
            load["data"]!!.jsonObject["user_info"]!!.jsonObject["user_name"]!!.jsonPrimitive.content,
        )
    }

    private fun fixture(name: String) = javaClass.classLoader!!
        .getResourceAsStream("bilibili/$name")
        ?.bufferedReader()
        ?.use { json.parseToJsonElement(it.readText()).jsonObject }
        ?: error("Missing fixture: $name")
}
