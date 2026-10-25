package com.landosol.toolbox.desktop.device

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class AdbDeviceServiceTest {
    @get:Rule val directory = TemporaryFolder()
    private class RecordingTouch : TouchConnection {
        override val header = MaaTouchHeader(10, 1920, 1080, 255, 123)
        override var alive = true
        val gestures = CopyOnWriteArrayList<String>()
        override suspend fun send(commands: String, durationMillis: Long) { gestures += commands }
        override fun close() { alive = false }
    }
    private fun service(fake: FakeAdb, touch: RecordingTouch = RecordingTouch(), events: MutableList<String> = CopyOnWriteArrayList(), lost: MutableList<String> = CopyOnWriteArrayList()) =
        AdbDeviceService(fake, directory.root.toPath(), emptySet(), events::add, lost::add, TouchConnectionFactory { _, _, _ -> touch }, emulatorCommands = { emptyList() })

    @Test fun duplicateConnectionKeepsSessionAndDoesNotBenchmarkOrNotifyLostAgain() = runBlocking {
        val adb = FakeAdb(); val lost = CopyOnWriteArrayList<String>(); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        adb.handler = { call ->
            if (call.arguments == listOf("getprop", "sys.boot_completed")) { entered.complete(Unit); release.await() }
            adb.default(call)
        }
        service(adb, lost = lost).use { device ->
            val first = launch { device.connect("127.0.0.1:5555") }
            entered.await()
            val second = launch { device.connect("127.0.0.1:5555") }
            release.complete(Unit); joinAll(first, second)
            val captures = adb.calls.count { it.screenshot }
            device.connect("127.0.0.1:5555")
            assertEquals(captures, adb.calls.count { it.screenshot })
            assertEquals(1, adb.calls.count { it.arguments == listOf("getprop", "sys.boot_completed") })
            assertTrue(lost.isEmpty())
            assertFalse(adb.calls.any { it.arguments.firstOrNull() == "connect" }) // Already registered transport.
        }
    }

    @Test fun discoveryOnlyConnectsListeningUnregisteredPortsAndKeepsReadyState() = runBlocking {
        val adb = FakeAdb(); val checked = CopyOnWriteArrayList<Int>()
        adb.handler = { call -> if (call.arguments == listOf("devices", "-l")) FakeAdb.success("List of devices attached\n127.0.0.1:5555 device model:Fixture") else adb.default(call) }
        AdbDeviceService(adb, directory.root.toPath(), emptySet(),
            emulatorCommands = { listOf(java.nio.file.Path.of("fixture/ldplayer.exe")) },
            portOpen = { port -> checked += port; port == 5557 }).use { device ->
            device.connect("127.0.0.1:5555", TouchMode.Compatibility)
            val ready = device.state.value
            device.discover()
            assertEquals(ready, device.state.value)
            assertFalse(5555 in checked)
            assertEquals(listOf(listOf("connect", "127.0.0.1:5557")), adb.calls.filter { it.arguments.firstOrNull() == "connect" }.map { it.arguments })
        }
    }

    @Test fun liveForegroundOverridesCacheAndUnknownRejectsTouch() = runBlocking {
        val adb = FakeAdb(); val touch = RecordingTouch()
        service(adb, touch).use { device ->
            device.connect("fixture")
            assertTrue(device.state.value is DeviceConnectionState.Ready)
            assertEquals(FakeAdb.GAME, device.queryForeground())
            adb.window = "mCurrentFocus=null\nmFocusedApp=null"
            assertEquals(TouchResult.Rejected("游戏不在前台"), device.tap(1f, 2f, FakeAdb.GAME))
            assertTrue(touch.gestures.isEmpty())
            adb.window = "mCurrentFocus=Window{123 u0 ${FakeAdb.GAME}/.MainActivity}"
            assertEquals(TouchResult.Completed, device.tap(1f, 2f, FakeAdb.GAME))
            assertEquals(listOf(MaaTouchProtocol.tap(1f, 2f)), touch.gestures)
            assertEquals(TouchResult.Rejected("动作坐标无效"), device.tap(Float.NaN, 2f, FakeAdb.GAME))
            assertEquals(TouchResult.Rejected("动作坐标无效"), device.tap(-1f, 2f, FakeAdb.GAME))
            for (duration in listOf(0L, 5001L)) assertEquals(TouchResult.Rejected("滑动时长超出范围"), device.swipe(0f, 0f, 1f, 1f, duration, 0, FakeAdb.GAME))
            assertEquals(TouchResult.Completed, device.tap(2000f, 1100f, FakeAdb.GAME)) // Preserve upstream out-of-screen pass-through.
            assertEquals(TouchResult.Rejected("滑动时长超出范围"), device.swipe(0f, 0f, 1f, 1f, 1, Long.MAX_VALUE, FakeAdb.GAME))
            device.capture()
            assertEquals(TouchResult.Rejected("触控坐标系与截图尺寸不一致"), device.back(FakeAdb.GAME))
        }
    }
    @Test fun fiveFailedCapturesNotifyLostBeforeReconnectAndNeverResumeTasks() = runBlocking {
        val adb = FakeAdb(); val lost = CopyOnWriteArrayList<String>()
        service(adb, lost = lost).use { device ->
            device.connect("fixture"); adb.failCapture = true
            repeat(5) { try { device.capture(); fail("failure expected") } catch (_: DeviceOperationException) { } }
            assertTrue(device.state.value is DeviceConnectionState.Lost)
            assertEquals(1, lost.size)
            assertNull(device.foregroundPackage)
            device.disconnect()
            assertEquals(DeviceConnectionState.Disconnected, device.state.value)
        }
    }
    @Test fun compatibilityIsExplicitAndRefusesHold() = runBlocking {
        val adb = FakeAdb()
        service(adb).use { device ->
            device.connect("fixture", TouchMode.Compatibility)
            assertEquals(TouchResult.Rejected("兼容模式不支持按住拖拽，请在设置中切换到 MaaTouch"), device.swipe(1f, 2f, 3f, 4f, 100, 1, FakeAdb.GAME))
            assertEquals(TouchResult.Completed, device.swipe(1f, 2f, 3f, 4f, 100, 0, FakeAdb.GAME))
            assertTrue(adb.calls.any { it.arguments == listOf("input", "swipe", "1", "2", "3", "4", "100") })
            assertTrue(device.home())
            assertTrue(adb.calls.any { it.arguments == listOf("input", "keyevent", "3") })
        }
    }
    @Test fun applicationLaunchDetectsExitZeroErrorAndForceStopPolls() = runBlocking {
        val adb = FakeAdb(); val polls = AtomicInteger()
        adb.handler = { call -> when {
            call.arguments.take(3) == listOf("cmd", "package", "resolve-activity") -> FakeAdb.success("priority=0\n${FakeAdb.GAME}/.MainActivity")
            call.arguments.take(2) == listOf("am", "start") -> FakeAdb.success("Error: Activity class does not exist.")
            call.arguments.firstOrNull() == "pidof" -> if (polls.incrementAndGet() == 1) FakeAdb.success("123") else AdbResult.Failure(AdbFailureKind.NonZeroExit, "adb 退出码 1")
            else -> adb.default(call)
        } }
        service(adb).use { device ->
            device.connect("fixture")
            assertFalse(device.launch(FakeAdb.GAME)); assertTrue(device.forceStop(FakeAdb.GAME)); assertEquals(2, polls.get())
        }
    }
    @Test fun uiAutomatorRetriesThreeTimesThenWarnsAndCanRecover() = runBlocking {
        val adb = FakeAdb(); val attempts = AtomicInteger(); val events = CopyOnWriteArrayList<String>()
        adb.handler = { call -> if (call.arguments.firstOrNull() == "uiautomator") {
            attempts.incrementAndGet(); FakeAdb.success("ERROR: could not get idle state")
        } else adb.default(call) }
        service(adb, events = events).use { device ->
            device.connect("fixture")
            assertTrue(device.uiTree().isEmpty()); assertEquals(4, attempts.get()); assertTrue("无法读取渠道登录界面" in events)
            adb.handler = { call -> when (call.arguments) {
                listOf("cat", "/data/local/tmp/ui.xml") -> FakeAdb.success("<hierarchy><node package='p' text='按钮' bounds='[1,2][3,4]'/></hierarchy>")
                else -> adb.default(call)
            } }
            assertEquals("按钮", device.uiTree().single().text)
        }
    }
    @Test fun discoveryDeduplicatesStableDeviceAndPrefersTcp() = runBlocking {
        val adb = FakeAdb()
        adb.handler = { call -> if (call.arguments == listOf("devices", "-l")) FakeAdb.success("List of devices attached\nemulator-5554 device model:Synthetic\n127.0.0.1:5555 device model:Synthetic\nother unauthorized") else adb.default(call) }
        service(adb).use { device ->
            val devices = device.discover()
            assertEquals(listOf("127.0.0.1:5555", "other"), devices.map { it.serial })
        }
    }
    @Test fun unsupportedAndroidFailsAndSleepIsVisibleWithoutWakeCommand() = runBlocking {
        val adb = FakeAdb()
        adb.handler = { call -> if (call.arguments == listOf("getprop", "ro.build.version.sdk")) FakeAdb.success("25") else adb.default(call) }
        service(adb).use { device -> device.connect("fixture"); assertEquals(DeviceConnectionState.Failed("不支持 Android 8 以下的模拟器"), device.state.value) }
        val sleeping = FakeAdb()
        sleeping.handler = { call -> if (call.arguments == listOf("dumpsys", "power")) FakeAdb.success("mWakefulness=Asleep") else sleeping.default(call) }
        service(sleeping).use { device -> device.connect("fixture"); assertFalse((device.state.value as DeviceConnectionState.Ready).device.awake); assertFalse(sleeping.calls.any { it.arguments.firstOrNull() == "input" }) }
    }
    @Test fun identityDoesNotPullUnrecognizedPackagesAndNormalizesNullAndroidId() = runBlocking {
        val adb = FakeAdb()
        adb.handler = { call -> when (call.arguments) {
            listOf("settings", "get", "secure", "android_id") -> FakeAdb.success("null")
            listOf("pm", "list", "packages") -> FakeAdb.success("package:com.unrelated.example")
            else -> adb.default(call)
        } }
        val identity = DeviceIdentityReader(adb, directory.root.toPath(), setOf(FakeAdb.GAME)).read("fixture", 1080 to 1920)
        assertEquals("", identity.androidId); assertEquals(1920, identity.displayWidth); assertEquals(1080, identity.displayHeight)
        assertTrue(identity.packages.isEmpty()); assertFalse(adb.calls.any { it.arguments.firstOrNull() == "pull" })
        assertFalse(Files.exists(directory.root.toPath().resolve("cache/apk")))
    }
    @Test fun disconnectCancelsBootPollingImmediately() = runBlocking {
        val adb = FakeAdb()
        adb.handler = { call -> if (call.arguments == listOf("getprop", "sys.boot_completed")) FakeAdb.success("0") else adb.default(call) }
        service(adb).use { device ->
            val connecting = launch { device.connect("fixture") }
            withTimeout(2000) { while (adb.calls.none { it.arguments == listOf("getprop", "sys.boot_completed") }) delay(10) }
            withTimeout(1000) { device.disconnect(); connecting.join() }
            assertTrue(connecting.isCancelled)
            assertEquals(DeviceConnectionState.Disconnected, device.state.value)
        }
    }
    @Test fun disconnectCancelsDiscoveryWaitingInsideAdb() = runBlocking {
        val adb = FakeAdb(); val entered = CompletableDeferred<Unit>()
        adb.handler = { call -> if (call.arguments == listOf("devices", "-l")) { entered.complete(Unit); awaitCancellation() } else adb.default(call) }
        service(adb).use { device ->
            val discovery = launch { device.discover() }
            entered.await()
            withTimeout(1000) { device.disconnect(); discovery.join() }
            assertTrue(discovery.isCancelled)
            assertEquals(DeviceConnectionState.Disconnected, device.state.value)
        }
    }
    @Test fun failedTouchRestartLosesConnectionAndReconnectDoesNotSendActions() = runBlocking {
        val adb = FakeAdb(); val lost = CopyOnWriteArrayList<String>(); val first = RecordingTouch(); val opens = AtomicInteger()
        AdbDeviceService(adb, directory.root.toPath(), emptySet(), onLost = lost::add, touchFactory = TouchConnectionFactory { _, _, _ ->
            when (opens.incrementAndGet()) { 1 -> first; 2 -> throw DeviceOperationException("synthetic restart failure"); else -> RecordingTouch() }
        }).use { device ->
            device.connect("fixture"); first.alive = false
            withTimeout(3000) { while (device.state.value !is DeviceConnectionState.Lost || lost.isEmpty()) delay(10) }
            assertEquals(TouchResult.Rejected("触控服务未连接"), device.back(FakeAdb.GAME))
            assertEquals(1, lost.size)
            withTimeout(8000) { while (device.state.value !is DeviceConnectionState.Ready) delay(20) }
            assertTrue(first.gestures.isEmpty()); assertEquals(1, lost.size); assertEquals(3, opens.get())
        }
    }
    @Test fun disconnectCancelsInFlightTouchRestart() = runBlocking {
        val first = RecordingTouch(); val restarting = CompletableDeferred<Unit>(); val opens = AtomicInteger()
        AdbDeviceService(FakeAdb(), directory.root.toPath(), emptySet(), touchFactory = TouchConnectionFactory { _, _, _ ->
            if (opens.incrementAndGet() == 1) first else { restarting.complete(Unit); awaitCancellation() }
        }).use { device ->
            device.connect("fixture"); first.alive = false
            withTimeout(3000) { restarting.await() }
            withTimeout(1000) { device.disconnect() }
            assertEquals(DeviceConnectionState.Disconnected, device.state.value)
        }
    }
}
