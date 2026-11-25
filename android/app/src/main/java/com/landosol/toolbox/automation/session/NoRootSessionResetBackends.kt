package com.landosol.toolbox.automation.session

import com.landosol.toolbox.automation.AutomationAction
import com.landosol.toolbox.automation.CoordinateMapper
import com.landosol.toolbox.automation.PixelRect
import com.landosol.toolbox.automation.ScreenPoint
import kotlin.math.hypot
import kotlinx.coroutines.delay

/**
 * 无 Root 环境下的客户端终止替代方案。
 *
 * 普通应用无法 force-stop 其他应用，因此“关闭客户端”必须借助游戏自身的
 * 退出通道，或请求用户协助。本模块不硬编码游戏流程：菜单入口、登出按钮等
 * 目标均由视觉锚点坐标（可配置）驱动，识别与决策分离。
 */

/**
 * 游戏内登出方案：模拟点击 菜单 → 设置 → 退出登录。
 * 每一步的目标坐标由调用方提供（来自视觉资源包/校准数据），不写死。
 */
data class GracefulLogoutStep(
    val label: String,
    val target: ScreenPoint,
    val expectPageToken: String? = null,
)

class GracefulLogoutTerminator(
    private val steps: List<GracefulLogoutStep>,
    private val onTap: suspend (AutomationAction) -> Unit,
    private val pauseBetweenStepsMillis: Long = 1_200L,
) : GameClientTerminator {
    override val kind: ClientTerminationKind get() = ClientTerminationKind.GRACEFUL_LOGOUT

    override suspend fun terminate(): ClientTerminationResult {
        if (steps.isEmpty()) return ClientTerminationResult.UNSUPPORTED
        for (step in steps) {
            onTap(AutomationAction.Tap(step.target))
            if (pauseBetweenStepsMillis > 0) kotlinx.coroutines.delay(pauseBetweenStepsMillis)
        }
        return ClientTerminationResult.TERMINATED
    }

    override fun isSupported(): Boolean = steps.isNotEmpty()
}

/** 用户协助方案：暂停流程并提示用户手动关闭客户端 */
class UserAssistTerminator(
    private val onPromptUser: suspend (String) -> Boolean,
) : GameClientTerminator {
    override val kind: ClientTerminationKind get() = ClientTerminationKind.USER_ASSIST

    override suspend fun terminate(): ClientTerminationResult {
        val accepted = onPromptUser("请手动关闭公主连结（划掉最近任务），完成后确认继续")
        return if (accepted) ClientTerminationResult.TERMINATED
        else ClientTerminationResult.REJECTED
    }

    override fun isSupported(): Boolean = true
}

/** 仅重启 Activity：不杀进程，通过全新任务栈启动客户端 */
class RelaunchOnlyTerminator : GameClientTerminator {
    override val kind: ClientTerminationKind get() = ClientTerminationKind.RELAUNCH_ONLY
    override suspend fun terminate(): ClientTerminationResult = ClientTerminationResult.ALREADY_GONE
    override fun isSupported(): Boolean = true
}

/**
 * 会话失效触发终止器：不杀进程，点击「冒险→黎明界」入口触发「会话失效」弹窗，
 * 命中「返回标题」按钮回到标题页。无 Root、无 ADB 依赖。
 *
 * 流程：
 *  1. 点击触发入口（点进冒险→黎明界，会话已失效时会弹出错误提示）
 *  2. 轮询识别「错误提示」弹窗出现（复用 [SessionBlockClassifier] 的锚点评分）
 *  3. 点击「返回标题」按钮
 *  4. 轮询标题页出现，作为完成条件
 *
 * 点击目标使用参考系坐标（默认 1920x1080，与识别模板一致），通过
 * [CoordinateMapper] 映射到实际屏幕；检测回调由上层注入。
 */
class SessionExpiryTerminator(
    private val onTap: suspend (ScreenPoint) -> Boolean,
    private val triggerEntryPoint: ScreenPoint,
    private val returnTitlePoint: ScreenPoint,
    private val popupVisible: suspend () -> Boolean,
    private val titleReached: suspend () -> Boolean,
    private val frameSize: () -> Pair<Int, Int>,
    private val coordinateMapper: CoordinateMapper = CoordinateMapper(),
    private val available: () -> Boolean = { true },
    private val popupTimeoutMillis: Long = 30_000L,
    private val titleTimeoutMillis: Long = 30_000L,
    private val frameReadyTimeoutMillis: Long = 5_000L,
    private val entryTapDelayMillis: Long = 2_000L,
    private val returnTapDelayMillis: Long = 1_500L,
    private val triggerAnchorPoint: () -> ScreenPoint? = { null },
    private val returnTitleAnchorPoint: () -> ScreenPoint? = { null },
) : GameClientTerminator {
    override val kind: ClientTerminationKind get() = ClientTerminationKind.SESSION_EXPIRY

    override suspend fun terminate(): ClientTerminationResult {
        // 等待首个有效识别帧（workflow 启动后首帧可能尚未到达）
        if (!await(frameReadyTimeoutMillis) {
                frameSize().let { (width, height) -> width > 0 && height > 0 }
            }
        ) {
            return ClientTerminationResult.UNSUPPORTED
        }
        val (width, height) = frameSize()
        val mapping = coordinateMapper.createMapping(
            PixelRect(0f, 0f, width.toFloat(), height.toFloat()),
        )
        val trigger = triggerAnchorPoint() ?: mapping.toScreen(triggerEntryPoint)
            ?: return ClientTerminationResult.UNSUPPORTED
        if (!onTap(trigger)) return ClientTerminationResult.UNSUPPORTED
        delay(entryTapDelayMillis)
        if (!await(popupTimeoutMillis) { popupVisible() }) {
            return ClientTerminationResult.TIMEOUT
        }
        val confirm = returnTitleAnchorPoint() ?: mapping.toScreen(returnTitlePoint)
            ?: return ClientTerminationResult.TIMEOUT
        if (!onTap(confirm)) return ClientTerminationResult.TIMEOUT
        delay(returnTapDelayMillis)
        if (!await(titleTimeoutMillis) { titleReached() }) {
            return ClientTerminationResult.TIMEOUT
        }
        return ClientTerminationResult.TERMINATED
    }

    override fun isSupported(): Boolean = available()

    private suspend fun await(timeoutMillis: Long, probe: suspend () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (probe()) return true
            delay(POLL_INTERVAL_MILLIS)
        }
        return false
    }

    private companion object {
        const val POLL_INTERVAL_MILLIS = 500L
    }
}

/**
 * 通过一次「会向服务端发请求」的点击触发旧会话失效，再点「返回标题」。
 *
 * 触发点由 [triggerPoint] 按当前识别页面给出（黎明界主页的底栏「我的主页」标签模板、战斗失败页
 * 识别到的「重新挑战」按钮）；识别不到就不点，等到超时返回 TIMEOUT。不存在任何固定坐标
 * 兜底：2026-09-17 用户指出固定点底栏在其他页面也会落下去。
 *
 * 为什么不能点「进入黎明界」：服务端刷开局后，客户端本地仍是上一局结束时的旧状态。
 * 此时点「进入黎明界」会先进入公会选择页；只有再点任意公会才会触发网络请求并弹出
 * 会话失效。公会选择页没有识别，正常流程（刷开局跳过该页）也从不经过它，所以这条路
 * 必然卡死。底栏标签则直接发起页面数据请求，被服务端拒绝后立即弹出会话失效。
 *
 * 弹窗出现后的确认与回标题逻辑和 [SessionExpiryTerminator] 相同：点「返回标题」，
 * 等标题页出现。
 */
class AnchorTriggerSessionExpiryTerminator(
    private val onTap: suspend (ScreenPoint) -> Boolean,
    /** Frame-space centre of the recognised trigger on the current page, or null. Re-read before every tap. */
    private val triggerPoint: () -> ScreenPoint?,
    private val returnTitlePoint: ScreenPoint,
    private val popupVisible: suspend () -> Boolean,
    private val titleReached: suspend () -> Boolean,
    private val synchronizedWithoutExpiry: suspend () -> Boolean = { false },
    private val frameSize: () -> Pair<Int, Int>,
    private val coordinateMapper: CoordinateMapper = CoordinateMapper(),
    private val available: () -> Boolean = { true },
    private val popupTimeoutMillis: Long = 30_000L,
    /**
     * Allow time for a server response after a trigger tap. A newly recognised trigger
     * advances the local dialog chain before this timeout.
     */
    private val popupAfterTapTimeoutMillis: Long = 12_000L,
    private val titleTimeoutMillis: Long = 30_000L,
    private val frameReadyTimeoutMillis: Long = 5_000L,
    private val triggerTapDelayMillis: Long = 2_000L,
    private val returnTapDelayMillis: Long = 1_500L,
    /** Upper bound on trigger taps; the failure-page chain is three (结束 → 撤退 → 确认) plus retries. */
    private val maxTriggerTaps: Int = 6,
    private val returnTitleAnchorPoint: () -> ScreenPoint? = { null },
    private val trace: (String) -> Unit = {},
    private val isPaused: suspend () -> Boolean = { false },
    private val isCancelled: suspend () -> Boolean = { false },
    private val clock: () -> Long = System::currentTimeMillis,
) : GameClientTerminator {
    override val kind: ClientTerminationKind get() = ClientTerminationKind.SESSION_EXPIRY

    /** Last step reached, for diagnostics after a non-TERMINATED result. */
    @Volatile
    var lastStep: String = "idle"
        private set
    override val lastDiagnosticStep: String get() = lastStep

    override suspend fun terminate(): ClientTerminationResult {
        fun step(name: String) { lastStep = name; trace(name) }
        step("await-frame")
        if (!await(frameReadyTimeoutMillis) {
                frameSize().let { (width, height) -> width > 0 && height > 0 }
            }
        ) {
            if (isCancelled()) {
                step("cancelled")
                return ClientTerminationResult.REJECTED
            }
            step("no-frame")
            return ClientTerminationResult.UNSUPPORTED
        }
        val (width, height) = frameSize()
        val mapping = coordinateMapper.createMapping(
            PixelRect(0f, 0f, width.toFloat(), height.toFloat()),
        )

        // 已在本轮之前弹出过（例如上一步刚失败）：直接进入确认阶段。
        var popup = popupVisible()
        var taps = 0
        val perAttemptTimeout = popupTimeoutMillis / maxTriggerTaps
        while (!popup && taps < maxTriggerTaps) {
            if (isCancelled()) {
                step("cancelled")
                return ClientTerminationResult.REJECTED
            }
            step("await-trigger#${taps + 1}")
            var trigger: ScreenPoint? = null
            val recognised = await(perAttemptTimeout) {
                trigger = triggerPoint()
                trigger != null || popupVisible() || synchronizedWithoutExpiry()
            }
            if (!recognised) {
                if (isCancelled()) {
                    step("cancelled")
                    return ClientTerminationResult.REJECTED
                }
                step("trigger-not-recognised-after-$taps-taps")
                return ClientTerminationResult.TIMEOUT
            }
            popup = popupVisible()
            if (!popup && synchronizedWithoutExpiry()) {
                step("client-already-synchronized")
                return ClientTerminationResult.ALREADY_GONE
            }
            if (popup) break
            val target = trigger ?: continue
            if (!waitWhilePaused()) {
                step("cancelled")
                return ClientTerminationResult.REJECTED
            }
            step("tap-trigger#${taps + 1}@${target.x.toInt()},${target.y.toInt()}")
            if (!onTap(target)) {
                step(if (isCancelled()) "cancelled" else "trigger-tap-rejected")
                return if (isCancelled()) ClientTerminationResult.REJECTED else ClientTerminationResult.UNSUPPORTED
            }
            taps++
            if (!activeDelay(triggerTapDelayMillis)) {
                step("cancelled")
                return ClientTerminationResult.REJECTED
            }
            var triggerChanged = false
            val responseObserved = await(popupAfterTapTimeoutMillis) {
                if (popupVisible() || synchronizedWithoutExpiry()) return@await true
                val next = triggerPoint()
                triggerChanged = next != null &&
                    hypot((next.x - target.x).toDouble(), (next.y - target.y).toDouble()) > NEXT_TRIGGER_MIN_DISTANCE
                triggerChanged
            }
            if (isCancelled()) {
                step("cancelled")
                return ClientTerminationResult.REJECTED
            }
            if (triggerChanged) continue
            popup = popupVisible()
            if (!responseObserved) continue
            if (!popup && synchronizedWithoutExpiry()) {
                step("client-already-synchronized")
                return ClientTerminationResult.ALREADY_GONE
            }
        }
        if (!popup && !popupVisible()) {
            step("popup-timeout-after-$taps-taps")
            return ClientTerminationResult.TIMEOUT
        }

        step("popup-visible")
        val confirm = returnTitleAnchorPoint() ?: mapping.toScreen(returnTitlePoint)
            ?: run { step("return-title-unmappable"); return ClientTerminationResult.TIMEOUT }
        if (!waitWhilePaused()) {
            step("cancelled")
            return ClientTerminationResult.REJECTED
        }
        step("tap-return-title@${confirm.x.toInt()},${confirm.y.toInt()}")
        if (!onTap(confirm)) {
            step(if (isCancelled()) "cancelled" else "return-title-tap-rejected")
            return if (isCancelled()) ClientTerminationResult.REJECTED else ClientTerminationResult.TIMEOUT
        }
        if (!activeDelay(returnTapDelayMillis)) {
            step("cancelled")
            return ClientTerminationResult.REJECTED
        }
        if (!await(titleTimeoutMillis) { titleReached() }) {
            if (isCancelled()) {
                step("cancelled")
                return ClientTerminationResult.REJECTED
            }
            step("title-timeout")
            return ClientTerminationResult.TIMEOUT
        }
        step("terminated")
        return ClientTerminationResult.TERMINATED
    }

    override fun isSupported(): Boolean = available()

    private suspend fun waitWhilePaused(): Boolean {
        while (isPaused()) {
            if (isCancelled()) return false
            delay(POLL_INTERVAL_MILLIS)
        }
        return !isCancelled()
    }

    private suspend fun activeDelay(durationMillis: Long): Boolean {
        var remaining = durationMillis
        var lastTick = clock()
        while (remaining > 0L) {
            if (isCancelled()) return false
            if (isPaused()) {
                delay(POLL_INTERVAL_MILLIS)
                lastTick = clock()
                continue
            }
            val stepMillis = minOf(POLL_INTERVAL_MILLIS, remaining)
            delay(stepMillis)
            val now = clock()
            if (!isPaused()) {
                remaining -= (now - lastTick).coerceAtLeast(stepMillis)
            }
            lastTick = now
        }
        return !isCancelled()
    }

    private suspend fun await(timeoutMillis: Long, probe: suspend () -> Boolean): Boolean {
        var elapsed = 0L
        var lastTick = clock()
        while (elapsed < timeoutMillis) {
            if (isCancelled()) return false
            if (isPaused()) {
                delay(POLL_INTERVAL_MILLIS)
                lastTick = clock()
                continue
            }
            if (probe()) return true
            delay(POLL_INTERVAL_MILLIS)
            val now = clock()
            elapsed += (now - lastTick).coerceAtLeast(0L)
            lastTick = now
        }
        return false
    }

    private companion object {
        const val POLL_INTERVAL_MILLIS = 500L
        const val NEXT_TRIGGER_MIN_DISTANCE = 24.0
    }
}

/** 不可用的空终止器（例如发布版本关闭了所有降级策略） */
class UnsupportedTerminator(
    override val kind: ClientTerminationKind,
) : GameClientTerminator {
    override suspend fun terminate(): ClientTerminationResult = ClientTerminationResult.UNSUPPORTED
    override fun isSupported(): Boolean = false
}

/**
 * 终止策略降级链：依次尝试候选策略，直到客户端确认不在场。
 * 每条策略只负责发出动作，确认由 [GameSessionResetWorkflow] 统一完成。
 */
class TerminatorFallbackChain(
    private val candidates: List<GameClientTerminator>,
) : GameClientTerminator {
    override val kind: ClientTerminationKind
        get() = candidates.firstOrNull { it.isSupported() }?.kind ?: ClientTerminationKind.USER_ASSIST

    override suspend fun terminate(): ClientTerminationResult {
        for (candidate in candidates) {
            if (!candidate.isSupported()) continue
            val result = candidate.terminate()
            if (result == ClientTerminationResult.TERMINATED || result == ClientTerminationResult.ALREADY_GONE) {
                return result
            }
            if (result == ClientTerminationResult.REJECTED) return result
        }
        return ClientTerminationResult.UNSUPPORTED
    }

    override fun isSupported(): Boolean = candidates.any { it.isSupported() }
}

/** 把“客户端是否已退出”作为决策输入的状态：关闭中 / 已退出 / 仍在运行 */
enum class ClientPresenceState {
    RUNNING,
    GONE,
    UNKNOWN,
}
