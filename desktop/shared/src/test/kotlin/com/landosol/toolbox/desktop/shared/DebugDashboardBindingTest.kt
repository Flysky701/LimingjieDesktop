package com.landosol.toolbox.desktop.shared

import com.landosol.toolbox.labyrinth.debug.LabyrinthDebugDashboardServer
import org.junit.Assert.*
import org.junit.Test
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

class DebugDashboardBindingTest {
    @Test fun ephemeralPortsAreBoundOnceAndReleasedOnClose() {
        val first=LabyrinthDebugDashboardServer(port=0,allowAddressReuse=false)
        val second=LabyrinthDebugDashboardServer(port=0,allowAddressReuse=false)
        try {
            first.start();second.start()
            val a=requireNotNull(first.boundPort);val b=requireNotNull(second.boundPort)
            assertNotEquals(a,b)
            Socket().use { it.connect(InetSocketAddress("127.0.0.1",a),1000) }
            assertThrows(java.net.BindException::class.java) {
                ServerSocket().use { it.reuseAddress=false;it.bind(InetSocketAddress("127.0.0.1",a)) }
            }
            first.close()
            assertNull(first.boundPort)
            Socket().use { it.connect(InetSocketAddress("127.0.0.1",b),1000) }
        } finally { first.close();second.close() }
    }
}
