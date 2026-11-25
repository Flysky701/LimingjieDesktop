package com.landosol.toolbox.protocol.bilibili

import com.landosol.toolbox.security.InMemorySdkSessionStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BilibiliLoginCoordinatorTest {
    @Test
    fun `manual captcha resumes the same login attempt and stores session`() = runTest {
        val gateway = FakeGateway(
            loginResults = ArrayDeque(
                listOf(
                    SdkLoginResult.CaptchaRequired,
                    SdkLoginResult.Success(SdkSession("uid-1", "access-key")),
                ),
            ),
            captchaResult = SdkCaptchaResult.Ready(
                CaptchaChallenge("gt", "challenge", "gt-user", "1"),
            ),
        )
        val sessions = InMemorySdkSessionStore()
        val coordinator = BilibiliLoginCoordinator(gateway, sessions, clock = { 99L })
        val material = AccountLoginMaterial(7L, "credential-key", "login", "password", GameServer.CN_BILIBILI)

        val first = coordinator.start(material)
        assertTrue(first is AccountLoginResult.CaptchaRequired)

        val completed = coordinator.completeCaptcha(7L, CaptchaProof("validate-token"))

        assertEquals(AccountLoginResult.Success("uid-1"), completed)
        assertEquals(SdkSession("uid-1", "access-key", 99L), sessions.read("credential-key"))
        assertEquals("challenge", gateway.lastCaptchaSolution?.challenge)
        assertEquals("gt-user", gateway.lastCaptchaSolution?.gtUserId)
        assertEquals("validate-token", gateway.lastCaptchaSolution?.validate)
        assertNull(coordinator.pendingChallenge(7L))
    }

    @Test
    fun `captcha proof cannot be submitted for another account`() = runTest {
        val gateway = FakeGateway(
            loginResults = ArrayDeque(listOf(SdkLoginResult.CaptchaRequired)),
            captchaResult = SdkCaptchaResult.Ready(CaptchaChallenge("gt", "challenge", "user", "1")),
        )
        val coordinator = BilibiliLoginCoordinator(gateway, InMemorySdkSessionStore())
        coordinator.start(AccountLoginMaterial(1L, "key", "login", "password", GameServer.CN_BILIBILI))

        val result = coordinator.completeCaptcha(2L, CaptchaProof("validate"))

        assertEquals(AccountLoginResult.Failure(LoginFailureKind.InvalidState, "登录验证已失效，请重新开始"), result)
    }

    private class FakeGateway(
        private val loginResults: ArrayDeque<SdkLoginResult>,
        private val captchaResult: SdkCaptchaResult,
    ) : BilibiliSdkGateway {
        var lastCaptchaSolution: CaptchaSolution? = null

        override suspend fun login(
            credentials: SdkLoginCredentials,
            captcha: CaptchaSolution?,
        ): SdkLoginResult {
            lastCaptchaSolution = captcha
            return loginResults.removeFirst()
        }

        override suspend fun startCaptcha(): SdkCaptchaResult = captchaResult
    }
}
