package com.landosol.toolbox.labyrinth.vision

import org.junit.Assert.*
import org.junit.Test

class RelicOcrSchedulerTest {
    @Test fun `shop variants each get one read and stale callbacks cannot replace new stock`() {
        var time = 0L
        val shop = RelicOcrScheduler({ time }, maximumAttempts = 1)
        val old = requireNotNull(shop.request("title-0", 1))
        shop.cached("title-0", 2)
        shop.complete(old, "旧印记")
        assertNull(shop.cached("title-0", 2))
        time = 500
        val current = requireNotNull(shop.request("title-0", 2))
        shop.complete(current, "未确认")
        time = 1_000
        assertNull(shop.request("title-0", 2))
        assertNotNull(shop.request("title-1", 2))
    }
    private var now = 0L
    private val scheduler = RelicOcrScheduler({ now })
    @Test fun `stable pixels have two independent reads then stop scanning`() {
        val first = requireNotNull(scheduler.request("a", 1))
        scheduler.complete(first, "4")
        assertEquals(first.id, scheduler.cached("a", 1)?.id)
        assertNull(scheduler.request("a", 1))
        now = 400
        val second = requireNotNull(scheduler.request("a", 1))
        assertNotEquals(first.id, second.id)
        scheduler.complete(second, "4")
        now = 100_000
        repeat(100) { assertNull(scheduler.request("a", 1)) }
        assertEquals(second.id, scheduler.cached("a", 1)?.id)
    }
    @Test fun `pixel change invalidates cache and allows fresh work`() {
        scheduler.complete(requireNotNull(scheduler.request("a", 1)), "1")
        now = 500
        assertNull(scheduler.cached("a", 2))
        assertNotNull(scheduler.request("a", 2))
    }
    @Test fun `late callback cannot overwrite changed viewport`() {
        val ticket = requireNotNull(scheduler.request("a", 1))
        scheduler.cached("a", 2)
        scheduler.complete(ticket, "old")
        assertNull(scheduler.cached("a", 2))
    }
    @Test fun `page reset cannot queue more bitmaps behind a stalled request`() {
        val ticket = requireNotNull(scheduler.request("a", 1))
        repeat(100) {
            scheduler.clear()
            now += 500
            assertNull(scheduler.request("a", 1))
        }
        scheduler.complete(ticket, "stale")
        assertNull(scheduler.cached("a", 1))
        assertNotNull(scheduler.request("a", 1))
    }
    @Test fun `other slots get first reading before a repeat verification`() {
        scheduler.complete(requireNotNull(scheduler.request("a", 1)), "1")
        scheduler.cached("b", 1)
        now = 500
        assertNull(scheduler.request("a", 1))
        assertNotNull(scheduler.request("b", 1))
    }
    @Test fun `a slot nobody asks for any more stops blocking the second read`() {
        // 2026-09-19 live: an EX monster-detail panel read 流夏的暗影 correctly and the run still
        // reported it as having no strategy on file. The identity is only trusted after two
        // independent reads, and the second never came: the challenge page had created a
        // single-target name slot on its first frames, the structural detector then recognised a
        // multi-monster encounter, and that branch stopped running while its unread slot kept
        // priority forever.
        val first = requireNotNull(scheduler.request("ex-monster-detail-name", 1))
        scheduler.cached("ex-single-target-name", 1)
        scheduler.complete(first, "流夏的暗影")

        // While that page is still live the unread slot keeps its priority, as before.
        now = 500
        assertNull(scheduler.request("ex-monster-detail-name", 1))

        // Once it has been abandoned, the detail slot gets the read it needs.
        now = 3_000
        val second = requireNotNull(scheduler.request("ex-monster-detail-name", 1))
        assertNotEquals(first.id, second.id)
        scheduler.complete(second, "流夏的暗影")
        assertEquals(second.id, scheduler.cached("ex-monster-detail-name", 1)?.id)
    }

    @Test fun `a slot still being asked for keeps its priority`() {
        val first = requireNotNull(scheduler.request("a", 1))
        scheduler.cached("b", 1)
        scheduler.complete(first, "1")
        // "b" is re-read every frame, so it never looks abandoned however long this runs.
        repeat(20) {
            now += 400
            scheduler.cached("b", 1)
            assertNull(scheduler.request("a", 1))
        }
        assertNotNull(scheduler.request("b", 1))
    }

    @Test fun `an encounter met twice in a run is still confirmed the second time`() {
        // 2026-09-20 live: 美空 appeared at two EX nodes of one run. The monster-detail name row is
        // pixel-identical both times, so the second visit was served entirely from this cache and
        // issued no OCR at all. The caller counted evidence ids to require two independent reads,
        // saw one cached id, and waited for a second read that would never be scheduled.
        val first = requireNotNull(scheduler.request("ex-monster-detail-name", 7))
        scheduler.complete(first, "美空")
        assertEquals(1, scheduler.cached("ex-monster-detail-name", 7)?.confirmations)
        now = 500
        scheduler.complete(requireNotNull(scheduler.request("ex-monster-detail-name", 7)), "美空")
        assertEquals(2, scheduler.cached("ex-monster-detail-name", 7)?.confirmations)

        // Nothing asks for the detail crop between the two encounters, so on returning to an
        // identical panel the slot answers from cache and schedules no further read.
        now = 120_000
        val revisit = requireNotNull(scheduler.cached("ex-monster-detail-name", 7))
        assertEquals("美空", revisit.text)
        assertEquals(2, revisit.confirmations)
        assertNull(scheduler.request("ex-monster-detail-name", 7))
    }

    @Test fun `result carries the original capture time rather than callback completion time`() {
        val ticket = requireNotNull(scheduler.request("a", 1, capturedAtMillis = 123))
        now = 10_000
        scheduler.complete(ticket, "9")
        assertEquals(123L, scheduler.cached("a", 1)?.capturedAtMillis)
        scheduler.clear()
        assertNull(scheduler.cached("a", 1))
        scheduler.complete(ticket, "9")
        assertNull(scheduler.cached("a", 1))
    }

    @Test fun `two reads that disagree buy one more look instead of deadlocking`() {
        scheduler.complete(requireNotNull(scheduler.request("a", 1)), "美空")
        now = 500
        scheduler.complete(requireNotNull(scheduler.request("a", 1)), "美究")
        assertEquals(1, scheduler.cached("a", 1)?.confirmations)
        now = 1_000
        val third = requireNotNull(scheduler.request("a", 1))
        scheduler.complete(third, "美究")
        assertEquals(2, scheduler.cached("a", 1)?.confirmations)
        // The extra look is bought once, not every time the reads disagree.
        now = 1_500
        assertNull(scheduler.request("a", 1))
    }

    @Test fun `failures use bounded retry budget too`() {
        repeat(2) {
            scheduler.complete(requireNotNull(scheduler.request("a", 1)), null)
            now += 500
        }
        assertNull(scheduler.request("a", 1))
    }
}
