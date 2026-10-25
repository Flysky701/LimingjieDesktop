package com.landosol.toolbox.desktop.app.control

import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonObject

interface WorkerLink {
    val instanceId: String
    val workerRunId: String
    val snapshots: StateFlow<JsonObject>
    val connection: StateFlow<WorkerConnectionStatus>
    val error: StateFlow<String?>
    suspend fun request(op: String, args: JsonObject = JsonObject(emptyMap())): JsonObject
    fun setSelected(selected: Boolean)
    suspend fun shutdown()
}
