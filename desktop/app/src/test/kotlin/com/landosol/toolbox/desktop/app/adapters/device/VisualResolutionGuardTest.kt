package com.landosol.toolbox.desktop.app.adapters.device

import com.landosol.toolbox.desktop.device.*
import com.landosol.toolbox.desktop.app.ui.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test

class VisualResolutionGuardTest {
    @Test fun `unsupported frame rejects startup and every device action`() = runBlocking {
        val fake = FakeDevice(1280, 720)
        val guard = VisualResolutionGuard(fake)
        val actions = ResolutionGuardedDevice(fake, guard)
        val failure = runCatching { guard.checkForStart() }.exceptionOrNull()
        assertTrue(failure?.message.orEmpty().contains("1280 × 720"))
        assertTrue(actions.tap(1f, 2f, "game") is TouchResult.Rejected)
        assertTrue(actions.swipe(1f, 2f, 3f, 4f, 100, 0, "game") is TouchResult.Rejected)
        assertTrue(actions.back("game") is TouchResult.Rejected)
        assertFalse(actions.home()); assertFalse(actions.forceStop("game")); assertFalse(actions.launch("game"))
        assertEquals(0, fake.actions)
        assertEquals("1280×720", guard.status.value.dimensionsLabel)
        assertEquals(guard.status.value.message, startMissing(DesktopTask.ENTRY, RunConsoleState(device=fake.state.value), true, true, resolution=guard.status.value))
        assertEquals("Devices", explainStop(null, guard.status.value.message).destination)
    }

    @Test fun `real landscape passes even when wm reports portrait and labels use frame dimensions`() = runBlocking {
        val fake = FakeDevice(1920, 1080)
        val guard = VisualResolutionGuard(fake)
        assertTrue(deviceLabel(fake.state.value).contains("待检查"))
        guard.checkForStart()
        assertTrue(guard.status.value.supported)
        assertTrue(deviceLabel(fake.state.value,guard.status.value.dimensionsLabel).contains("1920×1080"))
        assertEquals(TouchResult.Completed, ResolutionGuardedDevice(fake,guard).tap(1f,2f,"game"))
        assertEquals(1, fake.actions)
    }

    @Test fun `a new size immediately revokes clicks and rechecking never resumes`() = runBlocking {
        val fake = FakeDevice(1920,1080)
        var stopped = 0
        val guard = VisualResolutionGuard(fake) { stopped++ }
        val actions = ResolutionGuardedDevice(fake,guard)
        guard.checkForStart()
        fake.width = 1280; fake.height = 720
        // No periodic capture has delivered the new size yet: the action boundary must catch it.
        assertTrue(actions.tap(1f,2f,"game") is TouchResult.Rejected)
        assertEquals(1,stopped); assertEquals(0,fake.actions)
        fake.width = 1920; fake.height = 1080
        guard.recheck()
        assertTrue(actions.tap(1f,2f,"game") is TouchResult.Rejected)
        guard.checkForStart()
        assertEquals(TouchResult.Completed,actions.tap(1f,2f,"game"))
    }

    @Test fun `controlled close portrait launcher and relaunch stay possible without portrait taps`() = runBlocking {
        val fake = FakeDevice(1920,1080)
        var stopped = 0
        val guard = VisualResolutionGuard(fake) { stopped++ }
        val actions = ResolutionGuardedDevice(fake,guard)
        guard.checkForStart()
        assertTrue(actions.home())
        fake.width=1080;fake.height=1920
        guard.observe(1080,1920)
        assertTrue(guard.deferTransitionFrame(1080,1920))
        assertTrue(actions.forceStop("game"))
        assertTrue(actions.launch("game"))
        assertEquals(0,stopped)
        fake.width=1920;fake.height=1080
        guard.observe(1920,1080)
        assertEquals(TouchResult.Completed,actions.tap(1f,2f,"game"))
        assertEquals(4,fake.actions)
        // An ordinary unexpected rotation is still fatal.
        fake.width=1080;fake.height=1920
        guard.observe(1080,1920)
        assertEquals(1,stopped)
        assertTrue(actions.tap(1f,2f,"game") is TouchResult.Rejected)
    }

    @Test fun `even a controlled portrait transition never permits an automatic tap`() = runBlocking {
        val fake=FakeDevice(1920,1080)
        val guard=VisualResolutionGuard(fake)
        val actions=ResolutionGuardedDevice(fake,guard)
        guard.checkForStart(); actions.home()
        fake.width=1080;fake.height=1920
        assertTrue(actions.tap(1f,2f,"game") is TouchResult.Rejected)
        assertEquals(1,fake.actions)
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
        var onCapture:suspend()->Unit={}
        override suspend fun capture():DeviceFrame {onCapture();return DeviceFrame(width,height,IntArray(0),0)}
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
