package com.landosol.toolbox.protocol.bilibili

import java.security.SecureRandom
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class BilibiliGameProtocolGatewayTest {
    private lateinit var server: MockWebServer
    private val codec = MessagePackCodec()
    private val crypto = GameProtocolCrypto(FixedSecureRandom())
    private val responseKey = "11111111111111111111111111111111".toByteArray(Charsets.US_ASCII)

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `loads a real shaped account profile without retrying requests`() = runTest {
        server.dispatcher = protocolDispatcher(risk = false)
        val gateway = gateway()

        val result = gateway.loginAndLoadProfile(SdkSession("sdk-uid", "sdk-access"), "login-id")

        assertTrue(result is GameLoginResult.Success)
        val profile = (result as GameLoginResult.Success).profile
        assertEquals(987654321L, profile.viewerId)
        assertEquals("测试玩家", profile.userName)
        assertEquals(88, profile.teamLevel)
        assertEquals(5, server.requestCount)

        assertEquals("/source_ini/index?format=json", server.takeRequest().path)
        assertEquals("/source_ini/get_maintenance_status?format=json", server.takeRequest().path)
        val loginRequest = server.takeRequest()
        assertEquals("/tool/sdk_login", loginRequest.path)
        val loginPayload = codec.decode(crypto.decryptRequestForTest(loginRequest.body.readByteArray())) as Map<*, *>
        assertEquals("sdk-uid", loginPayload["uid"])
        assertEquals("sdk-access", loginPayload["access_key"])
        server.takeRequest()
        val loadRequest = server.takeRequest()
        assertEquals("request-2", loadRequest.getHeader("REQUEST-ID"))
        assertEquals("202607141753", loadRequest.getHeader("MANIFEST-VER"))
    }

    @Test
    fun `stops after tool login reports risk`() = runTest {
        server.dispatcher = protocolDispatcher(risk = true)

        val result = gateway().loginAndLoadProfile(SdkSession("sdk-uid", "sdk-access"), "login-id")

        assertEquals(GameLoginResult.RiskRequired, result)
        assertEquals(3, server.requestCount)
    }

    private fun gateway() = BilibiliGameProtocolGateway(
        client = okhttp3.OkHttpClient.Builder().retryOnConnectionFailure(false).build(),
        bootstrapEndpoint = server.url("/"),
        profile = GameProtocolProfile(
            appVersion = "11.4.0",
            headers = mapOf("APP-VER" to "11.4.0", "RES-VER" to "10002200"),
        ),
        codec = codec,
        crypto = crypto,
    )

    private fun protocolDispatcher(risk: Boolean) = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
            "/source_ini/index?format=json" -> jsonResponse(
                """{"data_headers":{"result_code":1},"data":{"server":["${server.url("/")}"],"server_error":null}}""",
            )
            "/source_ini/get_maintenance_status?format=json" -> jsonResponse(
                """{"data_headers":{"result_code":1},"data":{"required_manifest_ver":"202607141753","res_ver":"10002200","server_error":null}}""",
            )
            "/tool/sdk_login" -> encryptedResponse(
                data = mapOf("server_error" to null, "is_risk" to risk),
                requestId = "request-1",
                viewerId = "987654321",
                sid = "server-session",
            )
            "/check/game_start" -> encryptedResponse(
                data = mapOf("server_error" to null, "now_tutorial" to true),
                requestId = "request-2",
                viewerId = "987654321",
            )
            "/load/index" -> encryptedResponse(
                data = mapOf(
                    "server_error" to null,
                    "user_info" to mapOf(
                        "viewer_id" to 987654321L,
                        "user_name" to "测试玩家",
                        "team_level" to 88L,
                    ),
                ),
                requestId = "request-3",
                viewerId = "987654321",
            )
            else -> MockResponse().setResponseCode(404)
        }
    }

    private fun jsonResponse(body: String) = MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody(body)

    private fun encryptedResponse(
        data: Map<String, Any?>,
        requestId: String,
        viewerId: String,
        sid: String? = null,
    ): MockResponse {
        val headers = linkedMapOf<String, Any?>(
            "result_code" to 1L,
            "request_id" to requestId,
            "viewer_id" to viewerId,
            "servertime" to 1_700_000_000L,
        )
        if (sid != null) headers["sid"] = sid
        val envelope = mapOf("data_headers" to headers, "data" to data)
        val encrypted = crypto.encryptResponseForTest(codec.encode(envelope), responseKey)
        return MockResponse().setBody(Buffer().write(encrypted))
    }

    private class FixedSecureRandom : SecureRandom() {
        override fun nextInt(bound: Int): Int = 0
    }
}
