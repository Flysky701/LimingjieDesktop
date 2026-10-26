package com.landosol.toolbox.labyrinth

import com.landosol.toolbox.labyrinth.vision.EntryPixelRect

/** 按已保存路线执行/回放的进度快照，供 UI 与悬浮窗展示。 */
data class LabyrinthRouteProgress(
    val currentArea: Int,
    val visitedCount: Int,
    val routeNodeCount: Int,
    val nextNodeLabel: String? = null,
    val nextNodeRect: EntryPixelRect? = null,
    val complete: Boolean = false,
)

/** How long the run asks for a human to close a stuck movement dialog before giving up. */
internal const val ORPHAN_MOVE_CONFIRMATION_MANUAL_GRACE_MILLIS = 60_000L

internal data class PendingNodeTransition(
    val blockId: Long,
    val blockType: Int,
    val label: String,
    val dispatchedAtMillis: Long,
    val confirmationDispatchedAtMillis: Long? = null,
    val confirmationAttempts: Int = 0,
    val nodeSelectionFramesSinceAction: Int = 0,
)

internal data class ConfirmedNodeEntryReceipt(
    val transition: PendingNodeTransition,
    val sourceId: Long,
)
