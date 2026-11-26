package com.landosol.toolbox.labyrinth.debug

import com.landosol.toolbox.AppVersion
import com.landosol.toolbox.automation.capture.CaptureTelemetry
import com.landosol.toolbox.automation.overlay.AutomationOverlayPresentation
import com.landosol.toolbox.automation.session.GameSessionResetState
import com.landosol.toolbox.automation.session.GameSessionResetStatus
import com.landosol.toolbox.core.MonotonicClock
import com.landosol.toolbox.core.logging.AppLogger
import com.landosol.toolbox.core.logging.LogRedactor
import com.landosol.toolbox.labyrinth.LabyrinthEntryRecognitionSessionState
import com.landosol.toolbox.labyrinth.LabyrinthEntryRecognitionStatus
import com.landosol.toolbox.labyrinth.LabyrinthEventChoiceDecision
import com.landosol.toolbox.labyrinth.LabyrinthRoutePreviewCache
import com.landosol.toolbox.labyrinth.kernel.KernelEvent
import com.landosol.toolbox.labyrinth.kernel.KernelEventLog
import com.landosol.toolbox.labyrinth.vision.EntryPixelRect
import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryFrameResult
import com.landosol.toolbox.labyrinth.vision.LabyrinthFastFrameObservation
import com.landosol.toolbox.labyrinth.vision.LabyrinthShopCategoryState
import com.landosol.toolbox.vision.NativeBitmap
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import org.json.JSONArray
import org.json.JSONObject

/** Records bounded maze diagnostics in memory and exports a redacted ZIP on demand. */
class LabyrinthDiagnosticsRecorder(
    private val runtimeDiagnostics: LabyrinthRuntimeDiagnostics? = null,
    private val processLogSource: () -> ByteArray = AppLogger::snapshot,
    private val userLogSource: (() -> ByteArray?)? = null,
    private val traceIdProvider: () -> String? = AppLogger::currentTraceId,
) : Closeable {
    private data class IncidentEntry(val sequence: Long, val slug: String, val png: ByteArray, val json: ByteArray) {
        val totalBytes: Long get() = png.size.toLong() + json.size
    }

    private data class ExportSnapshot(
        val states: List<String>,
        val fastStates: List<String>,
        val incidents: List<IncidentEntry>,
        val kernelSource: KernelEventLog?,
    )

    private val lock = Any()
    private var closed = false
    private var kernelSource: KernelEventLog? = null
    @Volatile private var ocrHealthSource: (() -> Map<String, Any?>)? = null
    private val recentStates = ArrayDeque<String>()
    private val recentFastStates = ArrayDeque<String>()
    private val recentIncidents = ArrayDeque<IncidentEntry>()
    private var incidentSequence = 0L
    private var incidentTotalBytes = 0L
    private var lastAutoIncidentKey: String? = null
    private var lastAutoIncidentAt = Long.MIN_VALUE

    fun attachKernelEventSource(source: KernelEventLog?) = synchronized(lock) {
        if (!closed) kernelSource = source
    }

    fun attachOcrHealthSource(source: (() -> Map<String, Any?>)?) = synchronized(lock) {
        if (!closed) ocrHealthSource = source
    }

    fun incidentCount(): Int = synchronized(lock) { recentIncidents.size }

    fun publishFastFrame(
        observation: LabyrinthFastFrameObservation,
        state: LabyrinthEntryRecognitionSessionState,
    ) {
        val json = JSONObject(labyrinthFastDiagnosticPayload(
            observation, state, System.currentTimeMillis(), AppVersion.name, AppVersion.code,
        )).put("traceId", currentTraceId() ?: JSONObject.NULL).toString()
        synchronized(lock) {
            if (closed) return
            recentFastStates.addLast(json)
            if (recentFastStates.size > STATE_HISTORY_LIMIT) recentFastStates.removeFirst()
        }
    }

    fun publish(
        bitmap: NativeBitmap,
        state: LabyrinthEntryRecognitionSessionState,
        result: LabyrinthEntryFrameResult,
        presentation: AutomationOverlayPresentation,
        nowMillis: Long = System.currentTimeMillis(),
    ) = publishFrame(bitmap, state, result, presentation, nowMillis)

    fun publishReset(
        bitmap: NativeBitmap,
        state: GameSessionResetState,
        result: LabyrinthEntryFrameResult,
        presentation: AutomationOverlayPresentation,
        nowMillis: Long = System.currentTimeMillis(),
    ) {
        val status = when (state.status) {
            GameSessionResetStatus.IDLE -> LabyrinthEntryRecognitionStatus.IDLE
            GameSessionResetStatus.RUNNING -> LabyrinthEntryRecognitionStatus.RUNNING
            GameSessionResetStatus.STOPPED -> LabyrinthEntryRecognitionStatus.STOPPED
            GameSessionResetStatus.ERROR -> LabyrinthEntryRecognitionStatus.ERROR
        }
        val session = LabyrinthEntryRecognitionSessionState(
            status = status,
            sessionId = state.sessionId,
            dryRun = false,
            paused = state.paused,
            frameCount = state.frameCount,
            actionCount = state.actionCount,
            message = "会话重置 ${state.stage.name}：${state.message.orEmpty()}",
        )
        val reset = JSONObject().put("stage", state.stage.name).put("lastBlock", state.lastBlock.name)
        publishFrame(bitmap, session, result, presentation, nowMillis, reset)
    }

    private fun publishFrame(
        bitmap: NativeBitmap,
        state: LabyrinthEntryRecognitionSessionState,
        result: LabyrinthEntryFrameResult,
        presentation: AutomationOverlayPresentation,
        nowMillis: Long,
        reset: JSONObject? = null,
    ) {
        val json = buildJson(state, result, presentation, nowMillis)
        reset?.let { json.put("reset", it) }
        detectAutoIncidentReason(state, result)?.let { (reasonKey, reasonLabel) ->
            val shouldCapture = synchronized(lock) {
                if (closed) return
                if (lastAutoIncidentKey == reasonKey && nowMillis - lastAutoIncidentAt < INCIDENT_COOLDOWN_MILLIS) {
                    false
                } else {
                    lastAutoIncidentKey = reasonKey
                    lastAutoIncidentAt = nowMillis
                    true
                }
            }
            if (shouldCapture) storeIncident(reasonLabel, bitmap, json, nowMillis)
        }
        synchronized(lock) {
            if (closed) return
            recentStates.addLast(json.put("incidentCount", recentIncidents.size).toString())
            if (recentStates.size > STATE_HISTORY_LIMIT) recentStates.removeFirst()
        }
    }

    fun captureIncident(
        reason: String,
        bitmap: NativeBitmap,
        state: LabyrinthEntryRecognitionSessionState,
        result: LabyrinthEntryFrameResult,
        presentation: AutomationOverlayPresentation,
        nowMillis: Long = System.currentTimeMillis(),
    ) {
        storeIncident(reason, bitmap, buildJson(state, result, presentation, nowMillis), nowMillis)
    }

    private fun storeIncident(reason: String, bitmap: NativeBitmap, snapshot: JSONObject, nowMillis: Long) {
        try {
            val safeReason = LogRedactor.redact(reason)
            val png = ByteArrayOutputStream().use { output ->
                check(bitmap.compress(NativeBitmap.CompressFormat.PNG, 100, output)) { "PNG encoder unavailable" }
                output.toByteArray()
            }
            val meta = JSONObject(snapshot.toString()).apply {
                put("incidentReason", safeReason)
                put("incidentCapturedAtMillis", nowMillis)
            }
            val json = redactedJson(meta.toString()).encodeToByteArray()
            if (png.size.toLong() + json.size > MAX_INCIDENT_BYTES) return
            val slug = safeReason.lowercase(Locale.US)
                .replace(Regex("[^a-z0-9_-]+"), "_")
                .trim('_')
                .ifEmpty { "incident" }
                .take(40)
            synchronized(lock) {
                if (closed) return
                val entry = IncidentEntry(++incidentSequence, slug, png, json)
                recentIncidents.addLast(entry)
                incidentTotalBytes += entry.totalBytes
                while (recentIncidents.size > MAX_INCIDENT_COUNT || incidentTotalBytes > MAX_INCIDENT_BYTES) {
                    incidentTotalBytes -= recentIncidents.removeFirst().totalBytes
                }
            }
        } catch (failure: Exception) {
            AppLogger.w(LOG_TAG, "lossless incident capture failed", failure)
        }
    }

    private fun detectAutoIncidentReason(
        state: LabyrinthEntryRecognitionSessionState,
        result: LabyrinthEntryFrameResult,
    ): Pair<String, String>? {
        val page = result.observation.state.name
        val reject = state.frameTrace.actionRejectReason
        return when {
            state.status == LabyrinthEntryRecognitionStatus.ERROR ->
                "error:$page:${state.message.orEmpty()}" to "session_error"
            state.nodeBindings?.conflicts?.isNotEmpty() == true || reject?.contains("conflict") == true ->
                "conflict:${state.nodeBindings?.nextNode?.blockId}:${reject.orEmpty()}" to "node_conflict"
            reject?.contains("timeout") == true || state.message?.contains("超时") == true ->
                "timeout:$page:${reject.orEmpty()}" to "action_timeout"
            result.battleFailure != null ->
                "battle_failed:${result.battleFailure.layout.name}" to "battle_failed"
            else -> {
                val exhausted = result.shopObservation?.items.orEmpty().count {
                    it.categoryState == LabyrinthShopCategoryState.EXHAUSTED
                }
                if (exhausted > 0) "shop_exhausted:$exhausted" to "shop_ocr_exhausted" else null
            }
        }
    }

    fun exportDiagnosticZip(): ByteArray {
        val snapshot = synchronized(lock) {
            ExportSnapshot(recentStates.toList(), recentFastStates.toList(), recentIncidents.toList(), kernelSource)
        }
        val traceId = currentTraceId()
        val manifest = JSONObject().put("traceId", traceId ?: JSONObject.NULL)
        val latest = snapshot.states.lastOrNull()?.let(::JSONObject)
            ?: JSONObject().put("status", "waiting").put("traceId", traceId ?: JSONObject.NULL)
        latest.put("fastObservation", snapshot.fastStates.lastOrNull()?.let(::JSONObject)
            ?: JSONObject().put("available", false))

        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            fun entry(name: String, content: ByteArray) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content)
                zip.closeEntry()
            }
            fun textEntry(name: String, content: String) = entry(name, content.encodeToByteArray())
            fun historyEntry(name: String, lines: Sequence<String>) {
                zip.putNextEntry(ZipEntry(name))
                for (line in lines) {
                    zip.write(redactedJson(line).encodeToByteArray())
                    zip.write('\n'.code)
                }
                zip.closeEntry()
            }

            textEntry("manifest.json", manifest.toString())
            textEntry("summary.txt", "traceId=${traceId.orEmpty()}\n")
            textEntry("latest-snapshot.json", redactedJson(latest.toString()))
            historyEntry("state-history.jsonl", snapshot.states.asSequence())
            historyEntry("fast/history.ndjson", snapshot.fastStates.asSequence())
            runtimeDiagnostics?.let { diagnostics ->
                textEntry("environment.json", redactedJson(diagnostics.environmentJson().decodeToString()))
                historyEntry("runtime/history.ndjson", diagnostics.historyNdjson().decodeToString()
                    .lineSequence().filter(String::isNotBlank))
            }
            textEntry("process.log", LogRedactor.redact(processLogSource().decodeToString()))
            userLogSource?.invoke()?.takeIf { it.isNotEmpty() }?.let {
                textEntry("user-console.log", LogRedactor.redact(it.decodeToString()))
            }
            snapshot.kernelSource?.let { source ->
                val events = source.recent().joinToString("\n") {
                    LogRedactor.redactJson(Json.encodeToJsonElement<KernelEvent>(it)).toString()
                }
                textEntry("kernel/events.ndjson", events)
            }
            snapshot.incidents.forEach { incident ->
                val prefix = "incidents/%03d_%s".format(Locale.US, incident.sequence, incident.slug)
                entry("$prefix.png", incident.png)
                entry("$prefix.json", incident.json)
            }
        }
        return bytes.toByteArray()
    }

    private fun currentTraceId(): String? = traceIdProvider()?.let(LogRedactor::redact)

    private fun redactedJson(json: String): String = LogRedactor.redactJson(Json.parseToJsonElement(json)).toString()

    override fun close() = synchronized(lock) {
        closed = true
        kernelSource = null
        ocrHealthSource = null
        recentStates.clear()
        recentFastStates.clear()
        recentIncidents.clear()
        incidentTotalBytes = 0L
        lastAutoIncidentKey = null
    }

    private fun buildJson(
        state: LabyrinthEntryRecognitionSessionState,
        result: LabyrinthEntryFrameResult,
        presentation: AutomationOverlayPresentation,
        nowMillis: Long,
    ): JSONObject = JSONObject().apply {
        put("appVersion", AppVersion.name)
        put("appVersionCode", AppVersion.code)
        put("timestamp", nowMillis)
        put("elapsedRealtimeMillis", MonotonicClock.elapsedRealtime())
        put("traceId", currentTraceId() ?: JSONObject.NULL)
        put("historyLimit", STATE_HISTORY_LIMIT)
        put("sessionId", state.sessionId?.value ?: JSONObject.NULL)
        put("sessionStatus", state.status.name)
        val capture = CaptureTelemetry.snapshot()
        put("captureCounters", JSONObject().apply {
            put("scope", "process_lifetime")
            put("callbacks", capture.callbacks)
            put("acquiredImages", capture.acquiredImages)
            put("convertedFrames", capture.convertedFrames)
            put("captureIntervalDropped", capture.intervalDropped)
            put("orientationDropped", capture.orientationDropped)
            put("noConsumerDropped", capture.noConsumerDropped)
            put("conversionErrors", capture.conversionErrors)
        })
        val roiOcr = com.landosol.toolbox.labyrinth.vision.RelicRoiOcrTelemetry.snapshot()
        put("relicRoiOcr", JSONObject().apply {
            put("scope", "process_lifetime")
            put("durationScope", "roi_prepare_to_engine_callback_not_business_ack")
            put("requests", roiOcr.requests)
            put("completions", roiOcr.completions)
            put("inFlight", roiOcr.inFlight)
            put("last", roiOcr.last?.let { last -> JSONObject().apply {
                put("id", last.id)
                put("slot", last.slot)
                put("capturedAtMillis", last.capturedAtMillis)
                put("durationMillis", last.durationMillis)
                put("textLength", last.textLength ?: JSONObject.NULL)
            } } ?: JSONObject.NULL)
        })
        put("balancedDiagnostics", state.balancedDiagnostics?.let { JSONObject(labyrinthBalancedDiagnosticPayload(it)) } ?: JSONObject.NULL)
        put("frameTiming", state.frameTiming?.let { timing -> JSONObject().apply {
            put("frameSequence", timing.frameSequence)
            put("captureConvertMillis", timing.captureConvertMillis ?: JSONObject.NULL)
            put("captureToWorkerMillis", timing.captureToWorkerMillis ?: JSONObject.NULL)
            put("queueWaitMillis", timing.queueWaitMillis ?: JSONObject.NULL)
            put("visionMillis", timing.visionMillis)
            put("sessionHandlingMillis", timing.sessionHandlingMillis)
            put("responseIntervalMillis", timing.responseIntervalMillis)
            put("queueSeen", timing.queue.seen)
            put("queueAccepted", timing.queue.accepted)
            put("queueIntervalDropped", timing.queue.intervalDropped)
            put("queueInvalidDropped", timing.queue.invalidDropped)
            put("queueReplaced", timing.queue.replaced)
            put("nodeScanHoldReason", timing.nodeScanHoldReason ?: JSONObject.NULL)
            put("nodeScanQueryTimestampMillis", timing.nodeScanQueryTimestampMillis ?: JSONObject.NULL)
            put("nodeScanTimeBasis", if (timing.nodeScanQueryTimestampMillis != null) "captured_frame_timestamp" else JSONObject.NULL)
            put("discardedRoleDecisionResults", timing.discardedRoleDecisionResults)
            put("roleRound", timing.roleRound?.let { r -> JSONObject().apply {
                put("generation", r.generation); put("phase", r.phase)
                put("selectedId", r.selectedId ?: JSONObject.NULL); put("token", r.token ?: JSONObject.NULL)
                put("dispatchedAtMillis", r.dispatchedAtMillis ?: JSONObject.NULL)
                put("feedbackAfterMillis", r.feedbackAfterMillis ?: JSONObject.NULL)
                put("newRoundEvidenceFrames", r.newRoundEvidenceFrames)
            } } ?: JSONObject.NULL)
            put("bossTabAction", timing.bossTabAction?.let { r -> JSONObject().apply {
                put("sourceIndex", r.sourceIndex); put("expectedIndex", r.expectedIndex)
                put("phase", r.phase); put("active", r.active)
                put("dispatchedAtMillis", r.dispatchedAtMillis)
                put("feedbackAfterMillis", r.feedbackAfterMillis ?: JSONObject.NULL)
                put("ackFrames", r.ackFrames); put("lastEvidenceAtMillis", r.lastEvidenceAtMillis ?: JSONObject.NULL)
            } } ?: JSONObject.NULL)
        } } ?: JSONObject.NULL)
        // 路线预览的缓存键：包含当前节点，推进到下一格才会让页面重新取一次。
        put("routePreviewKey", LabyrinthRoutePreviewCache.key() ?: JSONObject.NULL)
        put("title", presentation.title)
        put("status", presentation.status)
        put("detail", presentation.detail.orEmpty())
        put("dryRun", presentation.dryRun)
        put("paused", presentation.paused)
        put("page", result.observation.state.name)
        put("pageConfidence", result.observation.confidence)
        put("pageReason", result.observation.reason ?: JSONObject.NULL)
        fun rectJson(rect: EntryPixelRect?): Any = rect?.let {
            JSONObject().apply {
                put("left", it.left)
                put("top", it.top)
                put("width", it.width)
                put("height", it.height)
            }
        } ?: JSONObject.NULL
        put("eventOcr", result.eventOcrDiagnostics?.let { diagnostic ->
            JSONObject().apply {
                put("status", diagnostic.status)
                put("crop", rectJson(diagnostic.cropRect))
                put("fingerprint", diagnostic.fingerprint ?: JSONObject.NULL)
                put("completeLayoutCount", diagnostic.completeLayoutCount ?: JSONObject.NULL)
                put("buttonConfidencesByOptionCount", JSONObject().apply {
                    diagnostic.buttonConfidencesByOptionCount.forEach { (count, scores) ->
                        put(count.toString(), JSONArray(scores))
                    }
                })
                put("requestScheduled", diagnostic.requestScheduled)
                put("rawText", diagnostic.rawText ?: JSONObject.NULL)
                put("candidateEventId", diagnostic.candidateEventId ?: JSONObject.NULL)
                put("candidateScore", diagnostic.candidateScore ?: JSONObject.NULL)
                put("rivalMargin", diagnostic.rivalMargin ?: JSONObject.NULL)
                put("stableFrames", diagnostic.stableFrames)
                put("trusted", diagnostic.trusted)
                put("cache", diagnostic.cache?.let { cache -> JSONObject().apply {
                    put("status", cache.status)
                    put("ageMillis", cache.ageMillis ?: JSONObject.NULL)
                    put("lifetimeMillis", cache.cacheLifetimeMillis)
                    put("fingerprintDistance", cache.fingerprintDistance ?: JSONObject.NULL)
                    put("cachedCrop", rectJson(cache.cachedCropRect))
                    put("cachedRequestId", cache.cachedRequestId ?: JSONObject.NULL)
                    put("pendingRequestId", cache.pendingRequestId ?: JSONObject.NULL)
                    put("pendingAgeMillis", cache.pendingAgeMillis ?: JSONObject.NULL)
                    put("lastCompletedRequestId", cache.lastCompletedRequestId ?: JSONObject.NULL)
                    put("lastRequestDurationMillis", cache.lastRequestDurationMillis ?: JSONObject.NULL)
                    put("lastResultTextLength", cache.lastResultTextLength ?: JSONObject.NULL)
                    put("lastResultStatus", cache.lastResultStatus ?: JSONObject.NULL)
                } } ?: JSONObject.NULL)
            }
        } ?: JSONObject.NULL)
        put("eventChoice", result.eventChoiceSelection?.let { event -> JSONObject().apply {
            put("eventId", event.event.id)
            put("optionCount", event.choices.size)
            put("trusted", event.trusted)
            put("choices", JSONArray().apply {
                event.choices.forEach { visual -> put(JSONObject().apply {
                    put("choiceId", visual.choice.id)
                    put("slot", visual.choice.slot)
                    put("label", visual.choice.name)
                    put("conditionType", visual.choice.conditionType)
                    put("conditionValue", visual.choice.conditionValue)
                    put("button", rectJson(visual.buttonRect))
                    put("buttonConfidence", visual.buttonConfidence)
                    put("enabledConfidence", visual.enabledConfidence)
                }) }
            })
        } } ?: JSONObject.NULL)
        put("eventChoiceDecision", when (val decision = state.eventChoiceDecision) {
            is LabyrinthEventChoiceDecision.Select -> JSONObject().apply {
                put("type", "SELECT")
                put("eventId", decision.eventId)
                put("choiceId", decision.choiceId)
                put("utilityEstimated", decision.utilityEstimated)
                put("utility", if (decision.utilityEstimated) decision.utility else JSONObject.NULL)
                put("explanation", decision.explanation)
                put("button", rectJson(decision.buttonRect))
                put("actionSafe", decision.actionSafe)
                put("safetyNote", decision.safetyNote ?: JSONObject.NULL)
            }
            is LabyrinthEventChoiceDecision.Wait -> JSONObject().apply {
                put("type", "WAIT")
                put("reason", decision.reason)
            }
            null -> JSONObject.NULL
        })
        put("elapsedMillis", result.elapsedMillis)
        put("stageMillis", JSONObject(result.stageMillis))
        put("nodeSearchMode", result.nodeSearchMode)
        put("nodeSearchWindowCount", result.nodeSearchWindowCount)
        put("nodeEvidenceSource", result.nodeEvidenceSource.name)
        put("nodeRevalidationCount", result.nodeRevalidationCount)
        put(
            "trace",
            JSONObject().apply {
                val trace = state.frameTrace
                put("expectedPages", JSONArray(trace.expectedPages))
                put("actionLabel", trace.actionLabel ?: JSONObject.NULL)
                put("actionRejectReason", trace.actionRejectReason ?: JSONObject.NULL)
                put("nodeTargetBlockId", trace.nodeTargetBlockId ?: JSONObject.NULL)
                put("nodeTargetColumn", trace.nodeTargetColumn ?: JSONObject.NULL)
                put("nodeSearchWindowCount", trace.nodeSearchWindowCount)
                put("nodeTrackerReliable", trace.nodeTrackerReliable ?: JSONObject.NULL)
                put("nodeTrackerWorldLeft", trace.nodeTrackerWorldLeft ?: JSONObject.NULL)
                put("nodeTargetExpectedX", trace.nodeTargetExpectedX ?: JSONObject.NULL)
            },
        )
        fun platformJson(matches: List<com.landosol.toolbox.labyrinth.node.FinalBossPlatformMatch>) =
            JSONArray().apply {
                matches.forEach { match -> put(JSONObject().apply {
                    put("left", match.labelRect.left)
                    put("top", match.labelRect.top)
                    put("width", match.labelRect.width)
                    put("height", match.labelRect.height)
                    put("confidence", match.confidence)
                    put("colorScore", match.colorScore)
                    put("purpleActivationScore", match.purpleActivationScore)
                    put("reliable", match.reliable)
                }) }
            }
        put("finalBossPlatforms", platformJson(result.finalBossPlatforms))
        put("finalBossPlatformCandidates", platformJson(result.finalBossPlatformCandidates))
        put("frameWidth", result.frameWidth)
        put("frameHeight", result.frameHeight)
        put(
            "frameAspectRatio",
            if (result.frameWidth > 0 && result.frameHeight > 0) {
                result.frameWidth.toDouble() / result.frameHeight.toDouble()
            } else {
                0.0
            },
        )
        put("receivedFrameCount", state.receivedFrameCount)
        put("recognizedFrameCount", state.frameCount)
        put("actionCount", state.actionCount)
        put("message", state.message ?: JSONObject.NULL)
        put("matchedFeatures", JSONArray(result.matchedFeatures))
        put(
            "pageScores",
            JSONObject().apply {
                result.observation.stateScores.entries
                    .sortedByDescending { it.value }
                    .forEach { (key, value) -> put(key.name, value) }
            },
        )
        put(
            "anchorScores",
            JSONObject().apply {
                result.observation.anchorScores.values.entries
                    .sortedByDescending { it.value }
                    .forEach { (key, value) -> put(key, value) }
            },
        )
        put(
            "boxes",
            JSONArray().apply {
                presentation.boxes.forEach { box ->
                    put(
                        JSONObject().apply {
                            put("left", box.left.toDouble())
                            put("top", box.top.toDouble())
                            put("width", box.width.toDouble())
                            put("height", box.height.toDouble())
                            put("label", box.label)
                            put("recognized", box.recognized)
                            put("selected", box.selected)
                        },
                    )
                }
            },
        )
        put("initial1088RefinementContext", result.initial1088RefinementContext?.let {
            JSONObject(labyrinthInitial1088ContextPayload(it))
        } ?: JSONObject.NULL)
        put("initial1088Recovery", state.initial1088Recovery?.let {
            JSONObject(labyrinthInitial1088RecoveryPayload(it))
        } ?: JSONObject.NULL)
        put("initial1088Refinement", JSONArray().apply {
            result.openingCharacterMatches.forEach { character ->
                character.initial1088RefinementEvidence?.let { evidence ->
                    put(JSONObject(labyrinthInitial1088RefinementPayload(character.slotId, evidence,
                        result.initial1088RefinementContext)))
                }
            }
        })
        put(
            "openingCharacters",
            JSONArray().apply {
                result.openingCharacterMatches.forEach { character ->
                    put(
                        JSONObject().apply {
                            put("slot", character.slotId)
                            put("name", character.displayName ?: JSONObject.NULL)
                            put("id", character.characterId ?: JSONObject.NULL)
                            put("suspectedName", character.suspectedDisplayName ?: JSONObject.NULL)
                            put("suspectedId", character.suspectedCharacterId ?: JSONObject.NULL)
                            put("attribute", character.detectedAttribute?.label ?: JSONObject.NULL)
                            put("confidence", character.confidence)
                            put("margin", character.rivalMargin)
                            put("trusted", character.trusted)
                            put("selected", character.selected)
                        },
                    )
                }
            },
        )
        put(
            "openingViewport",
            result.openingCharacterSelection?.let { viewport ->
                JSONObject().apply {
                    put("state", viewport.recognitionState.name)
                    put("revision", viewport.viewportRevision)
                    put("filter", viewport.currentFilter.label)
                    put("scrollbarVisible", viewport.scrollbar.visible)
                    put("scrollable", viewport.scrollbar.canScroll)
                    put("scrollPosition", viewport.scrollbar.position)
                    put("visibleCharacterCount", viewport.visibleCharacters.size)
                }
            } ?: JSONObject.NULL,
        )
        put(
            "nodeRelicStacks",
            JSONArray().apply {
                result.nodeRelicStackObservation?.entries.orEmpty().forEach { entry ->
                    put(
                        JSONObject().apply {
                            put("attribute", entry.attribute ?: JSONObject.NULL)
                            put("stacks", entry.stacks ?: JSONObject.NULL)
                            put("confidence", entry.iconConfidence)
                            put("evidence", entry.evidenceText ?: JSONObject.NULL)
                        },
                    )
                }
            },
        )
        put(
            "relicChoices",
            JSONArray().apply {
                result.relicChoiceSelection?.choices.orEmpty().forEach { relic ->
                    put(
                        JSONObject().apply {
                            put("name", relic.displayName ?: relic.suspectedDisplayName ?: JSONObject.NULL)
                            put("attribute", relic.attribute ?: relic.suspectedAttribute ?: JSONObject.NULL)
                            put("bonus", relic.attributeBonus ?: JSONObject.NULL)
                            put("currentStacks", relic.currentMarkStacks ?: JSONObject.NULL)
                            put("currentStacksEvidence", relic.currentMarkStacksEvidenceText ?: JSONObject.NULL)
                            put("confidence", relic.confidence)
                            put("margin", relic.rivalMargin)
                            put("recognized", relic.recognized)
                        },
                    )
                }
            },
        )
        put(
            "shop",
            result.shopObservation?.let { shop ->
                JSONObject().apply {
                    put("dialogState", shop.dialogState.name)
                    put("coinBalance", shop.coinBalance ?: JSONObject.NULL)
                    put("coinBalanceConfirmed", shop.coinBalanceConfirmed)
                    put("availableItemCount", shop.availableItemCount)
                    put("recognizedButtonCount", shop.recognizedButtonCount)
                    put("refreshButtonEnabled", shop.refreshButtonEnabled)
                    put("refreshButtonEnabledEvidence", shop.refreshButtonEnabledEvidence)
                    put("refreshDialog", result.shopRefreshDialog?.let { dlg ->
                        JSONObject().apply {
                            put("kind", dlg.kind.name)
                            put("fee", dlg.fee ?: JSONObject.NULL)
                            put("confirmed", dlg.confirmed)
                            put("text", dlg.text ?: JSONObject.NULL)
                            put("evidenceId", dlg.evidenceId ?: JSONObject.NULL)
                        }
                    } ?: JSONObject.NULL)
                    put(
                        "items",
                        JSONArray().apply {
                            shop.items.forEach { item ->
                                val relic = item.relicMatch
                                put(
                                    JSONObject().apply {
                                        put("slot", item.slotId)
                                        put("purchasable", item.purchasable)
                                        put("status", item.status.name)
                                        put("kind", item.kind.name)
                                        put("titleText", item.titleText ?: JSONObject.NULL)
                                        put("roleImprintLabel", item.roleImprintLabel ?: JSONObject.NULL)
                                        put("titleEvidenceId", item.titleEvidenceId ?: JSONObject.NULL)
                                        put("categoryState", item.categoryState.name)
                                        put("categoryAttempt", item.categoryAttempt)
                                        put("buyButtonScore", item.buyButtonScore)
                                        put("buyButtonEnabledEvidence", item.buyButtonEnabledEvidence)
                                        put("name", relic?.displayName ?: relic?.suspectedDisplayName ?: JSONObject.NULL)
                                        put("relicId", relic?.relicId ?: relic?.suspectedRelicId ?: JSONObject.NULL)
                                        put("attribute", relic?.attribute ?: relic?.suspectedAttribute ?: JSONObject.NULL)
                                        put("relicConfidence", relic?.confidence ?: 0.0)
                                        put("relicMargin", relic?.rivalMargin ?: 0.0)
                                        put("relicRecognized", relic?.recognized ?: false)
                                    },
                                )
                            }
                        },
                    )
                    put(
                        "purchaseCandidate",
                        shop.purchaseCandidate?.let { candidate ->
                            JSONObject().apply {
                                put("relicId", candidate.relicId ?: JSONObject.NULL)
                                put("name", candidate.displayName ?: JSONObject.NULL)
                                put("confidence", candidate.confidence)
                                put("margin", candidate.rivalMargin)
                            }
                        } ?: JSONObject.NULL,
                    )
                }
            } ?: JSONObject.NULL,
        )
        fun iconCandidatesJson(candidates: List<com.landosol.toolbox.labyrinth.vision.LabyrinthCharacterIconCandidate>) =
            JSONArray().apply {
                candidates.take(3).forEach { c ->
                    put(JSONObject().apply {
                        put("id", c.characterId)
                        put("name", c.displayName)
                        put("variant", c.iconVariant)
                        put("confidence", c.confidence)
                    })
                }
            }
        put(
            "characters",
            JSONArray().apply {
                result.characterMatches.forEach { character ->
                    put(
                        JSONObject().apply {
                            put("slotId", character.slotId)
                            put("rect", rectJson(character.screenRect))
                            put("name", character.displayName ?: character.suspectedDisplayName ?: JSONObject.NULL)
                            put("id", character.characterId ?: character.suspectedCharacterId ?: JSONObject.NULL)
                            put("confidence", character.confidence)
                            put("margin", character.rivalMargin)
                            put("trusted", character.trusted)
                            put("nameEvidence", character.nameEvidenceText ?: JSONObject.NULL)
                            put("nameEvidenceScore", character.nameEvidenceScore)
                            put("nameEvidenceMargin", character.nameEvidenceMargin)
                            put("nameAssisted", character.nameAssisted)
                            put("iconCandidates", iconCandidatesJson(character.iconCandidates))
                        },
                    )
                }
            },
        )
        put(
            "battleTeam",
            result.battleTeamSelection?.let { team ->
                fun battleMemberJson(m: com.landosol.toolbox.labyrinth.vision.LabyrinthBattleCharacterMatch) =
                    JSONObject().apply {
                        put("slotId", m.slotId)
                        put("rect", rectJson(m.screenRect))
                        put("id", m.characterId ?: m.suspectedCharacterId ?: JSONObject.NULL)
                        put("name", m.displayName ?: m.suspectedDisplayName ?: JSONObject.NULL)
                        put("confidence", m.confidence)
                        put("margin", m.rivalMargin)
                        put("trusted", m.trusted)
                        put("selected", m.selected)
                        put("iconCandidates", iconCandidatesJson(m.iconCandidates))
                    }
                JSONObject().apply {
                    put("recognitionState", team.recognitionState.name)
                    put("viewportRevision", team.viewportRevision)
                    put("currentFilter", team.currentFilter.label)
                    put("bossTeamIndex", team.bossTeamIndex ?: JSONObject.NULL)
                    put("scrollbarPosition", team.scrollbar.position)
                    put("scrollbarVisible", team.scrollbar.visible)
                    put("visibleCharacters", JSONArray().apply {
                        team.visibleCharacters.forEach { put(battleMemberJson(it)) }
                    })
                    put("selectedCharacters", JSONArray().apply {
                        team.selectedCharacters.forEach { put(battleMemberJson(it)) }
                    })
                }
            } ?: JSONObject.NULL,
        )
        put(
            "exEncounter",
            result.exEncounter?.let { ex ->
                JSONObject().apply {
                    put("challengeTitleText", ex.challengeTitleText ?: JSONObject.NULL)
                    put("challengeDifficultyResolved", ex.challengeDifficultyResolved)
                    put("extremeChallenge", ex.extremeChallenge)
                    put("multiMonsterLikely", ex.multiMonsterLikely)
                    put("specialDualLikely", ex.specialDualLikely)
                    put("monsterDetailOpen", ex.monsterDetailOpen)
                    put("encounterId", ex.encounterId ?: JSONObject.NULL)
                    put("encounterName", ex.encounterName ?: JSONObject.NULL)
                    put("trusted", ex.trusted)
                }
            } ?: JSONObject.NULL,
        )
        put(
            "modals",
            JSONObject().apply {
                put("battleFailure", result.battleFailure?.let { f ->
                    JSONObject().apply {
                        put("confidence", f.confidence)
                        put("layout", f.layout.name)
                    }
                } ?: JSONObject.NULL)
                put("battleEndConfirmation", result.battleEndConfirmation?.let { c ->
                    JSONObject().apply {
                        put("stage", c.stage.name)
                        put("confidence", c.confidence)
                    }
                } ?: JSONObject.NULL)
                put("nodeMoveConfirmation", result.nodeMoveConfirmation?.let { m ->
                    JSONObject().apply {
                        put("confidence", m.confidence)
                    }
                } ?: JSONObject.NULL)
                put("specialEquipment", result.specialEquipment?.let { s ->
                    JSONObject().apply {
                        put("stage", s.stage?.name ?: JSONObject.NULL)
                        put("confidence", s.confidence)
                        put("equipConfirmEnabled", s.equipConfirmEnabled ?: JSONObject.NULL)
                        put("entryButtonMissReason", s.entryButtonMissReason ?: JSONObject.NULL)
                    }
                } ?: JSONObject.NULL)
            },
        )
        put(
            "nodeBindings",
            state.nodeBindings?.let { match ->
                JSONObject().apply {
                    put("currentNodeId", match.currentNodeId ?: JSONObject.NULL)
                    put("nextNodeId", match.nextNode?.blockId ?: JSONObject.NULL)
                    put("logicalColumnOffset", match.logicalColumnOffset)
                    put("topologyAlignmentScore", match.topologyAlignmentScore)
                    put("visibleNodes", JSONArray().apply {
                        match.visibleNodes.forEach { v ->
                            put(JSONObject().apply {
                                put("blockId", v.blockId)
                                put("blockType", v.blockType)
                                put("detectedBlockType", v.detectedBlockType ?: JSONObject.NULL)
                                put("clickable", v.isClickable)
                                put("visualConfidence", v.visualConfidence)
                                put("typeConfidence", v.typeConfidence)
                                put("topologyConfidence", v.topologyConfidence ?: JSONObject.NULL)
                                put("bindingKind", v.topologyBindingKind?.name ?: JSONObject.NULL)
                                put("routeSemanticRepair", v.routeSemanticRepair)
                                put("rect", rectJson(v.screenRect))
                            })
                        }
                    })
                    put("conflicts", JSONArray().apply {
                        match.conflicts.forEach { c ->
                            put(JSONObject().apply {
                                put("blockId", c.blockId)
                                put("expectedType", c.expectedBlockType)
                                put("detectedType", c.detectedBlockType)
                                put("sourceClickable", c.sourceClickable)
                                put("confidence", c.confidence)
                                put("visualConfidence", c.visualConfidence ?: JSONObject.NULL)
                                put("topologyConfidence", c.topologyConfidence ?: JSONObject.NULL)
                                put("rect", rectJson(c.screenRect))
                            })
                        }
                    })
                    put("topology", JSONArray().apply {
                        match.topologyMappings.forEach { t ->
                            put(JSONObject().apply {
                                put("blockId", t.blockId)
                                put("logicalColumn", t.logicalColumn)
                                put("visualColumn", t.visualColumn)
                                put("visualRow", t.visualRow)
                                put("bindingKind", t.bindingKind.name)
                                put("topologyConfidence", t.topologyConfidence)
                                put("clickable", t.isClickable)
                            })
                        }
                    })
                }
            } ?: JSONObject.NULL,
        )
        put("incidentCount", incidentCount())
        put("ocrHealth", ocrHealthSource?.let { source ->
            runCatching { JSONObject(source()) }.getOrNull()
        } ?: JSONObject.NULL)
        put(
            "nodes",
            JSONArray().apply {
                result.nodeClassifications.forEach { node ->
                    put(
                        JSONObject().apply {
                            put("column", node.column)
                            put("row", node.row)
                            put("type", node.blockType)
                            put("confidence", node.confidence)
                            put("typeConfidence", node.typeConfidence)
                            put("activationScore", maxOf(node.cyanGlowScore, node.purpleGlowScore))
                            put("templateId", node.templateId)
                            put("clickable", node.isClickable)
                        },
                    )
                }
            },
        )
    }

    private companion object {
        const val LOG_TAG = "LabyrinthDiagnosticsRecorder"
        const val STATE_HISTORY_LIMIT = 600
        const val MAX_INCIDENT_COUNT = 10
        const val MAX_INCIDENT_BYTES = 30L * 1024 * 1024
        const val INCIDENT_COOLDOWN_MILLIS = 5_000L
    }
}
