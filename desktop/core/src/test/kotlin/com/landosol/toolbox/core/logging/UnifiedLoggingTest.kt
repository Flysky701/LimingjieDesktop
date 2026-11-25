package com.landosol.toolbox.core.logging

import java.io.RandomAccessFile
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class UnifiedLoggingTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun redactsCredentialsAndDpapiInText() {
        val input = listOf(
            "password=pw", "PASSWD: 'quoted pw'", "access_key=ak", "access-token=at", "refresh_token=rt",
            "token=t", "loginId=account", "Authorization: Bearer header-token", "Cookie: sid=one; uid=two",
            "session_key=sk", "secret=\"quoted \\\"secret\\\" tail\"", "DPAPI v1:ABCDEFGHIJKLMNOPQRSTUVWXYZ12+/=",
            "\"password\":\"nested secret Authorization: Bearer another\"",
        )
        val expected = listOf(
            "password=<redacted>", "PASSWD: '<redacted>'", "access_key=<redacted>", "access-token=<redacted>",
            "refresh_token=<redacted>", "token=<redacted>", "loginId=<redacted>", "Authorization: <redacted>",
            "Cookie: <redacted>", "session_key=<redacted>", "secret=\"<redacted>\"", "DPAPI <encrypted-redacted>",
            "\"password\":\"<redacted>\"",
        )
        val result = LogRedactor.redact(input.joinToString("\n"))
        assertEquals(expected.joinToString("\n"), result)
        assertEquals(result, LogRedactor.redact(result))
    }

    @Test fun rollsBeforeTheFileLimitAndKeepsFiveRedactedBackups() {
        val path = temporary.newFolder().toPath()
        val sink = RollingFileLogSink(path)
        val active = path.resolve("app.log")
        val limit = 10L * 1024 * 1024
        sink.write("rotation-0 secret=disk-fixture")
        for (generation in 1..6) {
            RandomAccessFile(active.toFile(), "rw").use { it.setLength(limit - 1) }
            sink.write("rotation-$generation secret=disk-fixture")
        }
        assertEquals("rotation-6 secret=<redacted>\n", Files.readString(active))
        for (backup in 1..5) {
            val file = path.resolve("app.$backup.log")
            assertEquals(limit - 1, Files.size(file))
            val expected = "rotation-${6 - backup} secret=<redacted>\n"
            val prefix = Files.newInputStream(file).use { it.readNBytes(expected.length).decodeToString() }
            assertEquals(expected, prefix)
        }
        assertFalse(Files.exists(path.resolve("app.6.log")))
        assertFalse(sink.snapshot().decodeToString().contains("disk-fixture"))
    }

    @Test fun snapshotCapsLinesAndUtf8Bytes() {
        val lineSink = RollingFileLogSink()
        repeat(4999) { lineSink.write("line-$it") }
        lineSink.write("line-4999\nline-5000\nline-5001")
        val lines = lineSink.snapshot().decodeToString().trimEnd().lines()
        assertEquals(5000, lines.size)
        assertEquals("line-2", lines.first())
        assertEquals("line-5001", lines.last())

        val byteSink = RollingFileLogSink()
        val body = "界".repeat(180_000)
        repeat(5) { byteSink.write("$it $body") }
        val snapshot = byteSink.snapshot()
        assertTrue(snapshot.size <= 2 * 1024 * 1024)
        assertEquals(listOf('2', '3', '4'), snapshot.decodeToString(throwOnInvalidSequence = true)
            .lineSequence().filter(String::isNotEmpty).map { it.first() }.toList())
        byteSink.write("long password=oversize-fixture ${body.repeat(4)}")
        val oversized = byteSink.snapshot()
        assertTrue(oversized.size <= 2 * 1024 * 1024)
        val truncated = oversized.decodeToString(throwOnInvalidSequence = true)
        assertTrue(truncated.startsWith("long password=<redacted> "))
        assertTrue(truncated.endsWith("…\n"))
    }

    @Test fun appLoggerFormatsActiveTraceAndClearsIt() {
        try {
            AppLogger.bindTrace("12345678-full-run-trace")
            assertEquals("12345678-full-run-trace", AppLogger.currentTraceId())
            AppLogger.e("TraceFixture", "trace marker password=message-fixture", IllegalStateException("secret=error-fixture"))
            AppLogger.clearTrace("previous-run-trace")
            assertEquals("12345678-full-run-trace", AppLogger.currentTraceId())
            AppLogger.clearTrace("12345678-full-run-trace")
            assertNull(AppLogger.currentTraceId())
            AppLogger.d("TraceFixture", "clear marker")

            val snapshot = AppLogger.snapshot().decodeToString()
            val lines = snapshot.lines().filter { "TraceFixture" in it }
            val pid = ProcessHandle.current().pid()
            val tid = Thread.currentThread().threadId()
            val prefix = """\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3} $pid $tid"""
            assertTrue(lines[0], Regex("""$prefix E \[trace=12345678-full-run-trace\] TraceFixture: trace marker password=<redacted>.*""").matches(lines[0]))
            assertTrue(snapshot.contains("IllegalStateException: secret=<redacted>"))
            assertFalse(snapshot.contains("message-fixture") || snapshot.contains("error-fixture"))
            assertTrue(lines[1], Regex("$prefix D TraceFixture: clear marker").matches(lines[1]))
        } finally {
            AppLogger.clearTrace()
        }
    }

    @Test fun telemetrySharesOneRedactedEventAndMirrorsItsOwnTraceAndDomain() {
        val first = mutableListOf<TelemetryEvent>()
        val second = mutableListOf<TelemetryEvent>()
        val event = TelemetryEvent(1_700_000_000_000L, "87654321-other-trace", "TelemetryFixture",
            TelemetryLevel.WARNING, "bus marker session_key=event-fixture")
        AppLogger.bindTrace("active-run")
        try {
            RunTelemetryBus.subscribe(first::add).use {
                RunTelemetryBus.subscribe(second::add).use {
                    RunTelemetryBus.publish(event)
                    AppLogger.i("TelemetryFixture", "direct marker")
                }
            }
            val safeEvent = event.copy(message = "bus marker session_key=<redacted>")
            assertEquals(listOf(safeEvent), first)
            assertEquals(listOf(safeEvent), second)
            assertSame(first.single(), second.single())
            assertEquals("active-run", AppLogger.currentTraceId())
            val logged = AppLogger.snapshot().decodeToString().lines().single { "bus marker" in it }
            assertTrue(logged, logged.endsWith("W [trace=87654321-other-trace] TelemetryFixture: ${safeEvent.message}"))
        } finally {
            AppLogger.clearTrace()
        }
    }
}
