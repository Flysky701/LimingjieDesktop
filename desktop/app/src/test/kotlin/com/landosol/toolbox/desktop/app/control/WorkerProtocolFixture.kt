package com.landosol.toolbox.desktop.app.control

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*

/** Test-only child JVM. It has no graph, device, account data, OCR, or network service. */
fun main(args: Array<String>) = runBlocking {
    fun argument(key: String) = args[args.indexOf(key) + 1]
    val id = argument("--instance-id")
    val directory = Path.of(argument("--data-dir"))
    val count = AtomicInteger()
    val revoked = AtomicBoolean()
    val domain = object : WorkerDomain {
        override suspend fun snapshot(includePreview: Boolean) = buildJsonObject {
            put("instance", id); put("count", count.get()); put("preview", includePreview)
        }
        override suspend fun execute(op: String, args: JsonObject): JsonObject {
            check(!revoked.get()) { "revoked" }
            if (op == "reject") error("fixture rejection")
            count.addAndGet(args["add"]?.jsonPrimitive?.intOrNull ?: 0)
            return buildJsonObject { put("instance", id); put("count", count.get()); put("pid", ProcessHandle.current().pid()) }
        }
        override fun revokeActions(reason: String) { revoked.set(true) }
        override suspend fun shutdown() { Files.writeString(directory.resolve("shutdown.marker"), "revoked=${revoked.get()}") }
    }
    runWorkerTransport(System.getenv(WorkerProtocol.PORT_ENV).toInt(), System.getenv(WorkerProtocol.TOKEN_ENV), id, argument("--worker-run-id"), domain)
}
