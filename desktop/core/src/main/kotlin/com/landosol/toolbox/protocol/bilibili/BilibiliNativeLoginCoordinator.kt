package com.landosol.toolbox.protocol.bilibili

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class BilibiliNativeLoginCoordinator(
    sdkCoordinatorProvider: () -> BilibiliLoginCoordinator,
    sdkGatewayProvider: () -> BilibiliSdkGateway,
    private val sessionStore: SdkSessionStore,
    /** 每个服务器一个游戏服网关：B 服与渠道服的网关、资源密钥和 PLATFORM-ID 各不相同。 */
    private val gameGatewayFor: (GameServer) -> BilibiliGameGateway,
    private val gameSessionRegistry: GameSessionRegistry,
) {
    // B 服 SDK 依赖按需求值：只有渠道服账号时绝不触发，否则会查询本机未安装的国服包。
    private val sdkCoordinatorLazy = lazy(sdkCoordinatorProvider)
    private val sdkCoordinator by sdkCoordinatorLazy
    private val sdkGateway by lazy { sdkGatewayProvider() }

    private val mutex = Mutex()
    private val pending = mutableMapOf<Long, PendingCaptcha>()

    suspend fun start(material: AccountLoginMaterial): NativeLoginResult = mutex.withLock {
        pending.remove(material.accountId)
        if (material.server.isChannelServer) {
            // 渠道服凭据直通：账号页填写的 loginId / password 即 uid / access_key，
            // 跳过 B 服 SDK 登录那一层，直接进入游戏服登录。
            // deviceSeed 传 loginId(uid)，与外部工具的 DEVICE-ID 推导一致。
            // 游戏服拒绝会话时不回落到 SDK 登录，直接返回失败。
            return@withLock loginGame(
                material,
                SdkSession(uid = material.loginId, accessKey = material.password),
            )
        }
        sdkCoordinator.cancel(material.accountId)
        val cached = sessionStore.read(material.credentialKey)
        if (cached != null) {
            when (val result = gameGatewayFor(material.server).loginAndLoadProfile(cached, material.loginId)) {
                is GameLoginResult.SessionRejected -> {
                    sessionStore.delete(material.credentialKey)
                    loginSdk(material)
                }
                else -> handleGameResult(material, cached, result)
            }
        } else {
            loginSdk(material)
        }
    }

    suspend fun completeCaptcha(accountId: Long, proof: CaptchaProof): NativeLoginResult = mutex.withLock {
        val attempt = pending[accountId]
            ?: return@withLock NativeLoginResult.Failure(LoginFailureKind.InvalidState, "登录验证已失效，请重新开始")
        if (proof.validate.isBlank() || proof.validate.length > MAX_CAPTCHA_TOKEN_LENGTH) {
            return@withLock NativeLoginResult.Failure(LoginFailureKind.InvalidState, "验证码结果无效")
        }
        when (attempt) {
            is PendingCaptcha.Sdk -> {
                when (val result = sdkCoordinator.completeCaptcha(accountId, proof)) {
                    is AccountLoginResult.Success -> {
                        val session = sessionStore.read(attempt.material.credentialKey)
                            ?: return@withLock failure(LoginFailureKind.Protocol, "SDK 会话保存失败", attempt.material)
                        pending.remove(accountId)
                        loginGame(attempt.material, session)
                    }
                    is AccountLoginResult.CaptchaRequired -> {
                        pending.remove(accountId)
                        failure(LoginFailureKind.Rejected, "SDK 验证未通过，请重新开始", attempt.material)
                    }
                    is AccountLoginResult.Failure -> {
                        pending.remove(accountId)
                        NativeLoginResult.Failure(result.kind, result.message)
                    }
                }
            }
            is PendingCaptcha.GameRisk -> {
                val solution = CaptchaSolution(
                    challenge = attempt.challenge.challenge,
                    gtUserId = attempt.challenge.gtUserId,
                    validate = proof.validate,
                )
                pending.remove(accountId)
                loginGame(attempt.material, attempt.session, solution)
            }
        }
    }

    suspend fun cancel(accountId: Long) {
        mutex.withLock {
            pending.remove(accountId)
            // 从未走过 SDK 登录就没有可取消的 SDK 流程；不为取消而创建 SDK 依赖。
            if (sdkCoordinatorLazy.isInitialized()) sdkCoordinator.cancel(accountId)
        }
    }

    private suspend fun loginSdk(material: AccountLoginMaterial): NativeLoginResult {
        return when (val result = sdkCoordinator.start(material)) {
            is AccountLoginResult.Success -> {
                val session = sessionStore.read(material.credentialKey)
                    ?: return failure(LoginFailureKind.Protocol, "SDK 会话保存失败", material)
                loginGame(material, session)
            }
            is AccountLoginResult.CaptchaRequired -> {
                pending[material.accountId] = PendingCaptcha.Sdk(material, result.challenge)
                NativeLoginResult.CaptchaRequired(result.challenge)
            }
            is AccountLoginResult.Failure -> NativeLoginResult.Failure(result.kind, result.message)
        }
    }

    private suspend fun loginGame(
        material: AccountLoginMaterial,
        session: SdkSession,
        captcha: CaptchaSolution? = null,
    ): NativeLoginResult = handleGameResult(
        material,
        session,
        gameGatewayFor(material.server).loginAndLoadProfile(session, material.loginId, captcha),
    )

    private suspend fun handleGameResult(
        material: AccountLoginMaterial,
        session: SdkSession,
        result: GameLoginResult,
    ): NativeLoginResult = when (result) {
        is GameLoginResult.Success -> {
            gameSessionRegistry.save(material.accountId, result.session)
            NativeLoginResult.Success(result.profile)
        }
        GameLoginResult.RiskRequired -> requestGameCaptcha(material, session)
        is GameLoginResult.SessionRejected -> failure(LoginFailureKind.Rejected, result.message, material)
        is GameLoginResult.Rejected -> failure(LoginFailureKind.Rejected, result.message, material)
        is GameLoginResult.Maintenance -> failure(LoginFailureKind.Rejected, result.message, material)
        is GameLoginResult.NetworkFailure -> failure(LoginFailureKind.Network, result.message, material)
        is GameLoginResult.ProtocolFailure -> failure(LoginFailureKind.Protocol, result.message, material)
    }

    private suspend fun requestGameCaptcha(
        material: AccountLoginMaterial,
        session: SdkSession,
    ): NativeLoginResult {
        if (material.server.isChannelServer) {
            return failure(
                LoginFailureKind.Rejected,
                "游戏服要求风险验证，渠道服凭据模式无法完成，请在游戏内完成验证后重试",
                material,
            )
        }
        return when (val captcha = sdkGateway.startCaptcha()) {
            is SdkCaptchaResult.Ready -> {
                pending[material.accountId] = PendingCaptcha.GameRisk(material, session, captcha.challenge)
                NativeLoginResult.CaptchaRequired(captcha.challenge)
            }
            is SdkCaptchaResult.NetworkFailure -> failure(LoginFailureKind.Network, captcha.message, material)
            is SdkCaptchaResult.ProtocolFailure -> failure(LoginFailureKind.Protocol, captcha.message, material)
            is SdkCaptchaResult.Rejected -> failure(LoginFailureKind.Rejected, captcha.message, material)
        }
    }

    private fun failure(
        kind: LoginFailureKind,
        message: String,
        material: AccountLoginMaterial,
    ): NativeLoginResult.Failure = NativeLoginResult.Failure(
        kind,
        message
            .replace(material.loginId, "<redacted>")
            .replace(material.password, "<redacted>")
            .ifBlank { "登录失败" }
            .take(MAX_MESSAGE_LENGTH),
    )

    private sealed interface PendingCaptcha {
        val material: AccountLoginMaterial
        val challenge: CaptchaChallenge

        data class Sdk(
            override val material: AccountLoginMaterial,
            override val challenge: CaptchaChallenge,
        ) : PendingCaptcha

        data class GameRisk(
            override val material: AccountLoginMaterial,
            val session: SdkSession,
            override val challenge: CaptchaChallenge,
        ) : PendingCaptcha
    }

    private companion object {
        const val MAX_MESSAGE_LENGTH = 200
        const val MAX_CAPTCHA_TOKEN_LENGTH = 4096
    }
}
