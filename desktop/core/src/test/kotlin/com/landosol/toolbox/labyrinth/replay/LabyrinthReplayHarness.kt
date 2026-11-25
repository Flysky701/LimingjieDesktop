package com.landosol.toolbox.labyrinth.replay

import com.landosol.toolbox.vision.PixelImage
import com.landosol.toolbox.labyrinth.node.AndroidLabyrinthNodeTemplateLoader
import com.landosol.toolbox.labyrinth.node.NodeTemplateSet
import com.landosol.toolbox.labyrinth.vision.AndroidLabyrinthEntryTemplateLoader
import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryFrameProcessor
import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryFrameResult
import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryPageState
import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryTemplateSet
import java.io.File

/**
 * One replayed frame: the recognition result plus what the recorded history said at the time.
 * `recorded` is null when the frame came from a bare screenshot rather than a debug bundle.
 */
data class LabyrinthReplayFrame(
    val source: String,
    val result: LabyrinthEntryFrameResult,
    val wallMillis: Long,
    val recorded: LabyrinthRecordedFrame? = null,
)

/** The subset of a `history.ndjson` entry the harness compares against. */
data class LabyrinthRecordedFrame(
    val page: String,
    val elapsedMillis: Long,
    val nodeSearchMode: String?,
    val nodeSearchWindowCount: Int?,
    val archivedFrame: String?,
    val actionLabel: String?,
    val actionRejectReason: String?,
)

data class LabyrinthReplaySummary(
    val totalFrames: Int,
    val unknownFrames: Int,
    val pageDisagreements: Int,
    val avgRecognitionMillis: Long,
    val p95RecognitionMillis: Long,
    val nodeSearchWindowCounts: List<Int>,
    val nodeSearchModes: Map<String, Int>,
) {
    fun report(): String = buildString {
        appendLine("totalFrames=$totalFrames")
        appendLine("unknownFrames=$unknownFrames")
        appendLine("pageDisagreements=$pageDisagreements")
        appendLine("avgRecognitionMs=$avgRecognitionMillis")
        appendLine("p95RecognitionMs=$p95RecognitionMillis")
        appendLine("nodeSearchModes=$nodeSearchModes")
        if (nodeSearchWindowCounts.isNotEmpty()) {
            val sorted = nodeSearchWindowCounts.sorted()
            val p95 = sorted[(sorted.size * 0.95).toInt().coerceAtMost(sorted.lastIndex)]
            appendLine(
                "nodeSearchWindows: n=${sorted.size} min=${sorted.first()} " +
                    "p50=${sorted[sorted.size / 2]} p95=$p95 max=${sorted.last()}",
            )
        }
    }
}

/**
 * Offline replay of the recognition pipeline. Runs the same [LabyrinthEntryFrameProcessor] the
 * app uses over stored frames and never touches an action executor, so recognition changes can
 * be compared against recorded runs without replaying the game.
 *
 * Two inputs are supported:
 * - a debug bundle directory (unzipped `limingjie-debug-*.zip`) whose `state/history.ndjson`
 *   entries carry an `archivedFrame` name that exists under `frames/`;
 * - a plain list of screenshot files, replayed without recorded expectations.
 */
class LabyrinthReplayHarness(
    private val processorFactory: () -> LabyrinthEntryFrameProcessor,
    private val readImage: (File) -> PixelImage,
) {
    fun replayScreenshots(files: List<File>): List<LabyrinthReplayFrame> {
        val processor = processorFactory()
        return files.map { file ->
            val frame = readImage(file)
            val started = System.nanoTime()
            val result = processor.process(frame)
            LabyrinthReplayFrame(file.name, result, (System.nanoTime() - started) / 1_000_000)
        }
    }

    fun replayBundle(bundleDirectory: File): List<LabyrinthReplayFrame> {
        val history = File(bundleDirectory, "state/history.ndjson")
        require(history.isFile) { "missing ${history.absolutePath}" }
        val framesDirectory = File(bundleDirectory, "frames")
        val processor = processorFactory()
        return history.readLines().filter(String::isNotBlank).mapNotNull { line ->
            val recorded = parseRecordedFrame(line) ?: return@mapNotNull null
            val archived = recorded.archivedFrame?.let { File(framesDirectory, it) }
                ?.takeIf(File::isFile)
                ?: return@mapNotNull null
            val frame = readImage(archived)
            val started = System.nanoTime()
            val result = processor.process(frame)
            LabyrinthReplayFrame(archived.name, result, (System.nanoTime() - started) / 1_000_000, recorded)
        }
    }

    fun summarize(frames: List<LabyrinthReplayFrame>): LabyrinthReplaySummary {
        val elapsed = frames.map(LabyrinthReplayFrame::wallMillis).sorted()
        val windows = frames.filter { it.result.nodeSearchMode != "none" }.map { it.result.nodeSearchWindowCount }
        return LabyrinthReplaySummary(
            totalFrames = frames.size,
            unknownFrames = frames.count { it.result.observation.state == LabyrinthEntryPageState.UNKNOWN },
            pageDisagreements = frames.count { frame ->
                frame.recorded != null && frame.recorded.page != frame.result.observation.state.name
            },
            avgRecognitionMillis = if (elapsed.isEmpty()) 0 else elapsed.sum() / elapsed.size,
            p95RecognitionMillis = if (elapsed.isEmpty()) {
                0
            } else {
                elapsed[(elapsed.size * 0.95).toInt().coerceAtMost(elapsed.lastIndex)]
            },
            nodeSearchWindowCounts = windows,
            nodeSearchModes = frames.groupingBy { it.result.nodeSearchMode }.eachCount(),
        )
    }

    companion object {
        /** Minimal line parser: the harness only needs a handful of scalar fields. */
        fun parseRecordedFrame(line: String): LabyrinthRecordedFrame? {
            fun string(key: String): String? =
                Regex("\"$key\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(line)?.groupValues?.get(1)
            fun long(key: String): Long? =
                Regex("\"$key\"\\s*:\\s*(-?\\d+)").find(line)?.groupValues?.get(1)?.toLongOrNull()
            val page = string("page") ?: return null
            return LabyrinthRecordedFrame(
                page = page,
                elapsedMillis = long("elapsedMillis") ?: 0L,
                nodeSearchMode = string("nodeSearchMode"),
                nodeSearchWindowCount = long("nodeSearchWindowCount")?.toInt(),
                archivedFrame = string("archivedFrame"),
                actionLabel = string("actionLabel"),
                actionRejectReason = string("actionRejectReason"),
            )
        }

        /**
         * Build the same entry template set the app loads, from the checked-in asset directory.
         * Optional templates that are absent on disk are skipped exactly like the Android loader.
         */
        fun entryTemplateSet(assetRoot: File, readImage: (File) -> PixelImage): LabyrinthEntryTemplateSet {
            fun file(path: String) = File(assetRoot, path.removePrefix(AndroidLabyrinthEntryTemplateLoader.ROOT + "/"))
            val required = AndroidLabyrinthEntryTemplateLoader.TEMPLATE_PATHS
                .mapValues { (_, path) -> readImage(file(path)) }
            val optional = AndroidLabyrinthEntryTemplateLoader.OPTIONAL_TEMPLATE_PATHS
                .mapNotNull { (id, path) -> file(path).takeIf(File::isFile)?.let { id to readImage(it) } }
                .toMap()
            return LabyrinthEntryTemplateSet(required + optional)
        }

        fun nodeTemplateSet(assetRoot: File, readImage: (File) -> PixelImage): NodeTemplateSet =
            NodeTemplateSet(
                AndroidLabyrinthNodeTemplateLoader.TEMPLATE_PATHS.mapValues { (_, path) ->
                    readImage(File(assetRoot, path.removePrefix(AndroidLabyrinthNodeTemplateLoader.ROOT + "/")))
                },
            )

        /** Default processor: entry anchors plus node templates, no character or relic matchers. */
        fun defaultProcessor(assetRoot: File, readImage: (File) -> PixelImage): LabyrinthEntryFrameProcessor =
            LabyrinthEntryFrameProcessor(
                templates = entryTemplateSet(assetRoot, readImage),
                nodeTemplates = nodeTemplateSet(assetRoot, readImage),
            )
    }
}
