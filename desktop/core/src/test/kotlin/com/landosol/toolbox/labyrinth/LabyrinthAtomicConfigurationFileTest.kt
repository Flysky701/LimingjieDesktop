package com.landosol.toolbox.labyrinth

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LabyrinthAtomicConfigurationFileTest {
    @get:Rule val directory = TemporaryFolder()

    @Test fun `completed replacements reopen the latest value and leave no pending file`() {
        val target = File(directory.root, "configuration.json")
        val store = LabyrinthAtomicConfigurationFile(target)
        assertNull(store.read())
        store.write("first")
        store.write("second")
        assertEquals("second", LabyrinthAtomicConfigurationFile(target).read())
        assertFalse(File(directory.root, "configuration.json.pending").exists())
    }

    @Test fun `failure opening the pending file preserves the original file`() {
        val target = directory.newFile("configuration.json")
        target.writeText("original")
        val blocked = directory.newFolder("configuration.json.pending")
        File(blocked, "keep").writeText("occupied")
        assertThrows(IllegalStateException::class.java) { LabyrinthAtomicConfigurationFile(target).write("replacement") }
        assertEquals("original", target.readText())
    }

    @Test fun `leftover pending content is ignored and a later write succeeds`() {
        val target = directory.newFile("configuration.json")
        target.writeText("committed")
        File(directory.root, "configuration.json.pending").writeText("partial")
        assertEquals("committed", LabyrinthAtomicConfigurationFile(target).read())
        LabyrinthAtomicConfigurationFile(target).write("next")
        assertEquals("next", target.readText())
    }
}
