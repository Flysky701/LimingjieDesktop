package com.landosol.toolbox.desktop.app

import com.landosol.toolbox.desktop.device.DeviceConnectionState
import com.landosol.toolbox.desktop.ocr.OcrEngineStatus
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO

/** Explicit manual L2 entry point, never run by test/check. Does not enter credentials or tap. */
fun main(args: Array<String>): Unit = runBlocking {
    require(args.size == 3 && args.all(String::isNotBlank)) { "Provide adb path, serial, and isolated probe directory" }
    val data = Path.of(args[2]).also(Files::createDirectories)
    val graph = DesktopAppGraph(data.resolve("graph-data"), startupUpdates = false)
    val report = mutableListOf<String>()
    fun record(line: String) { report += line; println(line) }
    try {
        graph.settings.update { it.copy(adbPath = args[0]) }
        graph.connectDevice(args[1])
        val ready = (graph.device.state.value as? DeviceConnectionState.Ready)?.device ?: error("Device not ready: ${graph.device.state.value}")
        record("Ready Android ${ready.identity.release}/API ${ready.identity.sdkInt}; ${ready.timing}")
        for (pkg in ready.identity.packages.values) record("package=${pkg.name}; version=${pkg.versionName}/${pkg.versionCode}; certificateMd5=${pkg.certificateMd5}")
        val target = "com.bilibili.priconne"
        check(target in ready.identity.packages) { "Bilibili client is not installed" }
        check(graph.device.launch(target))
        delay(15_000)
        record("foreground=${graph.device.queryForeground()}")
        val initial = graph.device.capture()
        BufferedImage(initial.width, initial.height, BufferedImage.TYPE_INT_ARGB).let { image ->
            image.setRGB(0, 0, initial.width, initial.height, initial.pixels, 0, initial.width)
            ImageIO.write(image, "png", data.resolve("private-initial-game.png").toFile())
        }
        val loaded = withTimeout(60_000) { graph.ocr.status.first { it is OcrEngineStatus.Ready || it is OcrEngineStatus.Failed } }
        check(loaded is OcrEngineStatus.Ready) { "OCR not ready: $loaded" }
        graph.startRecognition(dryRun = true).join()
        check(graph.labyrinthEntryRecognitionSession.state.value.dryRun)
        check(graph.labyrinthEntryRecognitionSession.state.value.running) { graph.log.text() }
        val pages = linkedSetOf<String>()
        repeat(20) {
            delay(1000)
            val current = graph.labyrinthEntryRecognitionSession.state.value
            current.lastResult?.observation?.state?.let { pages += it.name }
            check(current.dryRun) { "Probe must remain read-only" }
        }
        val state = graph.labyrinthEntryRecognitionSession.state.value
        record("dryRun=${state.dryRun}; running=${state.running}; frames=${state.frameCount}; pages=$pages; preview=${graph.preview.value != null}")
        graph.preview.value?.let { frame ->
            BufferedImage(frame.width, frame.height, BufferedImage.TYPE_INT_ARGB).let { image ->
                image.setRGB(0, 0, frame.width, frame.height, frame.pixels, 0, frame.width)
                ImageIO.write(image, "png", data.resolve("private-final-game.png").toFile())
            }
        }
    } finally {
        val start = System.nanoTime()
        graph.shutdown()
        record("graphShutdownMillis=${(System.nanoTime() - start) / 1_000_000}")
        Files.writeString(data.resolve("readonly-report.txt"), report.joinToString("\n"))
    }
}
