package com.landosol.toolbox.automation.capture

import org.junit.Assert.*
import org.junit.Test

/** Registration demand only: deliberately never creates or executes an Android Bitmap. */
class Batch5FrameBusSnapshotTest {
    @Test fun legacyConsumerDoesNotRequestSnapshotAndExplicitDemandEndsWithLease() {
        synchronized(CaptureFrameBus) {
            assertNull("test needs an unregistered bus", CaptureFrameBus.currentOwner())
            assertFalse(CaptureFrameBus.currentWantsArgbSnapshot())
            val legacy = CaptureFrameBus.register("batch5-legacy-test") { }
            assertTrue(legacy is CaptureFrameRegistrationResult.Registered)
            val legacyLease = (legacy as CaptureFrameRegistrationResult.Registered).lease
            try {
                assertFalse(CaptureFrameBus.currentWantsArgbSnapshot())
                assertTrue(CaptureFrameBus.register("batch5-balanced-test", true) { } is CaptureFrameRegistrationResult.Busy)
                assertFalse(CaptureFrameBus.currentWantsArgbSnapshot())
            } finally { CaptureFrameBus.unregister(legacyLease) }
            val balanced = CaptureFrameBus.register("batch5-balanced-test", wantsArgbSnapshot = true) { }
            val balancedLease = (balanced as CaptureFrameRegistrationResult.Registered).lease
            try {
                assertTrue(CaptureFrameBus.currentWantsArgbSnapshot())
                assertFalse(CaptureFrameBus.unregister(legacyLease))
                assertTrue(CaptureFrameBus.currentWantsArgbSnapshot())
            } finally { CaptureFrameBus.unregister(balancedLease) }
            assertFalse(CaptureFrameBus.currentWantsArgbSnapshot())
            assertNull(CaptureFrameBus.currentOwner())
        }
    }
}
