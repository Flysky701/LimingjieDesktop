package com.landosol.toolbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppVersionTest {
    @Test fun `the shipped version is the one this branch declares`() {
        assertEquals(BuildConfig.VERSION_NAME, AppVersion.name)
        assertTrue("versionCode must stay above the C-line start: ${AppVersion.code}",
            AppVersion.code >= 10060)
    }

    @Test fun `every surface prints one label`() {
        // A version this build will never have, so it cannot quietly pass by matching the real one.
        assertEquals("9.9.9 (99909)", appVersionLabel("9.9.9", 99909))
        assertEquals("版本 ${AppVersion.label}", AppVersion.display)
    }
}
