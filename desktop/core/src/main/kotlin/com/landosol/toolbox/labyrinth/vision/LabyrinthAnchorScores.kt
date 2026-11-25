package com.landosol.toolbox.labyrinth.vision

/** Measured anchor template scores keyed by [EntryAnchorId]; missing ids read as 0.0. */
data class LabyrinthAnchorScores(val values: Map<String, Double>) {
    init {
        require(values.keys.none(String::isBlank))
        require(values.values.all { it in 0.0..1.0 })
    }

    operator fun get(id: String): Double = values[id] ?: 0.0
}
