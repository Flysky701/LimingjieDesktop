package com.landosol.toolbox.desktop.device

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AdbLocatorTest {
    @get:Rule val folder = TemporaryFolder()
    private val adbVersion = "Android Debug Bridge version 1.0.41\n"

    @Test fun explicitSelectionSkipsAutomaticDiscoveryAndUsesChosenPath() = runBlocking {
        val selected = folder.newFile("中文 adb.exe").toPath()
        val probed = mutableListOf<java.nio.file.Path>()
        val location = AdbLocator.resolve("  $selected  ", { error("Explicit selection must skip automatic discovery") }) {
            probed.add(it)
            adbVersion
        }
        assertEquals(selected.toString(), location!!.path)
        assertEquals("用户指定", location.source)
        assertEquals(listOf(selected), probed)
    }

    @Test fun invalidExplicitSelectionDoesNotFallBackToAnotherAdb() = runBlocking {
        val invalidExecutable = folder.newFile("invalid.exe").toPath()
        for (selected in listOf(folder.root.resolve("missing.exe").path, "bad\u0000path", invalidExecutable.toString())) {
            assertNull(AdbLocator.resolve(selected, { error("Invalid selection must not fall back") }) { "not an adb executable" })
        }
        assertNull(AdbLocator.resolve(invalidExecutable.toString(), { error("Failed version check must not fall back") }) { null })
    }

    @Test fun blankSelectionKeepsAutomaticOrderAndSkipsUnusableCandidates() = runBlocking {
        val invalid = folder.newFile("invalid.exe").toPath()
        val vendor = folder.newFile("vendor.exe").toPath()
        val pathAdb = folder.newFile("path-adb.exe").toPath()
        val probed = mutableListOf<java.nio.file.Path>()
        val location = AdbLocator.resolve(" ", {
            listOf(folder.root.toPath().resolve("missing.exe") to "模拟器自带", invalid to "模拟器自带",
                invalid to "重复候选", vendor to "模拟器自带", pathAdb to "PATH")
        }) {
            probed.add(it)
            if (it == invalid) null else adbVersion
        }
        assertEquals(vendor.toString(), location!!.path)
        assertEquals("模拟器自带", location.source)
        assertEquals(listOf(invalid, vendor), probed)
    }
}
