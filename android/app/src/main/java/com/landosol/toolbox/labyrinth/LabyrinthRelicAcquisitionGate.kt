package com.landosol.toolbox.labyrinth

import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryPageState

/** A completed click needs two distinct, consecutive map captures before ownership is recorded. */
internal class LabyrinthRelicAcquisitionGate {
    private var mapFrames = 0
    private var lastFrameAt = Long.MIN_VALUE
    fun reset() { mapFrames = 0; lastFrameAt = Long.MIN_VALUE }
    fun observe(clicked: Boolean, page: LabyrinthEntryPageState, capturedAtMillis: Long): Boolean {
        if (!clicked || page != LabyrinthEntryPageState.NODE_SELECTION) { reset(); return false }
        if (capturedAtMillis <= lastFrameAt) return false
        lastFrameAt = capturedAtMillis
        mapFrames++
        return mapFrames >= 2
    }
}
