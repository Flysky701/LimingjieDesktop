package com.landosol.toolbox.desktop.device

import com.landosol.toolbox.desktop.platform.UserResourceLeases

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
        AdbDeviceService(fake, directory.root.toPath(), emptySet(), events::add, lost::add, TouchConnectionFactory { _, _, _ -> touch }, UserResourceLeases(directory.root.toPath().resolve("user-runtime")))

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

    @Test fun discoveryMergesBlankSerialAliasesFiltersOfflineAndKeepsConnectedEndpoint() = runBlocking {
        val adb = FakeAdb()
        adb.handler = { call -> when (call.arguments) {
            listOf("devices", "-l") -> FakeAdb.success("List of devices attached\n127.0.0.1:16416 offline model:Old\n127.0.0.1:16480 device model:Fixture\n127.0.0.1:7555 device model:Fixture")
            listOf("getprop", "ro.serialno") -> FakeAdb.success("")
            else -> adb.default(call)
        } }
        service(adb).use { device ->
            assertEquals(listOf("127.0.0.1:16480"), device.discover().map { it.serial })
            device.connect("127.0.0.1:7555", TouchMode.Compatibility)
            val ready = device.state.value
            repeat(2) {
                val devices = device.discover()
                assertEquals(listOf("127.0.0.1:7555"), devices.map { it.serial })
                assertEquals(setOf("127.0.0.1:16480", "127.0.0.1:7555"), devices.single().aliases)
            }
            assertEquals(ready, device.state.value)
            assertFalse(adb.calls.any { it.arguments.firstOrNull() in setOf("connect", "disconnect", "kill-server") })
            assertFalse(adb.calls.any { it.serial == "127.0.0.1:16416" })
        }
    }

    @Test fun offlineTransportIsDiagnosedWithoutBecomingUsableAndClearsOnNextDiscovery() = runBlocking {
        val adb = FakeAdb()
        val endpoint = "127.0.0.1:16384"
        var state = "offline"
        adb.handler = { call ->
            if (call.arguments == listOf("devices", "-l")) FakeAdb.success("$endpoint $state model:MuMu")
            else adb.default(call)
        }
        service(adb).use { device ->
            assertTrue(device.discover().isEmpty())
            assertTrue(device.discoveryNotice.orEmpty().contains("$endpoint（offline）"))
            assertFalse(adb.calls.any { it.serial == endpoint })
            state = "device"
            assertEquals(endpoint, device.discover().single().serial)
            assertNull(device.discoveryNotice)
        }
    }

    @Test fun differentBootsWithSameModelAndSerialAreNotMergedAndMissingIdentityIsConservative() = runBlocking {
        val adb = FakeAdb(); var readable = true
        adb.handler = { call -> when (call.arguments) {
            listOf("devices", "-l") -> FakeAdb.success("List of devices attached\nfirst device model:Clone\nsecond device model:Clone")
            listOf("cat", "/proc/sys/kernel/random/boot_id") -> if (!readable) AdbResult.Failure(AdbFailureKind.NonZeroExit, "denied")
                else FakeAdb.success(if (call.serial == "first") "10000000-1111-2222-3333-444444444444" else "20000000-1111-2222-3333-444444444444")
            else -> adb.default(call)
        } }
        service(adb).use { device ->
            assertEquals(listOf("first", "second"), device.discover().map { it.serial })
            readable = false
            assertEquals(listOf("first", "second"), device.discover().map { it.serial })
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

    @Test fun screenshotTimeoutVerifiesBootAndUsesBenchmarkedAlternateWithoutSendingInput() = runBlocking {
        val adb = FakeAdb()
        val events = CopyOnWriteArrayList<String>()
        val lost = CopyOnWriteArrayList<String>()
        val touch = RecordingTouch()
        service(adb, touch, events, lost).use { device ->
            device.connect("fixture")
            val primary = (device.state.value as DeviceConnectionState.Ready).device.selectedMode
            val alternate = CaptureMode.entries.single { it != primary }
            val pending = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            adb.calls.clear()
            adb.handler = { call ->
                if (!call.screenshot) adb.default(call)
                else if (("-p" in call.arguments) == (primary == CaptureMode.Png))
                    AdbResult.Failure(AdbFailureKind.Timeout, "synthetic timeout")
                else {
                    pending.complete(Unit)
                    release.await()
                    adb.default(call)
                }
            }
            val capture = async { device.capture() }
            withTimeout(3000) { pending.await() }
            val calls = adb.calls.toList()
            val probes = calls.subList(calls.indexOfFirst { it.screenshot } + 1, calls.indexOfLast { it.screenshot })
            val state = probes.indexOfFirst { it.arguments == listOf("get-state") && it.serial == "fixture" }
            assertTrue(state >= 0)
            assertTrue(probes.drop(state + 1).any { it.arguments == listOf("cat", "/proc/sys/kernel/random/boot_id") && it.serial == "fixture" })
            try {
                assertTrue(withTimeout(1000) { device.tap(1f, 1f, FakeAdb.GAME) } is TouchResult.Rejected)
            } finally { release.complete(Unit) }
            assertEquals(2, capture.await().width)
            assertEquals(alternate, (device.state.value as DeviceConnectionState.Ready).device.selectedMode)
            device.capture()
            assertEquals(3, adb.calls.count { it.screenshot })
            assertTrue(lost.isEmpty())
            assertTrue(touch.gestures.isEmpty())
            assertTrue(events.any { it.contains("synthetic timeout") && it.contains(primary.name) && it.contains("ms") })
            assertTrue(events.any { it.contains("截图已恢复") && it.contains(alternate.name) })
        }
    }

    @Test fun rebootDuringBackupDiscardsFrameAndRequiresNewSession() = runBlocking {
        val adb = FakeAdb()
        val lost = CopyOnWriteArrayList<String>()
        service(adb, lost = lost).use { device ->
            device.connect("fixture")
            val attempts = AtomicInteger()
            adb.handler = { call -> when {
                call.screenshot && attempts.incrementAndGet() == 1 -> AdbResult.Failure(AdbFailureKind.Timeout, "timeout")
                call.arguments == listOf("cat", "/proc/sys/kernel/random/boot_id") && attempts.get() == 2 ->
                    FakeAdb.success("20000000-1111-2222-3333-444444444444")
                else -> adb.default(call)
            } }
            assertTrue(runCatching { device.capture() }.exceptionOrNull() is DeviceOperationException)
            assertEquals(2, attempts.get())
            assertTrue(device.state.value is DeviceConnectionState.Failed)
            assertTrue(lost.single().contains("启动身份已变化"))
        }
    }

    @Test fun timedOutCaptureCannotUseBackupOnUnverifiedDevice() = runBlocking {
        val adb = FakeAdb()
        val lost = CopyOnWriteArrayList<String>()
        service(adb, lost = lost).use { device ->
            device.connect("fixture")
            val attempts = AtomicInteger()
            adb.handler = { call -> when {
                call.screenshot -> { attempts.incrementAndGet(); AdbResult.Failure(AdbFailureKind.Timeout, "timeout") }
                call.arguments == listOf("cat", "/proc/sys/kernel/random/boot_id") && attempts.get() > 0 ->
                    AdbResult.Failure(AdbFailureKind.DeviceOffline, "fixture offline")
                else -> adb.default(call)
            } }
            assertTrue(runCatching { device.capture() }.exceptionOrNull() is DeviceOperationException)
            assertEquals(1, attempts.get())
            assertTrue(lost.single().contains("fixture offline"))
        }
    }

    @Test fun noBenchmarkedBackupRetriesOnlyOriginalAndReportsBothErrors() = runBlocking {
        val adb = FakeAdb()
        val lost = CopyOnWriteArrayList<String>()
        adb.handler = { call -> if (call.screenshot && "-p" in call.arguments) FakeAdb.success(byteArrayOf())
            else adb.default(call) }
        service(adb, lost = lost).use { device ->
            device.connect("fixture")
            val attempts = AtomicInteger()
            adb.calls.clear()
            adb.handler = { call -> if (call.screenshot) {
                if (attempts.incrementAndGet() == 1) AdbResult.Failure(AdbFailureKind.Timeout, "first timeout")
                else FakeAdb.success(byteArrayOf())
            } else adb.default(call) }
            assertTrue(runCatching { device.capture() }.exceptionOrNull() is DeviceOperationException)
            assertEquals(2, attempts.get())
            assertFalse(adb.calls.any { it.screenshot && "-p" in it.arguments })
            assertTrue(lost.single().contains("first timeout"))
            assertTrue(lost.single().contains("Raw 截图头部不完整"))
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
    @Test fun packageProcessCheckUsesOnlyConnectedSerialAndExactGamePackage() = runBlocking {
        val adb = FakeAdb()
        var processResult: AdbResult = FakeAdb.success("NAME\n${FakeAdb.GAME}\n")
        adb.handler = { call -> if (call.arguments.firstOrNull() == "ps") processResult else adb.default(call) }
        AdbDeviceService(adb,directory.root.toPath(),setOf(FakeAdb.GAME),touchFactory=TouchConnectionFactory { _,_,_ -> RecordingTouch() },leases=UserResourceLeases(directory.root.toPath().resolve("user-runtime"))).use { device ->
            device.connect("fixture")
            assertTrue(device.isPackageRunning(FakeAdb.GAME))
            processResult=FakeAdb.success("NAME\n${FakeAdb.GAME}:push\n")
            assertTrue(device.isPackageRunning(FakeAdb.GAME))
            processResult=FakeAdb.success("NAME\n${FakeAdb.GAME}.other\nother.game\n")
            assertFalse(device.isPackageRunning(FakeAdb.GAME))
            processResult=AdbResult.Failure(AdbFailureKind.DeviceOffline,"offline")
            assertTrue(runCatching { device.isPackageRunning(FakeAdb.GAME) }.isFailure)
            processResult=FakeAdb.success("unknown")
            assertTrue(runCatching { device.isPackageRunning(FakeAdb.GAME) }.isFailure)
            assertTrue(runCatching { device.isPackageRunning("other.package") }.isFailure)
            val checks=adb.calls.filter { it.arguments.firstOrNull()=="ps" }
            assertEquals(5,checks.size)
            assertTrue(checks.all { it.serial=="fixture" && it.arguments==listOf("ps","-A","-w","-o","NAME") })
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
            assertEquals(listOf("127.0.0.1:5555"), devices.map { it.serial })
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
        AdbDeviceService(adb, directory.root.toPath(), emptySet(), onLost = lost::add, leases=UserResourceLeases(directory.root.toPath().resolve("user-runtime")), touchFactory = TouchConnectionFactory { _, _, _ ->
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
        AdbDeviceService(FakeAdb(), directory.root.toPath(), emptySet(), leases=UserResourceLeases(directory.root.toPath().resolve("user-runtime")), touchFactory = TouchConnectionFactory { _, _, _ ->
            if (opens.incrementAndGet() == 1) first else { restarting.complete(Unit); awaitCancellation() }
        }).use { device ->
            device.connect("fixture"); first.alive = false
            withTimeout(3000) { restarting.await() }
            withTimeout(1000) { device.disconnect() }
            assertEquals(DeviceConnectionState.Disconnected, device.state.value)
        }
    }
}
