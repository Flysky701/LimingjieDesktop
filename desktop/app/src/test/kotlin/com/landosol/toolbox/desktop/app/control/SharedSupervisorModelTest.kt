package com.landosol.toolbox.desktop.app.control

import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.FixMethodOrder
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runners.MethodSorters

/** No graph, executable, network, real credentials or emulator. Run single-device cases first. */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class SharedSupervisorModelTest {
    @get:Rule val temporary = TemporaryFolder()

    private class FakeWorker(override val instanceId: String) : WorkerLink {
        override val workerRunId = UUID.randomUUID().toString()
        override val snapshots = MutableStateFlow(JsonObject(emptyMap()))
        override val connection = MutableStateFlow(WorkerConnectionStatus.CONNECTED)
        override val error = MutableStateFlow<String?>(null)
        val operations = CopyOnWriteArrayList<String>()
        var bound: Long? = null
        var taskBusy = false
        var previewSelected = false
        var connectGate: CompletableDeferred<Unit>? = null
        override suspend fun request(op: String, args: JsonObject): JsonObject {
            check(connection.value == WorkerConnectionStatus.CONNECTED)
            operations += op
            when (op) {
                ControlOps.DEVICE_CONNECT -> connectGate?.await()
                ControlOps.ACCOUNT_SELECT -> {
                    check(!taskBusy) { "任务运行中不能换绑" }
                    bound = args["id"]?.jsonPrimitive?.longOrNull
                }
                ControlOps.DEVICE_DISCONNECT -> check(!taskBusy)
                ControlOps.STOP -> taskBusy = false
            }
            return buildJsonObject { put("accepted", true) }
        }
        override fun setSelected(selected: Boolean) { previewSelected = selected }
        override suspend fun shutdown() { connection.value = WorkerConnectionStatus.CLOSED; connectGate?.complete(Unit) }
    }

    private class GlobalPort : ControlSessionPort {
        override val instanceId = "shared"
        override val workerRunId = "host"
        override val snapshots = MutableStateFlow(JsonObject(emptyMap()))
        override val connection = MutableStateFlow(WorkerConnectionStatus.CONNECTED)
        override val error = MutableStateFlow<String?>(null)
        val operations = mutableListOf<String>()
        override suspend fun request(op: String, args: JsonObject): JsonObject {
            operations += op
            error("The supervisor must not use global account/task commands")
        }
        override fun setSelected(selected: Boolean) = Unit
        override suspend fun shutdown() { error("The caller, not the supervisor, owns the host") }
    }

    private inner class Fixture(existingRoot: Path? = null) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val root = existingRoot ?: temporary.newFolder().toPath()
        val global = GlobalPort()
        val targets = mutableListOf(RuntimeDeviceTarget("device-a", "127.0.0.1:5555", "模拟器 A",
            setOf("127.0.0.1:5555", "emulator-5554"), "00000000-0000-4000-8000-000000000001"))
        val workers = CopyOnWriteArrayList<FakeWorker>()
        val runtimeDirectories = CopyOnWriteArrayList<Path>()
        var gate: CompletableDeferred<Unit>? = null
        val model = SharedSupervisorModel(global, SharedDataEndpoint(12345, "x".repeat(32), "a".repeat(64)),
            WorkerLauncher(scope, { AutoCloseable {} }, listOf("unused")), root, scope,
            object : RuntimeDeviceInventory {
                override suspend fun discover(force: Boolean) = targets.toList()
                override suspend fun resolve(serial: String) = targets.first { serial in it.aliases }
            }, RuntimeWorkerFactory { spec ->
                assertTrue(spec.runtimeDirectory.startsWith(root))
                assertNull(spec.boundAccountId)
                runtimeDirectories.add(spec.runtimeDirectory)
                FakeWorker(spec.sessionId).also { it.connectGate = gate; workers += it }
            })
        suspend fun close() { try { model.shutdown() } finally { scope.cancel() } }
    }

    @Test fun phase1Single01ConnectSelectBindAndUnbindNeverStartOrChangeGlobalSelection() = runBlocking {
        val f = Fixture()
        try {
            f.model.restore()
            assertTrue(f.workers.isEmpty())
            val id = f.model.connectDevice("127.0.0.1:5555")
            f.model.selectSession(id)
            f.model.bindAccount(id, 42)
            assertEquals(42L, f.model.sessions.value.single().boundAccountId)
            assertEquals(42L, f.workers.single().bound)
            f.model.bindAccount(id, null)
            assertNull(f.model.sessions.value.single().boundAccountId)
            assertTrue(f.global.operations.isEmpty())
            assertFalse(f.workers.single().operations.contains(ControlOps.START))
            assertTrue(Files.exists(f.root.resolve("device-sessions.json")))
            assertFalse(Files.readString(f.root.resolve("device-sessions.json")).contains("password"))
        } finally { f.close() }
    }

    @Test fun phase1Single02BusyWorkerRejectsBindingAndDisconnectWithoutChangingSession() = runBlocking {
        val f = Fixture()
        try {
            val id = f.model.connectDevice("127.0.0.1:5555")
            f.model.bindAccount(id, 42)
            f.workers.single().taskBusy = true
            assertTrue(runCatching { f.model.bindAccount(id, 84) }.isFailure)
            assertTrue(runCatching { f.model.disconnectDevice(id) }.isFailure)
            assertEquals(42L, f.model.sessions.value.single().boundAccountId)
            assertEquals(WorkerConnectionStatus.CONNECTED, f.workers.single().connection.value)
        } finally { f.close() }
    }

    @Test fun phase1Single03RebootDoesNotReuseOldAccountBinding() = runBlocking {
        val f = Fixture()
        try {
            val id = f.model.connectDevice("127.0.0.1:5555")
            f.model.bindAccount(id, 42)
            f.targets[0] = f.targets[0].copy(deviceId = "new-boot", bootId = "00000000-0000-4000-8000-000000000002")
            f.model.reconnectSession(id)
            assertEquals(WorkerConnectionStatus.CLOSED, f.workers.first().connection.value)
            assertNull(f.model.sessions.value.single().boundAccountId)
            assertFalse(f.workers.last().operations.contains(ControlOps.ACCOUNT_SELECT))
        } finally { f.close() }
    }

    @Test fun phase1Single04ShutdownDuringConnectCleansPublishedWorker() = runBlocking {
        val f = Fixture()
        try {
            f.gate = CompletableDeferred()
            val connect = async { runCatching { f.model.connectDevice("127.0.0.1:5555") } }
            withTimeout(5_000) { while (f.workers.isEmpty()) yield() }
            f.model.shutdown()
            connect.await()
            assertTrue(f.model.sessions.value.isEmpty())
            assertTrue(f.workers.all { it.connection.value == WorkerConnectionStatus.CLOSED })
            assertTrue(runCatching { f.model.connectDevice("127.0.0.1:5555") }.isFailure)
        } finally { f.close() }
    }

    @Test fun phase1Single05ReconnectAndApplicationRestoreKeepVerifiedRuntimeConfiguration() = runBlocking {
        val f = Fixture()
        try {
            val id = f.model.connectDevice("127.0.0.1:5555")
            f.model.bindAccount(id, 42)
            val directory = f.runtimeDirectories.single()
            Files.createDirectories(directory)
            Files.writeString(directory.resolve("saved-task-options.fixture"), "saved batch goal")
            f.model.disconnectDevice(id)
            assertEquals(id, f.model.connectDevice("emulator-5554"))
            assertEquals(directory, f.runtimeDirectories.last())
            assertEquals(42L, f.workers.last().bound)
            f.close()
            val restored = Fixture(f.root)
            try {
                restored.model.restore()
                assertTrue(restored.workers.isEmpty())
                assertEquals(id, restored.model.connectDevice("127.0.0.1:5555"))
                assertEquals(directory, restored.runtimeDirectories.single())
                assertEquals(42L, restored.workers.single().bound)
                assertEquals("saved batch goal", Files.readString(directory.resolve("saved-task-options.fixture")))
                assertFalse(restored.workers.single().operations.contains(ControlOps.START))
            } finally { restored.close() }
        } finally { f.close() }
    }

    @Test fun phase2ConcurrentAliasesProduceOneWorkerAndTwoDevicesRemainIndependent() = runBlocking {
        val f = Fixture()
        try {
            f.targets += RuntimeDeviceTarget("device-b", "127.0.0.1:5557", "模拟器 B",
                setOf("127.0.0.1:5557"), "00000000-0000-4000-8000-000000000003")
            val ids = listOf("127.0.0.1:5555", "emulator-5554").map { serial -> async { f.model.connectDevice(serial) } }.awaitAll()
            assertEquals(ids.first(), ids.last())
            assertEquals(1, f.workers.size)
            val second = f.model.connectDevice("127.0.0.1:5557")
            f.model.bindAccount(ids.first(), 42)
            f.model.bindAccount(second, 84)
            f.model.selectSession(ids.first())
            assertEquals(84L, f.model.sessions.value.first { it.id == second }.boundAccountId)
            f.model.closeSession(ids.first())
            assertEquals(second, f.model.sessions.value.single().id)
            assertEquals(WorkerConnectionStatus.CONNECTED, f.workers.last().connection.value)
            assertEquals(84L, f.workers.last().bound)
            assertFalse(f.workers.last().operations.contains(ControlOps.STOP))
            assertFalse(f.workers.any { ControlOps.START in it.operations })
        } finally { f.close() }
    }
}
