package com.landosol.toolbox.automation.overlay

import com.landosol.toolbox.automation.AutomationSessionId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A normalized screen annotation drawn above the game without receiving touch events. */
data class AutomationOverlayBox(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
    val label: String,
    val recognized: Boolean,
    val selected: Boolean,
    val labelAbove: Boolean = false,
) {
    init {
        require(left in 0f..1f && top in 0f..1f)
        require(width > 0f && height > 0f)
        require(left + width <= 1.001f && top + height <= 1.001f)
    }
}

data class AutomationOverlayPresentation(
    val sessionId: AutomationSessionId,
    val title: String,
    val status: String,
    val detail: String? = null,
    val dryRun: Boolean,
    val paused: Boolean = false,
    val boxes: List<AutomationOverlayBox> = emptyList(),
    /** Keeps diagnostic boxes available to remote views without painting them into captured frames. */
    val renderBoxesOnDevice: Boolean = true,
)

interface AutomationOverlaySessionHandler {
    suspend fun setPaused(paused: Boolean): Boolean
    suspend fun stop(): Boolean
}

interface AutomationOverlayHost {
    fun show(sessionId: AutomationSessionId)
    fun hide(sessionId: AutomationSessionId)
}

object NoOpAutomationOverlayHost : AutomationOverlayHost {
    override fun show(sessionId: AutomationSessionId) = Unit
    override fun hide(sessionId: AutomationSessionId) = Unit
}

/**
 * Shared overlay state and command gate. The host only renders this state; pause and stop always
 * return to the owning feature session so capture leases and domain state are released together.
 */
class AutomationOverlayCoordinator(
    private val host: AutomationOverlayHost = NoOpAutomationOverlayHost,
) {
    private val lock = Any()
    private val _state = MutableStateFlow<AutomationOverlayPresentation?>(null)
    val state: StateFlow<AutomationOverlayPresentation?> = _state.asStateFlow()
    private var handler: AutomationOverlaySessionHandler? = null

    fun attach(
        presentation: AutomationOverlayPresentation,
        sessionHandler: AutomationOverlaySessionHandler,
    ): Boolean = synchronized(lock) {
        if (_state.value != null) return false
        handler = sessionHandler
        _state.value = presentation
        if (runCatching { host.show(presentation.sessionId) }.isFailure) {
            _state.value = null
            handler = null
            runCatching { host.hide(presentation.sessionId) }
            return false
        }
        true
    }

    fun update(
        sessionId: AutomationSessionId,
        presentation: AutomationOverlayPresentation,
    ): Boolean = synchronized(lock) {
        if (_state.value?.sessionId != sessionId || presentation.sessionId != sessionId) return false
        _state.value = presentation
        true
    }

    suspend fun setPaused(sessionId: AutomationSessionId, paused: Boolean): Boolean {
        val activeHandler = synchronized(lock) {
            if (_state.value?.sessionId != sessionId) return false
            handler ?: return false
        }
        val changed = activeHandler.setPaused(paused)
        if (changed) {
            synchronized(lock) {
                _state.value?.takeIf { it.sessionId == sessionId }?.let { current ->
                    _state.value = current.copy(paused = paused)
                }
            }
        }
        return changed
    }

    suspend fun stop(sessionId: AutomationSessionId): Boolean {
        val activeHandler = synchronized(lock) {
            if (_state.value?.sessionId != sessionId) return false
            handler ?: return false
        }
        val stopped = activeHandler.stop()
        if (stopped) detach(sessionId)
        return stopped
    }

    fun detach(sessionId: AutomationSessionId): Boolean = synchronized(lock) {
        if (_state.value?.sessionId != sessionId) return false
        _state.value = null
        handler = null
        runCatching { host.hide(sessionId) }
        true
    }
}
