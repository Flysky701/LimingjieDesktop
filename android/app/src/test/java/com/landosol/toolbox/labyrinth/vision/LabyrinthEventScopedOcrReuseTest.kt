package com.landosol.toolbox.labyrinth.vision

import com.landosol.toolbox.gamedata.EventResource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LabyrinthEventScopedOcrReuseTest {
    private val crop = EntryPixelRect(150, 95, 1620, 750)
    private val requestKey = LabyrinthEventOcrCache.Key(TITLE_A, crop, TITLE_A)

    @Test
    fun `only requested targets accept zero through eight crop bits with the same request title`() {
        for (eventId in listOf("arena", "2316", "3316")) {
            for (distance in listOf(0, 1, 2, 3, 8)) {
                val lookup = lookupWithDistance(distance)
                assertEquals("HIT", lookup.diagnostics.status)
                assertEquals(distance, lookup.diagnostics.fingerprintDistance)
                assertTrue("event=$eventId distance=$distance", canReuse(lookup, eventId))
            }
        }
    }

    @Test
    fun `nine crop bits still exceed the existing cache policy`() {
        val lookup = lookupWithDistance(9)
        assertEquals("FINGERPRINT_CHANGED", lookup.diagnostics.status)
        assertNull(lookup.text)
        for (eventId in listOf("arena", "2316", "3316")) assertFalse(canReuse(lookup, eventId))
    }

    @Test
    fun `new stage title rejects previous text even when crop dHash is unchanged`() {
        val cache = populatedCache()
        // Both crops have dHash zero; only the independently stored title changed.
        val stageThreeKey = requestKey.copy(fingerprint = TITLE_B, titleFingerprint = TITLE_B)
        val oldText = cache.lookup(stageThreeKey, 200L)
        assertEquals("HIT", oldText.diagnostics.status)
        assertEquals(1, oldText.diagnostics.fingerprintDistance)
        assertEquals(TITLE_A, oldText.requestTitleFingerprint)
        assertFalse(canReuse(oldText, "2316", TITLE_B))

        val nextRequest = requireNotNull(cache.begin(stageThreeKey, 300L))
        assertTrue(cache.complete(nextRequest, "最后一发！", 400L))
        val newText = cache.lookup(stageThreeKey, 401L)
        assertEquals(TITLE_B, newText.requestTitleFingerprint)
        assertTrue(canReuse(newText, "3316", TITLE_B))
    }

    @Test
    fun `equal XOR fingerprints cannot hide a different source title`() {
        val cache = populatedCache()
        // A changed title and crop can cancel in XOR. Comparing only that hash is insufficient.
        val lookup = cache.lookup(requestKey.copy(titleFingerprint = TITLE_B), 200L)
        assertEquals("HIT", lookup.diagnostics.status)
        assertEquals(0, lookup.diagnostics.fingerprintDistance)
        assertEquals(TITLE_A, lookup.requestTitleFingerprint)
        assertFalse(canReuse(lookup, "2316", TITLE_B))
    }

    @Test
    fun `current lookup or consecutive current frames never replace the request source title`() {
        val cache = populatedCache()
        val currentKey = requestKey.copy(titleFingerprint = TITLE_B)
        repeat(2) { frame ->
            val lookup = cache.lookup(currentKey, 200L + frame)
            assertEquals(TITLE_A, lookup.requestTitleFingerprint)
            assertFalse(canReuse(lookup, "2316", TITLE_B))
        }
    }

    @Test
    fun `missing source or current title evidence cannot open target reuse`() {
        val noSourceTitle = lookupWithDistance(0, requestTitle = null)
        assertEquals("HIT", noSourceTitle.diagnostics.status)
        assertNull(noSourceTitle.requestTitleFingerprint)
        assertFalse(canReuse(noSourceTitle))
        assertFalse(canReuse(lookupWithDistance(0), currentTitle = null))
        assertFalse(canReuse(lookupWithDistance(0, requestTitle = null), currentTitle = null))
    }

    @Test
    fun `cooking and other custom titles retain their exact zero gate`() {
        for (eventId in listOf("2315", "3315", "1108", "1111", "1112", "1101", "unrelated")) {
            assertTrue("event=$eventId zero", canReuse(lookupWithDistance(0), eventId))
            for (distance in listOf(1, 2, 3, 8)) {
                assertFalse("event=$eventId distance=$distance", canReuse(lookupWithDistance(distance), eventId))
            }
        }
    }

    @Test
    fun `source title metadata does not add a new gate to non target events`() {
        val lookup = populatedCache().lookup(requestKey.copy(titleFingerprint = TITLE_B), 200L)
        assertEquals("HIT", lookup.diagnostics.status)
        assertTrue(canReuse(lookup, "2315", TITLE_B))
        assertTrue(canReuse(lookup, "3315", TITLE_B))
    }

    @Test
    fun `single choice cooking and generic OCR keep their original cache tolerance`() {
        val cache = LabyrinthEventOcrCache()
        val genericKey = LabyrinthEventOcrCache.Key(0L, crop)
        assertTrue(cache.complete(requireNotNull(cache.begin(genericKey, 0L)), "料理第一段", 100L))
        val lookup = cache.lookup(genericKey.copy(fingerprint = 255L), 200L)
        assertEquals("HIT", lookup.diagnostics.status)
        assertTrue(canReuseLabyrinthEventOcrText(lookup, false, null, null))
        assertTrue(canReuseLabyrinthEventOcrText(lookup, false, null, match("1315")))
    }

    @Test
    fun `untrusted missing and ambiguous title matches cannot enable tolerant reuse`() {
        val lookup = lookupWithDistance(1)
        assertFalse(canReuseLabyrinthEventOcrText(lookup, true, TITLE_A, match("2316", trusted = false)))
        assertFalse(canReuseLabyrinthEventOcrText(lookup, true, TITLE_A, null))
        assertFalse(canReuseLabyrinthEventOcrText(lookup, true, TITLE_A, match("2316").copy(event = null)))
    }

    @Test
    fun `expired and clock rollback results never become reusable text`() {
        val cache = populatedCache()
        val expired = cache.lookup(requestKey, 5_100L)
        assertEquals("EXPIRED", expired.diagnostics.status)
        assertNull(expired.text)
        assertFalse(canReuse(expired))
        val rollback = cache.lookup(requestKey, 99L)
        assertEquals("CLOCK_ROLLBACK", rollback.diagnostics.status)
        assertFalse(canReuse(rollback))
    }

    @Test
    fun `crop changes including title probe to full OCR retain cache invalidation`() {
        val changed = populatedCache().lookup(requestKey.copy(rect = crop.copy(left = 151)), 200L)
        assertEquals("CROP_CHANGED", changed.diagnostics.status)
        assertFalse(canReuse(changed))

        val cache = LabyrinthEventOcrCache()
        val titleCropKey = requestKey.copy(rect = EntryPixelRect(281, 72, 717, 86))
        cache.complete(requireNotNull(cache.begin(titleCropKey, 0L)), "再较量一次！", 100L)
        val fullCropKey = titleCropKey.copy(rect = EntryPixelRect(217, 72, 845, 489))
        val fallback = cache.lookup(fullCropKey, 200L)
        assertEquals("CROP_CHANGED", fallback.diagnostics.status)
        assertFalse(canReuse(fallback))
    }

    @Test
    fun `page clear and late callbacks cannot attach an old title to the new request`() {
        val cache = LabyrinthEventOcrCache()
        val oldRequest = requireNotNull(cache.begin(requestKey, 0L))
        cache.clear()
        val newKey = requestKey.copy(fingerprint = TITLE_B, titleFingerprint = TITLE_B)
        val newRequest = requireNotNull(cache.begin(newKey, 10L))
        assertFalse(cache.complete(oldRequest, "再较量一次！", 20L))
        val pending = cache.lookup(newKey, 21L)
        assertNull(pending.text)
        assertNull(pending.requestTitleFingerprint)
        assertEquals(newRequest, pending.diagnostics.pendingRequestId)
        assertTrue(cache.complete(newRequest, "最后一发！", 30L))
        val current = cache.lookup(newKey, 31L)
        assertEquals(TITLE_B, current.requestTitleFingerprint)
        assertTrue(canReuse(current, "3316", TITLE_B))
        cache.clear()
        assertNull(cache.lookup(newKey, 32L).requestTitleFingerprint)
    }

    @Test
    fun `timed out callbacks cannot replace the fresh request title`() {
        val cache = LabyrinthEventOcrCache()
        val oldRequest = requireNotNull(cache.begin(requestKey, 0L))
        val newKey = requestKey.copy(fingerprint = TITLE_B, titleFingerprint = TITLE_B)
        val newRequest = requireNotNull(cache.begin(newKey, 5_000L))
        assertFalse(cache.complete(oldRequest, "再较量一次！", 5_100L))
        assertTrue(cache.complete(newRequest, "最后一发！", 5_200L))
        val current = cache.lookup(newKey, 5_201L)
        assertEquals(TITLE_B, current.requestTitleFingerprint)
        assertTrue(canReuse(current, "3316", TITLE_B))
    }

    @Test
    fun `empty or failed OCR produces no reusable text or source title`() {
        for (text in listOf<String?>(null, "", "   ")) {
            val cache = LabyrinthEventOcrCache()
            cache.complete(requireNotNull(cache.begin(requestKey, 0L)), text, 100L)
            val lookup = cache.lookup(requestKey, 200L)
            assertNull(lookup.text)
            assertNull(lookup.requestTitleFingerprint)
            assertFalse(canReuse(lookup))
        }
    }

    @Test
    fun `a newer pending request cannot replace the title of the text still in cache`() {
        val cache = populatedCache()
        val newKey = requestKey.copy(fingerprint = TITLE_B, titleFingerprint = TITLE_B)
        val newRequest = requireNotNull(cache.begin(newKey, 300L))
        // The new request has started but not completed; cached text is still the old A request.
        val lookup = cache.lookup(newKey, 301L)
        assertEquals("再较量一次！", lookup.text)
        assertEquals(TITLE_A, lookup.requestTitleFingerprint)
        assertEquals(newRequest, lookup.diagnostics.pendingRequestId)
        assertFalse(canReuse(lookup, "3316", TITLE_B))
    }

    private fun populatedCache(): LabyrinthEventOcrCache = LabyrinthEventOcrCache().also { cache ->
        assertTrue(cache.complete(requireNotNull(cache.begin(requestKey, 0L)), "再较量一次！", 100L))
    }

    private fun lookupWithDistance(
        distance: Int,
        requestTitle: Long? = TITLE_A,
        currentTitle: Long? = TITLE_A,
    ): LabyrinthEventOcrCache.Lookup {
        val cache = LabyrinthEventOcrCache()
        val key = requestKey.copy(titleFingerprint = requestTitle)
        cache.complete(requireNotNull(cache.begin(key, 0L)), "再较量一次！", 100L)
        val mask = (1L shl distance) - 1L
        return cache.lookup(key.copy(fingerprint = key.fingerprint xor mask, titleFingerprint = currentTitle), 200L)
    }

    private fun canReuse(
        lookup: LabyrinthEventOcrCache.Lookup,
        eventId: String = "2316",
        currentTitle: Long? = TITLE_A,
    ): Boolean = canReuseLabyrinthEventOcrText(lookup, true, currentTitle, match(eventId))

    private fun match(eventId: String, trusted: Boolean = true) = LabyrinthEventTextMatch(
        event = EventResource(id = eventId, name = eventId),
        score = 1.0,
        margin = 1.0,
        titleScore = 1.0,
        choiceScore = 0.0,
        trusted = trusted,
    )

    private companion object {
        const val TITLE_A = 0x123456789abcdef0L
        const val TITLE_B = 0x123456789abcdef1L
    }
}
