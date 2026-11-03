package com.landosol.toolbox.desktop.app.control

import com.landosol.toolbox.desktop.app.UserLogLine
import com.landosol.toolbox.desktop.app.adapters.storage.DesktopRunRecord
import com.landosol.toolbox.labyrinth.LabyrinthRoleRatingItem
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ControlSessionListTest {
    @Test fun unchangedListsSkipConversionWhileRowEditsAppendsAndClearsPassThrough() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var logs = listOf(line("first"))
        var history = listOf(record("first"))
        var catalog = listOf(role("first"))
        val snapshots = MutableStateFlow(ControlUiSnapshot(document(logs, history, catalog = catalog)))
        var logReads = 0
        var historyReads = 0
        var catalogReads = 0
        val lines = snapshots.decodedListField(scope, "logs") {
            logReads++; it.model<List<UserLogLine>>("logs", emptyList())
        }
        val records = snapshots.decodedListField(scope, "history") {
            historyReads++; it.model<List<DesktopRunRecord>>("history", emptyList())
        }
        val roles = snapshots.decodedListField(scope, "catalog") {
            catalogReads++; it.model<List<LabyrinthRoleRatingItem>>("catalog", emptyList())
        }
        fun publish(revision: Int = 0) { snapshots.value = ControlUiSnapshot(document(logs, history, revision, catalog)) }
        try {
            repeat(3) { publish(it + 1) } // New JSON objects, same list contents.
            assertEquals(1, logReads)
            assertEquals(1, historyReads)
            assertEquals(1, catalogReads)
            logs = logs + line("second")
            publish()
            assertEquals(logs, lines.value)
            assertEquals(2, logReads)
            assertEquals(1, historyReads)
            logs = logs.dropLast(1) + logs.last().copy(repeats = 2)
            publish()
            assertEquals(logs, lines.value)
            assertEquals(3, logReads)
            history = listOf(history.single().copy(result = "完成", endedAt = 20)) + record("second")
            publish()
            assertEquals(history, records.value)
            assertEquals(2, historyReads)
            assertEquals(3, logReads)
            assertEquals(1, catalogReads)
            catalog = listOf(catalog.single().copy(name = "renamed", iconAsset = "icons/updated.png"))
            publish()
            assertEquals(catalog, roles.value)
            assertEquals(2, catalogReads)
            logs = emptyList()
            history = emptyList()
            catalog = emptyList()
            publish()
            assertTrue(lines.value.isEmpty())
            assertTrue(records.value.isEmpty())
            assertTrue(roles.value.isEmpty())
            logs = listOf(line("after-clear"))
            history = listOf(record("after-clear"))
            catalog = listOf(role("after-clear"), role("added"))
            publish()
            assertEquals(logs, lines.value)
            assertEquals(history, records.value)
            assertEquals(catalog, roles.value)
            assertEquals(5, logReads)
            assertEquals(4, historyReads)
            assertEquals(4, catalogReads)
        } finally { scope.cancel() }
    }

    @Test fun uiListsStaySeparateAcrossSessionsAndWorkerReplacement() = runBlocking<Unit> {
        val a = ListPort("a", "run-1", document(listOf(line("a")), listOf(record("a")), catalog = listOf(role("a"))))
        val b = ListPort("b", "run-2", document(listOf(line("b")), listOf(record("b")), catalog = listOf(role("b"))))
        val first = withContext(Dispatchers.Main) { ControlSessionUi(a) }
        val second = withContext(Dispatchers.Main) { ControlSessionUi(b) }
        var replacement: ControlSessionUi? = null
        try {
            awaitLists(first, "a")
            awaitLists(second, "b")
            a.snapshots.value = document(listOf(line("updated-a")), listOf(record("updated-a")), catalog = listOf(role("updated-a")))
            awaitLists(first, "updated-a")
            assertEquals("b", second.log.lines.value.single().text)
            assertEquals("b", second.ux.history.records.value.single().id)
            assertEquals("b", second.ux.catalog.value.single().id)
            val nextPort = ListPort("a", "run-3", document(emptyList(), emptyList()))
            val next = withContext(Dispatchers.Main) { ControlSessionUi(nextPort, previous = first) }
            replacement = next
            withTimeout(5_000) {
                next.log.lines.first { it.isEmpty() }
                next.ux.history.records.first { it.isEmpty() }
                next.ux.catalog.first { it.isEmpty() }
            }
            withContext(Dispatchers.Main) { first.close() }
            nextPort.snapshots.value = document(listOf(line("new-a")), listOf(record("new-a")), catalog = listOf(role("new-a")))
            awaitLists(next, "new-a")
            assertEquals("updated-a", first.log.lines.value.single().text)
            assertEquals("updated-a", first.ux.catalog.value.single().id)
            assertEquals("b", second.log.lines.value.single().text)
            assertEquals("b", second.ux.history.records.value.single().id)
            assertEquals("b", second.ux.catalog.value.single().id)
        } finally {
            withContext(Dispatchers.Main) { first.close(); second.close(); replacement?.close() }
        }
    }

    private suspend fun awaitLists(ui: ControlSessionUi, text: String) = withTimeout(5_000) {
        ui.log.lines.first { it.singleOrNull()?.text == text }
        ui.ux.history.records.first { it.singleOrNull()?.id == text }
        ui.ux.catalog.first { it.singleOrNull()?.id == text }
    }

    private fun line(text: String) = UserLogLine("12:00:00", text, false)
    private fun record(id: String) = DesktopRunRecord(id, 10, "synthetic", "test", "test")
    private fun role(id: String) = LabyrinthRoleRatingItem(id, id, 50.0, null, "synthetic")
    private fun document(logs: List<UserLogLine>, history: List<DesktopRunRecord>, revision: Int = 0,
                         catalog: List<LabyrinthRoleRatingItem> = emptyList()) = buildJsonObject {
        put("console", JsonObject(emptyMap()))
        put("logs", ControlModelCodec.encode(logs)); put("history", ControlModelCodec.encode(history))
        put("catalog", ControlModelCodec.encode(catalog))
        put("revision", revision)
    }

    private class ListPort(override val instanceId: String, override val workerRunId: String, initial: JsonObject) : ControlSessionPort {
        override val snapshots = MutableStateFlow(initial)
        override val connection = MutableStateFlow(WorkerConnectionStatus.CONNECTED)
        override val error = MutableStateFlow<String?>(null)
        override suspend fun request(op: String, args: JsonObject): JsonObject = error("No commands in list tests")
        override fun setSelected(selected: Boolean) = Unit
        override suspend fun shutdown() = Unit
    }
}
