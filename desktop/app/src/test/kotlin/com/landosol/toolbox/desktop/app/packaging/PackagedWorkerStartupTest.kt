package com.landosol.toolbox.desktop.app.packaging

import com.landosol.toolbox.desktop.app.control.*
import com.landosol.toolbox.desktop.platform.WorkerProcessContainment
import java.nio.file.Path
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Real packaged JVMs, fresh local data, no accounts, discovery, capture, or game actions. */
class PackagedWorkerStartupTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun packagedWorkersHaveIndependentGraphsAndReleaseTheirDataDirectories() = runBlocking {
        val executable = System.getProperty("limingjie.test.packagedWorker")
        assumeTrue("Run explicitly with -PpackagedWorkerExecutable=<exe>", executable != null)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val launcher = WorkerLauncher(scope, WorkerProcessContainment::own, listOf(checkNotNull(executable), "--offline-startup"))
        val aDirectory = temporary.newFolder("packaged-a").toPath()
        val bDirectory = temporary.newFolder("packaged-b").toPath()
        val a = launcher.start(UUID.randomUUID().toString(), "打包检查 A", aDirectory)
        val b = launcher.start(UUID.randomUUID().toString(), "打包检查 B", bDirectory)
        try {
            suspend fun ready(link: WorkerLink): JsonObject = withTimeout(60_000) {
                link.connection.first { it != WorkerConnectionStatus.STARTING }
                assertEquals(link.error.value, WorkerConnectionStatus.CONNECTED, link.connection.value)
                link.snapshots.first { it["console"] != null }
            }
            val first = ready(a)
            val second = ready(b)
            assertEquals(a.instanceId, first["profileId"]!!.jsonPrimitive.content)
            assertEquals(b.instanceId, second["profileId"]!!.jsonPrimitive.content)
            assertEquals(aDirectory, Path.of(first["dataDirectory"]!!.jsonPrimitive.content))
            assertEquals(bDirectory, Path.of(second["dataDirectory"]!!.jsonPrimitive.content))
            assertTrue(first["accounts"]!!.jsonObject["accounts"]!!.jsonArray.isEmpty())
            assertTrue(second["accounts"]!!.jsonObject["accounts"]!!.jsonArray.isEmpty())
            assertNotNull(first["debugUrl"]?.jsonPrimitive?.contentOrNull)
            assertNotEquals(first["debugUrl"], second["debugUrl"])
            a.request(ControlOps.PREFS_PATCH, buildJsonObject { put("theme", "DARK") })
            withTimeout(10_000) { a.snapshots.first { it["preferences"]?.jsonObject?.get("theme")?.jsonPrimitive?.content == "DARK" } }
            assertEquals("SYSTEM", b.snapshots.value["preferences"]!!.jsonObject["theme"]!!.jsonPrimitive.content)
            a.shutdown()
            com.landosol.toolbox.desktop.platform.InstanceLock.acquire(aDirectory).close()
            b.request(ControlOps.PREFS_PATCH, buildJsonObject { put("largeText", true) })
            withTimeout(10_000) { b.snapshots.first { it["preferences"]?.jsonObject?.get("largeText")?.jsonPrimitive?.booleanOrNull == true } }
        } finally { a.shutdown(); b.shutdown(); scope.cancel() }
        com.landosol.toolbox.desktop.platform.InstanceLock.acquire(bDirectory).close()
    }
}
