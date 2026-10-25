package com.landosol.toolbox.desktop.app.control

import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import java.security.MessageDigest

/** Shared authority + account row protects task startup before a game UID is available. */
internal class AccountStartLease private constructor(private val channel: FileChannel, private val lock: FileLock) : AutoCloseable {
    override fun close() { try { if (lock.isValid) lock.release() } finally { channel.close() } }
    companion object {
        fun authority(directory: Path): String = MessageDigest.getInstance("SHA-256")
            .digest(directory.toAbsolutePath().normalize().toString().lowercase().toByteArray())
            .joinToString("") { "%02x".format(it) }
        fun acquire(directory: Path, authorityId: String, accountId: Long): AccountStartLease {
            require(accountId > 0 && authorityId.isNotBlank()) { "共享账号身份无效" }
            val key = MessageDigest.getInstance("SHA-256").digest("$authorityId:$accountId".toByteArray()).joinToString("") { "%02x".format(it) }
            val parent = Files.createDirectories(directory.resolve("leases"))
            val channel = FileChannel.open(parent.resolve("account-start-$key.lock"), CREATE, WRITE)
            try {
                val lock = try { channel.tryLock() } catch (_: OverlappingFileLockException) { null }
                return AccountStartLease(channel, lock ?: error("该账号正在另一个设备任务中使用，请先停止对应任务"))
            } catch (failure: Throwable) { channel.close(); throw failure }
        }
    }
}
