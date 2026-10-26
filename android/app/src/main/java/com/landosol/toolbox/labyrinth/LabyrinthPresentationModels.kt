package com.landosol.toolbox.labyrinth

import com.landosol.toolbox.labyrinth.vision.EntryPixelRect

internal data class CharacterOverlaySource(
    val characterId: String?,
    val suspectedId: String?,
    val displayName: String?,
    val confidence: Double,
    val rivalMargin: Double,
    val trusted: Boolean,
    val selected: Boolean,
    val rect: EntryPixelRect,
    val nameEvidenceText: String? = null,
    val nameEvidenceScore: Double = 0.0,
    val nameAssisted: Boolean = false,
    val nameEvidenceMargin: Double = 0.0,
)
