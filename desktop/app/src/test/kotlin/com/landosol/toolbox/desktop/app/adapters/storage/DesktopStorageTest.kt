package com.landosol.toolbox.desktop.app.adapters.storage

import com.landosol.toolbox.desktop.platform.*
import com.landosol.toolbox.desktop.shared.*
import com.landosol.toolbox.data.local.AppDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class DesktopStorageTest {
    @Test fun settingsPersistAndCorruptionIsPreserved() {
        val root = Files.createTempDirectory("desktop-settings")
        val settings = DesktopSettings(adbPath = "D:/中文 tools/adb.exe", networkMode = NetworkMode.DIRECT, wizardCompleted = true)
        DesktopSettingsStore(root).save(settings)
        assertEquals(settings, DesktopSettingsStore(root).state.value)
        Files.writeString(root.resolve("settings/desktop.json"), "broken{")
        var backup: Path? = null
        assertEquals(DesktopSettings(), DesktopSettingsStore(root) { backup = it }.state.value)
        assertEquals("broken{", Files.readString(backup!!))
        assertFalse(Files.exists(root.resolve("settings/desktop.json")))
    }

    @Test fun snapshotPersistsAndDeviceKeyCannotEscapeDirectory() {
        val root = Files.createTempDirectory("device-identity")
        val snapshot = DeviceIdentitySnapshot("../unsafe:serial", "fixture", "emulator", "12", 32,
            listOf("x86_64", "arm64-v8a"), "fixture-id", 1920, 1080,
            mapOf("com.bilibili.priconne" to GamePackageIdentity("1.0.0", 1, "fixture-sign")), 1234)
        FileDeviceIdentityStore(root).save(snapshot)
        assertEquals(snapshot, FileDeviceIdentityStore(root).current())
        Files.list(root.resolve("device")).use { assertEquals(2L, it.count()) }
        Files.writeString(root.resolve("device/current.json"), "invalid")
        var notified = false
        assertNull(FileDeviceIdentityStore(root) { notified = true }.current())
        assertTrue(notified)
    }

    @Test fun corruptRoomDatabaseIsBackedUpBeforeRecreation() = runBlocking {
        val root = Files.createTempDirectory("corrupt-room")
        val context = DesktopContext(root.toFile())
        val directory = Files.createDirectories(root.resolve("databases"))
        val corrupt = ByteArray(4096) { 0x41 }
        Files.write(directory.resolve(AppDatabase.FILE_NAME), corrupt)
        var notice = ""
        val database = DatabaseRecovery.open(context) { notice = it }
        try { assertEquals(0, database.accountDao().count()) } finally { database.close() }
        assertTrue(notice.contains("账号需要重新添加"))
        val backup = Files.list(directory).use { files -> files.filter { it.fileName.toString().startsWith("corrupt-") }.findFirst().orElseThrow() }
        assertArrayEquals(corrupt, Files.readAllBytes(backup.resolve(AppDatabase.FILE_NAME)))
        val reopened = DatabaseRecovery.open(context)
        try { assertEquals(0, reopened.accountDao().count()) } finally { reopened.close() }
    }
}
