package com.landosol.toolbox.desktop.app.adapters.device

import com.landosol.toolbox.desktop.device.*
import com.landosol.toolbox.desktop.app.ui.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test

class VisualResolutionGuardTest {
    @Test fun `unsupported frame rejects startup and every device action`() = runBlocking {
        val fake = FakeDevice(960, 540)
        val guard = VisualResolutionGuard(fake)
        val actions = ResolutionGuardedDevice(fake, guard)
        val failure = runCatching { guard.checkForStart() }.exceptionOrNull()
        assertTrue(failure?.message.orEmpty().contains("960 × 540"))
        assertTrue(actions.tap(1f, 2f, "game") is TouchResult.Rejected)
        assertTrue(actions.swipe(1f, 2f, 3f, 4f, 100, 0, "game") is TouchResult.Rejected)
        assertTrue(actions.back("game") is TouchResult.Rejected)
        assertFalse(actions.home()); assertFalse(actions.forceStop("game")); assertFalse(actions.launch("game"))
        assertEquals(0, fake.actions)
        assertEquals("960×540", guard.status.value.dimensionsLabel)
        assertEquals(guard.status.value.message, startMissing(DesktopTask.ENTRY, RunConsoleState(device=fake.state.value), true, true, resolution=guard.status.value))
        assertEquals("Devices", explainStop(null, guard.status.value.message).destination)
    }

    @Test fun `real landscape passes even when wm reports portrait and labels use frame dimensions`() = runBlocking {
        for ((width, height) in listOf(1920 to 1080, 1280 to 720)) {
            val fake = FakeDevice(width, height)
            val guard = VisualResolutionGuard(fake)
            assertTrue(deviceLabel(fake.state.value).contains("待检查"))
            guard.checkForStart()
            assertTrue(guard.status.value.supported)
            assertTrue(deviceLabel(fake.state.value,guard.status.value.dimensionsLabel).contains("${width}×${height}"))
            assertEquals(TouchResult.Completed, ResolutionGuardedDevice(fake,guard).tap(1f,2f,"game"))
            assertEquals(1, fake.actions)
            assertEquals(1, fake.captures)
        }
    }

    @Test fun `all actions reuse startup permission without another capture`() = runBlocking {
        val fake = FakeDevice(1280,720)
        val guard = VisualResolutionGuard(fake)
        val actions = ResolutionGuardedDevice(fake,guard)
        guard.checkForStart()
        fake.onCapture = { error("actions must not request a screenshot") }
        assertEquals(TouchResult.Completed,actions.tap(1f,2f,"game"))
        assertEquals(TouchResult.Completed,actions.swipe(1f,2f,3f,4f,100,0,"game"))
        assertEquals(TouchResult.Completed,actions.back("game"))
        assertTrue(actions.home())
        assertTrue(actions.forceStop("game"))
        assertTrue(actions.launch("game"))
        assertEquals(6,fake.actions)
        assertEquals(1,fake.captures)
        guard.disarm()
        assertTrue(actions.tap(1f,2f,"game") is TouchResult.Rejected)
        assertEquals(6,fake.actions)
        assertEquals(1,fake.captures)
    }

    @Test fun `manual recheck does not grant or restore task permission`() = runBlocking {
        val fake = FakeDevice(1920,1080)
        val guard = VisualResolutionGuard(fake)
        val actions = ResolutionGuardedDevice(fake,guard)
        assertTrue(guard.recheck().supported)
        assertTrue(actions.tap(1f,2f,"game") is TouchResult.Rejected)
        guard.checkForStart()
        assertEquals(TouchResult.Completed,actions.tap(1f,2f,"game"))
        guard.disarm()
        guard.recheck()
        assertTrue(actions.tap(1f,2f,"game") is TouchResult.Rejected)
        assertEquals(1,fake.actions)
    }

    @Test fun `both supported sizes can restart through portrait without portrait taps or extra captures`() = runBlocking {
        for ((width,height) in listOf(1920 to 1080,1280 to 720)) {
            val fake=FakeDevice(width,height)
            val guard=VisualResolutionGuard(fake)
            val actions=ResolutionGuardedDevice(fake,guard)
            guard.checkForStart()
            assertTrue(actions.home())
            assertTrue(guard.deferTransitionFrame(height,width))
            assertTrue(actions.tap(1f,2f,"game") is TouchResult.Rejected)
            assertTrue(actions.forceStop("game"))
            assertTrue(actions.launch("game"))
            assertFalse(guard.deferTransitionFrame(width,height))
            assertEquals(TouchResult.Completed,actions.tap(1f,2f,"game"))
            assertFalse(guard.deferTransitionFrame(height,width))
            assertEquals(4,fake.actions)
            assertEquals(1,fake.captures)
        }
    }

    @Test fun `stop during screenshot check cannot grant a late action permit`() = runBlocking {
        val fake=FakeDevice(1920,1080)
        val guard=VisualResolutionGuard(fake)
        val began=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        fake.onCapture={began.complete(Unit);release.await()}
        val checking=async { runCatching { guard.checkForStart() } }
        began.await();guard.disarm();release.complete(Unit)
        assertTrue(checking.await().isFailure)
        assertTrue(ResolutionGuardedDevice(fake,guard).tap(1f,2f,"game") is TouchResult.Rejected)
    }

    private class FakeDevice(var width:Int,var height:Int):DeviceService {
        val identity=DeviceIdentity("fake","fake","fake","12",31,emptyList(),"",1080,1920,emptyMap(),0)
        override val state=MutableStateFlow<DeviceConnectionState>(DeviceConnectionState.Ready(DeviceReady("fake","fake",1080,1920,emptyList(),CaptureMode.Raw,true,true,identity)))
        override val foregroundPackage="game"
        var actions=0
        var captures=0
        var onCapture:suspend()->Unit={}
        override suspend fun capture():DeviceFrame {captures++;onCapture();return DeviceFrame(width,height,IntArray(0),0)}
        override suspend fun discover()=emptyList<DeviceDescriptor>()
        override suspend fun connect(serial:String,touchMode:TouchMode) {}
        override suspend fun disconnect() {}
        override suspend fun queryForeground()="game"
        override suspend fun tap(x:Float,y:Float,expectedPackage:String)=TouchResult.Completed.also{actions++}
        override suspend fun swipe(fromX:Float,fromY:Float,toX:Float,toY:Float,durationMillis:Long,holdMillis:Long,expectedPackage:String)=TouchResult.Completed.also{actions++}
        override suspend fun back(expectedPackage:String)=TouchResult.Completed.also{actions++}
        override suspend fun home()=true.also{actions++}
        override suspend fun forceStop(packageName:String)=true.also{actions++}
        override suspend fun launch(packageName:String)=true.also{actions++}
        override suspend fun uiTree()=emptyList<UiNode>()
        override fun close() {}
    }
}
