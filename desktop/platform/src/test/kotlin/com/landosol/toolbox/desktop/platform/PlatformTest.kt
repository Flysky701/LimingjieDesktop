package com.landosol.toolbox.desktop.platform

import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.file.Files
import java.net.Proxy
import java.net.ProxySelector
import java.net.URI
import java.net.InetSocketAddress
import java.util.Collections

class PlatformTest {
    @Test fun resolvesDataDirectoryPrecedenceAndRejectsInvalidPath() {
        val root = Files.createTempDirectory("平台 中文 路径")
        val exe = root.resolve("LimingjieAssistant.exe")
        Files.createFile(root.resolve("portable.flag"))
        assertEquals(root.resolve("data"), DataDirectories.resolve(emptyArray(), exe.toString(), emptyMap(), "Windows 11", root.toString()))
        assertEquals(root.resolve("explicit"), DataDirectories.resolve(arrayOf("--data-dir", root.resolve("explicit").toString()), exe.toString(), emptyMap(), "Windows 11", root.toString()))
        assertEquals(root.resolve("LimingjieAssistant"), DataDirectories.resolve(emptyArray(), null, mapOf("LOCALAPPDATA" to root.toString()), "Windows 11", root.toString()))
        assertEquals(root.resolve(".local/share/LimingjieAssistant"), DataDirectories.resolve(emptyArray(), null, emptyMap(), "Linux", root.toString()))
        assertEquals(root.resolve("xdg/LimingjieAssistant"), DataDirectories.resolve(emptyArray(), null, mapOf("XDG_DATA_HOME" to root.resolve("xdg").toString()), "Linux", root.toString()))
        assertThrows(IllegalArgumentException::class.java) { DataDirectories.resolve(arrayOf("--data-dir")) }
        val file = root.resolve("not-a-directory"); Files.writeString(file, "fixture")
        assertThrows(IllegalStateException::class.java) { DataDirectories.ensureWritable(file) }
    }

    @Test fun sameDirectoryLockExcludesSecondInstanceAndCanReopen() {
        val root = Files.createTempDirectory("instance-lock")
        InstanceLock.acquire(root).use { assertThrows(IllegalStateException::class.java) { InstanceLock.acquire(root) } }
        InstanceLock.acquire(root).close()
    }

    @Test fun tempCleanupLeavesNormalFiles() {
        val root = Files.createTempDirectory("cleanup")
        Files.createDirectories(root.resolve("settings"))
        Files.writeString(root.resolve("settings/desktop.json.tmp-fixture"), "partial")
        Files.writeString(root.resolve("settings/desktop.json"), "saved")
        InstanceLock.acquire(root).use { DataDirectories.cleanTemporaryFiles(root) }
        assertFalse(Files.exists(root.resolve("settings/desktop.json.tmp-fixture")))
        assertEquals("saved", Files.readString(root.resolve("settings/desktop.json")))
    }

    @Test fun dpapiProtectsCurrentUserAndRejectsWrongEntropyOrTampering() {
        assumeTrue(WindowsDpapi.available)
        val plain = "test fixture 凭据".toByteArray()
        val entropy = "fixture|store|key".toByteArray()
        val encrypted = WindowsDpapi.protect(plain, entropy)
        assertFalse(plain.contentEquals(encrypted))
        assertArrayEquals(plain, WindowsDpapi.unprotect(encrypted, entropy))
        assertThrows(RuntimeException::class.java) { WindowsDpapi.unprotect(encrypted, "other-key".toByteArray()) }
        encrypted[encrypted.lastIndex] = (encrypted.last().toInt() xor 1).toByte()
        assertThrows(RuntimeException::class.java) { WindowsDpapi.unprotect(encrypted, entropy) }
    }

    @Test fun powerStateAlwaysUsesSameThreadAndClearsOnClose() {
        val calls = Collections.synchronizedList(mutableListOf<Pair<Long, Int>>())
        PowerSaveController { flags -> calls.add(Thread.currentThread().threadId() to flags); flags }.use {
            it.setMode(PowerMode.NETWORK); it.setMode(PowerMode.VISUAL); it.setMode(PowerMode.IDLE)
        }
        assertEquals(1, calls.map { it.first }.distinct().size)
        assertNotEquals(Thread.currentThread().threadId(), calls.first().first)
        assertEquals(listOf(-2147483647, -2147483645, Int.MIN_VALUE, Int.MIN_VALUE), calls.map { it.second })
    }

    @Test fun powerStateClearsAfterNativeFailure() {
        val calls = mutableListOf<Int>()
        PowerSaveController { flags -> calls.add(flags); if (flags == Int.MIN_VALUE) flags else 0 }.use {
            assertThrows(java.util.concurrent.ExecutionException::class.java) { it.setMode(PowerMode.VISUAL) }
        }
        assertEquals(Int.MIN_VALUE, calls.last())
        assertEquals(Int.MIN_VALUE, calls[1])
    }

    @Test fun proxySwitchAffectsPreviouslyCapturedSelector() {
        val proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", 8888))
        val original = object : ProxySelector() {
            override fun select(uri: URI) = listOf(proxy)
            override fun connectFailed(uri: URI, sa: java.net.SocketAddress, ioe: java.io.IOException) = Unit
        }
        val previous = ProxySelector.getDefault()
        try {
            NetworkProxyController(original).use { controller ->
                val captured = ProxySelector.getDefault()
                assertEquals(listOf(proxy), captured.select(URI("https://example.invalid")))
                controller.setMode(NetworkMode.DIRECT)
                assertEquals(listOf(Proxy.NO_PROXY), captured.select(URI("https://example.invalid")))
                controller.setMode(NetworkMode.SYSTEM)
                assertEquals(listOf(proxy), captured.select(URI("https://example.invalid")))
            }
            assertSame(original, ProxySelector.getDefault())
        } finally { ProxySelector.setDefault(previous) }
    }
}
