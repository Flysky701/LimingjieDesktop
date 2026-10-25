package com.landosol.toolbox.desktop.app.control

import kotlinx.serialization.json.JsonObject

/** Business operations live in one worker JVM, never in the supervisor UI. */
interface WorkerDomain {
    suspend fun snapshot(includePreview: Boolean): JsonObject
    suspend fun execute(op: String, args: JsonObject): JsonObject
    /** Must synchronously revoke permission for new work before asynchronous cleanup starts. */
    fun revokeActions(reason: String)
    suspend fun shutdown()
}

enum class WorkerConnectionStatus { STARTING, CONNECTED, LOST, CLOSED }

internal object WorkerProtocol {
    const val VERSION = 1
    const val MAX_FRAME_BYTES = 16 * 1024 * 1024
    const val HEARTBEAT_MILLIS = 1_000L
    const val LEASE_MILLIS = 8_000L
    const val TOKEN_ENV = "LIMINGJIE_WORKER_TOKEN"
    const val PORT_ENV = "LIMINGJIE_WORKER_PORT"
}
