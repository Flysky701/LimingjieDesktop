package com.landosol.toolbox.labyrinth

import com.landosol.toolbox.labyrinth.node.NodeSearchHint
import com.landosol.toolbox.labyrinth.concurrent.LabyrinthFourThreadRuntime
import com.landosol.toolbox.labyrinth.concurrent.WorkToken

/** Immutable T2 request, borrowed only for the lifetime of a synchronous vision root. */
object LabyrinthBalancedVisionContext {
    data class RequestSnapshot(
        val finalBossOnly: Boolean,
        val skipJoinedCharacters: Boolean,
        val relicEvidenceVersion: Long,
        val relicVerificationMarks: Set<String>,
        val nodeScanRequested: Boolean,
        val nodeScanConsumable: Boolean,
        val nodeSearchHint: NodeSearchHint?,
        val rosterCharacterIds: Set<String>,
        val allowInitial1088GeometryRefinement: Boolean,
        val lifecycleEpoch: Long = 0,
        val sceneEpoch: Long = 0,
    )
    private val local = ThreadLocal<RequestSnapshot?>()
    private val query = ThreadLocal<Boolean?>()
    private val owner = ThreadLocal<WorkToken?>()
    fun current(): RequestSnapshot? = if (owner.get() === LabyrinthFourThreadRuntime.currentToken()) local.get() else null
    fun markNodeScanQuery() { if (current() != null) query.set(true) }
    fun nodeScanQueryUsed(): Boolean = current() != null && query.get() == true
    fun <T> withSnapshot(snapshot: RequestSnapshot, body: () -> T): T {
        // help-join can run another root on this thread. Restore, never blindly remove.
        val previous = local.get()
        val previousQuery = query.get()
        val previousOwner = owner.get()
        owner.set(LabyrinthFourThreadRuntime.currentToken())
        local.set(snapshot)
        query.set(false)
        return try { body() } finally {
            if (previous == null) local.remove() else local.set(previous)
            if (previousQuery == null) query.remove() else query.set(previousQuery)
            if (previousOwner == null) owner.remove() else owner.set(previousOwner)
        }
    }
}

/**
 * Fast and detailed pixels are deliberately labelled as separate evidence sources.
 * Budgets describe owned business workers, not all process threads or simultaneous CPU activity.
 * Defaults access the runtime; production publication already runs on its control dispatcher.
 * Environment-only diagnostics must use the lightweight startup selection instead.
 */
data class LabyrinthBalancedDiagnostics(
    val enabled: Boolean = true,
    val businessThreadBudget: Int = LabyrinthFourThreadRuntime.businessThreadBudget,
    val cpuWorkerBudget: Int = LabyrinthFourThreadRuntime.cpuWorkerBudget,
    val controlThread: Boolean = false,
    val fastObservationCount: Long = 0,
    val fastFrameSequence: Long? = null,
    val fastTimestampMillis: Long? = null,
    val fastPage: String? = null,
    val fastElapsedMillis: Long? = null,
    val detailFrameSequence: Long? = null,
    val detailTimestampMillis: Long? = null,
    val detailActive: Boolean = false,
    val detailQuiescent: Boolean = true,
    val detailActorBusy: Boolean = false,
    val detailPending: Boolean = false,
    val detailDiscardCount: Long = 0,
    val fastReplacementCount: Long = 0,
    val plannerPendingCount: Int = 0,
    val plannerDiscardCount: Long = 0,
    val diagnosticActive: Boolean = false,
    val diagnosticQuiescent: Boolean = true,
    val diagnosticPending: Boolean = false,
    val diagnosticReplacementCount: Long = 0,
    val staleActionBlockedCount: Long = 0,
    val sceneEpoch: Long = 0,
    /** Android-visible processor count captured at startup, not a per-frame CPU resample. */
    val availableProcessors: Int = LabyrinthFourThreadRuntime.availableProcessors,
    val eventPlanning: LabyrinthEventPlanningDiagnostics? = null,
)

/** Checked before claiming the latch / creating a RoleRound dispatch token. */
internal object LabyrinthBalancedEvidence {
    const val MAX_ACTION_EVIDENCE_AGE_MILLIS = 1_200L
    fun fresh(
        now: Long, capturedAt: Long?, capturedAfter: Long,
        detailEpoch: Long, currentEpoch: Long,
        detailWidth: Int, detailHeight: Int, fastWidth: Int?, fastHeight: Int?,
    ): Boolean = capturedAt != null && capturedAt > capturedAfter &&
        now - capturedAt in 0..MAX_ACTION_EVIDENCE_AGE_MILLIS && detailEpoch == currentEpoch &&
        (fastWidth == null || detailWidth == fastWidth) &&
        (fastHeight == null || detailHeight == fastHeight)
}
