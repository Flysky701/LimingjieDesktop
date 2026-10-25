package com.landosol.toolbox.desktop.shared

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.APPEND
import java.nio.file.StandardOpenOption.CREATE
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

object DesktopLog {
    private var directory: Path? = null
    private val recent = ArrayDeque<String>()
    private val formatter = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss.SSS")
    @Synchronized fun initialize(path: Path) { Files.createDirectories(path); directory = path }
    @Synchronized fun write(level: Char, tag: String, message: String, error: Throwable? = null): Int {
        val line = "${LocalDateTime.now().format(formatter)} ${ProcessHandle.current().pid()} ${Thread.currentThread().threadId()} $level $tag: $message" +
            (error?.let { "\n${it.stackTraceToString()}" } ?: "") + "\n"
        recent.addLast(line)
        while (recent.size > 5000) recent.removeFirst()
        directory?.let { dir ->
            try {
                val file = dir.resolve("app.log")
                if (Files.exists(file) && Files.size(file) >= 10 * 1024 * 1024) {
                    for (index in 4 downTo 1) {
                        val previous = dir.resolve("app.$index.log")
                        if (Files.exists(previous)) Files.move(previous, dir.resolve("app.${index + 1}.log"), REPLACE_EXISTING)
                    }
                    Files.move(file, dir.resolve("app.1.log"), REPLACE_EXISTING)
                }
                Files.writeString(file, line, CREATE, APPEND)
            } catch (failure: java.io.IOException) { System.err.println("Desktop log write failed: ${failure.javaClass.simpleName}") }
        }
        return 0
    }
    @Synchronized fun snapshot(): ByteArray = recent.joinToString("").toByteArray(Charsets.UTF_8).let { bytes ->
        if (bytes.size <= 2 * 1024 * 1024) bytes else bytes.copyOfRange(bytes.size - 2 * 1024 * 1024, bytes.size)
    }
}
