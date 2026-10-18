package com.landosol.toolbox.automation

sealed interface AutomationAction {
    data class Tap(val point: ScreenPoint) : AutomationAction
    data class Swipe(
        val start: ScreenPoint,
        val end: ScreenPoint,
        val durationMillis: Long,
        /**
         * Keep the finger still at [end] for this long before lifting.
         *
         * A straight gesture path is dispatched at constant speed, so the pointer lifts at full
         * travel velocity and the game's own velocity tracker turns the drag into an inertial
         * fling: the map keeps sliding well past where the gesture ended. Holding still first
         * makes the measured lift-off velocity ~0, which turns the same gesture into a plain
         * drag. 0 keeps the legacy single-stroke behaviour.
         */
        val holdMillis: Long = 0L,
    ) : AutomationAction
    data object Back : AutomationAction
}

sealed interface AutomationBackendResult {
    data object Completed : AutomationBackendResult
    data class Rejected(val reason: String) : AutomationBackendResult
}

fun interface AutomationActionBackend {
    suspend fun execute(action: AutomationAction): AutomationBackendResult
}

sealed interface AutomationActionResult {
    data class Executed(val action: AutomationAction) : AutomationActionResult
    data class DryRun(val action: AutomationAction) : AutomationActionResult
    data class Rejected(val action: AutomationAction, val reason: String) : AutomationActionResult
    data object StaleSession : AutomationActionResult
    data object Paused : AutomationActionResult
}

class SessionBoundActionExecutor(
    private val sessionManager: AutomationSessionManager,
    private val backend: AutomationActionBackend,
) {
    suspend fun execute(
        sessionId: AutomationSessionId,
        action: AutomationAction,
    ): AutomationActionResult = when (
        val gated = sessionManager.executeIfActive(sessionId) { session ->
            if (session.dryRun) {
                AutomationActionResult.DryRun(action)
            } else {
                when (val result = backend.execute(action)) {
                    AutomationBackendResult.Completed -> AutomationActionResult.Executed(action)
                    is AutomationBackendResult.Rejected -> AutomationActionResult.Rejected(action, result.reason)
                }
            }
        }
    ) {
        is SessionExecutionResult.Accepted -> gated.value
        SessionExecutionResult.StaleSession -> AutomationActionResult.StaleSession
        SessionExecutionResult.Paused -> AutomationActionResult.Paused
    }

    /**
     * Runs an action that is not a gesture (an accessibility ACTION_CLICK on a node) under the same
     * session gate as [execute]: never for a stale or paused session, and not at all for a dry-run
     * session (the value is then null). The session lock is held until [block] returns, so a pause
     * or stop requested meanwhile takes effect right after it, exactly as for a gesture.
     */
    suspend fun <T : Any> executeDirect(
        sessionId: AutomationSessionId,
        block: suspend () -> T,
    ): SessionExecutionResult<T?> = sessionManager.executeIfActive(sessionId) { session ->
        if (session.dryRun) null else block()
    }
}
