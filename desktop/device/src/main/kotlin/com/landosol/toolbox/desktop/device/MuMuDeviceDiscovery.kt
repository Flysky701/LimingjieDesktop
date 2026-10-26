package com.landosol.toolbox.desktop.device

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path

/** Uses the running vendor's reported endpoints, never a port range or guessed instance index. */
object MuMuDeviceDiscovery {
    internal fun endpoints(document: String): Set<String> = runCatching {
        val root = Json.parseToJsonElement(document).jsonObject
        val players = if ("adb_port" in root) listOf(root) else root.values.filterIsInstance<JsonObject>()
        players.mapNotNull { player ->
            fun flag(name: String) = player[name]?.jsonPrimitive?.booleanOrNull == true
            val host = player["adb_host_ip"]?.jsonPrimitive?.contentOrNull
            val port = player["adb_port"]?.jsonPrimitive?.intOrNull
            val error = player["error_code"]?.jsonPrimitive?.intOrNull ?: 0
            if (!flag("is_android_started") || !flag("is_process_started") || error != 0 || port == null || port !in 1..65535) null
            else when (host) {
                "127.0.0.1", "localhost" -> "127.0.0.1:$port"
                "::1" -> "[::1]:$port"
                else -> null
            }
        }.toSet()
    }.getOrDefault(emptySet())

    internal suspend fun register(
        endpoints: Set<String>, registered: List<DeviceDescriptor>,
        connect: suspend (String) -> AdbResult, onEvent: (String) -> Unit,
    ) {
        val online = registered.filter { it.state == "device" }.map { it.serial }.toSet()
        for (endpoint in endpoints - online) {
            when (val result = connect(endpoint)) {
                is AdbResult.Success -> onEvent("已登记 MuMu 设备：$endpoint")
                is AdbResult.Failure -> onEvent("MuMu 设备 $endpoint 尚未就绪：${result.detail}")
            }
        }
    }

    suspend fun registerRunning(location: AdbLocation, onEvent: (String) -> Unit) {
        val adbPath = Path.of(location.path)
        if (!Files.isRegularFile(adbPath)) return
        val managers = (listOf(adbPath) + AdbLocator.vendorCandidates(AdbLocator.emulatorCommands()))
            .map { it.resolveSibling("MuMuManager.exe").toAbsolutePath().normalize() }
            .distinct().filter(Files::isRegularFile)
        if (managers.isEmpty()) return
        ProcessAdbExecutor(location.path, server = AdbServerClient()).use { adb ->
            val listed = adb.execute(listOf("devices", "-l")).requireSuccess().text().let(DeviceParsers::devices)
            val found = linkedSetOf<String>()
            for (manager in managers) {
                try {
                    ProcessAdbExecutor(manager.toString()).use { vendor ->
                        val result = vendor.execute(listOf("info", "-v", "all"), timeoutMillis = 3_000)
                        if (result is AdbResult.Success) found += endpoints(result.text())
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { /* Existing registered devices remain discoverable. */ }
            }
            register(found, listed, { adb.execute(listOf("connect", it), timeoutMillis = 3_000) }, onEvent)
        }
    }
}
