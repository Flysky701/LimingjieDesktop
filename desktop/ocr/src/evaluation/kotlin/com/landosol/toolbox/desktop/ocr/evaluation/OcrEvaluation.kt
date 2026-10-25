package com.landosol.toolbox.desktop.ocr.evaluation

import com.landosol.toolbox.desktop.ocr.OnnxOcrEngine
import com.landosol.toolbox.desktop.ocr.OcrEngineStatus
import com.landosol.toolbox.clanbattle.recognition.PixelImage
import com.landosol.toolbox.gamedata.EventResource
import com.landosol.toolbox.labyrinth.LabyrinthExEncounterCatalog
import com.landosol.toolbox.labyrinth.vision.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import org.json.JSONArray
import org.json.JSONObject
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import kotlin.math.ceil
import kotlin.math.min

/** A separate source set keeps upstream matcher dependencies out of production :ocr. */
fun main(args: Array<String>) = runBlocking {
    val root = Path.of(args[0])
    val reports = Path.of(args[1]).also(Files::createDirectories)
    val manifest = JSONObject(Files.readString(root.resolve("desktop/ocr/evaluation/manifest.json")))
    val cases = manifest.getJSONArray("samples")
    val json = Json { ignoreUnknownKeys = true }
    val events = json.parseToJsonElement(Files.readString(root.resolve("android/app/src/main/assets/resource-packs/cn-bilibili/events.placeholder.json")))
        .jsonObject.getValue("events").jsonArray.map { json.decodeFromJsonElement<EventResource>(it) }
    val eventMatcher = LabyrinthEventTextRecognizer(events)
    val engine = OnnxOcrEngine()
    val results = JSONArray()
    var selfTest = JSONObject()
    try {
        engine.warmUp()
        val loaded = withTimeout(60_000) { engine.status.first { it is OcrEngineStatus.Ready || it is OcrEngineStatus.Failed } }
        check(loaded is OcrEngineStatus.Ready) { "OCR initialization failed: $loaded" }
        val selfImage = ImageIO.read(root.resolve("desktop/ocr/src/main/resources/ocr/self-test.png").toFile())
        val selfExpected = Files.readString(root.resolve("desktop/ocr/src/main/resources/ocr/self-test.txt")).trim()
        val selfActual = recognize(engine, selfImage).first
        selfTest = JSONObject().put("expected", selfExpected).put("actual", selfActual ?: JSONObject.NULL).put("passed", selfActual?.trim() == selfExpected)
        for (index in 0 until cases.length()) {
            val sample = cases.getJSONObject(index)
            val image = ImageIO.read(root.resolve(sample.getString("image")).toFile()) ?: error("Unreadable image: ${sample.getString("image")}")
            val roi = sample.getJSONArray("roi")
            val rect = EntryPixelRect(roi.getInt(0), roi.getInt(1), roi.getInt(2), roi.getInt(3))
            check(rect.left >= 0 && rect.top >= 0 && rect.left + rect.width <= image.width && rect.top + rect.height <= image.height) { "ROI outside image: ${sample.getString("id")}" }
            var crop = image.getSubimage(rect.left, rect.top, rect.width, rect.height)
            if (sample.optBoolean("tightenInk")) crop = tighten(crop)
            val scale = sample.optInt("scale", 1)
            if (scale > 1) crop = scaleNearest(crop, scale)
            val (text, elapsed) = recognize(engine, crop)
            val parsed = if (text == null) null else when (sample.getString("matcher")) {
                "shop-imprint" -> LabyrinthShopItemText.roleImprintLabel(text)
                "series-title" -> internalDetail("isSeriesTitle", text).toString()
                "series-visible" -> (internalDetail("parseVisibleSeries", text) as Map<*, *>).entries.map { "${it.key}:${it.value}" }.sorted().joinToString(";")
                "node-stack" -> {
                    val resolver = AndroidLabyrinthNodeRelicStackResolver(submitTextRead = { _, callback -> callback(null) })
                    resolver.javaClass.methods.single { it.name.startsWith("parseStacks") }.invoke(resolver, text)?.toString()
                }
                "event" -> eventMatcher.recognize(text).let { if (it.trusted) it.event?.id else null }
                "ex" -> LabyrinthExEncounterCatalog.matchObservedName(text)?.id
                else -> error("Unknown matcher")
            }
            val expected = sample.getString("expected")
            val reference = sample.getString("referenceText")
            results.put(JSONObject().put("id", sample.getString("id")).put("kind", sample.getString("kind"))
                .put("expected", expected).put("actual", parsed ?: JSONObject.NULL).put("passed", expected == parsed)
                .put("text", text ?: JSONObject.NULL).put("referenceText", reference).put("characterErrors", distance(reference, text.orEmpty()))
                .put("referenceCharacters", reference.length).put("durationMillis", elapsed))
        }
    } finally { engine.close() }
    val items = (0 until results.length()).map(results::getJSONObject)
    val timings = items.map { it.getDouble("durationMillis") }.sorted()
    fun percentile(value: Double) = timings[(ceil(timings.size * value).toInt() - 1).coerceIn(0, timings.lastIndex)]
    val accuracy = items.count { it.getBoolean("passed") }.toDouble() / items.size
    val byKind = JSONObject()
    val kinds = manifest.getJSONArray("kinds")
    var coverageComplete = items.size >= manifest.getInt("minimumSamples")
    var accuracyPassed = accuracy >= 0.95
    for (i in 0 until kinds.length()) {
        val kind = kinds.getString(i)
        val selected = items.filter { it.getString("kind") == kind }
        val count = selected.size
        val kindAccuracy = selected.count { it.getBoolean("passed") }.toDouble() / count.coerceAtLeast(1)
        if (count < manifest.getInt("minimumPerKind")) coverageComplete = false
        if (count > 0 && kindAccuracy < 0.85) accuracyPassed = false
        byKind.put(kind, JSONObject().put("samples", count).put("accuracy", if (count > 0) kindAccuracy else JSONObject.NULL)
            .put("missingToMinimum", (manifest.getInt("minimumPerKind") - count).coerceAtLeast(0)))
    }
    val report = JSONObject().put("schemaVersion", 1).put("timestamp", java.time.Instant.now().toString())
        .put("samples", items.size).put("accuracy", accuracy).put("accuracyThresholdPassed", accuracyPassed)
        .put("coverageComplete", coverageComplete).put("status", if (!accuracyPassed) "FAILED_ACCURACY" else if (!coverageComplete) "INCOMPLETE_COVERAGE" else "PASSED")
        .put("targetSamples", manifest.getInt("minimumSamples")).put("byKind", byKind)
        .put("p50Millis", percentile(0.5)).put("p95Millis", percentile(0.95))
        .put("characterErrorRate", items.sumOf { it.getInt("characterErrors") }.toDouble() / items.sumOf { it.getInt("referenceCharacters") }.coerceAtLeast(1))
        .put("characterErrorRateDefinition", "Raw transcription edit distance including punctuation/whitespace; diagnostic only")
        .put("selfTest", selfTest).put("results", results)
    Files.writeString(reports.resolve("ocr-eval.json"), report.toString(2))
    Files.writeString(reports.resolve("ocr-eval-diff.tsv"), "id\tkind\tpassed\texpected\tactual\ttext\tdurationMillis\n" + items.joinToString("\n") {
        listOf(it.getString("id"), it.getString("kind"), it.getBoolean("passed"), it.get("expected"), it.get("actual"), it.get("text").toString().replace("\n", "\\n").replace("\t", " "), it.get("durationMillis")).joinToString("\t")
    })
    println("OCR: ${items.count { it.getBoolean("passed") }}/${items.size}, p50=${percentile(.5)} ms, p95=${percentile(.95)} ms; coverageComplete=$coverageComplete; selfTest=${selfTest.optBoolean("passed")}")
    check(accuracyPassed) { "OCR accuracy below SPEC-20 threshold; see ${reports.resolve("ocr-eval.json")}" }
    check(selfTest.optBoolean("passed")) { "Packaged real-image OCR self-test did not match its source transcription" }
}

private fun recognize(engine: OnnxOcrEngine, image: BufferedImage): Pair<String?, Double> {
    val start = System.nanoTime()
    val pixels = image.getRGB(0, 0, image.width, image.height, null, 0, image.width)
    val pending = CompletableFuture<String?>()
    engine.submit(image.width, image.height, pixels) { pending.complete(it) }
    val text = pending.get(30, TimeUnit.SECONDS)
    return text to (System.nanoTime() - start) / 1_000_000.0
}

/** Reflection reaches internal upstream helpers without copying or widening production APIs. */
private fun internalDetail(name: String, text: String): Any {
    val type = Class.forName("com.landosol.toolbox.labyrinth.vision.LabyrinthRelicDetailText")
    return type.getMethod(name, String::class.java).invoke(type.getField("INSTANCE").get(null), text)
}
private fun tighten(image: BufferedImage): BufferedImage {
    val type = Class.forName("com.landosol.toolbox.labyrinth.vision.LabyrinthOcrInkBoundsKt")
    val pixels = PixelImage(image.width, image.height, image.getRGB(0, 0, image.width, image.height, null, 0, image.width))
    val bounds = type.getMethod("labyrinthOcrInkBounds", PixelImage::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
        .invoke(null, pixels, 150, 24, 2, 6) as? EntryPixelRect ?: return image
    return image.getSubimage(bounds.left, bounds.top, bounds.width, bounds.height)
}
private fun scaleNearest(image: BufferedImage, scale: Int) = BufferedImage(image.width * scale, image.height * scale, BufferedImage.TYPE_INT_ARGB).apply {
    createGraphics().let { graphics ->
        try { graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR); graphics.drawImage(image, 0, 0, width, height, null) }
        finally { graphics.dispose() }
    }
}
private fun distance(expected: String, actual: String): Int {
    var previous = IntArray(actual.length + 1) { it }
    for (i in expected.indices) {
        val next = IntArray(actual.length + 1); next[0] = i + 1
        for (j in actual.indices) next[j + 1] = min(min(previous[j + 1] + 1, next[j] + 1), previous[j] + if (expected[i] == actual[j]) 0 else 1)
        previous = next
    }
    return previous.last()
}
