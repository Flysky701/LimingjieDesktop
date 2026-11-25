package com.landosol.toolbox.labyrinth

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LabyrinthCnDatabaseUpdaterTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `validated download installs before metadata is committed`() {
        val calls = mutableListOf<String>()
        val metadata = MemoryMetadataStore(null, calls)
        val source = FakeSource(calls)
        val candidate = temporaryFolder.newFile("candidate.db")
        val updater = LabyrinthCnDatabaseUpdater(
            source = source,
            candidateFileFactory = { candidate },
            validator = LabyrinthCnDatabaseCandidateValidator {
                calls += "validate"
                LabyrinthCnDatabaseValidation.Valid(emptyResolution())
            },
            installer = LabyrinthCnDatabaseInstaller { file ->
                assertEquals(candidate, file)
                calls += "install"
            },
            metadataStore = metadata,
            clock = { 1234L },
        )

        val result = updater.updateIfNeeded() as LabyrinthCnDatabaseUpdateResult.Updated

        assertEquals(listOf("version", "download", "validate", "install", "metadata"), calls)
        assertEquals("202607231828", result.metadata.version)
        assertEquals(TEST_DATABASE_SHA256, result.metadata.upstreamHash)
        assertEquals(1234L, result.metadata.installedAt)
        assertTrue(result.metadata.fileSha256.length == 64)
    }

    @Test
    fun `invalid sqlite candidate never replaces old database or metadata`() {
        val calls = mutableListOf<String>()
        val original = LabyrinthCnDatabaseMetadata("old", "old-hash", "old-sha", 1L)
        val metadata = MemoryMetadataStore(original, calls)
        var installed = false
        val candidate = temporaryFolder.newFile("invalid.db")
        val updater = LabyrinthCnDatabaseUpdater(
            source = FakeSource(calls),
            candidateFileFactory = { candidate },
            validator = LabyrinthCnDatabaseCandidateValidator {
                calls += "validate"
                LabyrinthCnDatabaseValidation.Invalid("quick_check failed")
            },
            installer = LabyrinthCnDatabaseInstaller { installed = true },
            metadataStore = metadata,
        )

        val result = updater.updateIfNeeded()

        assertTrue(result is LabyrinthCnDatabaseUpdateResult.Failed)
        assertFalse(installed)
        assertEquals(original, metadata.current)
        assertFalse(calls.contains("metadata"))
    }

    @Test
    fun `matching version and hash skips database download`() {
        val calls = mutableListOf<String>()
        val installed = LabyrinthCnDatabaseMetadata(
            version = "202607231828",
            upstreamHash = TEST_DATABASE_SHA256,
            fileSha256 = "sha",
            installedAt = 1L,
        )
        val updater = LabyrinthCnDatabaseUpdater(
            source = FakeSource(calls),
            candidateFileFactory = { error("must not create candidate") },
            validator = LabyrinthCnDatabaseCandidateValidator { error("must not validate") },
            installer = LabyrinthCnDatabaseInstaller { error("must not install") },
            metadataStore = MemoryMetadataStore(installed, calls),
        )

        val result = updater.updateIfNeeded()

        assertTrue(result is LabyrinthCnDatabaseUpdateResult.UpToDate)
        assertEquals(listOf("version"), calls)
    }

    @Test
    fun `matching metadata redownloads when database file is missing`() {
        val calls = mutableListOf<String>()
        val installed = LabyrinthCnDatabaseMetadata(
            version = "202607231828",
            upstreamHash = TEST_DATABASE_SHA256,
            fileSha256 = "sha",
            installedAt = 1L,
        )
        val candidate = temporaryFolder.newFile("replacement.db")
        val updater = LabyrinthCnDatabaseUpdater(
            source = FakeSource(calls),
            candidateFileFactory = { candidate },
            validator = LabyrinthCnDatabaseCandidateValidator {
                calls += "validate"
                LabyrinthCnDatabaseValidation.Valid(emptyResolution())
            },
            installer = LabyrinthCnDatabaseInstaller { calls += "install" },
            metadataStore = MemoryMetadataStore(installed, calls),
            databaseAvailable = { false },
        )

        val result = updater.updateIfNeeded()

        assertTrue(result is LabyrinthCnDatabaseUpdateResult.Updated)
        assertEquals(listOf("version", "download", "validate", "install", "metadata"), calls)
    }

    @Test
    fun `checksum mismatch never reaches sqlite validation or install`() {
        val calls = mutableListOf<String>()
        val candidate = temporaryFolder.newFile("checksum-mismatch.db")
        var installed = false
        val updater = LabyrinthCnDatabaseUpdater(
            source = FakeSource(calls, hash = "0".repeat(64)),
            candidateFileFactory = { candidate },
            validator = LabyrinthCnDatabaseCandidateValidator {
                calls += "validate"
                LabyrinthCnDatabaseValidation.Valid(emptyResolution())
            },
            installer = LabyrinthCnDatabaseInstaller { installed = true },
            metadataStore = MemoryMetadataStore(null, calls),
        )

        val result = updater.updateIfNeeded() as LabyrinthCnDatabaseUpdateResult.Failed

        assertTrue(result.reason.contains("SHA-256"))
        assertFalse(installed)
        assertEquals(listOf("version", "download"), calls)
    }

    private fun emptyResolution() = LabyrinthMasterSchemaResolution(emptyMap(), emptyList())

    private class FakeSource(
        private val calls: MutableList<String>,
        private val hash: String = TEST_DATABASE_SHA256,
    ) : LabyrinthCnDatabaseSource {
        override fun fetchVersion(): LabyrinthCnDatabaseVersion {
            calls += "version"
            return LabyrinthCnDatabaseVersion(
                version = "202607231828",
                hash = hash,
            )
        }

        override fun downloadDatabase(version: LabyrinthCnDatabaseVersion, destination: File) {
            calls += "download"
            destination.writeBytes("SQLite format 3\u0000test".toByteArray())
        }
    }

    private class MemoryMetadataStore(
        var current: LabyrinthCnDatabaseMetadata?,
        private val calls: MutableList<String>,
    ) : LabyrinthCnDatabaseMetadataStore {
        override fun load(): LabyrinthCnDatabaseMetadata? = current

        override fun save(metadata: LabyrinthCnDatabaseMetadata) {
            calls += "metadata"
            current = metadata
        }
    }

    private companion object {
        const val TEST_DATABASE_SHA256 =
            "18e851abfac892230fd8d830d2e5e201d3aea8341a874a675eea2182a86830a4"
    }
}
