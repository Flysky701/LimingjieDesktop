package com.landosol.toolbox.desktop.device

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.xml.sax.SAXException

class ScreenAndParserTest {
    @Test fun rawAllHeadersAndFormats() {
        for (header in listOf(12, 16)) for (format in listOf(1, 2, 5)) {
            val frame = ScreenCapturer.decodeRaw(FakeAdb.raw(header, format), 42)
            assertEquals(2, frame.width); assertEquals(1, frame.height); assertEquals(42L, frame.timestampMillis)
            val expected = when (format) { 2 -> 0xff112233.toInt(); 5 -> 0x44332211; else -> 0x44112233 }
            assertEquals("header=$header format=$format", expected, frame.pixels[0])
        }
    }
    @Test fun rawRejectsTruncationFormatAndInvalidDimensions() {
        assertThrows(DeviceOperationException::class.java) { ScreenCapturer.decodeRaw(FakeAdb.raw().copyOf(15)) }
        assertThrows(DeviceOperationException::class.java) { ScreenCapturer.decodeRaw(FakeAdb.raw(format = 99)) }
        assertThrows(DeviceOperationException::class.java) { ScreenCapturer.decodeRaw(FakeAdb.raw().apply { this[3] = 127 }) }
    }
    @Test fun pngMatchesArgb() {
        assertArrayEquals(intArrayOf(0x44112233, 0x7f556677), ScreenCapturer.decodePng(FakeAdb.png()).pixels)
        assertThrows(DeviceOperationException::class.java) { ScreenCapturer.decodePng(byteArrayOf(1, 2)) }
    }
    @Test fun benchmarkRejectsAnyFailedModeAndUsesSeparateChannel() = runBlocking {
        val fake = FakeAdb()
        fake.handler = { if (it.arguments == listOf("exec-out", "screencap")) FakeAdb.success(FakeAdb.raw(format = 3)) else fake.default(it) }
        val (timings, selected) = ScreenCapturer(fake, "fixture").benchmark()
        assertEquals(CaptureMode.Png, selected); assertEquals(listOf(CaptureMode.Png), timings.map { it.mode })
        assertTrue(fake.calls.all { it.screenshot }); assertEquals(5, fake.calls.count { "-p" in it.arguments })
    }
    @Test fun foregroundFixturesAreLiveAndFailClosed() {
        for (version in listOf("android9", "android12")) {
            assertEquals(FakeAdb.GAME, DeviceParsers.foreground(fixture("$version/window-game.txt")))
            assertEquals(FakeAdb.GAME, DeviceParsers.foreground(fixture("$version/window-null-focus.txt")))
            assertEquals("com.android.inputmethod.latin", DeviceParsers.foreground(fixture("$version/window-inputmethod.txt")))
            assertNull(DeviceParsers.foreground(fixture("$version/window-statusbar.txt")))
            assertEquals("com.android.permissioncontroller", DeviceParsers.foreground(fixture("$version/window-dialog.txt")))
            assertEquals("com.bilibili.priconne", DeviceParsers.foreground(fixture("$version/window-channel.txt")))
        }
        assertNull(DeviceParsers.foreground("mCurrentFocus=null\nmFocusedApp=null"))
    }
    @Test fun nodesPreserveTextBoundsAndRejectExternalEntities() {
        val root = DeviceParsers.uiTree(fixture("android12/ui.xml")).single()
        assertEquals("com.bilibili.priconne", root.packageName)
        assertEquals(UiBounds(10, 20, 110, 60), root.children.single().bounds)
        assertEquals("登录", root.children.single().text)
        assertTrue(root.children.single().clickable)
        assertThrows(SAXException::class.java) { DeviceParsers.uiTree("<!DOCTYPE hierarchy [<!ENTITY xxe SYSTEM 'file:///never-read'>]><hierarchy>&xxe;</hierarchy>") }
    }
    @Test fun nodeLimitsMatchUpstream() {
        val node = "<node package='p' bounds='[0,0][1,1]'/>"
        val root = DeviceParsers.uiTree("<hierarchy><node package='p' bounds='[0,0][1,1]'>${node.repeat(900)}</node></hierarchy>").single()
        assertEquals(599, root.children.size)
        val tree = DeviceParsers.uiTree("<hierarchy>" + "<node package='p' bounds='[0,0][1,1]'>".repeat(100) + "</node>".repeat(100) + "</hierarchy>").single()
        assertEquals(41, generateSequence(tree) { it.children.singleOrNull() }.count())
    }
    @Test fun discoveryAndPackageParsers() {
        assertEquals(listOf("127.0.0.1:16384", "emulator-5554"), DeviceParsers.devices("List of devices attached\n127.0.0.1:16384 device product:mumu model:MuMu_12\nemulator-5554 offline\n").map { it.serial })
        assertEquals(listOf(16384, 16416), DeviceParsers.mumuPorts("{\"0\":{\"adb_port\":16384},\"1\":{\"adb_port\":\"16416\"}}"))
        assertEquals(listOf(0), DeviceParsers.ldplayerIndices("0,雷电,1,2,1,3,4\n1,未启动,0,0,0,-1,-1"))
        assertEquals(1920 to 1080, DeviceParsers.screenSize("Physical size: 1280x720\nOverride size: 1920x1080"))
        assertEquals("/data/app/abc/base.apk", DeviceParsers.baseApk("package:/data/app/abc/split.apk\npackage:/data/app/abc/base.apk"))
        assertEquals(Triple("1.2.3", 123L, "2026-10-25 10:00:00"), DeviceParsers.packageVersion("versionCode=123 minSdk=26\nversionName=1.2.3\nlastUpdateTime=2026-10-25 10:00:00"))
        assertEquals("pkg/.Main", DeviceParsers.launchComponent("priority=0 preferredOrder=0 match=0\npkg/.Main\n"))
        assertFalse(DeviceParsers.launchSucceeded("Starting: Intent {}\nError: Activity class does not exist."))
    }
    private fun fixture(name: String) = javaClass.getResourceAsStream("/fixtures/$name")!!.bufferedReader().use { it.readText() }
}
