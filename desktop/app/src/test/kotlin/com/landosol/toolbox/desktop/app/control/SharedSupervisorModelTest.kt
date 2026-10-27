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
        var discoveryFailure: String? = null
        val model = SharedSupervisorModel(global, SharedDataEndpoint(12345, "x".repeat(32), "a".repeat(64)),
            WorkerLauncher(scope, { AutoCloseable {} }, listOf("unused")), root, scope,
            object : RuntimeDeviceInventory {
                override suspend fun discover(force: Boolean): List<RuntimeDeviceTarget> {
                    discoveryFailure?.let { error(it) }
                    return targets.toList()
                }
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

    @Test fun phase1Single06ConnectAfterRebootRetiresTheOldSessionWithoutTransferringItsBinding() = runBlocking {
        val f = Fixture()
        try {
            val oldId = f.model.connectDevice("127.0.0.1:5555")
            f.model.bindAccount(oldId, 42)
            f.targets[0] = f.targets[0].copy(deviceId = "new-boot", bootId = "00000000-0000-4000-8000-000000000002")
            val newId = f.model.connectDevice("127.0.0.1:5555")
            assertNotEquals(oldId, newId)
            assertEquals(WorkerConnectionStatus.CLOSED, f.workers.first().connection.value)
            assertEquals(newId, f.model.sessions.value.single().id)
            assertEquals("new-boot", f.model.devices.value.single().id)
            assertNull(f.model.sessions.value.single().boundAccountId)
            assertFalse(f.workers.last().operations.contains(ControlOps.ACCOUNT_SELECT))
            assertTrue(f.global.operations.isEmpty())
        } finally { f.close() }
    }

    @Test fun phase1Single07DiscoveryRetiresOnlyTheSessionWhoseAddressHasANewBoot() = runBlocking {
        val f = Fixture()
        try {
            f.targets += RuntimeDeviceTarget("device-b", "127.0.0.1:5557", "模拟器 B",
                setOf("127.0.0.1:5557"), "00000000-0000-4000-8000-000000000003")
            f.model.connectDevice("127.0.0.1:5555")
            val unaffected = f.model.connectDevice("127.0.0.1:5557")
            f.model.bindAccount(unaffected, 84)
            f.targets[0] = f.targets[0].copy(deviceId = "new-boot", bootId = "00000000-0000-4000-8000-000000000002")
            f.model.refreshDevices(true)
            assertEquals(WorkerConnectionStatus.CLOSED, f.workers.first().connection.value)
            assertEquals(unaffected, f.model.sessions.value.single().id)
            assertEquals(WorkerConnectionStatus.CONNECTED, f.workers.last().connection.value)
            assertEquals(84L, f.model.sessions.value.single().boundAccountId)
            assertEquals(setOf("new-boot", "device-b"), f.model.devices.value.map { it.id }.toSet())
            assertEquals(2, f.workers.size) // Detection must not launch a replacement worker.
        } finally { f.close() }
    }

    @Test fun phase1Single08RebootDetectionPreservesUnsavedDraftsAndBusyTasks() = runBlocking {
        val f = Fixture()
        try {
            val id = f.model.connectDevice("127.0.0.1:5555")
            f.targets[0] = f.targets[0].copy(deviceId = "new-boot", bootId = "00000000-0000-4000-8000-000000000002")
            f.model.markDirty(id, true)
            assertTrue(runCatching { f.model.connectDevice("127.0.0.1:5555") }.isFailure)
            f.model.markDirty(id, false)
            f.workers.single().snapshots.value = buildJsonObject { put("console", buildJsonObject { put("busy", true) }) }
            assertTrue(runCatching { f.model.refreshDevices(true) }.isFailure)
            assertEquals(id, f.model.sessions.value.single().id)
            assertEquals(WorkerConnectionStatus.CONNECTED, f.workers.single().connection.value)
            assertEquals(1, f.workers.size)
        } finally { f.close() }
    }

    @Test fun phase1Single09PreparationPhaseRemainsVisibleUntilTheDeviceIsReady() = runBlocking {
        val f = Fixture()
        try {
            f.gate = CompletableDeferred()
            val connection = async { f.model.connectDevice("127.0.0.1:5555") }
            withTimeout(5_000) { while (f.workers.isEmpty()) yield() }
            f.workers.single().snapshots.value = buildJsonObject {
                put("console", buildJsonObject { put("device", buildJsonObject { put("_type", "Verifying"); put("detail", "核验游戏安装信息") }) })
            }
            withTimeout(5_000) { while (f.model.devices.value.singleOrNull()?.connecting != true) yield() }
            assertEquals("核验游戏安装信息", f.model.devices.value.single().status)
            f.gate!!.complete(Unit)
            connection.await()
            f.workers.single().snapshots.value = buildJsonObject {
                put("console", buildJsonObject { put("device", buildJsonObject { put("_type", "Ready") }) })
            }
            withTimeout(5_000) { while (f.model.devices.value.singleOrNull()?.connected != true) yield() }
            assertFalse(f.model.devices.value.single().connecting)
            assertEquals("已连接", f.model.devices.value.single().status)
            assertEquals(1, f.workers.size)
        } finally { f.close() }
    }

    @Test fun successfulRefreshRemovesMissingRecordsFromMemoryAndSavedInventory() = runBlocking {
        val f = Fixture()
        try {
            f.targets += RuntimeDeviceTarget("device-b", "127.0.0.1:5557", "模拟器 B",
                setOf("127.0.0.1:5557"), "00000000-0000-4000-8000-000000000003")
            f.model.refreshDevices(true)
            f.targets.removeAt(0)
            f.model.refreshDevices(true)
            assertEquals(listOf("device-b"), f.model.devices.value.map { it.id })
            val restored = Fixture(f.root)
            try {
                restored.model.restore()
                assertEquals(listOf("device-b"), restored.model.devices.value.map { it.id })
            } finally { restored.close() }
            f.targets.clear()
            f.model.refreshDevices(true)
            assertTrue(f.model.devices.value.isEmpty())
            val saved = Json.parseToJsonElement(Files.readString(f.root.resolve("device-sessions.json"))).jsonObject
            assertTrue(saved.getValue("devices").jsonArray.isEmpty())
            assertTrue(f.workers.isEmpty())
        } finally { f.close() }
    }

    @Test fun missingIdleSessionClosesOnlyItsWorkerAndKeepsSavedTaskOptions() = runBlocking {
        val f = Fixture()
        try {
            f.targets += RuntimeDeviceTarget("device-b", "127.0.0.1:5557", "模拟器 B",
                setOf("127.0.0.1:5557"), "00000000-0000-4000-8000-000000000003")
            val old = f.model.connectDevice("127.0.0.1:5555")
            f.model.bindAccount(old, 42)
            val options = f.runtimeDirectories.first().resolve("saved-options.fixture")
            Files.createDirectories(options.parent)
            Files.writeString(options, "saved batch goal")
            val online = f.model.connectDevice("127.0.0.1:5557")
            f.targets.removeAt(0)
            f.model.refreshDevices(true)
            assertEquals(WorkerConnectionStatus.CLOSED, f.workers.first().connection.value)
            assertEquals(WorkerConnectionStatus.CONNECTED, f.workers.last().connection.value)
            assertEquals(online, f.model.sessions.value.single().id)
            assertEquals("device-b", f.model.devices.value.single().id)
            assertEquals("saved batch goal", Files.readString(options))
            assertTrue(f.global.operations.isEmpty())
        } finally { f.close() }
    }

    @Test fun failedDiscoveryNeverPrunesRecordsOrClosesSessions() = runBlocking {
        val f = Fixture()
        try {
            val id = f.model.connectDevice("127.0.0.1:5555")
            val saved = Files.readString(f.root.resolve("device-sessions.json"))
            f.targets.clear()
            f.discoveryFailure = "ADB 不可用"
            assertTrue(runCatching { f.model.refreshDevices(true) }.isFailure)
            assertEquals("device-a", f.model.devices.value.single().id)
            assertEquals(id, f.model.sessions.value.single().id)
            assertEquals(WorkerConnectionStatus.CONNECTED, f.workers.single().connection.value)
            assertEquals(saved, Files.readString(f.root.resolve("device-sessions.json")))
        } finally { f.close() }
    }

    @Test fun missingDeviceKeepsDirtyBusyAndConnectingSessionsUntilAnIdleRefresh() = runBlocking {
        val f = Fixture()
        try {
            val id = f.model.connectDevice("127.0.0.1:5555")
            f.targets.clear()
            f.model.markDirty(id, true)
            f.model.refreshDevices(true)
            assertEquals(id, f.model.sessions.value.single().id)
            f.model.markDirty(id, false)
            f.workers.single().snapshots.value = buildJsonObject { put("console", buildJsonObject { put("busy", true) }) }
            f.model.refreshDevices(true)
            assertEquals(id, f.model.sessions.value.single().id)
            f.workers.single().snapshots.value = buildJsonObject {
                put("console", buildJsonObject { put("device", buildJsonObject { put("_type", "Verifying") }) })
            }
            f.model.refreshDevices(true)
            assertEquals("device-a", f.model.devices.value.single().id)
            assertFalse(ControlOps.DEVICE_DISCONNECT in f.workers.single().operations)
            f.workers.single().snapshots.value = JsonObject(emptyMap())
            f.model.refreshDevices(true)
            assertTrue(f.model.sessions.value.isEmpty())
            assertTrue(f.model.devices.value.isEmpty())
        } finally { f.close() }
    }

    @Test fun missingDeviceDuringConnectDoesNotWaitAndThenCloseTheNewConnection() = runBlocking {
        val f = Fixture()
        try {
            f.gate = CompletableDeferred()
            val connecting = async { f.model.connectDevice("127.0.0.1:5555") }
            withTimeout(5_000) { while (f.workers.isEmpty()) yield() }
            f.targets.clear()
            withTimeout(1_000) { f.model.refreshDevices(true) }
            assertEquals(1, f.model.devices.value.size)
            assertEquals(WorkerConnectionStatus.CONNECTED, f.workers.single().connection.value)
            f.gate!!.complete(Unit)
            assertEquals(connecting.await(), f.model.sessions.value.single().id)
            assertFalse(ControlOps.DEVICE_DISCONNECT in f.workers.single().operations)
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
