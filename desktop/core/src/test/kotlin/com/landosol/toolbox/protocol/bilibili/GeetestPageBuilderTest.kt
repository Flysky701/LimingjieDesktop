package com.landosol.toolbox.protocol.bilibili

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeetestPageBuilderTest {
    @Test
    fun `challenge values are encoded as JSON and credentials are never accepted`() {
        val html = GeetestPageBuilder.build(
            CaptchaChallenge(
                gt = "gt-value",
                challenge = "</script><script>alert(1)</script>",
                gtUserId = "provider-user",
                captchaType = "1",
            ),
        )

        assertTrue(html.contains("gt-value"))
        assertTrue(html.contains("\\u003c", ignoreCase = true))
        assertFalse(html.contains("</script><script>alert(1)</script>"))
        assertFalse(html.contains("password", ignoreCase = true))
        assertTrue(html.contains("AndroidCaptcha.onSolved"))
    }
}
