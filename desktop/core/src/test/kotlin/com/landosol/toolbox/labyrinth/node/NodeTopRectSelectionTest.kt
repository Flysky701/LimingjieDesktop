package com.landosol.toolbox.labyrinth.node

import com.landosol.toolbox.labyrinth.vision.EntryPixelRect
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The proposal pass keeps the best few rectangles per template. It used to do that by boxing a pair
 * per rectangle and sorting the whole pool; [topRectsByScore] selects into a fixed buffer instead.
 * These cases pin the part that is easy to get wrong when hand-rolling a selection: the tie rule.
 */
class NodeTopRectSelectionTest {
    private fun rect(i: Int) = EntryPixelRect(i, i, 10, 10)

    private fun reference(
        rects: List<EntryPixelRect>,
        limit: Int,
        score: (EntryPixelRect) -> Double,
    ): List<EntryPixelRect> =
        rects.map { it to score(it) }.sortedByDescending { it.second }.take(limit).map { it.first }

    @Test fun `matches a stable descending sort on random pools including ties`() {
        val random = Random(20260920)
        repeat(300) {
            val rects = List(random.nextInt(1, 40)) { rect(it) }
            // A small score range guarantees repeated values, so ties are exercised on purpose.
            val scores = rects.associateWith { random.nextInt(0, 5).toDouble() }
            val limit = random.nextInt(0, 12)
            assertEquals(
                reference(rects, limit) { scores.getValue(it) },
                rects.topRectsByScore(limit) { scores.getValue(it) },
            )
        }
    }

    @Test fun `an all-equal pool keeps the rectangles the sort would have kept`() {
        val rects = List(10) { rect(it) }
        assertEquals(rects.take(3), rects.topRectsByScore(3) { 1.0 })
    }

    @Test fun `a limit above the pool size returns every rectangle in order`() {
        val rects = List(3) { rect(it) }
        val scores = mapOf(rects[0] to 1.0, rects[1] to 3.0, rects[2] to 2.0)
        assertEquals(listOf(rects[1], rects[2], rects[0]), rects.topRectsByScore(9) { scores.getValue(it) })
    }

    @Test fun `degenerate requests return nothing instead of failing`() {
        assertEquals(emptyList<EntryPixelRect>(), List(5) { rect(it) }.topRectsByScore(0) { 1.0 })
        assertEquals(emptyList<EntryPixelRect>(), emptyList<EntryPixelRect>().topRectsByScore(4) { 1.0 })
    }
}
