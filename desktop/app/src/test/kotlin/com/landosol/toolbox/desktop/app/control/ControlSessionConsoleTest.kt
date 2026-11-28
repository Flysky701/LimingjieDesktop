package com.landosol.toolbox.desktop.app.control

import com.landosol.toolbox.core.logging.AppLogger
import com.landosol.toolbox.core.logging.RunTelemetryBus
import com.landosol.toolbox.core.logging.TelemetryEvent
import com.landosol.toolbox.core.logging.TelemetryLevel
import com.landosol.toolbox.desktop.app.UserLog
import com.landosol.toolbox.desktop.app.UserLogKind
import com.landosol.toolbox.desktop.app.UserLogLine
import com.landosol.toolbox.desktop.app.ui.RunConsoleState
import com.landosol.toolbox.desktop.device.DeviceConnectionState
import com.landosol.toolbox.labyrinth.LabyrinthUiState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ControlSessionConsoleTest {
    @Test fun consolesFoldLocallyAndPublishRedactedEventsWithTheirOwnTrace() {
        val first = UserLog { "first-console-trace" }
        val second = UserLog { "second-console-trace" }
        val events = mutableListOf<TelemetryEvent>()
        RunTelemetryBus.subscribe(events::add).use {
            first.add("first console token=first-fixture", kind = UserLogKind.WARNING)
            first.add("first console token=rotated-fixture", kind = UserLogKind.WARNING)
            second.add("second console secret=second-fixture", error = true)
        }

        val firstLine = first.lines.value.single()
        val secondLine = second.lines.value.single()
        assertEquals("first console token=<redacted>", firstLine.text)
        assertEquals(2, firstLine.repeats)
        assertEquals("second console secret=<redacted>", secondLine.text)
        val lines = listOf(firstLine, secondLine)
        assertEquals(lines, ControlModelCodec.decode<List<UserLogLine>>(ControlModelCodec.encode(lines)))
        assertEquals(listOf(firstLine.text, firstLine.text, secondLine.text), events.map { it.message })
        assertEquals(listOf(TelemetryLevel.WARNING, TelemetryLevel.WARNING, TelemetryLevel.ERROR), events.map { it.level })
        assertEquals(listOf("first-console-trace", "first-console-trace", "second-console-trace"), events.map { it.traceId })
        val mirrored = AppLogger.snapshot().decodeToString().lines().filter { "UserLog: " in it }
        assertEquals(2, mirrored.count { it.endsWith("W [trace=first-console-trace] UserLog: ${firstLine.text}") })
        assertEquals(1, mirrored.count { it.endsWith("E [trace=second-console-trace] UserLog: ${secondLine.text}") })
    }

    @Test fun viewsReuseDecodedConsoleAndKeepSessionChangesIsolated() = runBlocking<Unit> {
        val firstPort = ConsolePort("first")
        val secondPort = ConsolePort("second")
        val first = withContext(Dispatchers.Main) { ControlSessionUi(firstPort) }
        val second = withContext(Dispatchers.Main) { ControlSessionUi(secondPort) }
        try {
            awaitConsole(first, "first", false)
            awaitConsole(second, "second", false)
            val before = first.ux.console.value
            // Unrelated transport fields must not decode a fresh console for any view.
            firstPort.snapshots.value = JsonObject(firstPort.snapshots.value + ("revision" to JsonPrimitive(1)))
            withContext(Dispatchers.Main) { yield() }
            assertSame(before, first.ux.console.value)

            firstPort.publish("updated", stopping = true)
            awaitConsole(first, "updated", true)
            awaitConsole(second, "second", false)
            assertNotSame(first.ux.console.value, second.ux.console.value)
        } finally {
            withContext(Dispatchers.Main) { first.close(); second.close() }
        }
    }

    @Test fun malformedConsoleSnapshotPreservesLastValidStateAndRecovers() = runBlocking<Unit> {
        val port = ConsolePort("connected-device")
        val ui = withContext(Dispatchers.Main) { ControlSessionUi(port) }
        try {
            awaitConsole(ui, "connected-device", false)
            val validBefore = ui.ux.console.value

            port.snapshots.value = buildJsonObject {
                put("console", buildJsonObject { put("busy", JsonPrimitive("not-a-boolean")) })
            }
            val errorMessage = withTimeout(5_000) { ui.error.first { it != null } }
            assertNotNull(errorMessage)
            withContext(Dispatchers.Main) {
                assertSame(validBefore, ui.ux.console.value)
                assertEquals(DeviceConnectionState.Connecting("connected-device"), ui.device.state.value)
            }

            port.publish("recovered-device", stopping = true)
            awaitConsole(ui, "recovered-device", true)
            withTimeout(5_000) { ui.error.first { it == null } }
        } finally {
            withContext(Dispatchers.Main) { ui.close() }
        }
    }

    @Test fun awaitSnapshotReturnsMatchedSnapshotAndSurfacesTimeoutWithoutSwallowingCallerCancel() = runBlocking<Unit> {
        val port = ConsolePort("await-test")
        val ui = withContext(Dispatchers.Main) { ControlSessionUi(port) }
        try {
            val matched = async {
                ui.awaitSnapshot("保存超时") { it.console.task.targetScore == 50_000L }
            }
            delay(50)
            port.snapshots.value = buildJsonObject {
                put("console", ControlModelCodec.encode(RunConsoleState(task = LabyrinthUiState(targetScore = 50_000L))))
            }
            assertEquals(50_000L, withTimeout(3_000) { matched.await() }.console.task.targetScore)

            // Timeout must throw IllegalStateException (not CancellationException) so UiActions displays it.
            val timeoutJob = async {
                runCatching { ui.awaitSnapshot("保存结果尚未确认") { it.console.task.targetScore == 99_999L } }
            }
            val timeoutFailure = withTimeout(12_000) { timeoutJob.await() }.exceptionOrNull()
            assertTrue(timeoutFailure is IllegalStateException)
            assertEquals("保存结果尚未确认", timeoutFailure?.message)

            // External caller cancellation still propagates as CancellationException.
            val cancelledJob = launch {
                ui.awaitSnapshot("保存结果尚未确认") { false }
            }
            delay(50)
            cancelledJob.cancel()
            cancelledJob.join()
            assertTrue(cancelledJob.isCancelled)
        } finally {
            withContext(Dispatchers.Main) { ui.close() }
        }
    }

    private suspend fun awaitConsole(ui: ControlSessionUi, serial: String, stopping: Boolean) = withTimeout(5_000) {
        ui.ux.console.first { it.device == DeviceConnectionState.Connecting(serial) && it.stopping == stopping }
        ui.device.state.first { it == DeviceConnectionState.Connecting(serial) }
        ui.labyrinthController.uiState.first { it.message == serial }
        ui.taskStopping.first { it == stopping }
        withContext(Dispatchers.Main) {
            val console = ui.ux.console.value
            assertSame(console.task, ui.labyrinthController.uiState.value)
            assertSame(console.device, ui.device.state.value)
            assertEquals(console.overlay, ui.automationOverlayCoordinator.state.value)
        }
    }

    private class ConsolePort(override val instanceId: String) : ControlSessionPort {
        override val workerRunId = "synthetic-$instanceId"
        override val snapshots = MutableStateFlow(document(instanceId, false))
        override val connection = MutableStateFlow(WorkerConnectionStatus.CONNECTED)
        override val error = MutableStateFlow<String?>(null)
        fun publish(serial: String, stopping: Boolean) { snapshots.value = document(serial, stopping) }
        override suspend fun request(op: String, args: JsonObject): JsonObject = error("No device commands")
        override fun setSelected(selected: Boolean) = Unit
        override suspend fun shutdown() = Unit

        companion object {
            private fun document(serial: String, stopping: Boolean) = buildJsonObject {
                put("console", ControlModelCodec.encode(RunConsoleState(
                    task = LabyrinthUiState(message = serial),
                    device = DeviceConnectionState.Connecting(serial), stopping = stopping,
                )))
            }
        }
    }
}
