package com.landosol.toolbox.desktop.app.adapters.device

import com.landosol.toolbox.desktop.device.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test

class BatchClientPreflightTest {
    @Test fun `running client is stopped once and verified while absent client is untouched`() = runBlocking {
        val fake=FakeDevice()
        assertTrue(prepareBatchClient(fake,"pcr.selected"))
        assertEquals(listOf("query:pcr.selected","stop:pcr.selected","query:pcr.selected"),fake.calls)
        fake.calls.clear()
        assertFalse(prepareBatchClient(fake,"pcr.selected"))
        assertEquals(listOf("query:pcr.selected"),fake.calls)
    }

    @Test fun `query stop and exit confirmation failures block and cancellation propagates`() = runBlocking {
        for (mode in listOf("query","stop","alive","cancel")) {
            val fake=FakeDevice().apply { failure=mode }
            val failure=runCatching { prepareBatchClient(fake,"pcr.selected") }.exceptionOrNull()
            assertNotNull(mode,failure)
            if(mode=="cancel") assertTrue(failure is CancellationException)
            else assertTrue(failure!!.message.orEmpty().contains("批量任务未启动"))
            assertTrue(fake.calls.count { it.startsWith("stop:") }<=1)
            if(mode=="query") assertEquals(listOf("query:pcr.selected"),fake.calls)
        }
    }

    private class FakeDevice:DeviceService {
        var running=true
        var failure=""
        val calls=mutableListOf<String>()
        override val state=MutableStateFlow<DeviceConnectionState>(DeviceConnectionState.Disconnected)
        override val foregroundPackage:String?=null
        override suspend fun isPackageRunning(packageName:String):Boolean {
            calls+="query:$packageName"
            if(failure=="query") error("query failed")
            if(failure=="cancel") throw CancellationException("cancelled")
            return running
        }
        override suspend fun forceStop(packageName:String):Boolean {
            calls+="stop:$packageName"
            if(failure=="stop") return false
            if(failure!="alive") running=false
            return true
        }
        override suspend fun discover()=emptyList<DeviceDescriptor>()
        override suspend fun connect(serial:String,touchMode:TouchMode) {}
        override suspend fun disconnect() {}
        override suspend fun capture():DeviceFrame=error("unused")
        override suspend fun queryForeground():String?=null
        override suspend fun tap(x:Float,y:Float,expectedPackage:String):TouchResult=error("unused")
        override suspend fun swipe(fromX:Float,fromY:Float,toX:Float,toY:Float,durationMillis:Long,holdMillis:Long,expectedPackage:String):TouchResult=error("unused")
        override suspend fun back(expectedPackage:String):TouchResult=error("unused")
        override suspend fun home():Boolean=error("unused")
        override suspend fun launch(packageName:String):Boolean=error("unused")
        override suspend fun uiTree()=emptyList<UiNode>()
        override fun close() {}
    }
}
