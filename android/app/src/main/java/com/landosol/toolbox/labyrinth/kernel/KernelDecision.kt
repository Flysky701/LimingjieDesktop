package com.landosol.toolbox.labyrinth.kernel

import com.landosol.toolbox.labyrinth.vision.EntryPixelRect
import kotlinx.serialization.Serializable

@Serializable
enum class KernelErrorCategory { EVIDENCE, STATE, POLICY, PERMIT, EXECUTION, TIMEOUT }

@Serializable
data class KernelError(val code: String, val category: KernelErrorCategory, val source: String, val detail: String = "")

sealed interface KernelDecision {
    data class Wait(val reason: String, val error: KernelError? = null) : KernelDecision
    data class Act(val actionKind: String, val label: String, val targetId: String? = null,
                   val rect: EntryPixelRect? = null, val reason: String = "") : KernelDecision
    data class Complete(val encounterId: String?, val summary: String) : KernelDecision
    data class FinishRun(val cleared: Boolean, val reason: String) : KernelDecision
    data class NeedEvidence(val error: KernelError, val reason: String) : KernelDecision
}
