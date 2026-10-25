package com.landosol.toolbox.desktop.device

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/** Real child processes with synthetic I/O; never invokes ADB or an emulator. */
object ProcessFixture {
    private fun command(vararg args: String): List<String> = listOf(
        Path.of(System.getProperty("java.home"), "bin", if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java").toString(),
        "-cp", listOf(ProcessFixture::class.java, kotlin.Unit::class.java).map { Path.of(it.protectionDomain.codeSource.location.toURI()).toString() }.distinct().joinToString(java.io.File.pathSeparator),
        ProcessFixture::class.java.name,
    ) + args
    fun start(vararg args: String): Process = ProcessBuilder(command(*args)).start()
    @JvmStatic fun main(args: Array<String>) {
        when (args[0]) {
            "echo" -> System.out.write(args.drop(1).joinToString("|").toByteArray(Charsets.UTF_8))
            "error" -> { System.err.print(args[1]); kotlin.system.exitProcess(args[2].toInt()) }
            "large" -> {
                val other = Thread { repeat(1024) { System.err.write(ByteArray(8192) { 69 }) }; System.err.flush() }.apply { start() }
                repeat(8192) { System.out.write(ByteArray(8192) { 42 }) }; System.out.flush(); other.join()
            }
            "parent" -> { val child = start("sleep"); Files.writeString(Path.of(args[1]), child.pid().toString()); Thread.sleep(60_000) }
            "sleep" -> Thread.sleep(60_000)
            "touch" -> {
                println("^ 10 1920 1080 255"); println("\$ 1234"); System.out.flush()
                val destination = Path.of(args[1])
                System.`in`.bufferedReader().use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line == "EXIT") break
                        Files.writeString(destination, line + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND)
                    }
                }
            }
        }
    }
}
