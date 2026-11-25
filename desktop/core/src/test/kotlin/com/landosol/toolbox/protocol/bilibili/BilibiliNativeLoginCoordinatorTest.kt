package com.landosol.toolbox.protocol.bilibili

import com.landosol.toolbox.security.InMemorySdkSessionStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BilibiliNativeLoginCoordinatorTest {
    private val material = AccountLoginMaterial(7L, "credential-key", "login-id", "password", GameServer.CN_BILIBILI)

    @Test
    fun `expired cached SDK session is refreshed once`() = runTest {
        val store = InMemorySdkSessionStore().apply {
            save(material.credentialKey, SdkSession("old-uid", "old-access"))
        }
        val sdkGateway = FakeSdkGateway(
            loginResults = ArrayDeque(listOf(SdkLoginResult.Success(SdkSession("new-uid", "new-access")))),
        )
        val gameGateway = FakeGameGateway(
            results = ArrayDeque(
                listOf(
                    GameLoginResult.SessionRejected("会话已过期"),
                    success(123L, "玩家", 10),
                ),
            ),
        )
        val coordinator = BilibiliNativeLoginCoordinator(
            sdkCoordinatorProvider = { BilibiliLoginCoordinator(sdkGateway, store) },
            sdkGatewayProvider = { sdkGateway },
            sessionStore = store,
            gameGatewayFor = { gameGateway },
            gameSessionRegistry = InMemoryGameSessionRegistry(),
        )

        val result = coordinator.start(material)

        assertTrue(result is NativeLoginResult.Success)
        assertEquals("new-uid", store.read(material.credentialKey)?.uid)
        assertEquals(1, sdkGateway.loginCount)
        assertEquals(2, gameGateway.loginCount)
    }

    @Test
    fun `game risk uses human captcha proof and resumes login`() = runTest {
        val store = InMemorySdkSessionStore().apply {
            save(material.credentialKey, SdkSession("uid", "access"))
        }
        val challenge = CaptchaChallenge("gt", "challenge", "gt-user", "1")
        val sdkGateway = FakeSdkGateway(
            captchaResults = ArrayDeque(listOf(SdkCaptchaResult.Ready(challenge))),
        )
        val gameGateway = FakeGameGateway(
            results = ArrayDeque(
                listOf(
                    GameLoginResult.RiskRequired,
                    success(456L, "验证玩家", 20),
                ),
            ),
        )
        val coordinator = BilibiliNativeLoginCoordinator(
            sdkCoordinatorProvider = { BilibiliLoginCoordinator(sdkGateway, store) },
            sdkGatewayProvider = { sdkGateway },
            sessionStore = store,
            gameGatewayFor = { gameGateway },
            gameSessionRegistry = InMemoryGameSessionRegistry(),
        )

        val first = coordinator.start(material)
        val second = coordinator.completeCaptcha(material.accountId, CaptchaProof("human-validate"))

        assertEquals(NativeLoginResult.CaptchaRequired(challenge), first)
        assertTrue(second is NativeLoginResult.Success)
        assertEquals("human-validate", gameGateway.lastCaptcha?.validate)
        assertEquals("challenge", gameGateway.lastCaptcha?.challenge)
    }

    @Test
    fun `channel account logs in with its own gateway and never touches the Bilibili SDK`() = runTest {
        val channelMaterial = AccountLoginMaterial(8L, "channel-key", "12345678", "access-key", GameServer.CN_XIAOMI)
        val bilibiliGateway = FakeGameGateway(results = ArrayDeque())
        val channelGateway = FakeGameGateway(results = ArrayDeque(listOf(success(789L, "渠道玩家", 30))))
        val coordinator = BilibiliNativeLoginCoordinator(
            sdkCoordinatorProvider = { error("channel login must not create the Bilibili SDK coordinator") },
            sdkGatewayProvider = { error("channel login must not create the Bilibili SDK gateway") },
            sessionStore = InMemorySdkSessionStore(),
            gameGatewayFor = { server -> if (server.isChannelServer) channelGateway else bilibiliGateway },
            gameSessionRegistry = InMemoryGameSessionRegistry(),
        )

        val result = coordinator.start(channelMaterial)
        coordinator.cancel(channelMaterial.accountId)

        assertTrue(result is NativeLoginResult.Success)
        assertEquals(0, bilibiliGateway.loginCount)
        assertEquals(1, channelGateway.loginCount)
        assertEquals("12345678", channelGateway.lastSession?.uid)
        assertEquals("access-key", channelGateway.lastSession?.accessKey)
        assertEquals("12345678", channelGateway.lastDeviceSeed)
    }

    @Test
    fun `channel account risk check fails instead of starting a Bilibili captcha`() = runTest {
        val channelMaterial = AccountLoginMaterial(9L, "channel-key", "12345678", "access-key", GameServer.CN_HUAWEI)
        val coordinator = BilibiliNativeLoginCoordinator(
            sdkCoordinatorProvider = { error("unexpected SDK coordinator") },
            sdkGatewayProvider = { error("unexpected SDK gateway") },
            sessionStore = InMemorySdkSessionStore(),
            gameGatewayFor = { FakeGameGateway(results = ArrayDeque(listOf(GameLoginResult.RiskRequired))) },
            gameSessionRegistry = InMemoryGameSessionRegistry(),
        )

        val result = coordinator.start(channelMaterial)

        assertTrue(result is NativeLoginResult.Failure)
        assertEquals(LoginFailureKind.Rejected, (result as NativeLoginResult.Failure).kind)
    }

    private class FakeSdkGateway(
        private val loginResults: ArrayDeque<SdkLoginResult> = ArrayDeque(),
        private val captchaResults: ArrayDeque<SdkCaptchaResult> = ArrayDeque(),
    ) : BilibiliSdkGateway {
        var loginCount = 0

        override suspend fun login(credentials: SdkLoginCredentials, captcha: CaptchaSolution?): SdkLoginResult {
            loginCount++
            return loginResults.removeFirstOrNull() ?: SdkLoginResult.Rejected(1, "unexpected SDK login")
        }

        override suspend fun startCaptcha(): SdkCaptchaResult =
            captchaResults.removeFirstOrNull() ?: SdkCaptchaResult.Rejected(1, "unexpected captcha")
    }

    private class FakeGameGateway(
        private val results: ArrayDeque<GameLoginResult>,
    ) : BilibiliGameGateway {
        var loginCount = 0
        var lastCaptcha: CaptchaSolution? = null
        var lastSession: SdkSession? = null
        var lastDeviceSeed: String? = null

        override suspend fun loginAndLoadProfile(
            sdkSession: SdkSession,
            deviceSeed: String,
            captcha: CaptchaSolution?,
        ): GameLoginResult {
            loginCount++
            lastCaptcha = captcha
            lastSession = sdkSession
            lastDeviceSeed = deviceSeed
            return results.removeFirst()
        }
    }

    private companion object {
        fun success(viewerId: Long, name: String, level: Int): GameLoginResult.Success {
            val profile = GameAccountProfile(viewerId, name, level, "11.4.0")
            return GameLoginResult.Success(profile, FakeGameSession(profile))
        }
    }

    private class FakeGameSession(
        override val profile: GameAccountProfile,
    ) : BilibiliGameSession {
        override suspend fun request(
            path: String,
            fields: LinkedHashMap<String, Any?>,
        ): GameSessionResult = GameSessionResult.ProtocolFailure("not used")
    }
}
