package com.landosol.toolbox.desktop.app.adapters.device

import com.landosol.toolbox.desktop.device.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ManagedDeviceServiceTest {
    @get:Rule val directory = TemporaryFolder()

    @Test fun successfulLookupIsReusedUntilConfigurationChanges() = runBlocking {
        var selected = "first"; var lookups = 0; val services = mutableListOf<ConnectionFixture>()
        ManagedDeviceService(directory.root.toPath(), { selected }, {}, {},
            locate = { path, _ -> lookups++; AdbLocation(path!!, "fixture", "version") },
            createService = { ConnectionFixture().also(services::add) }).use { managed ->
            managed.prepare(); managed.discover(); managed.connect("fixture"); managed.disconnect(); managed.connect("fixture")
            assertEquals(1, lookups); assertEquals(1, services.size)
            selected = "second"
            try { managed.discover(); fail("connected ADB cannot change") } catch (_: IllegalStateException) { }
            assertEquals(1, lookups)
            managed.disconnect(); managed.discover()
            assertEquals(2, lookups); assertEquals(2, services.size); assertEquals(1, services.first().closes)
            managed.prepare() // Explicit "save and check" revalidates even an unchanged path.
            assertEquals(3, lookups); assertEquals(2, services.size)
        }
        assertEquals(1, services.last().closes)
    }

    @Test fun cancelledLookupDoesNotCreateServiceAndCanRetry() = runBlocking {
        val entered = CompletableDeferred<Unit>(); var shouldWait = true; var creations = 0
        ManagedDeviceService(directory.root.toPath(), { "adb" }, {}, {},
            locate = { _, _ -> if (shouldWait) { entered.complete(Unit); awaitCancellation() }; AdbLocation("adb", "fixture", "v") },
            createService = { creations++; ConnectionFixture() }).use { managed ->
            val lookup = launch { managed.discover() }
            entered.await(); lookup.cancelAndJoin(); assertEquals(0, creations)
            shouldWait = false; managed.discover(); assertEquals(1, creations)
        }
    }

    @Test fun failedExplicitRecheckInvalidatesPreviouslyResolvedService() = runBlocking {
        var available = true; var lookups = 0; val device = ConnectionFixture()
        ManagedDeviceService(directory.root.toPath(), { "adb" }, {}, {},
            locate = { _, _ -> lookups++; if (available) AdbLocation("adb", "fixture", "v") else null },
            createService = { device }).use { managed ->
            managed.prepare()
            available = false
            try { managed.prepare(); fail("invalid selection") } catch (_: IllegalStateException) { }
            try { managed.discover(); fail("must not use previous successful validation") } catch (_: IllegalStateException) { }
            assertEquals(3, lookups); assertEquals(1, device.closes); assertEquals(0, device.discoveries)
        }
    }
}
