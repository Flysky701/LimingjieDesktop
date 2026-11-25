package com.landosol.toolbox.labyrinth

import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryPageState

/** Reconcile a move acknowledged before an interrupted entry, only on its actual destination. */
internal fun labyrinthCanRecoverConfirmedNodeEntry(
    expectedType: Int,
    page: LabyrinthEntryPageState,
    sourceId: Long?,
    currentId: Long?,
    hasUniqueReachableTarget: Boolean,
    confirmedAt: Long?,
    now: Long,
): Boolean = sourceId != null && sourceId == currentId && hasUniqueReachableTarget &&
    confirmedAt != null && now - confirmedAt in 0..600_000 &&
    labyrinthConfirmsNodeEntry(page) && labyrinthNodeEntryMatchesExpectedType(expectedType, page)
