package com.landosol.toolbox.core.logging

import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference

object AppLogger {
    private val sink = RollingFileLogSink()
    private val formatter = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss.SSS", Locale.ROOT)
        .withZone(ZoneId.systemDefault())
    private val pid = ProcessHandle.current().pid()

    // The active run belongs to the process, including its HTTP and background threads.
    private val activeTraceId = AtomicReference<String?>(null)

    fun initialize(path: Path) = sink.initialize(path)
    fun snapshot(): ByteArray = sink.snapshot()
    fun redact(text: String): String = LogRedactor.redact(text)

    fun bindTrace(traceId: String) { activeTraceId.set(traceId) }
    fun currentTraceId(): String? = activeTraceId.get()
    fun clearTrace() { activeTraceId.set(null) }
    fun clearTrace(traceId: String) {
        activeTraceId.updateAndGet { current -> current.takeUnless { it == traceId } }
    }

    fun v(tag: String, msg: String, tr: Throwable? = null): Int = write('V', tag, msg, tr)
    fun d(tag: String, msg: String, tr: Throwable? = null): Int = write('D', tag, msg, tr)
    fun i(tag: String, msg: String, tr: Throwable? = null): Int = write('I', tag, msg, tr)
    fun w(tag: String, msg: String, tr: Throwable? = null): Int = write('W', tag, msg, tr)
    fun e(tag: String, msg: String, tr: Throwable? = null): Int = write('E', tag, msg, tr)

    private fun write(level: Char, tag: String, message: String, error: Throwable?): Int {
        val text = message + (error?.let { "\n${it.stackTraceToString()}" } ?: "")
        val traceId = currentTraceId()?.let(LogRedactor::redact)
        sink.write(format(System.currentTimeMillis(), traceId, level, tag, text))
        return 0
    }

    internal fun writeRedacted(timestampMillis: Long, traceId: String?, level: Char, tag: String, message: String) {
        sink.writeRedacted(format(timestampMillis, traceId, level, tag, message))
    }

    private fun format(timestampMillis: Long, traceId: String?, level: Char, tag: String, message: String): String {
        val timestamp = formatter.format(Instant.ofEpochMilli(timestampMillis))
        val trace = traceId?.let { " [trace=$it]" }.orEmpty()
        return "$timestamp $pid ${Thread.currentThread().threadId()} $level$trace $tag: $message"
    }
}
