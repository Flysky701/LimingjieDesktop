package com.landosol.toolbox.core.logging

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.APPEND
import java.nio.file.StandardOpenOption.CREATE

class RollingFileLogSink(path: Path? = null) {
    private var directory = path?.let { Files.createDirectories(it) }
    private val recent = ArrayDeque<ByteArray>()
    private var recentBytes = 0

    @Synchronized
    fun initialize(path: Path) {
        directory = Files.createDirectories(path)
    }

    fun write(line: String) = writeRedacted(LogRedactor.redact(line))

    @Synchronized
    internal fun writeRedacted(line: String) {
        val normalized = line.replace("\r\n", "\n").replace('\r', '\n')
        val terminated = if (normalized.endsWith('\n')) normalized else "$normalized\n"
        val fileLine = limitLine(terminated.encodeToByteArray(), MAX_FILE_BYTES)
        remember(fileLine)

        val dir = directory ?: return
        try {
            val file = dir.resolve("app.log")
            if (Files.exists(file) && Files.size(file) + fileLine.size > MAX_FILE_BYTES) {
                for (index in BACKUP_COUNT - 1 downTo 1) {
                    val previous = dir.resolve("app.$index.log")
                    if (Files.exists(previous)) Files.move(previous, dir.resolve("app.${index + 1}.log"), REPLACE_EXISTING)
                }
                Files.move(file, dir.resolve("app.1.log"), REPLACE_EXISTING)
            }
            Files.write(file, fileLine, CREATE, APPEND)
        } catch (failure: IOException) {
            System.err.println("App log write failed: ${failure.javaClass.simpleName}")
        }
    }

    private fun remember(bytes: ByteArray) {
        var start = 0
        for (end in bytes.indices) {
            if (bytes[end] != '\n'.code.toByte()) continue
            val line = if (start == 0 && end == bytes.lastIndex) bytes else bytes.copyOfRange(start, end + 1)
            val recentLine = limitLine(line, MAX_SNAPSHOT_BYTES)
            recent.addLast(recentLine)
            recentBytes += recentLine.size
            while (recent.size > MAX_SNAPSHOT_LINES || recentBytes > MAX_SNAPSHOT_BYTES) {
                recentBytes -= recent.removeFirst().size
            }
            start = end + 1
        }
    }

    @Synchronized
    fun snapshot(): ByteArray {
        val result = ByteArray(recentBytes)
        var offset = 0
        for (line in recent) {
            line.copyInto(result, offset)
            offset += line.size
        }
        return result
    }

    private fun limitLine(bytes: ByteArray, limit: Int): ByteArray {
        if (bytes.size <= limit) return bytes
        var end = limit - TRUNCATED.size
        while ((bytes[end].toInt() and 0xc0) == 0x80) end--
        return TRUNCATED.copyInto(bytes.copyOf(end + TRUNCATED.size), end)
    }

    private companion object {
        const val MAX_FILE_BYTES = 10 * 1024 * 1024
        const val BACKUP_COUNT = 5
        const val MAX_SNAPSHOT_LINES = 5000
        const val MAX_SNAPSHOT_BYTES = 2 * 1024 * 1024
        val TRUNCATED = "…\n".encodeToByteArray()
    }
}
