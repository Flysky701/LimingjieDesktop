package com.landosol.toolbox.core.config

import kotlinx.serialization.builtins.serializer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors

class AtomicConfigStoreTest {
    @get:Rule val directory = TemporaryFolder()
    private val file get() = directory.root.toPath().resolve("nested/config.json")
    private fun open(onRecovered: (Path) -> Unit = {}, migrate: (() -> Int?)? = null) =
        AtomicConfigStore(file, Int.serializer(), { 5 }, validate = { require(it >= 0) },
            onRecovered = onRecovered, migrateLegacy = migrate)

    @Test fun defaultsAndAtomicUpdatesSurviveReopen() {
        val store = open()
        assertEquals(5, store.state.value)
        val pool = Executors.newFixedThreadPool(4)
        try {
            val tasks = (1..12).map { pool.submit { store.update { it + 1 } } }
            tasks.forEach { it.get() }
        } finally { pool.shutdownNow() }
        assertEquals(17, store.state.value)
        assertEquals(17, open().state.value)
        val saved = Files.readString(file)
        assertThrows(IllegalArgumentException::class.java) { store.update { -1 } }
        assertEquals(17, store.state.value)
        assertEquals(saved, Files.readString(file))
    }

    @Test fun corruptJsonIsBackedUpBeforeDefaultsArePublished() {
        Files.createDirectories(file.parent)
        for (invalid in listOf("{broken".toByteArray(), byteArrayOf(0xff.toByte()))) {
            Files.write(file, invalid)
            val backups = mutableListOf<Path>()
            assertEquals(5, open(onRecovered = backups::add).state.value)
            val copy = backups.single()
            assertTrue(copy.fileName.toString().matches(Regex("""config\.corrupt-\d+\.json""")))
            assertArrayEquals(invalid, Files.readAllBytes(copy))
            assertEquals(5, open(onRecovered = { fail("already recovered") }).state.value)
        }
    }

    @Test fun futureVersionRefusesOpenAndUpdateWithoutTouchingFile() {
        val store = open()
        val future = """{"schemaVersion":2,"unknownFormat":[]}"""
        Files.writeString(file, future)
        assertThrows(IllegalStateException::class.java) { open(onRecovered = { fail("not corrupt") }) }
        assertThrows(IllegalStateException::class.java) { store.update { 12 } }
        assertEquals(5, store.state.value)
        assertEquals(future, Files.readString(file))
        Files.list(file.parent).use { assertEquals(1L, it.count()) }
    }

    @Test fun legacyMigrationRunsOnceAndFailedMigrationCanBeRetried() {
        assertThrows(IllegalStateException::class.java) { open(migrate = { error("legacy unavailable") }) }
        assertFalse(Files.exists(file))
        assertEquals(42, open(migrate = { 42 }).state.value)
        assertEquals(42, open(migrate = { error("already migrated") }).state.value)
    }
}
