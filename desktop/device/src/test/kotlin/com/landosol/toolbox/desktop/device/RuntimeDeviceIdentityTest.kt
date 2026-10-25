package com.landosol.toolbox.desktop.device

import com.landosol.toolbox.desktop.platform.UserResourceLeases
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RuntimeDeviceIdentityTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun resolveUsesBootProofWithoutCaptureTouchOrGameInput() = runBlocking {
        val adb = FakeAdb()
        AdbDeviceService(adb, temporary.root.toPath(), emptySet(),
            touchFactory = TouchConnectionFactory { _, _, _ -> error("Identity resolution must not install touch") },
            leases = UserResourceLeases(temporary.root.toPath().resolve("leases")), expectedBootId = null).use { device ->
            val result = device.resolveDevice("127.0.0.1:5555")
            assertEquals("10000000-1111-2222-3333-444444444444", result.bootId)
            assertEquals(DeviceConnectionState.Disconnected, device.state.value)
            assertTrue(adb.calls.all { it.arguments in listOf(listOf("get-state"),
                listOf("cat", "/proc/sys/kernel/random/boot_id"), listOf("getprop", "ro.product.model")) })
        }
    }

    @Test fun changedBootIsRejectedBeforeTouchOrCapture() = runBlocking {
        val adb = FakeAdb()
        AdbDeviceService(adb, temporary.root.toPath(), emptySet(),
            touchFactory = TouchConnectionFactory { _, _, _ -> error("Changed boot must not install touch") },
            leases = UserResourceLeases(temporary.root.toPath().resolve("leases")),
            expectedBootId = "20000000-1111-2222-3333-444444444444").use { device ->
            device.connect("127.0.0.1:5555")
            assertTrue(device.state.value is DeviceConnectionState.Failed)
            assertFalse(adb.calls.any { it.screenshot || it.arguments.take(2) == listOf("exec-out", "screencap") })
        }
    }

    @Test fun unverifiedBootCannotResolveAsAnotherKnownDevice() = runBlocking {
        val adb = FakeAdb()
        adb.handler = { call ->
            if (call.arguments == listOf("cat", "/proc/sys/kernel/random/boot_id")) FakeAdb.success("") else adb.default(call)
        }
        AdbDeviceService(adb, temporary.root.toPath(), emptySet(), expectedBootId = null).use { device ->
            assertTrue(runCatching { device.resolveDevice("127.0.0.1:5555") }.isFailure)
            assertEquals(DeviceConnectionState.Disconnected, device.state.value)
        }
    }
}
