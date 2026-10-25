package com.landosol.toolbox.desktop.device

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.xml.sax.SAXException
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

class AdbDeviceService(
    private val adb: AdbExecutor,
    private val dataDirectory: Path,
    private val gamePackages: Set<String>,
    private val onEvent: (String) -> Unit = {},
    private val onLost: (String) -> Unit = {},
    private val touchFactory: TouchConnectionFactory = MaaTouchConnection,
) : DeviceService {
    private class Session(val serial: String, val touchMode: TouchMode, val capturer: ScreenCapturer, var ready: DeviceReady, val bootId: String, @Volatile var touch: TouchConnection?) {
        var failures = 0
        var failureSince = 0L
        var lastCaptureCompleted = 0L
        @Volatile var frameSize = ready.width to ready.height
        var touchRestarted = false
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lifecycle = Mutex()
    private val closed = AtomicBoolean()
    private val connectingJobs = ConcurrentHashMap.newKeySet<Job>()
    private val mutableState = MutableStateFlow<DeviceConnectionState>(DeviceConnectionState.Disconnected)
    override val state: StateFlow<DeviceConnectionState> = mutableState.asStateFlow()
    private val identities = DeviceIdentityReader(adb, dataDirectory, gamePackages)
    @Volatile private var session: Session? = null
    @Volatile private var cachedForeground: String? = null
    @Volatile private var foregroundUpdated = 0L
    private var monitor: Job? = null
    private var reconnect: Job? = null
    override val foregroundPackage: String? get() = cachedForeground.takeIf { session != null && System.nanoTime() - foregroundUpdated <= 2_000_000_000L }

    override suspend fun discover(): List<DeviceDescriptor> {
        check(!closed.get()) { "设备服务已关闭" }
        var listed = DeviceParsers.devices(adb.execute(listOf("devices", "-l")).requireSuccess().text())
        val commands = AdbLocator.emulatorCommands()
        val mumu = commands.filter { Regex("mumu|nemu", RegexOption.IGNORE_CASE).containsMatchIn(it.toString()) }
        val ld = commands.filter { Regex("ldplayer|dnplayer|ldvbox|leidian", RegexOption.IGNORE_CASE).containsMatchIn(it.toString()) }
        suspend fun manager(paths: List<Path>, name: String, arguments: List<String>): String? {
            val candidate = paths.flatMap { generateSequence(it.parent) { parent -> parent.parent }.take(3).toList() }
                .asSequence().map { it.resolve(name) }.firstOrNull(Files::isRegularFile) ?: return null
            return ProcessAdbExecutor(candidate.toString()).use { runner -> (runner.execute(arguments, timeoutMillis = 5000) as? AdbResult.Success)?.text() }
        }
        val ports = mutableSetOf<Int>()
        if (mumu.isNotEmpty()) {
            val found = manager(mumu, "MuMuManager.exe", listOf("info", "-v", "all"))?.let(DeviceParsers::mumuPorts).orEmpty()
            ports += found.ifEmpty { (0..7).map { 16384 + 32 * it } + 7555 }
        }
        if (ld.isNotEmpty()) {
            val found = manager(ld, "ldconsole.exe", listOf("list2"))?.let(DeviceParsers::ldplayerIndices)
            ports += (found ?: (0..7).toList()).map { 5555 + 2 * it }
        }
        coroutineScope {
            ports.filter { port -> listed.none { it.serial == "127.0.0.1:$port" && it.state == "device" } }.map { port ->
                async { adb.execute(listOf("connect", "127.0.0.1:$port"), timeoutMillis = 3000) }
            }.awaitAll()
        }
        if (ports.isNotEmpty()) listed = DeviceParsers.devices(adb.execute(listOf("devices", "-l")).requireSuccess().text())
        val deduplicated = linkedMapOf<String, DeviceDescriptor>()
        for (device in listed) {
            val serialNo = if (device.state == "device") shellText(device.serial, "getprop", "ro.serialno") else null
            val bootId = if (device.state == "device") shellText(device.serial, "cat", "/proc/sys/kernel/random/boot_id") else null
            val key = if (!serialNo.isNullOrBlank() && !bootId.isNullOrBlank()) "$serialNo\u0000$bootId" else device.serial
            val previous = deduplicated[key]
            if (previous == null || (':' in device.serial && ':' !in previous.serial)) deduplicated[key] = device
        }
        return deduplicated.values.toList()
    }

    override suspend fun connect(serial: String, touchMode: TouchMode): Unit = coroutineScope {
        val operation = currentCoroutineContext().job
        connectingJobs.add(operation)
        try { lifecycle.withLock {
        check(!closed.get()) { "设备服务已关闭" }
        require(serial.isNotBlank() && serial.none { it.isWhitespace() || it == '\u0000' }) { "设备地址无效" }
        reconnect?.cancel(); reconnect = null
        stopSession("切换设备，任务已停止")
        mutableState.value = DeviceConnectionState.Connecting(serial)
        try {
            activate(establish(serial, touchMode, true))
        } catch (cancelled: CancellationException) { mutableState.value = DeviceConnectionState.Disconnected; throw cancelled }
        catch (error: Exception) { mutableState.value = DeviceConnectionState.Failed(error.message ?: "连接模拟器失败") }
        } } finally { connectingJobs.remove(operation) }
    }

    private suspend fun establish(serial: String, touchMode: TouchMode, initial: Boolean): Session {
        if (':' in serial) adb.execute(listOf("connect", serial), timeoutMillis = 3000).requireSuccess()
        val bootCompleted = withTimeoutOrNull(120_000) {
            if (initial) mutableState.value = DeviceConnectionState.Verifying(serial, "模拟器启动中")
            while (adb.shell(serial, listOf("getprop", "sys.boot_completed")).requireSuccess().text().trim() != "1") delay(2000)
            true
        } ?: false
        if (!bootCompleted) throw DeviceOperationException("模拟器未完成启动")
        val sdk = adb.shell(serial, listOf("getprop", "ro.build.version.sdk")).requireSuccess().text().trim().toIntOrNull()
            ?: throw DeviceOperationException("无法读取 Android 版本")
        if (sdk < 26) throw DeviceOperationException("不支持 Android 8 以下的模拟器")
        if (initial) mutableState.value = DeviceConnectionState.Verifying(serial, "读取设备信息并测试截图")
        val size = DeviceParsers.screenSize(adb.shell(serial, listOf("wm", "size")).requireSuccess().text())
        val bootId = adb.shell(serial, listOf("cat", "/proc/sys/kernel/random/boot_id")).requireSuccess().text().trim()
        val identity = identities.read(serial, size)
        val awake = DeviceParsers.awake(adb.shell(serial, listOf("dumpsys", "power")).requireSuccess().text())
        val capturer = ScreenCapturer(adb, serial)
        val (timing, selected) = capturer.benchmark()
        timing.forEach { emit("截图 ${it.mode}：p50 ${it.p50Millis} ms，p95 ${it.p95Millis} ms") }
        if (timing.any { it.p95Millis > 600 }) emit("截图耗时较长，可尝试调整模拟器渲染模式或关闭高帧率")
        if (maxOf(size.first, size.second) != 1920 || minOf(size.first, size.second) != 1080) emit("上游只在 1920×1080 下验证过")
        if (!awake) emit("模拟器屏幕已休眠，请先在模拟器中唤醒")
        if (initial) mutableState.value = DeviceConnectionState.Verifying(serial, "连接触控服务")
        val touch = if (touchMode == TouchMode.MaaTouch) touchFactory.open(adb, serial, dataDirectory) else null
        val ready = DeviceReady(serial, identity.model.ifBlank { serial }, size.first, size.second, timing, selected, true, awake, identity)
        return Session(serial, touchMode, capturer, ready, bootId, touch)
    }

    private fun activate(value: Session) {
        session = value
        cachedForeground = null; foregroundUpdated = 0
        mutableState.value = DeviceConnectionState.Ready(value.ready)
        monitor = scope.launch {
            var iteration = 0
            while (isActive && session === value) {
                queryForeground()
                if (iteration++ % 4 == 0) {
                    val status = adb.execute(listOf("get-state"), value.serial, timeoutMillis = 3000)
                    if (status is AdbResult.Failure && status.kind == AdbFailureKind.ServerVersionConflict) { lose(value, status.detail, retry = false); break }
                    if (status !is AdbResult.Success || status.text().trim() != "device") { lose(value, "设备连接已断开，任务已停止"); break }
                    val bootId = shellText(value.serial, "cat", "/proc/sys/kernel/random/boot_id")
                    if (bootId != null && bootId != value.bootId) { lose(value, "模拟器已重启，任务已停止"); break }
                    val power = shellText(value.serial, "dumpsys", "power")
                    if (power != null && session === value) {
                        val awake = DeviceParsers.awake(power)
                        if (awake != value.ready.awake) {
                            value.ready = value.ready.copy(awake = awake)
                            mutableState.value = DeviceConnectionState.Ready(value.ready)
                            if (!awake) emit("模拟器屏幕已休眠，请先在模拟器中唤醒")
                        }
                    }
                }
                if (value.touchMode == TouchMode.MaaTouch && value.touch?.alive != true) recoverTouch(value)
                delay(500)
            }
        }
    }

    private suspend fun recoverTouch(value: Session) = lifecycle.withLock {
        if (session !== value) return@withLock
        if (value.touchRestarted) { lose(value, "触控服务已断开，任务已停止"); return@withLock }
        value.touchRestarted = true
        value.touch?.close(); value.touch = null
        try { value.touch = touchFactory.open(adb, value.serial, dataDirectory) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { lose(value, "触控服务已断开，任务已停止") }
    }

    private fun lose(value: Session, reason: String, retry: Boolean = true) {
        if (session !== value || !mutableState.compareAndSet(DeviceConnectionState.Ready(value.ready), DeviceConnectionState.Lost(reason))) return
        session = null; cachedForeground = null; foregroundUpdated = 0
        // The application owns task cancellation. Notify synchronously before any reconnect work.
        notifyLost(reason)
        value.touch?.close()
        monitor?.cancel()
        if (!retry) { mutableState.value = DeviceConnectionState.Failed(reason); return }
        reconnect = scope.launch {
            repeat(12) { index ->
                delay(5000)
                mutableState.value = DeviceConnectionState.Reconnecting(index + 1)
                try {
                    lifecycle.withLock {
                        ensureActive()
                        val connected = establish(value.serial, value.touchMode, false)
                        activate(connected)
                    }
                    emit("设备已重新连接；任务保持停止，请手动启动")
                    return@launch
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) {
                    if ((error as? DeviceOperationException)?.adbFailure == AdbFailureKind.ServerVersionConflict) {
                        mutableState.value = DeviceConnectionState.Failed(error.message ?: "ADB 版本冲突")
                        return@launch
                    }
                    if (index == 11) mutableState.value = DeviceConnectionState.Failed(error.message ?: "设备重连失败")
                }
            }
        }
    }

    override suspend fun disconnect() {
        connectingJobs.toList().forEach { it.cancel() }
        reconnect?.cancel()
        monitor?.cancel()
        lifecycle.withLock {
            reconnect = null
            stopSession("设备已断开，任务已停止")
            mutableState.value = DeviceConnectionState.Disconnected
        }
    }

    private suspend fun stopSession(reason: String) {
        val previous = session
        session = null; cachedForeground = null; foregroundUpdated = 0
        if (previous != null) notifyLost(reason)
        monitor?.cancel(); monitor = null
        previous?.touch?.close()
    }

    override suspend fun capture(): DeviceFrame {
        val value = session ?: throw DeviceOperationException("设备未连接")
        val started = System.nanoTime()
        // A gap in calls means the screenshot consumer is idle; do not count idle time as outage.
        if (value.lastCaptureCompleted != 0L && started - value.lastCaptureCompleted > 1_000_000_000L) value.failureSince = 0
        try {
            val frame = value.capturer.capture(value.ready.selectedMode)
            if (session !== value) throw DeviceOperationException("设备连接已更改")
            value.failures = 0; value.failureSince = 0
            value.frameSize = frame.width to frame.height
            return frame
        } catch (error: IOException) {
            if ((error as? DeviceOperationException)?.adbFailure == AdbFailureKind.ServerVersionConflict) {
                lose(value, error.message ?: "ADB 版本冲突", retry = false)
                throw error
            }
            if (value.failureSince == 0L) value.failureSince = started
            if (++value.failures >= 5 || System.nanoTime() - value.failureSince >= 10_000_000_000L) lose(value, "无法获取模拟器画面，任务已停止")
            throw error
        } finally { value.lastCaptureCompleted = System.nanoTime() }
    }

    override suspend fun queryForeground(): String? {
        val value = session ?: return null
        val result = adb.shell(value.serial, listOf("dumpsys", "window"))
        val foreground = (result as? AdbResult.Success)?.text()?.let(DeviceParsers::foreground)
        if (session === value) { cachedForeground = foreground; foregroundUpdated = System.nanoTime() }
        return foreground.takeIf { session === value }
    }

    private suspend fun action(expectedPackage: String, commands: String, durationMillis: Long, compatibility: List<String>): TouchResult {
        val value = session ?: return TouchResult.Rejected("触控服务未连接")
        if (queryForeground() != expectedPackage) return TouchResult.Rejected("游戏不在前台")
        if (session !== value) return TouchResult.Rejected("触控服务未连接")
        return try {
            if (value.touchMode == TouchMode.Compatibility) adb.shell(value.serial, listOf("input") + compatibility, durationMillis + 10_000).requireSuccess()
            else {
                val touch = value.touch ?: return TouchResult.Rejected("触控服务未连接")
                if (!touch.alive) return TouchResult.Rejected("触控服务已断开")
                if (!MaaTouchProtocol.matchesDimensions(touch.header, value.frameSize.first, value.frameSize.second)) return TouchResult.Rejected("触控坐标系与截图尺寸不一致")
                touch.send(commands, durationMillis)
            }
            TouchResult.Completed
        } catch (_: IOException) { TouchResult.Rejected("触控服务已断开") }
    }

    override suspend fun tap(x: Float, y: Float, expectedPackage: String): TouchResult {
        if (!validCoordinates(x, y)) return TouchResult.Rejected("动作坐标无效")
        return action(expectedPackage, MaaTouchProtocol.tap(x, y), 60, listOf("tap", x.roundToInt().toString(), y.roundToInt().toString()))
    }

    override suspend fun swipe(fromX: Float, fromY: Float, toX: Float, toY: Float, durationMillis: Long, holdMillis: Long, expectedPackage: String): TouchResult {
        if (!validCoordinates(fromX, fromY, toX, toY)) return TouchResult.Rejected("动作坐标无效")
        if (durationMillis !in 1..5000 || holdMillis.coerceAtLeast(0) > 5000 - durationMillis) return TouchResult.Rejected("滑动时长超出范围")
        if (session?.touchMode == TouchMode.Compatibility && holdMillis > 0) return TouchResult.Rejected("兼容模式不支持按住拖拽，请在设置中切换到 MaaTouch")
        return action(expectedPackage, MaaTouchProtocol.swipe(fromX, fromY, toX, toY, durationMillis, holdMillis), durationMillis + holdMillis.coerceAtLeast(0),
            listOf("swipe", fromX.roundToInt().toString(), fromY.roundToInt().toString(), toX.roundToInt().toString(), toY.roundToInt().toString(), durationMillis.toString()))
    }

    override suspend fun back(expectedPackage: String): TouchResult = action(expectedPackage, MaaTouchProtocol.key(4), 0, listOf("keyevent", "4"))

    override suspend fun home(): Boolean {
        val value = session ?: return false
        return try {
            if (value.touchMode == TouchMode.Compatibility) adb.shell(value.serial, listOf("input", "keyevent", "3")).requireSuccess()
            else (value.touch ?: return false).send(MaaTouchProtocol.key(3), 0)
            true
        } catch (_: IOException) { false }
    }

    override suspend fun launch(packageName: String): Boolean {
        val serial = session?.serial ?: return false
        val component = shellText(serial, "cmd", "package", "resolve-activity", "--brief", "-c", "android.intent.category.LAUNCHER", packageName)?.let(DeviceParsers::launchComponent) ?: return false
        val output = adb.shell(serial, listOf("am", "start", "-n", component))
        return output is AdbResult.Success && DeviceParsers.launchSucceeded(output.text() + "\n" + output.stderr)
    }

    override suspend fun forceStop(packageName: String): Boolean {
        val serial = session?.serial ?: return false
        if (adb.shell(serial, listOf("am", "force-stop", packageName)) !is AdbResult.Success) return false
        val deadline = System.nanoTime() + 3_000_000_000L
        while (System.nanoTime() < deadline) {
            val remaining = ((deadline - System.nanoTime()) / 1_000_000).coerceIn(1, 1000)
            when (val result = adb.shell(serial, listOf("pidof", packageName), remaining)) {
                is AdbResult.Success -> if (result.text().isBlank()) return true
                is AdbResult.Failure -> if (result.kind == AdbFailureKind.NonZeroExit && result.detail == "adb 退出码 1") return true else return false
            }
            delay(100)
        }
        emit("游戏进程未在 3 秒内退出")
        return false
    }

    override suspend fun uiTree(): List<UiNode> {
        val serial = session?.serial ?: return emptyList()
        repeat(4) { attempt ->
            val dump = adb.shell(serial, listOf("uiautomator", "dump", "/data/local/tmp/ui.xml"))
            val text = when (dump) { is AdbResult.Success -> dump.text() + dump.stderr; is AdbResult.Failure -> dump.detail }
            if (text.contains("could not get idle state", ignoreCase = true) && attempt < 3) { delay(500); return@repeat }
            if (dump is AdbResult.Success && !text.contains("ERROR:", ignoreCase = true)) {
                val xml = shellText(serial, "cat", "/data/local/tmp/ui.xml")
                if (xml != null) {
                    try { return DeviceParsers.uiTree(xml) }
                    catch (_: IOException) { }
                    catch (_: SAXException) { }
                    catch (_: IllegalArgumentException) { }
                }
            }
            emit("无法读取渠道登录界面")
            return emptyList()
        }
        emit("无法读取渠道登录界面")
        return emptyList()
    }

    private suspend fun shellText(serial: String, vararg args: String): String? = (adb.shell(serial, args.toList()) as? AdbResult.Success)?.text()?.trim()
    private fun validCoordinates(vararg coordinates: Float): Boolean = coordinates.all { it.isFinite() && it >= 0 }
    private fun emit(message: String) { try { onEvent(message) } catch (_: Exception) { /* Observers cannot break device cleanup. */ } }
    private fun notifyLost(reason: String) { try { onLost(reason) } catch (_: Exception) { emit("任务停止通知失败") } }
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runBlocking { disconnect() }
        scope.cancel()
        adb.close()
    }
}
