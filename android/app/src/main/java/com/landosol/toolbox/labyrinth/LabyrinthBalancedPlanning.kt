package com.landosol.toolbox.labyrinth

import com.landosol.toolbox.labyrinth.concurrent.LabyrinthFourThreadRuntime
import com.landosol.toolbox.labyrinth.concurrent.WorkHandle
import com.landosol.toolbox.labyrinth.concurrent.WorkKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.CancellationException

/** T2-owned single flight: cancellation does not free the instance until onQuiesced. */
internal class LabyrinthBalancedPlanningSlot<K : Any, T : Any>(
    private val scope: CoroutineScope,
    private val now: () -> Long,
    private val changed: () -> Unit,
) {
    private class Request<K, T>(val key: K, val body: () -> T) {
        var handle: WorkHandle<T>? = null
        var outcome: Result<T>? = null
        var obsolete = false
        var quiesced = false
    }
    private var active: Request<K, T>? = null
    private var pending: Request<K, T>? = null
    private var desired: K? = null
    private var readyKey: K? = null
    private var ready: T? = null
    var readyAt: Long = Long.MIN_VALUE
        private set
    var discarded: Long = 0
        private set
    val busy: Boolean get() = active != null || pending != null

    fun request(key: K, body: () -> T): T? {
        desired = key
        if (key == readyKey) return ready
        if (active?.key == key && active?.obsolete == false) return null
        if (pending?.key == key) return null
        ready = null; readyKey = null
        val request = Request(key, body)
        val running = active
        if (running == null) start(request) else {
            running.obsolete = true
            pending = request
            running.handle?.cancel(false)
        }
        changed()
        return null
    }

    fun invalidate() {
        desired = null; readyKey = null; ready = null; pending = null
        active?.let { it.obsolete = true; it.handle?.cancel(false) }
        changed()
    }

    private fun complete(request: Request<K, T>) {
        val outcome = request.outcome ?: return
        if (active !== request || !request.quiesced) return
        active = null
        if (!request.obsolete && desired == request.key && outcome.isSuccess) {
            ready = outcome.getOrThrow(); readyKey = request.key; readyAt = now()
        } else {
            discarded++
            readyKey = null
        }
        val next = pending; pending = null
        if (next != null && desired == next.key) start(next)
        changed()
    }

    private fun start(request: Request<K, T>) {
        active = request
        try {
            request.handle = LabyrinthFourThreadRuntime.submitWork(
                kind = WorkKind.PLANNING,
                onResult = { outcome -> scope.launch {
                    if (active === request) { request.outcome = outcome; complete(request) }
                } },
                onQuiesced = { scope.launch {
                    if (active === request) { request.quiesced = true; complete(request) }
                } },
                body = request.body,
            )
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            // Runtime guarantees result + quiesced callbacks even for rejection.
        }
    }
}

/** Ordered qualification, not geometry / score jitter. 2-safe -> 3-safe changes this key. */
internal data class LabyrinthBalancedRoleCandidateKey(
    val slotId: String, val characterId: String?, val displayName: String?, val qualified: Boolean,
)
internal data class LabyrinthBalancedRolePlanningKey(
    val session: String, val generation: Long, val round: Long, val sceneEpoch: Long,
    val planner: LabyrinthRoleRewardChoicePlanner,
    val candidates: List<LabyrinthBalancedRoleCandidateKey>,
    val acquiredIds: List<String>, val context: LabyrinthRoleDecisionContext,
)

internal data class LabyrinthBalancedBattlePlanningOutput(
    val result: LabyrinthBattleTeamRecommendationResult, val cacheable: Boolean,
)

/** Only immutable request inputs and planner math; no Session getter / state commit on CPU. */
internal fun labyrinthBalancedBattleRecommendation(
    planner: LabyrinthBattleTeamRecommendationPlanner?, unavailableReason: String?,
    eligibleIds: List<String>, context: LabyrinthRoleDecisionContext,
    failedSignatures: Set<String>, lastFailedSignature: String?, retryNumber: Int,
    requestedBossTeamCount: Int, allowSupplementLead: Boolean, teamIndex: Int,
): LabyrinthBalancedBattlePlanningOutput {
    if (planner == null) return LabyrinthBalancedBattlePlanningOutput(
        LabyrinthBattleTeamRecommendationResult.Unavailable(unavailableReason ?: "角色决策资料未加载"), false)
    return try {
        val result = if (retryNumber > 0) planner.retryRecommendation(
            acquiredCharacterIds = eligibleIds, context = context,
            failedTeamSignatures = failedSignatures, retryNumber = retryNumber,
            lastFailedTeamSignature = lastFailedSignature, requestedBossTeamCount = requestedBossTeamCount,
            allowSupplementLead = allowSupplementLead,
        ) else planner.initialRecommendation(acquiredCharacterIds = eligibleIds, context = context,
            requestedBossTeamCount = requestedBossTeamCount, allowSupplementLead = allowSupplementLead)
        LabyrinthBalancedBattlePlanningOutput(result, true)
    } catch (failure: Throwable) {
        // Runtime cancellation is not an unavailable recommendation or an automatic stop.
        if (failure is CancellationException) throw failure
        LabyrinthBalancedBattlePlanningOutput(LabyrinthBattleTeamRecommendationResult.Unavailable(
            if (failure is OutOfMemoryError) "编组计算内存不足，已停止本次自动编组；请停止并重启辅助 App 后再试"
            else "生成第${teamIndex}队建议失败：${failure.message ?: "未知错误"}",
        ), failure is OutOfMemoryError)
    }
}
