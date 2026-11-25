package com.landosol.toolbox.protocol.bilibili

import java.security.KeyPairGenerator
import java.util.Base64
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class BilibiliSdkHttpGatewayTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `captcha response is mapped without leaking plaintext password`() = runTest {
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val publicPem = "-----BEGIN PUBLIC KEY-----\n" +
            Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(keyPair.public.encoded) +
            "\n-----END PUBLIC KEY-----"
        server.enqueue(
            MockResponse().setBody(
                """{"code":0,"hash":"fixture-hash","rsa_key":${Json.encodeToString(publicPem)}}""",
            ),
        )
        server.enqueue(
            MockResponse().setBody(
                fixture("bilibili/sdk_login_captcha_required.json"),
            ),
        )
        val gateway = BilibiliSdkHttpGateway(
            client = OkHttpClient.Builder().retryOnConnectionFailure(false).build(),
            endpoint = server.url("/"),
            requestProfile = BilibiliSdkRequestProfile.fixture(),
            signer = BilibiliSdkSigner("fixture-secret"),
            json = Json { ignoreUnknownKeys = true },
            epochSeconds = { 1234L },
        )

        val result = gateway.login(SdkLoginCredentials("fixture-user", "plain-password"))

        assertTrue(result is SdkLoginResult.CaptchaRequired)
        val rsaRequest = server.takeRequest()
        val loginRequest = server.takeRequest()
        assertTrue(rsaRequest.path.orEmpty().endsWith("/api/client/rsa"))
        assertTrue(loginRequest.path.orEmpty().endsWith("/api/client/login"))
        val body = loginRequest.body.readUtf8()
        assertTrue(body.contains("user_id=fixture-user"))
        assertTrue(body.contains("sign="))
        assertFalse(body.contains("plain-password"))
    }

    @Test
    fun `captcha initialization maps only provider challenge fields`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                fixture("bilibili/sdk_captcha_ready.json"),
            ),
        )
        val gateway = BilibiliSdkHttpGateway(
            client = OkHttpClient.Builder().retryOnConnectionFailure(false).build(),
            endpoint = server.url("/"),
            requestProfile = BilibiliSdkRequestProfile.fixture(),
            signer = BilibiliSdkSigner("fixture-secret"),
            json = Json { ignoreUnknownKeys = true },
            epochSeconds = { 1234L },
        )

        val result = gateway.startCaptcha()

        assertTrue(result is SdkCaptchaResult.Ready)
        result as SdkCaptchaResult.Ready
        assertTrue(result.challenge.gt == "fixture-gt")
        assertTrue(server.takeRequest().path.orEmpty().endsWith("/api/client/start_captcha"))
    }

    private fun fixture(path: String): String = checkNotNull(javaClass.classLoader?.getResource(path)) {
        "Missing fixture $path"
    }.readText()
}
