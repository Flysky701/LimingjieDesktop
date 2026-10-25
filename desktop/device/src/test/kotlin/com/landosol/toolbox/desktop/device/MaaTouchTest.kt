package com.landosol.toolbox.desktop.device

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.nio.file.Files

class MaaTouchTest {
    @get:Rule val folder = TemporaryFolder()
    @Test fun headerAndPackagedAsset() {
        val header = MaaTouchProtocol.parseHeader(listOf("banner", "^ 10 1920 1080 255", "\$ 1234"))!!
        assertEquals(MaaTouchHeader(10, 1920, 1080, 255, 1234), header)
        assertTrue(MaaTouchProtocol.matchesDimensions(header, 1080, 1920))
        assertFalse(MaaTouchProtocol.matchesDimensions(header, 1280, 720))
        assertNull(MaaTouchProtocol.parseHeader(listOf("^ 0 1920 1080 255", "\$ 2")))
        assertTrue(MaaTouchProtocol.resourceBytes().size > 1000)
    }
    @Test fun exactGestureBytesAndHoldWithoutDrift() {
        assertEquals("d 0 11 20 1\nc\nw 60\nu 0\nc\n", MaaTouchProtocol.tap(10.5f, 20.4f))
        assertEquals("d 0 0 0 1\nc\nw 8\nm 0 8 16 1\nc\nw 8\nm 0 16 32 1\nc\nw 200\nu 0\nc\n", MaaTouchProtocol.swipe(0f, 0f, 16f, 32f, 16, 200))
        assertEquals("d 0 0 0 1\nc\nw 1\nm 0 2 3 1\nc\nu 0\nc\n", MaaTouchProtocol.swipe(0f, 0f, 2f, 3f, 1, -50))
        assertEquals("k 4 o\nc\n", MaaTouchProtocol.key(4))
        assertThrows(IllegalArgumentException::class.java) { MaaTouchProtocol.swipe(0f, 0f, 1f, 1f, 5000, 1) }
    }
    @Test fun persistentActorSerializesGesturesAndDetectsProcessExit() = runBlocking {
        val output = folder.root.toPath().resolve("touch.txt")
        val runner = ProcessAdbExecutor("C:/中文 模拟器/adb.exe", AdbProcessFactory { args ->
            if (args.any { "app_process" in it }) ProcessFixture.start("touch", output.toString()) else ProcessFixture.start("echo")
        })
        runner.use {
            val touch = MaaTouchConnection.open(runner, "synthetic", folder.root.toPath())
            try {
                coroutineScope { (1..8).map { n -> async { touch.send(MaaTouchProtocol.tap(n.toFloat(), 2f), 5) } }.awaitAll() }
                val data = Files.readString(output)
                assertEquals(8, Regex("d 0").findAll(data).count())
                assertEquals((1..8).map { MaaTouchProtocol.tap(it.toFloat(), 2f) }.toSet(), data.split(Regex("(?=d 0)")).filter(String::isNotEmpty).toSet())
                touch.send("EXIT\n", 0)
                withTimeout(3000) { while (touch.alive) delay(10) }
                try { touch.send("k 4 o\nc\n", 0); fail("closed touch accepted an action") } catch (_: IOException) { }
            } finally { touch.close() }
        }
    }
}
