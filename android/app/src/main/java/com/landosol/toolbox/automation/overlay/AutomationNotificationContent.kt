package com.landosol.toolbox.automation.overlay

import com.landosol.toolbox.automation.AutomationSessionId

internal data class AutomationNotificationContent(
    val sessionId: AutomationSessionId,
    val title: String,
    val summary: String,
    val expanded: String,
    val paused: Boolean,
)

internal fun AutomationOverlayPresentation.notificationContent() = AutomationNotificationContent(
    sessionId = sessionId,
    title = title.take(120),
    summary = buildString {
        append(if (paused) "已暂停 · " else "")
        append(status)
        if (dryRun) append(" · 只读识别")
    }.take(240),
    expanded = listOfNotNull(status, detail?.takeIf { it.isNotBlank() }).joinToString("\n").take(4_000),
    paused = paused,
)

internal fun automationNotificationActionMatches(
    requestedId: Long,
    issuedToken: String?,
    activeId: AutomationSessionId?,
    processToken: String,
): Boolean = requestedId > 0L && activeId?.value == requestedId && issuedToken == processToken
