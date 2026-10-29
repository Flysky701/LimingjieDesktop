package com.landosol.toolbox.desktop.ocr

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Level
import java.util.logging.Logger

/** CPU PP-OCRv5 engine. The shared adapter owns pixel copying and dispatches callbacks to Main. */
class OnnxOcrEngine internal constructor(
    private val backendFactory: () -> OcrBackend,
    private val warn: (String, Throwable?) -> Unit,
    private val maxPendingBytes: Long = 64L * 1024 * 1024,
) : OcrEngine {
    constructor() : this(::OnnxBackend, { text, error -> Logger.getLogger(OnnxOcrEngine::class.java.name).log(Level.WARNING, text, error) })

    private val state = MutableStateFlow<OcrEngineStatus>(OcrEngineStatus.NotLoaded)
    override val status = state.asStateFlow()
    private val queue = ArrayBlockingQueue<Request>(64)
    private val lifecycle = Any()
    private var started = false
    private var pendingBytes = 0L
    @Volatile private var closed = false
    private var lastOverflowWarning = Long.MIN_VALUE
    private val worker = Thread(::work, "landosol-ocr").apply { isDaemon = true }

    override fun warmUp() {
        synchronized(lifecycle) {
            if (!closed) startWorker()
        }
    }

    override fun submit(width: Int, height: Int, pixels: IntArray, onResult: (String?) -> Unit) {
        if (width <= 0 || height <= 0 || width.toLong() * height != pixels.size.toLong()) {
            complete(onResult, null)
            return
        }
        val request = Request(OcrImage(width, height, pixels), onResult)
        val accepted = synchronized(lifecycle) {
            if (closed || state.value is OcrEngineStatus.Failed) false
            else {
                startWorker()
                val fits = pendingBytes + request.pixelBytes <= maxPendingBytes
                (fits && queue.offer(request)).also { accepted ->
                    if (accepted) {
                        request.reserved = true
                        pendingBytes += request.pixelBytes
                    }
                    if (!accepted) {
                        val now = System.nanoTime()
                        if (lastOverflowWarning == Long.MIN_VALUE || now - lastOverflowWarning >= 60_000_000_000L) {
                            lastOverflowWarning = now
                            warn("OCR 工作队列已满，本次文字识别返回失败", null)
                        }
                    }
                }
            }
        }
        if (!accepted) request.finish(null)
    }

    private fun startWorker() {
        if (!started) {
            started = true
            state.value = OcrEngineStatus.Loading
            worker.start()
        }
    }

    private fun work() {
        var backend: OcrBackend? = null
        try {
            backend = backendFactory()
            backend.warmUp()
            synchronized(lifecycle) {
                if (!closed) state.value = OcrEngineStatus.Ready
            }
            while (!closed) {
                val request = queue.take()
                if (closed) request.finish(null)
                else {
                    try {
                        val result = backend.recognize(request.image).trim()
                        request.finish(if (closed) null else result)
                    } catch (error: Exception) {
                        request.finish(null)
                        if (!closed) throw error
                    } catch (error: LinkageError) {
                        request.finish(null)
                        if (!closed) throw error
                    }
                }
            }
        } catch (error: InterruptedException) {
            if (!closed) fail(error)
        } catch (error: Exception) {
            if (!closed) fail(error)
        } catch (error: LinkageError) {
            if (!closed) fail(error)
        } finally {
            val pending = mutableListOf<Request>()
            synchronized(lifecycle) { queue.drainTo(pending) }
            pending.forEach { it.finish(null) }
            try { backend?.close() } catch (error: Exception) { warn("OCR 模型释放失败", error) }
        }
    }

    private fun fail(error: Throwable) {
        val hint = if (error is LinkageError && System.getProperty("os.name").startsWith("Windows")) {
            "；请安装 Microsoft Visual C++ 2015–2022 x64 运行库：https://aka.ms/vs/17/release/vc_redist.x64.exe"
        } else ""
        state.value = OcrEngineStatus.Failed("OCR 初始化或推理失败：${error.message ?: error.javaClass.simpleName}$hint")
        warn("OCR 引擎不可用", error)
    }

    override fun close() {
        val pending = mutableListOf<Request>()
        val mustJoin = synchronized(lifecycle) {
            if (closed) return
            closed = true
            state.value = OcrEngineStatus.Failed("OCR 引擎已关闭")
            queue.drainTo(pending)
            if (started) worker.interrupt()
            started && Thread.currentThread() !== worker
        }
        pending.forEach { it.finish(null) }
        if (mustJoin) {
            try { worker.join(5_000) } catch (error: InterruptedException) { Thread.currentThread().interrupt() }
            if (worker.isAlive) warn("OCR 正在结束当前原生推理，模型将在推理结束后释放", null)
        }
    }

    private fun complete(callback: (String?) -> Unit, value: String?) {
        // A faulty consumer must not kill the OCR worker or abandon other accepted requests.
        try { callback(value) } catch (error: Exception) { warn("OCR 结果回调失败", error) }
    }

    private inner class Request(val image: OcrImage, val callback: (String?) -> Unit) {
        private val completed = AtomicBoolean()
        val pixelBytes = image.pixels.size.toLong() * Int.SIZE_BYTES
        var reserved = false // protected by lifecycle, including completion on the worker
        fun finish(value: String?) {
            if (completed.compareAndSet(false, true)) {
                synchronized(lifecycle) { if (reserved) { pendingBytes -= pixelBytes; reserved = false } }
                complete(callback, value)
            }
        }
    }
}

internal interface OcrBackend : AutoCloseable {
    fun recognize(image: OcrImage): String
    fun warmUp()
}
