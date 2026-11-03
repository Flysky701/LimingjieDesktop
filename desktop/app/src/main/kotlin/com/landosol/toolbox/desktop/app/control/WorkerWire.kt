package com.landosol.toolbox.desktop.app.control

import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Socket
import kotlinx.serialization.json.*

/** One reader per connection; a write lock keeps commands, replies and snapshots framed. */
internal class WorkerWire(val socket: Socket) : AutoCloseable {
    private val input = DataInputStream(socket.getInputStream().buffered())
    private val output = DataOutputStream(socket.getOutputStream().buffered())
    private val outputLock = Any()
    var lastReadBytes = 0
        private set

    fun read(): JsonObject {
        val size = input.readInt()
        require(size in 1..WorkerProtocol.MAX_FRAME_BYTES) { "控制消息长度无效" }
        val bytes = ByteArray(size)
        input.readFully(bytes)
        lastReadBytes = size
        return Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
    }

    fun write(value: JsonObject) = synchronized(outputLock) {
        val bytes = value.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= WorkerProtocol.MAX_FRAME_BYTES) { "控制消息过大" }
        output.writeInt(bytes.size)
        output.write(bytes)
        output.flush()
    }

    override fun close() { runCatching { socket.close() } }
}

internal fun JsonObject.wireString(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull
internal fun JsonObject.wireObject(key: String): JsonObject = get(key) as? JsonObject ?: JsonObject(emptyMap())
internal fun wireMessage(kind: String, profileId: String, runId: String, content: JsonObjectBuilder.() -> Unit = {}): JsonObject = buildJsonObject {
    put("kind", kind); put("profileId", profileId); put("runId", runId); content()
}

/** A full state also represents key removal, leaving JSON null available as a real field value. */
internal fun workerStateMessage(
    profileId: String, runId: String, sequence: Long, previous: JsonObject?, state: JsonObject,
): JsonObject? {
    if (previous === state) return null
    val field: String
    val payload: JsonObject
    if (previous == null || previous.keys.any { it !in state }) {
        field = "state"
        payload = state
    } else {
        val changed = state.filter { (key, value) ->
            val old = previous[key]
            // Cached sections can contain hundreds of rows. JSON collections compare structurally.
            old !== value && old != value
        }
        if (changed.isEmpty()) return null
        field = "patch"
        payload = JsonObject(changed)
    }
    return wireMessage("state", profileId, runId) {
        put("sequence", sequence)
        put(field, payload)
    }
}

/** One receiver per authenticated worker connection. Only its socket reader advances the baseline. */
internal class WorkerStateReceiver(private val maxBytes: Int = WorkerProtocol.MAX_FRAME_BYTES) {
    private var sequence = 0L
    private var state = JsonObject(emptyMap())
    private var byteUpperBound = 0

    fun accept(message: JsonObject, frameBytes: Int): JsonObject? {
        val next = message.getValue("sequence").jsonPrimitive.long
        if (next <= sequence) return null
        val full = message["state"]
        val updated = if (full != null) full.jsonObject else {
            check(sequence != 0L && next == sequence + 1) { "增量状态缺少连续的实例快照" }
            JsonObject(state + message.getValue("patch").jsonObject)
        }
        // Combining two compact JSON objects cannot cost more than the sum of their wire sizes.
        // Recount only when this conservative bound crosses the original full-frame limit.
        val bound = if (full != null) frameBytes.toLong() else byteUpperBound.toLong() + frameBytes
        byteUpperBound = if (bound <= maxBytes) bound.toInt() else {
            val complete = JsonObject(message - "patch" + ("state" to updated))
            val bytes = complete.toString().toByteArray(Charsets.UTF_8).size
            require(bytes <= maxBytes) { "实例快照超过控制消息长度上限" }
            bytes
        }
        state = updated
        sequence = next
        return updated
    }
}
