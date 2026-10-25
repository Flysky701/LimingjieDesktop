package com.landosol.toolbox.desktop.ocr

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.FloatBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.math.max
import kotlin.math.min

/** CPU PP-OCRv5 engine. The shared adapter owns pixel copying and dispatches callbacks to Main. */
class OnnxOcrEngine internal constructor(
    private val backendFactory: () -> OcrBackend,
    private val warn: (String, Throwable?) -> Unit,
) : OcrEngine {
    constructor() : this(::OnnxBackend, { text, error -> Logger.getLogger(OnnxOcrEngine::class.java.name).log(Level.WARNING, text, error) })

    private val state = MutableStateFlow<OcrEngineStatus>(OcrEngineStatus.NotLoaded)
    override val status = state.asStateFlow()
    private val queue = ArrayBlockingQueue<Request>(64)
    private val lifecycle = Any()
    private var started = false
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
                queue.offer(request).also { accepted ->
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
        fun finish(value: String?) { if (completed.compareAndSet(false, true)) complete(callback, value) }
    }
}

internal interface OcrBackend : AutoCloseable {
    fun recognize(image: OcrImage): String
    fun warmUp()
}

internal class OnnxBackend : OcrBackend {
    private val environment: OrtEnvironment
    private val detection: OrtSession
    private val recognition: OrtSession
    private val dictionary: List<String>

    init {
        if (System.getProperty("os.name").startsWith("Windows")) {
            listOf("msvcp140.dll", "vcruntime140_1.dll").forEach { name ->
                val library = Path.of(System.getProperty("java.home"), "bin", name)
                if (Files.isRegularFile(library)) System.load(library.toAbsolutePath().toString())
            }
        }
        environment = OrtEnvironment.getEnvironment()
        val options = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(min(4, max(1, Runtime.getRuntime().availableProcessors() / 2)))
            setInterOpNumThreads(1)
        }
        try {
            detection = environment.createSession(resource("ch_PP-OCRv5_det_mobile.onnx"), options)
            try { recognition = environment.createSession(resource("ch_PP-OCRv5_rec_mobile.onnx"), options) }
            catch (error: Exception) { detection.close(); throw error }
        } finally { options.close() }
        try {
            val characters = resource("ppocrv5_dict.txt").toString(Charsets.UTF_8).lineSequence().filter { it.isNotEmpty() }.toList()
            dictionary = listOf("") + characters + " "
        } catch (error: Exception) {
            recognition.close(); detection.close(); throw error
        }
    }

    private fun resource(name: String): ByteArray = checkNotNull(javaClass.getResourceAsStream("/ocr/$name")) { "缺少 OCR 资源 $name" }.use { it.readBytes() }

    override fun warmUp() {
        val blank = OcrImage(64, 48, IntArray(64 * 48) { -1 })
        detect(blank)
        recognizeLine(blank)
    }

    override fun recognize(image: OcrImage): String {
        val recognized = detect(image).mapNotNull { box ->
            val (text, score) = recognizeLine(image.crop(box))
            if (text.isBlank()) null else RecognizedText(box, text, score)
        }
        // FB-14: isolated, outlined counters can produce an all-zero detector map.
        // A compact OCR crop gets one recognizer attempt with the unchanged 0.5 threshold.
        // Larger/name/multiline crops stay on the detection path; solid images remain empty.
        if (recognized.isEmpty() && image.width in 32..256 && image.height in 32..256 &&
            image.width * 2 >= image.height && image.height * 2 >= image.width &&
            image.pixels.any { (it and 0xffffff) != (image.pixels[0] and 0xffffff) }) {
            Logger.getLogger(OnnxOcrEngine::class.java.name).fine("OCR 紧凑区域整图识别：${image.width}×${image.height}")
            return recognizeLine(image).first
        }
        return OcrPostprocessing.join(recognized)
    }

    private fun detect(image: OcrImage): List<TextBox> {
        val input = OcrPreprocessing.detection(image)
        return run(detection, input) { output ->
            val shape = output.info.shape
            check(shape.size == 4 && shape[0] == 1L && shape[1] == 1L) { "检测模型输出维度不兼容" }
            OcrPostprocessing.boxes(output.floatBuffer.toFloatArray(), shape[3].toInt(), shape[2].toInt(), image.width, image.height)
        }
    }

    private fun recognizeLine(image: OcrImage): Pair<String, Float> = run(recognition, OcrPreprocessing.recognition(image)) { output ->
        val shape = output.info.shape
        check(shape.size == 3 && shape[0] == 1L) { "识别模型输出维度不兼容" }
        check(shape[2].toInt() == dictionary.size) { "OCR 模型字符数与字典不一致" }
        OcrPostprocessing.ctc(output.floatBuffer.toFloatArray(), shape[1].toInt(), shape[2].toInt(), dictionary)
    }

    private fun <T> run(session: OrtSession, input: TensorImage, block: (OnnxTensor) -> T): T {
        OnnxTensor.createTensor(environment, FloatBuffer.wrap(input.values), longArrayOf(1, 3, input.height.toLong(), input.width.toLong())).use { tensor ->
            session.run(mapOf(session.inputNames.single() to tensor)).use { result -> return block(result[0] as OnnxTensor) }
        }
    }

    override fun close() { try { recognition.close() } finally { detection.close() } }
}

private fun FloatBuffer.toFloatArray(): FloatArray = FloatArray(remaining()).also { get(it) }
