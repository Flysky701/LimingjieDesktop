package com.landosol.toolbox.labyrinth.kernel

import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.Required
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class KernelJournalSnapshot(
    @Required val schemaVersion: Int = 1,
    val traceId: String,
    val revision: Long,
    val eventCount: Long,
    val lastEventSeq: Long,
    val updatedAtMillis: Long,
)

data class LoadedKernelJournal(val snapshot: KernelJournalSnapshot, val events: List<KernelEvent>)

private val journalJson = Json { encodeDefaults = true }

class KernelJournalSink(private val journalDir: File) : KernelEventSink, AutoCloseable {
    private class Session(val directory: File, val output: FileOutputStream, var snapshot: KernelJournalSnapshot)

    private var session: Session? = null
    private var closed = false

    @Synchronized override fun append(event: KernelEvent) {
        check(!closed) { "Journal is closed" }
        val current = session?.takeIf { it.snapshot.traceId == event.traceId } ?: openSession(event.traceId)
        val previous = current.snapshot
        require(event.eventSeq > previous.lastEventSeq) { "Journal eventSeq must increase" }
        val next = previous.copy(revision = previous.revision + 1, eventCount = previous.eventCount + 1,
            lastEventSeq = event.eventSeq, updatedAtMillis = event.timestampMillis)
        val line = (journalJson.encodeToString<KernelEvent>(event) + "\n").toByteArray(Charsets.UTF_8)
        val offset = current.output.channel.size()
        try {
            current.output.write(line)
            current.output.flush()
            writeSnapshot(current.directory, next)
        } catch (failure: Exception) {
            // Keep the previous committed prefix if appending or publishing the snapshot failed.
            current.output.channel.truncate(offset)
            throw failure
        }
        current.snapshot = next
    }

    private fun openSession(traceId: String): Session {
        session?.output?.close()
        session = null
        val dir = sessionDirFor(journalDir, traceId)
        require(!Files.isSymbolicLink(dir.toPath())) { "Unexpected journal directory link" }
        Files.createDirectories(dir.toPath())
        val eventsFile = dir.resolve("events.ndjson")
        val snapshotFile = dir.resolve("snapshot.json")
        require(!Files.isSymbolicLink(eventsFile.toPath()) && !Files.isSymbolicLink(snapshotFile.toPath())) {
            "Unexpected journal file link"
        }
        val snapshot = if (eventsFile.exists() || snapshotFile.exists()) {
            KernelJournalReader.verifyAndLoad(dir).snapshot.also {
                require(it.traceId == traceId) { "Journal traceId mismatch" }
            }
        } else KernelJournalSnapshot(traceId = traceId, revision = 0, eventCount = 0, lastEventSeq = 0, updatedAtMillis = 0)
        return Session(dir, FileOutputStream(eventsFile, true), snapshot).also { session = it }
    }

    private fun writeSnapshot(dir: File, snapshot: KernelJournalSnapshot) {
        val target = dir.resolve("snapshot.json").toPath()
        val tmp = Files.createTempFile(dir.toPath(), "snapshot-", ".tmp")
        try {
            Files.write(tmp, journalJson.encodeToString(snapshot).toByteArray(Charsets.UTF_8))
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    @Synchronized override fun close() {
        closed = true
        session?.output?.close()
        session = null
    }

    companion object {
        private val reserved = Regex("(?i)^(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\\..*)?$")

        fun safeTraceId(traceId: String): String {
            require(traceId.isNotBlank()) { "Blank journal traceId" }
            val stem = traceId.replace(Regex("[^A-Za-z0-9._-]"), "_").trim('.').take(80)
            // Reserve '~' for digest suffixes, including names which only differ in case on disk.
            if (stem == traceId && stem == stem.lowercase() && !reserved.matches(stem)) return stem
            val digest = MessageDigest.getInstance("SHA-256").digest(traceId.toByteArray(Charsets.UTF_8))
                .take(8).joinToString("") { "%02x".format(it) }
            return "trace-" + stem + "~" + digest
        }

        fun sessionDirFor(journalDir: File, traceId: String): File = journalDir.resolve(safeTraceId(traceId))
    }
}

object KernelJournalReader {
    fun verifyAndLoad(sessionDir: File): LoadedKernelJournal {
        require(sessionDir.isDirectory) { "Missing journal directory: " + sessionDir }
        val snapshotFile = sessionDir.resolve("snapshot.json")
        val eventsFile = sessionDir.resolve("events.ndjson")
        require(snapshotFile.isFile) { "Missing snapshot.json" }
        require(eventsFile.isFile) { "Missing events.ndjson" }
        val snapshot = journalJson.decodeFromString<KernelJournalSnapshot>(
            snapshotFile.readBytes().decodeToString(throwOnInvalidSequence = true))
        require(snapshot.schemaVersion == 1) { "Unsupported journal schemaVersion" }
        require(snapshot.traceId.isNotBlank()) { "Blank snapshot traceId" }
        require(snapshot.eventCount >= 0 && snapshot.revision == snapshot.eventCount) { "Invalid revision/eventCount" }

        val bytes = eventsFile.readBytes()
        require(bytes.isEmpty() || bytes.last() == 0x0A.toByte()) { "events.ndjson is missing its final newline" }
        var previousSeq = 0L
        val events = mutableListOf<KernelEvent>()
        val lines = if (bytes.isEmpty()) emptySequence() else
            bytes.decodeToString(endIndex = bytes.size - 1, throwOnInvalidSequence = true).splitToSequence('\n')
        for (line in lines) {
            val number = events.size + 1
            require(line.isNotBlank()) { "Blank journal line " + number }
            val event = try {
                journalJson.decodeFromString<KernelEvent>(line)
            } catch (failure: IllegalArgumentException) {
                throw IllegalArgumentException("Invalid event JSON at line " + number, failure)
            }
            require(event.traceId == snapshot.traceId) { "Mismatched traceId at line " + number }
            require(event.eventSeq > previousSeq) { "Non-increasing eventSeq at line " + number }
            previousSeq = event.eventSeq
            events.add(event)
        }
        require(events.size.toLong() == snapshot.eventCount) { "Journal eventCount mismatch" }
        require(events.isEmpty() || previousSeq == snapshot.lastEventSeq) { "Journal lastEventSeq mismatch" }
        return LoadedKernelJournal(snapshot, events)
    }
}
