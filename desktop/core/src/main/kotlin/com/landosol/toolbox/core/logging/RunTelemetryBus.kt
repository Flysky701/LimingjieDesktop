package com.landosol.toolbox.core.logging

import java.util.concurrent.CancellationException
import java.util.concurrent.CopyOnWriteArrayList

enum class TelemetryLevel(val label: String) { KEY("关键"), NORMAL("普通"), WARNING("警告"), ERROR("错误") }

data class TelemetryEvent(
    val timestampMillis: Long,
    val traceId: String?,
    val domain: String,
    val level: TelemetryLevel,
    val message: String,
)

object RunTelemetryBus {
    private val listeners = CopyOnWriteArrayList<(TelemetryEvent) -> Unit>()

    fun subscribe(listener: (TelemetryEvent) -> Unit): AutoCloseable {
        val subscription: (TelemetryEvent) -> Unit = { listener(it) }
        listeners.add(subscription)
        return AutoCloseable { listeners.remove(subscription) }
    }

    fun publish(domain: String, level: TelemetryLevel, message: String, traceId: String? = AppLogger.currentTraceId()) {
        publish(TelemetryEvent(System.currentTimeMillis(), traceId, domain, level, message))
    }

    fun publish(event: TelemetryEvent) {
        val safe = event.copy(
            traceId = event.traceId?.let(LogRedactor::redact),
            domain = LogRedactor.redact(event.domain),
            message = LogRedactor.redact(event.message),
        )
        val level = when (safe.level) {
            TelemetryLevel.KEY, TelemetryLevel.NORMAL -> 'I'
            TelemetryLevel.WARNING -> 'W'
            TelemetryLevel.ERROR -> 'E'
        }
        AppLogger.writeRedacted(safe.timestampMillis, safe.traceId, level, safe.domain, safe.message)
        for (listener in listeners) {
            try {
                listener(safe)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                AppLogger.w("RunTelemetryBus", "Telemetry listener failed", failure)
            }
        }
    }
}
