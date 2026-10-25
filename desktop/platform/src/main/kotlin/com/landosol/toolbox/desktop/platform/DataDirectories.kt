package com.landosol.toolbox.desktop.platform

import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE

object DataDirectories {
    fun resolve(
        args: Array<String>,
        appPath: String? = System.getProperty("jpackage.app-path"),
        environment: Map<String, String> = System.getenv(),
        osName: String = System.getProperty("os.name"),
        userHome: String = System.getProperty("user.home"),
    ): Path {
        val index = args.indexOf("--data-dir")
        if (index >= 0) {
            require(index + 1 < args.size && args[index + 1].isNotBlank() && !args[index + 1].startsWith("--")) { "--data-dir 需要指定数据目录" }
            return Path.of(args[index + 1]).toAbsolutePath().normalize()
        }
        val executableDirectory = appPath?.let { Path.of(it).toAbsolutePath().parent }
        if (executableDirectory != null && Files.exists(executableDirectory.resolve("portable.flag"))) {
            return executableDirectory.resolve("data")
        }
        val base = if (osName.startsWith("Windows", ignoreCase = true)) {
            Path.of(requireNotNull(environment["LOCALAPPDATA"]?.takeIf(String::isNotBlank)) { "无法确定 LOCALAPPDATA 数据目录，请使用 --data-dir 指定" })
        } else {
            environment["XDG_DATA_HOME"]?.takeIf(String::isNotBlank)?.let(Path::of) ?: Path.of(userHome, ".local", "share")
        }
        return base.resolve("LimingjieAssistant").toAbsolutePath().normalize()
    }

    fun ensureWritable(directory: Path) {
        try {
            Files.createDirectories(directory)
            val probe = Files.createTempFile(directory, ".write-check-", ".tmp")
            Files.delete(probe)
        } catch (failure: java.io.IOException) {
            throw IllegalStateException("数据目录不可写：$directory。${failure.message}。请将助手移到可写目录，或使用 --data-dir 指定位置。", failure)
        }
    }

    /** Call only after acquiring InstanceLock; never follows symbolic links. */
    fun cleanTemporaryFiles(directory: Path) {
        if (!Files.isDirectory(directory)) return
        Files.walk(directory).use { paths ->
            paths.filter { Files.isRegularFile(it, java.nio.file.LinkOption.NOFOLLOW_LINKS) && it.fileName.toString().contains(".tmp-") }
                .forEach(Files::deleteIfExists)
        }
    }
}

class InstanceLock private constructor(private val channel: FileChannel, private val lock: FileLock) : AutoCloseable {
    override fun close() { try { if (lock.isValid) lock.release() } finally { channel.close() } }
    companion object {
        fun acquire(dataDirectory: Path): InstanceLock {
            Files.createDirectories(dataDirectory)
            val channel = FileChannel.open(dataDirectory.resolve("instance.lock"), CREATE, WRITE)
            try {
                val lock = try { channel.tryLock() } catch (_: OverlappingFileLockException) { null }
                    ?: throw IllegalStateException("另一个助手实例正在使用这个数据目录（$dataDirectory）")
                return InstanceLock(channel, lock)
            } catch (failure: Throwable) { channel.close(); throw failure }
        }
    }
}
