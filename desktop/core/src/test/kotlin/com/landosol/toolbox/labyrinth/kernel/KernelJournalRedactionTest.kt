package com.landosol.toolbox.labyrinth.kernel

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class KernelJournalRedactionTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun redactsFieldsBeforeWritingAndReopensWithTheSameSnapshotIdentity() {
        val root = temporary.newFolder()
        val traceId = "token=trace-fixture"
        val event = KernelEventEnvelope(
            eventSeq = 1L, traceId = traceId, causeEventSeq = null, timestampMillis = 123L,
            frameSeq = null, sceneEpoch = 0L, domain = KernelDomain.CUSTOM_HOOK, kind = "fixture",
            reasonCode = "fixture", summary = "连接失败 password=summary-fixture",
            payload = CustomPayload(mapOf("secret" to "map-fixture", "text" to "v1:ABCDEFGHIJKLMNOPQRSTUVWXYZ12+/=", "hint" to "保留")),
        )
        KernelJournalSink(root).use { it.append(event) }
        KernelJournalSink(root).use { it.append(event.copy(eventSeq = 2L, timestampMillis = 456L)) }

        val directory = KernelJournalSink.sessionDirFor(root, traceId)
        val loaded = KernelJournalReader.verifyAndLoad(directory)
        assertEquals("token=<redacted>", loaded.snapshot.traceId)
        assertEquals(2L, loaded.snapshot.revision)
        assertEquals(456L, loaded.snapshot.updatedAtMillis)
        assertEquals(listOf(1L, 2L), loaded.events.map { it.eventSeq })
        val safe = loaded.events.first()
        assertEquals(loaded.snapshot.traceId, safe.traceId)
        assertEquals("连接失败 password=<redacted>", safe.summary)
        assertEquals(CustomPayload(mapOf("secret" to "<redacted>", "text" to "<encrypted-redacted>", "hint" to "保留")), safe.payload)
        assertFalse(directory.name.contains("trace-fixture"))
        val files = directory.resolve("events.ndjson").readText() + directory.resolve("snapshot.json").readText()
        for (secret in listOf("trace-fixture", "summary-fixture", "map-fixture", "ABCDEFGHIJKLMNOPQRSTUVWXYZ12+/=")) {
            assertFalse(files.contains(secret))
        }
    }
}
