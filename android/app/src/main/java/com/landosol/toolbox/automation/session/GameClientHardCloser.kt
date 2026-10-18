package com.landosol.toolbox.automation.session

import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryPageState
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.delay

sealed interface GameClientHardCloseResult {
    /**
     * The game left the foreground and the system accepted the kill request.
     *
     * Not proof that the process is gone: `killBackgroundProcesses` returns nothing, and a process
     * the system declines to kill stays alive silently. The cold start afterwards proves it
     * ([ColdStartTitleGate]).
     */
    data object KillRequested : GameClientHardCloseResult

    data class Failed(val reason: String) : GameClientHardCloseResult
}

/**
 * Hard-closes (大退) a game client without root: leaves it with the home key, then kills its
 * now-background processes (`ActivityManager.killBackgroundProcesses`, a normal permission).
 *
 * Android only lets an ordinary app kill another app's processes once they are in the background,
 * so the home step is required and is confirmed through the accessibility foreground package
 * before the kill. From Android 14 the call does nothing at all for other apps (it only logs
 * `Invalid packageName`), so there the closer refuses up front instead of pretending.
 * Every wait is bounded.
 */
class GameClientHardCloser(
    private val foregroundPackage: () -> String?,
    private val pressHome: suspend () -> Boolean,
    private val killBackgroundProcesses: (String) -> Unit,
    /** False from Android 14 (API 34), where killBackgroundProcesses cannot reach other apps. */
    private val canKillOtherApps: Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
    private val leaveForegroundTimeoutMillis: Long = 5_000L,
    private val pollMillis: Long = 200L,
    /** Lets the system demote the process out of the "previous app" slot before the kill. */
    private val settleMillis: Long = 500L,
    private val trace: (String) -> Unit = {},
) {
    init {
        require(leaveForegroundTimeoutMillis > 0)
        require(pollMillis > 0)
        require(settleMillis >= 0)
    }

    suspend fun close(packageName: String): GameClientHardCloseResult {
        if (!canKillOtherApps) {
            return GameClientHardCloseResult.Failed(ANDROID_14_REASON)
        }
        if (foregroundPackage() == packageName) {
            trace("hard-close home pkg=$packageName")
            if (!pressHome()) return GameClientHardCloseResult.Failed("系统拒绝返回桌面，无法让游戏退到后台")
            val deadline = clock() + leaveForegroundTimeoutMillis
            while (foregroundPackage() == packageName) {
                if (clock() >= deadline) {
                    return GameClientHardCloseResult.Failed("游戏 ${leaveForegroundTimeoutMillis / 1_000} 秒内没有退到后台")
                }
                delay(pollMillis)
            }
        }
        delay(settleMillis)
        val killed = runCatching { killBackgroundProcesses(packageName) }
        killed.exceptionOrNull()?.let { error ->
            return GameClientHardCloseResult.Failed("结束游戏进程失败：${error.message ?: error.javaClass.simpleName}")
        }
        trace("hard-close kill-requested pkg=$packageName")
        return GameClientHardCloseResult.KillRequested
    }

    companion object {
        /** API 34; killBackgroundProcesses stopped working on other apps there. */
        const val LAST_SDK_THAT_KILLS_OTHER_APPS = 33

        const val ANDROID_14_REASON =
            "Android 14 及以上系统不允许助手结束其他应用的进程，无法大退游戏；" +
                "为避免渠道账号被登出，本次不刷开局"
    }
}

/** Who a hard close was done for. A ticket only counts for exactly this account and client. */
data class GameClientHardCloseTicket(val accountId: Long, val packageName: String)

/**
 * One-shot proof that a reroll hard-closed the client, so the next client sync may cold-start it
 * instead of invalidating a session.
 *
 * Callers [clear] it at the start of every reroll and at batch boundaries, [issue] it only after
 * a successful close, and [consume] it with the identity they are about to act for. Consuming
 * always spends the ticket, matching or not, so a ticket left by a failed or cancelled reroll can
 * never reach a later task on another account or channel.
 */
class GameClientHardCloseLedger {
    private val ticket = AtomicReference<GameClientHardCloseTicket?>(null)

    fun clear() {
        ticket.set(null)
    }

    fun issue(accountId: Long, packageName: String) {
        ticket.set(GameClientHardCloseTicket(accountId, packageName))
    }

    /** True only if the outstanding ticket was issued for [accountId] and [packageName]. */
    fun consume(accountId: Long?, packageName: String?): Boolean {
        val taken = ticket.getAndSet(null) ?: return false
        return accountId != null && packageName != null &&
            taken == GameClientHardCloseTicket(accountId, packageName)
    }
}

enum class ColdStartVerdict {
    /** Nothing conclusive yet: splash, loading or an unrecognised frame. */
    PENDING,

    /** The title page appeared first: the process really started from scratch. */
    CONFIRMED,

    /** An in-game page appeared before the title: the old process survived the kill. */
    CLIENT_SURVIVED,
}

/**
 * Checks that a client relaunched after a hard close really cold-started.
 *
 * A fresh process shows only splash/loading frames before the title page. A process that survived
 * the kill comes back on whatever page it was on (home, adventure, 黎明界, ...), and taking it
 * over from there is exactly the 6002 → 返回标题 → SDK logout that the hard close exists to avoid.
 * So the first conclusive page decides: the title confirms, any other recognised page seen on
 * [requiredSightings] consecutive frames means the close did not work. One stray frame is not
 * enough to stop on.
 */
class ColdStartTitleGate(private val requiredSightings: Int = 2) {
    private var confirmed = false
    private var survivorPage: LabyrinthEntryPageState? = null
    private var survivorSightings = 0

    /** Whether the title page has been seen, i.e. the cold start is confirmed. */
    val titleSeen: Boolean get() = confirmed

    init {
        require(requiredSightings > 0)
    }

    fun observe(page: LabyrinthEntryPageState): ColdStartVerdict {
        if (confirmed) return ColdStartVerdict.CONFIRMED
        if (page == LabyrinthEntryPageState.TITLE_WAITING_TAP) {
            confirmed = true
            return ColdStartVerdict.CONFIRMED
        }
        if (page in BEFORE_TITLE) {
            survivorPage = null
            survivorSightings = 0
            return ColdStartVerdict.PENDING
        }
        survivorSightings = if (page == survivorPage) survivorSightings + 1 else 1
        survivorPage = page
        return if (survivorSightings >= requiredSightings) ColdStartVerdict.CLIENT_SURVIVED else ColdStartVerdict.PENDING
    }

    private companion object {
        val BEFORE_TITLE = setOf(
            LabyrinthEntryPageState.UNKNOWN,
            LabyrinthEntryPageState.GAME_LOADING_PROGRESS,
        )
    }
}
