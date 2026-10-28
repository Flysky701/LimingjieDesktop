package com.landosol.toolbox.desktop.app.control

import com.landosol.toolbox.account.AccountUiState
import com.landosol.toolbox.desktop.app.ui.RunConsoleState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class AccountDraftInputTest {
    @Test fun keystrokesAndImeCompositionEchoBeforeTheNextEvent() = runBlocking {
        withContext(Dispatchers.Main) {
            val port = DraftPort()
            val ui = ControlSessionUi(port)
            try {
                ui.accounts.openNewAccount()
                // An IME can deliver several composing/committed updates in one UI turn.
                for (text in listOf("T", "Te", "Tes", "Test", "", "n", "ni", "你", "你好")) {
                    ui.accounts.updateEditor { it.copy(alias = text) }
                    assertEquals("The input must echo synchronously", text, ui.accounts.uiState.value.editor?.alias)
                }
                assertEquals(0, port.requests)
            } finally { ui.close() }
        }
    }

    @Test fun backendRefreshKeepsTheNewestLocalDraft() = runBlocking {
        val port = DraftPort()
        val ui = withContext(Dispatchers.Main) { ControlSessionUi(port) }
        try {
            withContext(Dispatchers.Main) {
                ui.accounts.openNewAccount()
                ui.accounts.updateEditor { it.copy(alias = "中文 Test", password = "synthetic-unsaved") }
                port.snapshots.value = port.document("backend-refresh")
            }
            withTimeout(5_000) { ui.accounts.uiState.first { it.message == "backend-refresh" } }
            assertEquals("中文 Test", ui.accounts.uiState.value.editor?.alias)
            assertEquals("synthetic-unsaved", ui.accounts.uiState.value.editor?.password)
            assertEquals(0, port.requests)
        } finally { withContext(Dispatchers.Main) { ui.close() } }
    }

    @Test fun sectionDedupStillPublishesCaptchaAndTaskCompletion() = runBlocking<Unit> {
        val port = DraftPort()
        val ui = withContext(Dispatchers.Main) { ControlSessionUi(port) }
        val captcha = buildJsonObject { put("ticketId", "synthetic-ticket"); put("url", "http://127.0.0.1/") }
        try {
            val initial = port.document()
            port.snapshots.value = JsonObject(initial + mapOf(
                "console" to ControlModelCodec.encode(RunConsoleState(busy = true)), "captcha" to captcha))
            withTimeout(5_000) { ui.captcha.first { it == captcha }; ui.ux.console.first { it.busy } }
            val console = ui.ux.console.value
            // An unrelated update preserves the already decoded console object.
            port.snapshots.value = JsonObject(port.snapshots.value + ("logRevision" to JsonPrimitive(1)))
            withTimeout(5_000) { ui.log.revision.first { it == 1L } }
            assertSame(console, ui.ux.console.value)
            port.snapshots.value = JsonObject(initial + mapOf("captcha" to JsonNull,
                "console" to ControlModelCodec.encode(console.copy(busy = false,
                    task = console.task.copy(message = "任务已完成")))))
            withTimeout(5_000) {
                ui.captcha.first { it == null }
                ui.ux.console.first { !it.busy && it.task.message == "任务已完成" }
            }
        } finally { withContext(Dispatchers.Main) { ui.close() } }
    }

    private class DraftPort : ControlSessionPort {
        override val instanceId = "draft-test"
        override val workerRunId = "draft-run"
        fun document(message: String? = null) = buildJsonObject {
            put("console", ControlModelCodec.encode(RunConsoleState()))
            put("accounts", ControlModelCodec.encode(AccountUiState(message = message)))
        }
        override val snapshots = MutableStateFlow(document())
        override val connection = MutableStateFlow(WorkerConnectionStatus.CONNECTED)
        override val error = MutableStateFlow<String?>(null)
        var requests = 0
        override suspend fun request(op: String, args: JsonObject): JsonObject {
            requests++
            return JsonObject(emptyMap())
        }
        override fun setSelected(selected: Boolean) = Unit
        override suspend fun shutdown() = Unit
    }
}
