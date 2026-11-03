package com.landosol.toolbox.desktop.device

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.Base64

/** Real child processes with synthetic I/O; never invokes ADB or an emulator. */
object ProcessFixture {
    // The Windows Java launcher converts Unicode argv through the ANSI code page (JDK-8337506).
    // This synthetic child is not adb: carry its payload as ASCII while tests assert raw adb argv.
    private fun command(vararg args: String): List<String> = listOf(
        Path.of(System.getProperty("java.home"), "bin", if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java").toString(),
        "-cp", listOf(ProcessFixture::class.java, kotlin.Unit::class.java).map { Path.of(it.protectionDomain.codeSource.location.toURI()).toString() }.distinct().joinToString(java.io.File.pathSeparator),
        ProcessFixture::class.java.name,
    ) + args.map { Base64.getEncoder().encodeToString(it.toByteArray(Charsets.UTF_8)) }
    fun start(vararg args: String): Process = ProcessBuilder(command(*args)).start()
    @JvmStatic fun main(encodedArgs: Array<String>) {
        val args = encodedArgs.map { Base64.getDecoder().decode(it).toString(Charsets.UTF_8) }
        when (args[0]) {
            "echo" -> System.out.write(args.drop(1).joinToString("|").toByteArray(Charsets.UTF_8))
            "error" -> { System.err.print(args[1]); args.getOrNull(3)?.let { System.out.print(it) }; kotlin.system.exitProcess(args[2].toInt()) }
            "large" -> {
                val other = Thread { repeat(1024) { System.err.write(ByteArray(8192) { 69 }) }; System.err.flush() }.apply { start() }
                repeat(8192) { System.out.write(ByteArray(8192) { 42 }) }; System.out.flush(); other.join()
            }
            "parent" -> { val child = start("sleep"); Files.writeString(Path.of(args[1]), child.pid().toString()); Thread.sleep(60_000) }
            "daemon" -> {
                val child = ProcessBuilder(command("sleep"))
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start()
                Files.writeString(Path.of(args[1]), child.pid().toString())
            }
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
