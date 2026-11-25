package com.landosol.toolbox.protocol.bilibili

class SdkLoginCredentials(
    val loginId: String,
    val password: String,
) {
    override fun toString(): String = "SdkLoginCredentials(REDACTED)"
}

data class CaptchaChallenge(
    val gt: String,
    val challenge: String,
    val gtUserId: String,
    val captchaType: String,
)

data class CaptchaProof(val validate: String)

data class CaptchaSolution(
    val challenge: String,
    val gtUserId: String,
    val validate: String,
)

class SdkSession(
    val uid: String,
    val accessKey: String,
    val updatedAt: Long = 0L,
) {
    override fun equals(other: Any?): Boolean =
        other is SdkSession && uid == other.uid && accessKey == other.accessKey && updatedAt == other.updatedAt

    override fun hashCode(): Int = 31 * (31 * uid.hashCode() + accessKey.hashCode()) + updatedAt.hashCode()

    override fun toString(): String = "SdkSession(uid=REDACTED, accessKey=REDACTED, updatedAt=$updatedAt)"
}

sealed interface SdkLoginResult {
    data class Success(val session: SdkSession) : SdkLoginResult
    data object CaptchaRequired : SdkLoginResult
    data class Rejected(val code: Int, val message: String) : SdkLoginResult
    data class NetworkFailure(val message: String) : SdkLoginResult
    data class ProtocolFailure(val message: String) : SdkLoginResult
}

sealed interface SdkCaptchaResult {
    data class Ready(val challenge: CaptchaChallenge) : SdkCaptchaResult
    data class Rejected(val code: Int, val message: String) : SdkCaptchaResult
    data class NetworkFailure(val message: String) : SdkCaptchaResult
    data class ProtocolFailure(val message: String) : SdkCaptchaResult
}

interface BilibiliSdkGateway {
    suspend fun login(
        credentials: SdkLoginCredentials,
        captcha: CaptchaSolution? = null,
    ): SdkLoginResult

    suspend fun startCaptcha(): SdkCaptchaResult
}

interface SdkSessionStore {
    suspend fun save(key: String, session: SdkSession)
    suspend fun read(key: String): SdkSession?
    suspend fun delete(key: String)
}

class AccountLoginMaterial(
    val accountId: Long,
    val credentialKey: String,
    val loginId: String,
    val password: String,
    val server: GameServer,
) {
    override fun toString(): String =
        "AccountLoginMaterial(accountId=$accountId, server=${server.storageId}, REDACTED)"
}

enum class LoginFailureKind {
    InvalidState,
    Rejected,
    Network,
    Protocol,
}

sealed interface AccountLoginResult {
    data class Success(val uid: String) : AccountLoginResult
    data class CaptchaRequired(val challenge: CaptchaChallenge) : AccountLoginResult
    data class Failure(val kind: LoginFailureKind, val message: String) : AccountLoginResult
}
