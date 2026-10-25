package com.landosol.toolbox.desktop.platform

import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** Deliberately independent of executable, portable.flag, --data-dir and profile directories. */
object UserRuntimeDirectory {
    fun resolve(environment: Map<String, String> = System.getenv(), osName: String = System.getProperty("os.name"),
                userHome: String = System.getProperty("user.home")): Path {
        val base = if (osName.startsWith("Windows", ignoreCase = true)) {
            Path.of(requireNotNull(environment["LOCALAPPDATA"]?.takeIf(String::isNotBlank)) { "无法确定用户级占用锁目录" })
        } else Path.of(userHome, ".local", "share")
        return base.resolve("LimingjieAssistant-runtime").toAbsolutePath().normalize()
    }
}

class ResourceLease internal constructor(private val channel: FileChannel, private val lock: FileLock) : AutoCloseable {
    private val closed = AtomicBoolean()
    val isValid: Boolean get() = !closed.get() && lock.isValid
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try { if (lock.isValid) lock.release() } finally { channel.close() }
    }
}

/** OS locks release on process death. Lock files are never deleted or judged by stale PIDs. */
class UserResourceLeases(private val root: Path = UserRuntimeDirectory.resolve()) {
    fun acquireDevice(bootId: String): ResourceLease =
        acquire("device", canonicalBootId(bootId), "该模拟器已被另一个实例占用")

    /** Call only with a UID returned by the authenticated server, never account aliases/login IDs. */
    fun acquireAccount(serverId: String, authoritativeUid: String): ResourceLease {
        require(serverId.isNotBlank() && '\u0000' !in serverId) { "账号服务器未确认" }
        require(authoritativeUid.matches(Regex("[1-9][0-9]*"))) { "账号 UID 尚未确认" }
        return acquire("account", "${serverId.length}:$serverId:$authoritativeUid", "该游戏账号已被另一个实例占用")
    }

    private fun acquire(kind: String, identity: String, message: String): ResourceLease {
        val digest = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return acquireFile(root.resolve("leases").resolve("$kind-$digest.lock"), message)
    }

    companion object {
        fun canonicalBootId(value: String): String {
            require(value.matches(Regex("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")) &&
                value != "00000000-0000-0000-0000-000000000000") { "无法确认模拟器启动身份，禁止控制" }
            return UUID.fromString(value).toString()
        }
        internal fun acquireFile(path: Path, message: String): ResourceLease {
            Files.createDirectories(path.parent)
            val channel = FileChannel.open(path, CREATE, WRITE)
            try {
                val lock = try { channel.tryLock() } catch (_: OverlappingFileLockException) { null }
                return ResourceLease(channel, lock ?: throw IllegalStateException(message))
            } catch (failure: Throwable) { channel.close(); throw failure }
        }
    }
}
