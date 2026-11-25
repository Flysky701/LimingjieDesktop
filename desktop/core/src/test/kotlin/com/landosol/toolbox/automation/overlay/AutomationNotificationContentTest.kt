package com.landosol.toolbox.automation.overlay

import com.landosol.toolbox.automation.AutomationSessionId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomationNotificationContentTest {
    private val presentation = AutomationOverlayPresentation(
        sessionId = AutomationSessionId(7), title = "黎明界路线", status = "区域2 · 已走9/36",
        detail = "等待战斗结算（最长5分钟）", dryRun = false,
    )

    @Test
    fun `notification preserves task summary and expanded detail`() {
        val content = presentation.notificationContent()
        assertEquals(presentation.title, content.title)
        assertEquals(presentation.status, content.summary)
        assertTrue(content.expanded.contains(presentation.detail!!))
        assertEquals(presentation.sessionId, content.sessionId)
    }

    @Test
    fun `paused and read only modes remain visible`() {
        val content = presentation.copy(paused = true, dryRun = true).notificationContent()
        assertTrue(content.paused)
        assertTrue(content.summary.contains("已暂停"))
        assertTrue(content.summary.contains("只读识别"))
    }

    @Test
    fun `diagnostic boxes do not change the notification or pollute detail`() {
        val withBoxes = presentation.copy(boxes = listOf(
            AutomationOverlayBox(0f, 0f, 0.1f, 0.1f, "头像诊断", true, false),
        ))
        assertEquals(presentation.notificationContent(), withBoxes.notificationContent())
        assertFalse(withBoxes.notificationContent().expanded.contains("头像诊断"))
    }

    @Test
    fun `long output is bounded for notification transport`() {
        val content = presentation.copy(title = "标题".repeat(200), status = "状态".repeat(500),
            detail = "详情".repeat(5000)).notificationContent()
        assertEquals(120, content.title.length)
        assertEquals(240, content.summary.length)
        assertEquals(4000, content.expanded.length)
        assertFalse(presentation.copy(detail = null).notificationContent().expanded.contains("null"))
    }
}
