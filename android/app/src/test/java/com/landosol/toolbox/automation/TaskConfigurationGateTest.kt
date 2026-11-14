package com.landosol.toolbox.automation

import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class TaskConfigurationGateTest {
    @Test fun `startup reservations block transfer even before a running state exists`() {
        val gate = TaskConfigurationGate()
        val first = gate.tryBeginTask()!!
        val second = gate.tryBeginTask()!!
        var idleChecks = 0
        assertThrows(IllegalStateException::class.java) { gate.beginTransfer { idleChecks++; true } }
        assertEquals(0, idleChecks)
        first.close()
        first.close()
        assertEquals(1, gate.state.value.admissions)
        second.close()
        gate.beginTransfer { true }.close()
        assertEquals(TaskConfigurationGate.State(), gate.state.value)
    }

    @Test fun `idle predicate rejection leaves no transfer reservation`() {
        val gate = TaskConfigurationGate()
        assertThrows(IllegalStateException::class.java) { gate.beginTransfer { false } }
        assertFalse(gate.state.value.transferring)
        gate.beginTransfer { true }.close()
    }

    @Test fun `transfer reservation rejects new launches rather than queuing them`() {
        val gate = TaskConfigurationGate()
        val transfer = gate.beginTransfer { true }
        assertNull(gate.tryBeginTask())
        assertThrows(IllegalStateException::class.java) { gate.beginTransfer { true } }
        transfer.close()
        assertNotNull(gate.tryBeginTask()?.also { it.close() })
    }

    @Test fun `startup cannot enter while the transfer idle check is in flight`() {
        val gate = TaskConfigurationGate()
        val idleEntered = CountDownLatch(1)
        val releaseIdle = CountDownLatch(1)
        val taskCalling = CountDownLatch(1)
        val taskReturned = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val failures = mutableListOf<Throwable>()
        fun await(latch: CountDownLatch, label: String) {
            assertTrue(label, latch.await(5, TimeUnit.SECONDS))
        }
        fun collect(action: () -> Unit) {
            try { action() } catch (failure: Throwable) {
                failures += (failure as? ExecutionException)?.cause ?: failure
            }
        }
        val work = listOf(
            FutureTask<Unit> {
                gate.beginTransfer {
                    idleEntered.countDown()
                    await(releaseIdle, "idle check was not released")
                    true
                }.use { await(finish, "transfer owner was not released") }
            },
            FutureTask<Unit> {
                await(idleEntered, "transfer never entered its idle check")
                taskCalling.countDown()
                gate.tryBeginTask().use { lease ->
                    taskReturned.countDown()
                    // Keep an incorrectly granted lease alive until both admissions are inspected.
                    await(finish, "startup owner was not released")
                    assertNull("startup admitted alongside transfer", lease)
                }
            },
        )
        val workers = work.mapIndexed { index, task ->
            Thread(task, "configuration-gate-test-$index").apply { isDaemon = true }
        }
        try {
            workers.forEach(Thread::start)
            await(idleEntered, "idle check was not reached")
            await(taskCalling, "startup worker did not attempt entry")
            // Observe a controlled overlap, not the old already-completed admission sequence.
            // This bounded negative wait gives the contender time to run; it is not a stress proof.
            assertFalse("startup returned before the idle check was released",
                taskReturned.await(250, TimeUnit.MILLISECONDS))
            releaseIdle.countDown()
            await(taskReturned, "startup did not return after transfer publication")
            assertEquals(TaskConfigurationGate.State(transferring = true), gate.state.value)
        } catch (failure: Throwable) {
            failures += failure
        } finally {
            releaseIdle.countDown()
            finish.countDown()
            // Future.get propagates worker assertions, including AssertionError, to JUnit.
            work.forEach { future -> collect { future.get(5, TimeUnit.SECONDS) } }
            workers.forEach { worker ->
                if (worker.isAlive) worker.interrupt()
                collect { worker.join(1_000) }
                if (worker.isAlive) failures += AssertionError("worker did not terminate: ${worker.name}")
            }
        }
        if (failures.isNotEmpty()) throw AssertionError("controlled admission interleaving failed").also { error ->
            failures.forEach(error::addSuppressed)
        }
        assertEquals(TaskConfigurationGate.State(), gate.state.value)
    }
}
