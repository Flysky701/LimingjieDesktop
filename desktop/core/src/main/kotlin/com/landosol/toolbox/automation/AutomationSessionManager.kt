package com.landosol.toolbox.automation

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.sync.Mutex

enum class AutomationMode {
    LABYRINTH,
    CLAN_BATTLE,
    SESSION_RESET,
}

@JvmInline
value class AutomationSessionId(val value: Long)

data class AutomationSession(
    val id: AutomationSessionId,
    val mode: AutomationMode,
    val dryRun: Boolean,
    val startedAt: Long,
    val paused: Boolean = false,
)

sealed interface AutomationSessionStartResult {
    data class Started(val session: AutomationSession) : AutomationSessionStartResult
    data class AlreadyRunning(val session: AutomationSession) : AutomationSessionStartResult
}

sealed interface SessionExecutionResult<out T> {
    data class Accepted<T>(val session: AutomationSession, val value: T) : SessionExecutionResult<T>
    data object StaleSession : SessionExecutionResult<Nothing>
    data object Paused : SessionExecutionResult<Nothing>
}

/**
 * 所有画面自动化功能共用的会话仲裁器。会话切换和动作执行使用同一把锁，
 * 因此旧会话排队中的动作不可能在新会话启动后落到设备上。
 */
class AutomationSessionManager(
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()
    private val sequence = AtomicLong(0)
    private var active: AutomationSession? = null

    suspend fun start(mode: AutomationMode, dryRun: Boolean): AutomationSessionStartResult {
        mutex.lock()
        return try {
            active?.let { AutomationSessionStartResult.AlreadyRunning(it) }
                ?: AutomationSession(
                    id = AutomationSessionId(sequence.incrementAndGet()),
                    mode = mode,
                    dryRun = dryRun,
                    startedAt = clock(),
                ).also { active = it }
                    .let(AutomationSessionStartResult::Started)
        } finally {
            mutex.unlock()
        }
    }

    suspend fun current(): AutomationSession? {
        mutex.lock()
        return try {
            active
        } finally {
            mutex.unlock()
        }
    }

    suspend fun stop(sessionId: AutomationSessionId): Boolean {
        mutex.lock()
        return try {
            if (active?.id != sessionId) false else true.also { active = null }
        } finally {
            mutex.unlock()
        }
    }

    suspend fun setPaused(sessionId: AutomationSessionId, paused: Boolean): Boolean {
        mutex.lock()
        return try {
            val session = active?.takeIf { it.id == sessionId } ?: return false
            active = session.copy(paused = paused)
            true
        } finally {
            mutex.unlock()
        }
    }

    suspend fun <T> executeIfActive(
        sessionId: AutomationSessionId,
        action: suspend (AutomationSession) -> T,
    ): SessionExecutionResult<T> {
        return executeIfActiveInternal(sessionId, allowPaused = false, action)
    }

    suspend fun <T> executeIfActiveAllowPaused(
        sessionId: AutomationSessionId,
        action: suspend (AutomationSession) -> T,
    ): SessionExecutionResult<T> {
        return executeIfActiveInternal(sessionId, allowPaused = true, action)
    }

    private suspend fun <T> executeIfActiveInternal(
        sessionId: AutomationSessionId,
        allowPaused: Boolean,
        action: suspend (AutomationSession) -> T,
    ): SessionExecutionResult<T> {
        mutex.lock()
        return try {
            val session = active?.takeIf { it.id == sessionId } ?: return SessionExecutionResult.StaleSession
            if (session.paused && !allowPaused) return SessionExecutionResult.Paused
            SessionExecutionResult.Accepted(session, action(session))
        } finally {
            mutex.unlock()
        }
    }
}
