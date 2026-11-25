package com.landosol.toolbox.protocol.bilibili

data class GameAccountProfile(
    val viewerId: Long,
    val userName: String,
    val teamLevel: Int,
    val appVersion: String,
)

sealed interface GameLoginResult {
    data class Success(
        val profile: GameAccountProfile,
        val session: BilibiliGameSession,
    ) : GameLoginResult
    data object RiskRequired : GameLoginResult
    data class SessionRejected(val message: String) : GameLoginResult
    data class Rejected(val message: String) : GameLoginResult
    data class Maintenance(val message: String) : GameLoginResult
    data class NetworkFailure(val message: String) : GameLoginResult
    data class ProtocolFailure(val message: String) : GameLoginResult
}

sealed interface GameSessionResult {
    data class Success(val data: Map<String, Any?>) : GameSessionResult
    data class Rejected(val message: String) : GameSessionResult
    data class NetworkFailure(val message: String) : GameSessionResult
    data class ProtocolFailure(val message: String) : GameSessionResult
}

interface BilibiliGameSession {
    val profile: GameAccountProfile

    suspend fun request(path: String, fields: LinkedHashMap<String, Any?>): GameSessionResult
}

interface GameSessionRegistry {
    suspend fun save(accountId: Long, session: BilibiliGameSession)
    suspend fun read(accountId: Long): BilibiliGameSession?
    suspend fun delete(accountId: Long)
}

class InMemoryGameSessionRegistry : GameSessionRegistry {
    private val sessions = mutableMapOf<Long, BilibiliGameSession>()
    private val mutex = kotlinx.coroutines.sync.Mutex()

    override suspend fun save(accountId: Long, session: BilibiliGameSession) {
        mutex.lock()
        try {
            sessions[accountId] = session
        } finally {
            mutex.unlock()
        }
    }

    override suspend fun read(accountId: Long): BilibiliGameSession? {
        mutex.lock()
        return try {
            sessions[accountId]
        } finally {
            mutex.unlock()
        }
    }

    override suspend fun delete(accountId: Long) {
        mutex.lock()
        try {
            sessions.remove(accountId)
        } finally {
            mutex.unlock()
        }
    }
}

interface BilibiliGameGateway {
    suspend fun loginAndLoadProfile(
        sdkSession: SdkSession,
        deviceSeed: String,
        captcha: CaptchaSolution? = null,
    ): GameLoginResult
}

sealed interface NativeLoginResult {
    data class Success(val profile: GameAccountProfile) : NativeLoginResult
    data class CaptchaRequired(val challenge: CaptchaChallenge) : NativeLoginResult
    data class Failure(val kind: LoginFailureKind, val message: String) : NativeLoginResult
}
