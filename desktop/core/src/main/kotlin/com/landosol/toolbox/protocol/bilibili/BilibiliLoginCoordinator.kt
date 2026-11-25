package com.landosol.toolbox.protocol.bilibili

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class BilibiliLoginCoordinator(
    private val gateway: BilibiliSdkGateway,
    private val sessionStore: SdkSessionStore,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()
    private val pending = mutableMapOf<Long, PendingLogin>()

    suspend fun start(material: AccountLoginMaterial): AccountLoginResult = mutex.withLock {
        pending.remove(material.accountId)
        when (val result = gateway.login(material.toCredentials())) {
            is SdkLoginResult.Success -> persistSuccess(material, result.session)
            SdkLoginResult.CaptchaRequired -> when (val captcha = gateway.startCaptcha()) {
                is SdkCaptchaResult.Ready -> {
                    pending[material.accountId] = PendingLogin(material, captcha.challenge)
                    AccountLoginResult.CaptchaRequired(captcha.challenge)
                }
                is SdkCaptchaResult.NetworkFailure -> failure(LoginFailureKind.Network, captcha.message, material)
                is SdkCaptchaResult.ProtocolFailure -> failure(LoginFailureKind.Protocol, captcha.message, material)
                is SdkCaptchaResult.Rejected -> failure(LoginFailureKind.Rejected, captcha.message, material)
            }
            is SdkLoginResult.NetworkFailure -> failure(LoginFailureKind.Network, result.message, material)
            is SdkLoginResult.ProtocolFailure -> failure(LoginFailureKind.Protocol, result.message, material)
            is SdkLoginResult.Rejected -> failure(LoginFailureKind.Rejected, result.message, material)
        }
    }

    suspend fun completeCaptcha(
        accountId: Long,
        proof: CaptchaProof,
    ): AccountLoginResult = mutex.withLock {
        val attempt = pending[accountId]
            ?: return@withLock AccountLoginResult.Failure(
                LoginFailureKind.InvalidState,
                "登录验证已失效，请重新开始",
            )
        if (proof.validate.isBlank() || proof.validate.length > MAX_CAPTCHA_TOKEN_LENGTH) {
            return@withLock AccountLoginResult.Failure(LoginFailureKind.InvalidState, "验证码结果无效")
        }
        val solution = CaptchaSolution(
            challenge = attempt.challenge.challenge,
            gtUserId = attempt.challenge.gtUserId,
            validate = proof.validate,
        )
        when (val result = gateway.login(attempt.material.toCredentials(), solution)) {
            is SdkLoginResult.Success -> {
                pending.remove(accountId)
                persistSuccess(attempt.material, result.session)
            }
            SdkLoginResult.CaptchaRequired -> {
                pending.remove(accountId)
                AccountLoginResult.Failure(LoginFailureKind.Rejected, "验证未通过，请重新开始登录")
            }
            is SdkLoginResult.NetworkFailure -> failure(LoginFailureKind.Network, result.message, attempt.material)
            is SdkLoginResult.ProtocolFailure -> failure(LoginFailureKind.Protocol, result.message, attempt.material)
            is SdkLoginResult.Rejected -> {
                pending.remove(accountId)
                failure(LoginFailureKind.Rejected, result.message, attempt.material)
            }
        }
    }

    suspend fun cancel(accountId: Long) {
        mutex.withLock { pending.remove(accountId) }
    }

    suspend fun pendingChallenge(accountId: Long): CaptchaChallenge? = mutex.withLock {
        pending[accountId]?.challenge
    }

    private suspend fun persistSuccess(
        material: AccountLoginMaterial,
        session: SdkSession,
    ): AccountLoginResult {
        val stored = SdkSession(session.uid, session.accessKey, clock())
        sessionStore.save(material.credentialKey, stored)
        return AccountLoginResult.Success(stored.uid)
    }

    private fun failure(
        kind: LoginFailureKind,
        message: String,
        material: AccountLoginMaterial,
    ): AccountLoginResult.Failure {
        val safe = message
            .replace(material.loginId, "<redacted>")
            .replace(material.password, "<redacted>")
            .ifBlank { "登录失败" }
            .take(MAX_MESSAGE_LENGTH)
        return AccountLoginResult.Failure(kind, safe)
    }

    private fun AccountLoginMaterial.toCredentials() = SdkLoginCredentials(loginId, password)

    private data class PendingLogin(
        val material: AccountLoginMaterial,
        val challenge: CaptchaChallenge,
    )

    private companion object {
        const val MAX_MESSAGE_LENGTH = 200
        const val MAX_CAPTCHA_TOKEN_LENGTH = 4096
    }
}
