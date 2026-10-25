package com.landosol.toolbox.desktop.device

import com.landosol.toolbox.desktop.platform.UserResourceLeases
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.atomic.AtomicInteger

class DeviceLeaseTest {
    @get:Rule val folder=TemporaryFolder()
    private val boot="10000000-1111-2222-3333-444444444444"
    private fun leases()=UserResourceLeases(folder.root.toPath().resolve("user"))
    private fun service(adb:FakeAdb, factory:TouchConnectionFactory)=AdbDeviceService(
        adb,folder.root.toPath().resolve("profile-"+java.util.UUID.randomUUID()),emptySet(),touchFactory=factory,leases=leases())
    private class Touch(val cleanup:()->Unit={}):TouchConnection {
        override val header=MaaTouchHeader(10,1920,1080,255,123)
        override var alive=true
        val sent=AtomicInteger()
        override suspend fun send(commands:String,durationMillis:Long) { sent.incrementAndGet() }
        override fun close() { cleanup();alive=false }
    }
    @Test fun aliasesExcludeBeforeTouchDeploymentAndLeaseLastsThroughCleanup()=runBlocking {
        val opens=AtomicInteger();var cleaned=false
        val touch=Touch {
            assertThrows(IllegalStateException::class.java) { leases().acquireDevice(boot) }
            cleaned=true
        }
        service(FakeAdb(),TouchConnectionFactory { _,_,_->touch }).use { first ->
            service(FakeAdb(),TouchConnectionFactory { _,_,_->opens.incrementAndGet();Touch() }).use { second ->
                first.connect("emulator-5554")
                assertTrue(first.state.value is DeviceConnectionState.Ready)
                second.connect("127.0.0.1:7555")
                assertTrue(second.state.value is DeviceConnectionState.Failed)
                assertEquals(0,opens.get())
                assertThrows(IllegalStateException::class.java) { leases().acquireDevice(boot.uppercase()) }
                first.disconnect()
                assertTrue(cleaned)
                second.connect("127.0.0.1:7555")
                assertTrue(second.state.value is DeviceConnectionState.Ready)
                assertEquals(1,opens.get())
            }
        }
    }
    @Test fun unknownIdentityAndFailedOrCancelledDeploymentNeverLeakLease()=runBlocking {
        val adb=FakeAdb().apply { handler={ call -> if(call.arguments==listOf("cat","/proc/sys/kernel/random/boot_id")) FakeAdb.success("") else default(call) } }
        service(adb,TouchConnectionFactory { _,_,_->error("must not deploy") }).use {
            it.connect("fixture")
            assertTrue(it.state.value is DeviceConnectionState.Failed)
        }
        service(FakeAdb(),TouchConnectionFactory { _,_,_->error("synthetic deploy failure") }).use {
            it.connect("fixture")
            assertTrue(it.state.value is DeviceConnectionState.Failed)
            leases().acquireDevice(boot).close()
        }
        val entered=CompletableDeferred<Unit>()
        service(FakeAdb(),TouchConnectionFactory { _,_,_->entered.complete(Unit);awaitCancellation() }).use {
            val job=launch { it.connect("fixture") }
            withTimeout(3000) { entered.await() }
            job.cancelAndJoin()
            leases().acquireDevice(boot).close()
        }
    }
    @Test fun failedCleanupKeepsLeaseUntilRetryCompletes()=runBlocking {
        var attempts=0
        val touch=Touch { if (++attempts==1) error("synthetic cleanup failure") }
        service(FakeAdb(),TouchConnectionFactory { _,_,_->touch }).use { device ->
            device.connect("fixture")
            assertTrue(runCatching { device.disconnect() }.isFailure)
            assertThrows(IllegalStateException::class.java) { leases().acquireDevice(boot) }
            device.disconnect()
            leases().acquireDevice(boot).close()
        }
    }
    @Test fun rebootRejectsActionsAndOldPidCleanupNeverTargetsNewBoot()=runBlocking {
        val adb=FakeAdb(); val touch=Touch()
        service(adb,TouchConnectionFactory { _,_,_->touch }).use { device ->
            device.connect("fixture")
            adb.handler={ call -> if(call.arguments==listOf("cat","/proc/sys/kernel/random/boot_id"))
                FakeAdb.success("20000000-1111-2222-3333-444444444444") else adb.default(call) }
            assertTrue(device.back(FakeAdb.GAME) is TouchResult.Rejected)
            assertEquals(0,touch.sent.get())
            leases().acquireDevice(boot).close()
            cleanupTouchPid(adb,"fixture",boot,123)
            assertFalse(adb.calls.any { it.arguments.firstOrNull() in setOf("kill","sh") })
        }
        val same=FakeAdb()
        cleanupTouchPid(same,"fixture",boot,123)
        assertTrue(same.calls.any { it.arguments.take(2)==listOf("sh","-c") && it.arguments.last().contains(boot) })
    }
}
