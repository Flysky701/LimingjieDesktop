package com.landosol.toolbox.desktop.app.adapters.score

import android.graphics.Bitmap
import com.landosol.toolbox.desktop.ocr.OcrEngine
import com.landosol.toolbox.desktop.ocr.OnnxOcrEngine
import com.landosol.toolbox.labyrinth.vision.EntryReferenceRect
import com.landosol.toolbox.labyrinth.vision.EntryReferenceSize
import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryPageState
import com.landosol.toolbox.labyrinth.vision.ReferenceFitMapper
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ChannelResult
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

/** Owns copied pixels and a separate OCR engine. No actions, shared OCR queue, or session callbacks. */
internal class RunScoreSidecar(
    private val save: (String, Long) -> Unit,
    private val notice: (String) -> Unit,
    private val engineFactory: () -> OcrEngine = ::OnnxOcrEngine,
    private val modelIdleMillis: Long = 60_000,
    private val readTimeoutMillis: Long = 30_000,
) {
    private data class Crop(val width: Int, val height: Int, val pixels: IntArray)
    private data class Frame(val timestamp: Long, val title: Crop, val total: Crop)
    private data class Pending(val recordId: String, val frames: ArrayDeque<Frame> = ArrayDeque())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queue = Channel<Pending>(4)
    private var pending: Pending? = null
    private var closed = false
    private var engine: OcrEngine? = null // Owned by the queue consumer, including native teardown.
    private val outstandingReads = ConcurrentHashMap.newKeySet<Any>()
    private val worker = scope.launch {
        try {
            while (true) {
                try {
                    val candidate = nextCandidate() ?: break
                    recognize(candidate)?.let { save(candidate.recordId, it) }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { notice("分数旁路识别未完成，保留原任务流程：${failure.message}") }
                catch (failure: LinkageError) { notice("分数旁路 OCR 不可用：${failure.message}") }
            }
        } finally {
            releaseEngine()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun nextCandidate(): Pending? {
        while (engine != null) {
            // The receive and timeout compete atomically; a result arriving at expiry stays in the queue.
            val next = select<ChannelResult<Pending>?> {
                queue.onReceiveCatching { it }
                onTimeout(modelIdleMillis) { null }
            }
            if (next != null) return next.getOrNull()
            // A timed-out native request may still be running. Keep its engine until it calls back.
            if (outstandingReads.isEmpty()) releaseEngine()
        }
        return queue.receiveCatching().getOrNull()
    }

    private fun releaseEngine() {
        val owned = engine
        engine = null
        owned?.close()
    }

    /** Called before the borrowed bitmap is recycled. Only two small pixel copies happen here. */
    @Synchronized fun observe(recordId: String?, page: LabyrinthEntryPageState, bitmap: Bitmap, timestamp: Long) {
        if (closed) return
        try {
            if (pending?.recordId != recordId || page != LabyrinthEntryPageState.RUN_CLEAR_RESULT) seal()
            if (recordId == null || page != LabyrinthEntryPageState.RUN_CLEAR_RESULT) return
            val current = pending ?: Pending(recordId).also { pending = it }
            if (current.frames.lastOrNull()?.timestamp?.let { timestamp <= it } == true) return
            val title = crop(bitmap, EntryReferenceRect(245, 447, 165, 51)) ?: return
            val total = crop(bitmap, EntryReferenceRect(730, 195, 460, 105)) ?: return
            current.frames.addLast(Frame(timestamp, title, total))
            while (current.frames.size > 2) current.frames.removeFirst()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            pending = null
            notice("分数画面未缓存，保留原任务流程：${failure.message}")
        }
    }

    private fun crop(bitmap: Bitmap, reference: EntryReferenceRect): Crop? {
        val rect = ReferenceFitMapper.map(bitmap.width, bitmap.height, EntryReferenceSize(1920, 1080), reference) ?: return null
        val pixels = IntArray(rect.width * rect.height)
        bitmap.getPixels(pixels, 0, rect.width, rect.left, rect.top, rect.width, rect.height)
        return Crop(rect.width, rect.height, pixels)
    }

    private fun seal() {
        val candidate = pending ?: return
        pending = null
        // An OCR backlog never delays capture/actions. Keep memory bounded even if OCR stalls.
        if (candidate.frames.size == 2 && !queue.trySend(candidate).isSuccess)
            notice("分数识别队列繁忙，本次保持未识别")
    }

    private suspend fun recognize(candidate: Pending): Long? {
        // Read the last two distinct captured frames after leaving RESULT, avoiding early count-up frames.
        val latest = candidate.frames.last()
        if (!isScoreDetailsTitle(read(latest.title))) return null
        val score = parseSidecarScore(read(latest.total)) ?: return null
        val previous = candidate.frames.first()
        if (!isScoreDetailsTitle(read(previous.title))) return null
        return score.takeIf { parseSidecarScore(read(previous.total)) == it }
    }

    private suspend fun read(crop: Crop): String? {
        val ownEngine = engine ?: engineFactory().also { engine = it }
        return withTimeoutOrNull(readTimeoutMillis) {
            suspendCancellableCoroutine { continuation ->
                val request = Any()
                outstandingReads.add(request)
                try {
                    ownEngine.submit(crop.width, crop.height, crop.pixels) { text ->
                        if (outstandingReads.remove(request)) continuation.resume(text)
                    }
                } catch (failure: Throwable) {
                    outstandingReads.remove(request)
                    throw failure
                }
            }
        }
    }

    suspend fun closeAndJoin() {
        synchronized(this) {
            if (!closed) {
                closed = true
                seal()
                queue.close()
            }
        }
        try {
            withTimeoutOrNull(5_000) { worker.join() }
        } finally {
            scope.cancel()
            withContext(NonCancellable) { worker.join() }
        }
    }
}

internal fun isScoreDetailsTitle(text: String?): Boolean = text?.filterNot(Char::isWhitespace) == "分数明细"

internal fun parseSidecarScore(text: String?): Long? {
    val value = text?.trim()?.replace('，', ',') ?: return null
    if (!Regex("(?:[0-9]{1,9}|[0-9]{1,3}(?:,[0-9]{3}){1,2})").matches(value)) return null
    return value.replace(",", "").toLongOrNull()?.takeIf { it > 0 }
}
