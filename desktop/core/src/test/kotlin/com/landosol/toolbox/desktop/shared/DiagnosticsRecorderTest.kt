package com.landosol.toolbox.desktop.shared

import com.landosol.toolbox.automation.AutomationSessionId
import com.landosol.toolbox.automation.overlay.AutomationOverlayPresentation
import com.landosol.toolbox.automation.session.GameSessionResetStage
import com.landosol.toolbox.automation.session.GameSessionResetState
import com.landosol.toolbox.automation.session.GameSessionResetStatus
import com.landosol.toolbox.core.logging.AppLogger
import com.landosol.toolbox.labyrinth.LabyrinthEntryRecognitionSessionState
import com.landosol.toolbox.labyrinth.debug.LabyrinthDiagnosticsRecorder
import com.landosol.toolbox.labyrinth.kernel.CustomPayload
import com.landosol.toolbox.labyrinth.kernel.KernelDomain
import com.landosol.toolbox.labyrinth.kernel.KernelEventEnvelope
import com.landosol.toolbox.labyrinth.kernel.KernelEventLog
import com.landosol.toolbox.labyrinth.vision.LabyrinthAnchorScores
import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryFrameResult
import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryPageObservation
import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryPageState
import com.landosol.toolbox.labyrinth.vision.LabyrinthFastFrameObservation
import com.landosol.toolbox.vision.NativeBitmap
import java.util.zip.ZipInputStream
import javax.imageio.ImageIO
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DiagnosticsRecorderTest {
    private val sessionId = AutomationSessionId(1L)
    private val presentation = AutomationOverlayPresentation(sessionId, "测试", "等待", dryRun = true)
    private val observation = LabyrinthEntryPageObservation(
        LabyrinthEntryPageState.UNKNOWN, 0.0, emptyMap(), LabyrinthAnchorScores(emptyMap()),
    )
    private val result = LabyrinthEntryFrameResult(observation, emptyList(), 1L)

    @Test fun directExportRedactsLogsSnapshotsAndEventsAndRetainsCaptureTrace() {
        val captureTrace = "incident-trace-123"
        var traceId = captureTrace
        val events = KernelEventLog().apply {
            append(KernelEventEnvelope(
                eventSeq = 1L, traceId = captureTrace, causeEventSeq = null, timestampMillis = 123L,
                frameSeq = null, sceneEpoch = 0L, domain = KernelDomain.CUSTOM_HOOK, kind = "fixture",
                reasonCode = "fixture", summary = "password=event-fixture",
                payload = CustomPayload(mapOf("secret" to "payload-fixture")),
            ))
        }
        LabyrinthDiagnosticsRecorder(
            processLogSource = { "Authorization: Bearer process-fixture\n".encodeToByteArray() },
            userLogSource = { "用户消息 session_key=console-fixture\n".encodeToByteArray() },
            traceIdProvider = { traceId },
        ).use { recorder ->
            val bitmap = NativeBitmap.create(8, 8)
            try {
                val state = LabyrinthEntryRecognitionSessionState(message = "token=state-fixture")
                recorder.attachKernelEventSource(events)
                recorder.publish(bitmap, state, result, presentation, nowMillis = 123L)
                recorder.captureIncident("test incident", bitmap, state, result, presentation, nowMillis = 123L)
                traceId = "export-trace-456"
                val entries = export(recorder)
                assertEquals("traceId=$traceId\n", entries.getValue("summary.txt").decodeToString())
                assertEquals(traceId, entries.json("manifest.json").getString("traceId"))
                assertEquals("Authorization: <redacted>\n", entries.getValue("process.log").decodeToString())
                assertEquals("用户消息 session_key=<redacted>\n", entries.getValue("user-console.log").decodeToString())
                for (name in listOf("latest-snapshot.json", "state-history.jsonl", "incidents/001_test_incident.json")) {
                    val snapshot = entries.json(name)
                    assertEquals(captureTrace, snapshot.getString("traceId"))
                    assertEquals("token=<redacted>", snapshot.getString("message"))
                }
                val event = entries.json("kernel/events.ndjson")
                assertEquals(captureTrace, event.getString("traceId"))
                assertEquals("password=<redacted>", event.getString("summary"))
                assertFalse(event.toString().contains("payload-fixture"))
                assertTrue(event.toString().contains("<redacted>"))
            } finally {
                bitmap.recycle()
            }
        }
    }

    @Test fun directExportDefaultsToAppLoggerAndOmitsEmptyConsoleAndDetachedKernel() {
        AppLogger.i("DiagnosticsFixture", "shared snapshot token=snapshot-fixture")
        LabyrinthDiagnosticsRecorder(userLogSource = { ByteArray(0) }).use { recorder ->
            val entries = export(recorder)
            val text = entries.getValue("process.log").decodeToString()
            assertTrue(text.contains("DiagnosticsFixture: shared snapshot token=<redacted>"))
            assertFalse(text.contains("snapshot-fixture"))
            assertFalse(entries.containsKey("user-console.log"))
            assertFalse(entries.containsKey("kernel/events.ndjson"))
            assertEquals("waiting", entries.json("latest-snapshot.json").getString("status"))
        }
    }

    @Test fun incidentRingRetainsTenLosslessPngs() {
        LabyrinthDiagnosticsRecorder(processLogSource = { ByteArray(0) }).use { recorder ->
            val pixels = intArrayOf(0xff123456.toInt(), 0x80234567.toInt(), 0xffa0b0c0.toInt(), 0xff0369ab.toInt())
            val bitmap = NativeBitmap.create(pixels, 2, 2)
            try {
                for (i in 1..11) {
                    recorder.captureIncident("test incident", bitmap, LabyrinthEntryRecognitionSessionState(),
                        result, presentation, nowMillis = i.toLong())
                }
                assertEquals(10, recorder.incidentCount())
                val entries = export(recorder)
                val prefixes = (2..11).map { "incidents/%03d_test_incident".format(it) }
                assertEquals(prefixes.flatMap { listOf("$it.png", "$it.json") }.toSet(),
                    entries.keys.filter { it.startsWith("incidents/") }.toSet())
                val png = ImageIO.read(entries.getValue("${prefixes.first()}.png").inputStream())
                assertEquals(2, png.width)
                assertEquals(2, png.height)
                assertArrayEquals(pixels, png.getRGB(0, 0, 2, 2, null, 0, 2))
            } finally {
                bitmap.recycle()
            }
        }
    }

    @Test fun detailedAndFastHistoriesAreBoundedAndRetainTheirOwnTimestamps() {
        LabyrinthDiagnosticsRecorder(processLogSource = { ByteArray(0) }).use { recorder ->
            val bitmap = NativeBitmap.create(2, 2)
            try {
                val state = LabyrinthEntryRecognitionSessionState(sessionId = sessionId)
                for (frame in 1L..601L) {
                    recorder.publish(bitmap, state.copy(frameCount = frame), result, presentation, nowMillis = frame)
                    recorder.publishFastFrame(LabyrinthFastFrameObservation(observation, frame, frame + 1000, 2, 2, 0), state)
                }
                recorder.publishReset(
                    bitmap, GameSessionResetState(status = GameSessionResetStatus.RUNNING, sessionId = sessionId),
                    result, presentation, nowMillis = 602L,
                )
                val entries = export(recorder)
                val detailed = entries.getValue("state-history.jsonl").decodeToString().trimEnd().lines()
                val fast = entries.getValue("fast/history.ndjson").decodeToString().trimEnd().lines()
                assertEquals(600, detailed.size)
                assertEquals(600, fast.size)
                assertEquals(3L, JSONObject(detailed.first()).getLong("timestamp"))
                assertEquals(1002L, JSONObject(fast.first()).getLong("timestamp"))
                val latest = entries.json("latest-snapshot.json")
                assertEquals(602L, latest.getLong("timestamp"))
                assertEquals(GameSessionResetStage.PENDING.name, latest.getJSONObject("reset").getString("stage"))
                assertEquals(1601L, latest.getJSONObject("fastObservation").getLong("timestamp"))
                assertEquals(0, recorder.incidentCount())
                assertFalse(entries.keys.any { it.endsWith(".jpg") })
            } finally {
                bitmap.recycle()
            }
        }
    }

    private fun Map<String, ByteArray>.json(name: String): JSONObject = JSONObject(getValue(name).decodeToString())

    private fun export(recorder: LabyrinthDiagnosticsRecorder): Map<String, ByteArray> =
        ZipInputStream(recorder.exportDiagnosticZip().inputStream()).use { zip ->
            buildMap {
                while (true) {
                    val entry = zip.nextEntry ?: break
                    put(entry.name, zip.readBytes())
                }
            }
        }
}
