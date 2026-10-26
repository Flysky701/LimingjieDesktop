package com.landosol.toolbox.desktop.device

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class MuMuDeviceDiscoveryTest {
    private fun player(port: Int, host: String = "127.0.0.1", running: Boolean = true) =
        """{"adb_host_ip":"$host","adb_port":$port,"is_android_started":$running,"is_process_started":$running,"error_code":0}"""

    @Test fun vendorReportFindsBothRunningInstancesWithoutGuessingPorts() {
        assertEquals(setOf("127.0.0.1:16384", "127.0.0.1:16416"),
            MuMuDeviceDiscovery.endpoints("""{"0":${player(16384)},"1":${player(16416)},"2":${player(16448, running=false)}}"""))
    }

    @Test fun invalidOrNonlocalEndpointsAreNeverRegistered() {
        for (report in listOf(player(16384, "192.0.2.1"), player(0), player(65536), "invalid JSON"))
            assertTrue(MuMuDeviceDiscovery.endpoints(report).isEmpty())
    }

    @Test fun registersOnlyTheMissingInstanceAndKeepsExistingTransport() = runBlocking {
        val connected = mutableListOf<String>()
        MuMuDeviceDiscovery.register(setOf("127.0.0.1:16384", "127.0.0.1:16416"),
            listOf(DeviceDescriptor("127.0.0.1:16416", "existing", "device")),
            { connected += it; FakeAdb.success("connected") }, {})
        assertEquals(listOf("127.0.0.1:16384"), connected)
    }

    @Test fun oneUnavailableInstanceDoesNotPreventRegisteringTheOther() = runBlocking {
        val connected = mutableListOf<String>()
        MuMuDeviceDiscovery.register(linkedSetOf("127.0.0.1:16384", "127.0.0.1:16416"), emptyList(), {
            connected += it
            if (it.endsWith(":16384")) AdbResult.Failure(AdbFailureKind.ConnectionRefused, "not ready") else FakeAdb.success("connected")
        }, {})
        assertEquals(2, connected.size)
    }
}
