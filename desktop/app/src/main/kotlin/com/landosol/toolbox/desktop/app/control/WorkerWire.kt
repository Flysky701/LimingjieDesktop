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

    fun read(): JsonObject {
        val size = input.readInt()
        require(size in 1..WorkerProtocol.MAX_FRAME_BYTES) { "控制消息长度无效" }
        val bytes = ByteArray(size)
        input.readFully(bytes)
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
