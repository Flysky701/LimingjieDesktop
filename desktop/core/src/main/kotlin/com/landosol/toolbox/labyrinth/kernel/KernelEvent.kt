package com.landosol.toolbox.labyrinth.kernel

import kotlinx.serialization.Serializable
import com.landosol.toolbox.labyrinth.kernel.strategy.ShadowBattleSample

@Serializable
enum class KernelDomain { RUNTIME, ENTRY, MAP, EVENT, BATTLE, SHOP, REWARD, SETTLEMENT, RECOVERY, CUSTOM_HOOK }

object KernelEventKinds {
    const val SESSION_LIFECYCLE = "SESSION_LIFECYCLE"
    const val FRAME_OBSERVED = "FRAME_OBSERVED"
    const val STATE_REDUCED = "STATE_REDUCED"
    const val EVENT_CATALOG_MATCHED = "EVENT_CATALOG_MATCHED"
    const val EVENT_UNKNOWN_OBSERVED = "EVENT_UNKNOWN_OBSERVED"
    const val EVENT_RULE_EVALUATED = "EVENT_RULE_EVALUATED"
    const val EVENT_DECISION_PROPOSED = "EVENT_DECISION_PROPOSED"
    const val PERMIT_ISSUED = "PERMIT_ISSUED"
    const val PERMIT_REVOKED = "PERMIT_REVOKED"
    const val ACTION_DISPATCHED = "ACTION_DISPATCHED"
    const val ACTION_RECEIPT_OBSERVED = "ACTION_RECEIPT_OBSERVED"
    const val HOOK_INVOKED = "HOOK_INVOKED"
    const val BATTLE_SAMPLE_OBSERVED = "BATTLE_SAMPLE_OBSERVED"
}

@Serializable
data class KernelEventEnvelope<out P : KernelEventPayload>(
    val eventSeq: Long,
    val traceId: String,
    val causeEventSeq: Long?,
    val timestampMillis: Long,
    val frameSeq: Long?,
    val sceneEpoch: Long,
    val domain: KernelDomain,
    val kind: String,
    val reasonCode: String,
    val summary: String,
    val payload: P,
)

@Serializable sealed interface KernelEventPayload
@Serializable data class BattleSamplePayload(val sample: ShadowBattleSample) : KernelEventPayload
@Serializable data class SessionLifecyclePayload(val sessionId: String, val phase: String) : KernelEventPayload
@Serializable data class FrameObservedPayload(val pageState: String, val capturedAtMillis: Long) : KernelEventPayload
@Serializable data class StateReducedPayload(
    val pageState: String,
    val encounterId: String? = null,
    val parentId: String? = null,
    val depth: Int? = null,
    val detail: Map<String, String> = emptyMap(),
) : KernelEventPayload
@Serializable data class GameEventObservedPayload(val eventId: String, val stableFrames: Int, val trusted: Boolean) : KernelEventPayload
@Serializable data class UnknownGameEventObservedPayload(
    val rawTextExcerpt: String, val parsedPrimitives: List<String> = emptyList(),
    val choiceTextExcerpts: Map<String, String> = emptyMap(),
) : KernelEventPayload
@Serializable data class PolicyRuleEvaluatedPayload(
    val ruleId: String?, val action: String, val examinedRuleIds: List<String> = emptyList(),
    val ruleProvenance: String? = null,
) : KernelEventPayload
@Serializable data class DecisionProposedPayload(val eventId: String, val choiceId: String?, val actionSafe: Boolean, val reason: String) : KernelEventPayload
@Serializable data class PermitTransitionPayload(val choiceId: String?, val permitRevision: Long, val reason: String) : KernelEventPayload
@Serializable data class ActionDispatchedPayload(val choiceId: String) : KernelEventPayload
@Serializable data class ActionReceiptPayload(val choiceId: String, val verdict: String) : KernelEventPayload
@Serializable data class HookInvokedPayload(val hookId: String, val verdict: String, val reason: String = "") : KernelEventPayload
@Serializable data class CustomPayload(val attributes: Map<String, String>) : KernelEventPayload
