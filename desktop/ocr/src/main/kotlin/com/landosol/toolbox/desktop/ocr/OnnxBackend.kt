package com.landosol.toolbox.desktop.ocr

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.util.logging.Logger
import kotlin.math.max
import kotlin.math.min

/** Native model ownership and inference, confined to the engine's single worker thread. */
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
            // Each active device has its own inference worker and model sessions.
            setIntraOpNumThreads(min(3, max(1, Runtime.getRuntime().availableProcessors() / 2)))
            setInterOpNumThreads(1)
            // Variable game ROIs must not keep the largest activation arena indefinitely.
            setMemoryPatternOptimization(false)
            addConfigEntry("session.intra_op.allow_spinning", "0")
            addConfigEntry("session.inter_op.allow_spinning", "0")
        }
        try {
            detection = environment.createSession(resource("ch_PP-OCRv5_det_mobile.onnx"), options)
            try { recognition = environment.createSession(resource("ch_PP-OCRv5_rec_mobile_ctc.onnx"), options) }
            catch (error: Exception) { detection.close(); throw error }
        } finally { options.close() }
        try {
            val characters = resource("ppocrv5_dict.txt").toString(Charsets.UTF_8).lineSequence().filter { it.isNotEmpty() }.toList()
            dictionary = listOf("") + characters + " "
            check(recognition.metadata.customMetadata["desktop_ctc_classes"]?.toIntOrNull() == dictionary.size) {
                "OCR 模型字符数与字典不一致"
            }
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
        val recognized = recognizeBoxes(image)
        // FB-14: isolated, outlined counters can produce an all-zero detector map.
        // A compact OCR crop gets one recognizer attempt with the unchanged 0.5 threshold.
        // Larger/name/multiline crops stay on the detection path; solid images remain empty.
        if (recognized.isEmpty() && image.width in 32..256 && image.height in 32..256 &&
            image.width * 2 >= image.height && image.height * 2 >= image.width &&
            image.pixels.any { (it and 0xffffff) != (image.pixels[0] and 0xffffff) }) {
            Logger.getLogger(OnnxOcrEngine::class.java.name).fine("OCR 紧凑区域整图识别：${image.width}×${image.height}")
            val direct = recognizeLine(image).first
            if (direct.isNotBlank()) return direct
            // FB-14: contextual pixels at an ROI edge can suppress the detector. Only after
            // both original attempts fail, give it one black border, without changing pixels
            // inside the crop or relaxing either confidence threshold. Do not recurse.
            val margin = min(image.width, image.height) / 4
            val width = image.width + 2 * margin
            val height = image.height + 2 * margin
            val pixels = IntArray(width * height) { -0x1000000 }
            repeat(image.height) { y ->
                image.pixels.copyInto(pixels, (y + margin) * width + margin, y * image.width, (y + 1) * image.width)
            }
            Logger.getLogger(OnnxOcrEngine::class.java.name).fine("OCR 紧凑区域补边检测：${image.width}×${image.height}，边距 $margin")
            return OcrPostprocessing.join(recognizeBoxes(OcrImage(width, height, pixels)))
        }
        return OcrPostprocessing.join(recognized)
    }

    private fun recognizeBoxes(image: OcrImage): List<RecognizedText> = detect(image).mapNotNull { box ->
        val (text, score) = recognizeLine(image.crop(box))
        if (text.isBlank()) null else RecognizedText(box, text, score)
    }

    private fun detect(image: OcrImage): List<TextBox> {
        val input = OcrPreprocessing.detection(image)
        return run(detection, input) { result ->
            val output = result[0] as OnnxTensor
            val shape = output.info.shape
            check(shape.size == 4 && shape[0] == 1L && shape[1] == 1L) { "检测模型输出维度不兼容" }
            OcrPostprocessing.boxes(output.floatBuffer.toFloatArray(), shape[3].toInt(), shape[2].toInt(), image.width, image.height)
        }
    }

    private fun recognizeLine(image: OcrImage): Pair<String, Float> = run(recognition, OcrPreprocessing.recognition(image)) { result ->
        val indices = result.get("ctc_indices").orElseThrow() as OnnxTensor
        val confidence = result.get("ctc_confidence").orElseThrow() as OnnxTensor
        val shape = indices.info.shape
        check(shape.size == 2 && shape[0] == 1L && shape.contentEquals(confidence.info.shape)) { "识别模型输出维度不兼容" }
        val values = indices.longBuffer
        OcrPostprocessing.ctc(IntArray(values.remaining()) { Math.toIntExact(values.get()) }, confidence.floatBuffer.toFloatArray(), dictionary)
    }

    private fun <T> run(session: OrtSession, input: TensorImage, block: (OrtSession.Result) -> T): T {
        OnnxTensor.createTensor(environment, FloatBuffer.wrap(input.values), longArrayOf(1, 3, input.height.toLong(), input.width.toLong())).use { tensor ->
            OrtSession.RunOptions().use { options ->
                options.addRunConfigEntry("memory.enable_memory_arena_shrinkage", "cpu:0")
                session.run(mapOf(session.inputNames.single() to tensor), options).use { result -> return block(result) }
            }
        }
    }

    override fun close() { try { recognition.close() } finally { detection.close() } }
}

/** ORT returns an owned heap copy; reuse its array instead of copying the full output again. */
internal fun FloatBuffer.toFloatArray(): FloatArray {
    if (hasArray() && arrayOffset() == 0 && position() == 0 && remaining() == array().size) return array()
    return FloatArray(remaining()).also { duplicate().get(it) }
}
