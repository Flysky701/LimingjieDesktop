package com.landosol.toolbox.labyrinth

/**
 * Per-frame diagnostic record for the replay harness (phase zero of the revised plan).
 *
 * Every field is observational. Nothing reads this record to decide an action; it exists so the
 * debug history can show, for each frame, which action was proposed, why it was withheld, what
 * the route wanted and how expensive the node search was. `expectedPages` is reserved for the
 * approval gate and is written as the full page set until that gate exists.
 */
data class LabyrinthFrameTrace(
    /** Pages the current flow phase allows. Full set until the approval gate lands. */
    val expectedPages: List<String> = emptyList(),
    /** Label of the action dispatched on this frame, or null when none was sent. */
    val actionLabel: String? = null,
    /** Why an otherwise-plannable action was withheld this frame, or null. */
    val actionRejectReason: String? = null,
    /** Route target block id the node executor is currently trying to reach. */
    val nodeTargetBlockId: Long? = null,
    /** Logical column of that target, when the route knows it. */
    val nodeTargetColumn: Int? = null,
    /** Candidate windows the node classifier actually scored on this frame. */
    val nodeSearchWindowCount: Int = 0,
    /** Whether the viewport tracker considered its fitted geometry reliable. */
    val nodeTrackerReliable: Boolean? = null,
    /** Fitted world-space left edge of the viewport, when the tracker had one. */
    val nodeTrackerWorldLeft: Double? = null,
    /** Predicted screen x of the target column from the tracker, when available. */
    val nodeTargetExpectedX: Double? = null,
)
