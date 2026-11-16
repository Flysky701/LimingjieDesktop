package com.landosol.toolbox.labyrinth.node

/** How this frame obtained node evidence. A memo may cache search geometry, never click proof. */
enum class NodeFrameEvidenceSource(val currentFrameVerified: Boolean) {
    NONE(false),
    SEARCH(true),
    TRACKED(true),
    MEMO_REVALIDATED(true),
    FINAL_BOSS_PLATFORM(true),
}
