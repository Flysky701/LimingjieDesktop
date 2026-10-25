package com.landosol.toolbox.desktop.device

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.zip.ZipInputStream

object AdbLocator {
    internal fun emulatorCommands(): List<Path> = ProcessHandle.allProcesses().use { processes ->
        processes.map { it.info().command().orElse("") }.filter { it.isNotBlank() && Regex("mumu|nemu|ldplayer|dnplayer|ldvbox|leidian", RegexOption.IGNORE_CASE).containsMatchIn(it) }
            .map { Path.of(it) }.toList()
    }
    internal fun vendorCandidates(commands: List<Path>): List<Path> = commands.flatMap { command ->
        generateSequence(command.parent) { it.parent }.take(3).flatMap { root ->
            sequenceOf(root.resolve("adb.exe"), root.resolve("shell/adb.exe"), root.resolve("nx_main/adb.exe"), root.resolve("vmonitor/bin/adb.exe"))
        }.toList()
    }.distinct()

    suspend fun find(configuredPath: String? = null, downloadedDirectory: Path? = null): AdbLocation? {
        val candidates = mutableListOf<Pair<Path, String>>()
        candidates += vendorCandidates(emulatorCommands()).map { it to "模拟器自带" }
        if (System.getProperty("os.name").startsWith("Windows")) {
            ProcessAdbExecutor("reg.exe").use { registry ->
                val result = registry.execute(listOf("query", "HKCU\\Software\\leidian\\ldplayer9", "/v", "InstallDir"), timeoutMillis = 3000)
                if (result is AdbResult.Success) Regex("InstallDir\\s+REG_SZ\\s+(.+)").find(result.text())?.groupValues?.get(1)?.trim()?.let { candidates += Path.of(it).resolve("adb.exe") to "雷电注册表" }
            }
        }
        val executableName = if (System.getProperty("os.name").startsWith("Windows")) "adb.exe" else "adb"
        candidates += System.getenv("PATH").orEmpty().split(java.io.File.pathSeparator).filter { it.isNotBlank() }.map { Path.of(it).resolve(executableName) to "PATH" }
        configuredPath?.takeIf { it.isNotBlank() }?.let { candidates += Path.of(it) to "用户指定" }
        downloadedDirectory?.let { candidates += it.resolve(executableName) to "已下载 platform-tools" }
        for ((path, source) in candidates.distinctBy { it.first.toAbsolutePath().normalize() }) {
            if (!Files.isRegularFile(path)) continue
            ProcessAdbExecutor(path.toString()).use { runner ->
                val version = runner.execute(listOf("version"), timeoutMillis = 3000)
                if (version is AdbResult.Success && version.text().contains("Android Debug Bridge")) return AdbLocation(path.toString(), source, version.text().trim())
            }
        }
        return null
    }

    /** Explicit user action only. Official ADB is downloaded into the application's data directory. */
    suspend fun downloadPlatformTools(dataDirectory: Path): AdbLocation = withContext(Dispatchers.IO) {
        val target = dataDirectory.resolve("tools/platform-tools")
        Files.createDirectories(target)
        val request = HttpRequest.newBuilder(URI("https://dl.google.com/android/repository/platform-tools-latest-windows.zip"))
            .timeout(Duration.ofMinutes(3)).GET().build()
        val response = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(20)).build()
            .send(request, HttpResponse.BodyHandlers.ofInputStream())
        if (response.statusCode() != 200) { response.body().close(); throw DeviceOperationException("platform-tools 下载失败（HTTP ${response.statusCode()}）") }
        val required = setOf("adb.exe", "AdbWinApi.dll", "AdbWinUsbApi.dll")
        val extracted = mutableSetOf<String>()
        ZipInputStream(response.body()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = entry.name.removePrefix("platform-tools/")
                if (!entry.isDirectory && entry.name == "platform-tools/$name" && name in required) {
                    Files.newOutputStream(target.resolve(name)).use { zip.copyTo(it) }
                    extracted += name
                }
                zip.closeEntry()
            }
        }
        if (extracted != required) throw DeviceOperationException("platform-tools 压缩包缺少所需文件")
        val path = target.resolve("adb.exe")
        ProcessAdbExecutor(path.toString()).use { runner ->
            val version = runner.execute(listOf("version"), timeoutMillis = 3000).requireSuccess().text().trim()
            if (!version.contains("Android Debug Bridge")) throw DeviceOperationException("下载的 adb 无法通过版本检查")
            Files.writeString(target.resolve("version.txt"), version)
            AdbLocation(path.toString(), "已下载 platform-tools", version)
        }
    }
}
