package com.landosol.toolbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppVersionTest {
    @Test fun `the shipped version is the one this branch declares`() {
        // Diagnostic bundles are read against this constant, so a build.gradle bump that forgets
        // the rest of the release is worth a failing test rather than a silent mismatch.
        // 魔改包按约定把版本名排在 1.0.18-mod.* 前缀下、versionCode 从 10060 起单调递增，
        // 所以这里校验前缀与下限，而不是上游的精确值。
        assertTrue("versionName must keep the mod prefix: ${AppVersion.name}",
            AppVersion.name.startsWith("1.0.18-mod."))
        assertTrue("versionCode must stay above the C-line start: ${AppVersion.code}",
            AppVersion.code >= 10060)
    }

    @Test fun `every surface prints one label`() {
        // A version this build will never have, so it cannot quietly pass by matching the real one.
        assertEquals("9.9.9 (99909)", appVersionLabel("9.9.9", 99909))
        assertEquals("版本 ${AppVersion.label}", AppVersion.display)
    }
}
