package com.landosol.toolbox.desktop.shared

import com.landosol.toolbox.automation.AutomationSessionId
import com.landosol.toolbox.automation.overlay.AutomationOverlayPresentation
import com.landosol.toolbox.core.logging.AppLogger
import com.landosol.toolbox.labyrinth.LabyrinthEntryRecognitionSessionState
import com.landosol.toolbox.labyrinth.debug.LabyrinthDebugDashboardServer
import com.landosol.toolbox.labyrinth.vision.LabyrinthAnchorScores
import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryFrameResult
import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryPageObservation
import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryPageState
import com.landosol.toolbox.vision.NativeBitmap
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.util.zip.ZipInputStream

class DebugDashboardBindingTest {
    @Test fun ephemeralPortsAreBoundOnceAndReleasedOnClose() {
        val first=LabyrinthDebugDashboardServer(port=0,allowAddressReuse=false)
        val second=LabyrinthDebugDashboardServer(port=0,allowAddressReuse=false)
        try {
            first.start();second.start()
            val a=requireNotNull(first.boundPort);val b=requireNotNull(second.boundPort)
            assertNotEquals(a,b)
            Socket().use { it.connect(InetSocketAddress("127.0.0.1",a),1000) }
            assertThrows(java.net.BindException::class.java) {
                ServerSocket().use { it.reuseAddress=false;it.bind(InetSocketAddress("127.0.0.1",a)) }
            }
            first.close()
            assertNull(first.boundPort)
            Socket().use { it.connect(InetSocketAddress("127.0.0.1",b),1000) }
        } finally { first.close();second.close() }
    }

    @Test fun logsZipRedactsBothLogsAndKeepsTheTraceAtIncidentCapture() {
        LabyrinthDebugDashboardServer(
            port = 0,
            processLogSource = { "Authorization: Bearer process-fixture\n".encodeToByteArray() },
            userLogSource = { "用户消息 session_key=console-fixture\n".encodeToByteArray() },
        ).use { server ->
            val bitmap = NativeBitmap.create(8, 8)
            try {
                AppLogger.bindTrace("incident-trace-123")
                server.start()
                server.recordIncident(
                    "test incident", bitmap, LabyrinthEntryRecognitionSessionState(),
                    LabyrinthEntryFrameResult(
                        LabyrinthEntryPageObservation(LabyrinthEntryPageState.UNKNOWN, 0.0, emptyMap(), LabyrinthAnchorScores(emptyMap())),
                        emptyList(), 1L,
                    ),
                    AutomationOverlayPresentation(AutomationSessionId(1L), "测试", "等待", dryRun = true),
                )
                AppLogger.bindTrace("export-trace-456")
                val entries = downloadLogs(server)
                assertEquals("traceId=export-trace-456\n", entries.getValue("summary.txt").decodeToString())
                assertEquals("export-trace-456", JSONObject(entries.getValue("manifest.json").decodeToString()).getString("traceId"))
                assertEquals("Authorization: <redacted>\n", entries.getValue("process.log").decodeToString())
                assertEquals("用户消息 session_key=<redacted>\n", entries.getValue("user-console.log").decodeToString())
                assertEquals("incident-trace-123", JSONObject(entries.getValue("incidents/001_test_incident.json").decodeToString())
                    .getString("traceId"))
                assertArrayEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a),
                    entries.getValue("incidents/001_test_incident.png").copyOf(8))
            } finally {
                AppLogger.clearTrace()
                bitmap.recycle()
            }
        }
    }

    @Test fun logsZipDefaultsToAppLoggerAndOmitsAnEmptyConsole() {
        AppLogger.i("DashboardFixture", "shared snapshot token=snapshot-fixture")
        LabyrinthDebugDashboardServer(port = 0, userLogSource = { ByteArray(0) }).use { server ->
            server.start()
            val entries = downloadLogs(server)
            val text = entries.getValue("process.log").decodeToString()
            assertTrue(text.contains("DashboardFixture: shared snapshot token=<redacted>"))
            assertFalse(text.contains("snapshot-fixture"))
            assertFalse(entries.containsKey("user-console.log"))
        }
    }

    private fun downloadLogs(server: LabyrinthDebugDashboardServer): Map<String, ByteArray> {
        val port = requireNotNull(server.boundPort)
        val connection = URI("http://127.0.0.1:$port/logs.zip").toURL().openConnection().apply {
            connectTimeout = 3000
            readTimeout = 3000
        }
        return ZipInputStream(connection.getInputStream()).use { zip ->
            buildMap {
                while (true) {
                    val entry = zip.nextEntry ?: break
                    put(entry.name, zip.readBytes())
                }
            }
        }
    }
}
