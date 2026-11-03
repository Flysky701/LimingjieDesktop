package com.landosol.toolbox.desktop.app.control

import com.landosol.toolbox.desktop.app.ui.RunConsoleState
import com.landosol.toolbox.desktop.device.DeviceConnectionState
import com.landosol.toolbox.labyrinth.LabyrinthUiState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ControlSessionConsoleTest {
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
