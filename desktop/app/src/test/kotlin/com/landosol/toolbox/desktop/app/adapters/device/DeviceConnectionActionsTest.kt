package com.landosol.toolbox.desktop.app.adapters.device

import com.landosol.toolbox.desktop.device.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test

internal class ConnectionFixture : DeviceService {
    override val state = MutableStateFlow<DeviceConnectionState>(DeviceConnectionState.Disconnected)
    override val foregroundPackage: String? = null
    var connections = 0; var discoveries = 0; var disconnections = 0; var closes = 0
    var onConnect: suspend () -> Unit = {}
    var onDiscover: suspend () -> Unit = {}
    override suspend fun discover(): List<DeviceDescriptor> { discoveries++; onDiscover(); return listOf(DeviceDescriptor("fixture", "Fixture", "device")) }
    override suspend fun connect(serial: String, touchMode: TouchMode) {
        connections++; state.value = DeviceConnectionState.Connecting(serial); onConnect()
        state.value = DeviceConnectionState.Ready(DeviceReady(serial, "Fixture", 1920, 1080, emptyList(), CaptureMode.Raw, true, true,
            DeviceIdentity("fixture", "fixture", "fixture", "9", 28, emptyList(), "", 1920, 1080, emptyMap(), 1)))
    }
    override suspend fun disconnect() { disconnections++; state.value = DeviceConnectionState.Disconnected }
    override fun close() { closes++ }
    override suspend fun capture(): DeviceFrame = error("unused")
    override suspend fun queryForeground(): String? = null
    override suspend fun tap(x: Float, y: Float, expectedPackage: String): TouchResult = error("unused")
    override suspend fun swipe(fromX: Float, fromY: Float, toX: Float, toY: Float, durationMillis: Long, holdMillis: Long, expectedPackage: String): TouchResult = error("unused")
    override suspend fun back(expectedPackage: String): TouchResult = error("unused")
    override suspend fun home() = false
    override suspend fun launch(packageName: String) = false
    override suspend fun forceStop(packageName: String) = false
    override suspend fun uiTree(): List<UiNode> = emptyList()
}

class DeviceConnectionActionsTest {
    @Test fun leavingPageOnlyCancelsWaiterButExplicitCancelStopsApplicationOwnedWork() = runBlocking {
        val device = ConnectionFixture(); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        device.onConnect = { entered.complete(Unit); release.await() }
        val actions = DeviceConnectionActions(device, { "adb" }, { fail(it) })
        try {
            val page = launch { actions.connect("fixture", TouchMode.MaaTouch) }
            entered.await(); page.cancelAndJoin()
            assertEquals(1, device.connections); assertEquals(0, device.disconnections)
            assertNotNull(actions.activity.value)
            actions.connect("fixture", TouchMode.MaaTouch) // Re-entering another page cannot enqueue another attempt.
            assertEquals(1, device.connections)
            release.complete(Unit)
            withTimeout(1000) { while (actions.activity.value != null) delay(5) }
            assertTrue(device.state.value is DeviceConnectionState.Ready)

            val discovering = CompletableDeferred<Unit>()
            device.onDiscover = { discovering.complete(Unit); awaitCancellation() }
            val secondPage = launch { actions.discover() }
            discovering.await(); secondPage.cancelAndJoin()
            assertNotNull(actions.activity.value)
            withTimeout(1000) { actions.cancel() }
            assertNull(actions.activity.value)
            assertTrue("Cancelling discovery must preserve the connected device", device.state.value is DeviceConnectionState.Ready)
            assertEquals(0, device.disconnections)

            val reconnecting = CompletableDeferred<Unit>()
            device.onConnect = { reconnecting.complete(Unit); awaitCancellation() }
            val thirdPage = launch { actions.connect("fixture", TouchMode.MaaTouch, reconnect = true) }
            reconnecting.await(); thirdPage.cancelAndJoin()
            withTimeout(1000) { actions.cancel() }
            assertEquals(DeviceConnectionState.Disconnected, device.state.value)
            assertNull(actions.activity.value)
        } finally { actions.closeAndJoin() }
    }

    @Test fun overlappingRequestsAreNotQueuedAndReadyConnectionIsReused() = runBlocking {
        val device = ConnectionFixture(); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        device.onConnect = { entered.complete(Unit); release.await() }
        val actions = DeviceConnectionActions(device, { "adb" }, { fail(it) })
        var prepares = 0; var ready = 0
        val first = launch { actions.connect("fixture", TouchMode.MaaTouch, beforeConnect = { prepares++ }, onReady = { ready++ }) }
        entered.await()
        actions.connect("fixture", TouchMode.MaaTouch)
        actions.discover(); actions.configure { fail("must not configure during connect") }
        assertNotNull(actions.activity.value); assertEquals(1, device.connections); assertEquals(0, device.discoveries)
        release.complete(Unit); first.join()
        actions.connect("fixture", TouchMode.MaaTouch, beforeConnect = { prepares++ }, onReady = { ready++ })
        assertEquals(1, device.connections); assertEquals(1, prepares); assertEquals(1, ready)
        val connected = device.state.value
        actions.discover()
        assertEquals(connected, device.state.value)
        actions.connect("fixture", TouchMode.Compatibility)
        assertEquals(2, device.connections)
        actions.connect("fixture", TouchMode.Compatibility, reconnect = true)
        assertEquals(3, device.connections); assertEquals(1, device.disconnections)
    }

    @Test fun automaticDiscoveryCachesFailureUntilExplicitRetryOrConfigurationChange() = runBlocking {
        val device = ConnectionFixture(); var path = "first"; val errors = mutableListOf<String>()
        device.onDiscover = { error("请启动模拟器") }
        val actions = DeviceConnectionActions(device, { path }, errors::add)
        repeat(3) { actions.discover(force = false) }
        assertEquals(1, device.discoveries); assertEquals(listOf("请启动模拟器"), errors)
        assertEquals("请启动模拟器", actions.message.value)
        device.onDiscover = {}; actions.discover()
        assertEquals(2, device.discoveries); assertEquals(1, actions.devices.value.size); assertNull(actions.message.value)
        path = "second"; actions.discover(force = false)
        assertEquals(3, device.discoveries)
        actions.configure { }; actions.discover(force = false)
        assertEquals(4, device.discoveries)
    }

    @Test fun failedConnectionReportsOnceAndExplicitRetrySucceeds() = runBlocking {
        val device = ConnectionFixture(); val errors = mutableListOf<String>()
        device.onConnect = { error("设备尚未授权，请允许调试") }
        val actions = DeviceConnectionActions(device, { "adb" }, errors::add)
        actions.connect("fixture", TouchMode.MaaTouch)
        assertEquals(1, errors.size); assertNull(actions.activity.value)
        device.onConnect = {}; actions.connect("fixture", TouchMode.MaaTouch)
        assertTrue(device.state.value is DeviceConnectionState.Ready); assertNull(actions.message.value)
    }

    @Test fun cancelAndCloseStopConnectionOrDiscoveryWithoutBackgroundRetry() = runBlocking {
        val device = ConnectionFixture(); val entered = CompletableDeferred<Unit>()
        device.onConnect = { entered.complete(Unit); awaitCancellation() }
        val actions = DeviceConnectionActions(device, { "adb" }, { fail(it) })
        val first = launch { actions.connect("fixture", TouchMode.MaaTouch) }
        entered.await(); withTimeout(1000) { actions.disconnect(); first.join() }
        assertTrue(first.isCancelled); assertEquals(DeviceConnectionState.Disconnected, device.state.value)
        assertNull(actions.activity.value); assertNull(actions.message.value)
        val discovering = CompletableDeferred<Unit>()
        device.onDiscover = { discovering.complete(Unit); awaitCancellation() }
        val second = launch { actions.discover() }
        discovering.await(); withTimeout(1000) { actions.closeAndJoin(); second.join() }
        assertTrue(second.isCancelled); assertNull(actions.activity.value)
        assertEquals(1, device.connections); assertEquals(1, device.discoveries)
    }
}
