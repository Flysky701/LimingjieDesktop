package com.landosol.toolbox.labyrinth.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EventChainOcrRefreshGuardTest {
    private val crop = EntryPixelRect(281, 72, 717, 86)
    private val expiry = expiredDiagnostic()

    @Test
    fun `real 2315 expiry retains only calculation evidence`() {
        val diagnostic = expiry
        val previous = requireNotNull(identity(diagnostic.copy(
            status = "TITLE_TRUSTED_MATCH", rawText = "料理完成了!不过食材还有剩余哦!",
            candidateEventId = "2315", candidateScore = 1.0, stableFrames = 16, trusted = true,
            cache = requireNotNull(diagnostic.cache).copy(status = "HIT", ageMillis = 4_818L),
        )))
        assertTrue(retain(diagnostic, lastTrusted = previous))
        assertEquals(0, diagnostic.stableFrames)
        assertFalse(diagnostic.trusted)
        assertNull(diagnostic.rawText)
        assertNull(diagnostic.candidateEventId)
        assertEquals(5_127L, diagnostic.cache?.ageMillis)
        assertEquals(0, diagnostic.cache?.fingerprintDistance)
    }

    @Test
    fun `real 1112 expiry has the same narrowly permitted shape`() {
        val diagnostic = expiredDiagnostic(
            fingerprint = "d561b9b9d1cf018c", age = 5_402L, request = 125L,
            duration = 46L, textLength = 10,
        ).copy(buttonConfidencesByOptionCount = mapOf(
            1 to listOf(0.125), 2 to listOf(0.7890625, 0.78125),
            3 to listOf(0.0234375, 0.125, 0.03125),
        ))
        val current = requireNotNull(identity(diagnostic, sceneEpoch = 190L))
        assertTrue(retain(diagnostic, lastTrusted = current, current = current))
        assertEquals("d561b9b9d1cf018c", current.fingerprint)
        assertEquals(190L, current.sceneEpoch)
    }

    @Test
    fun `all logged 1112 and 2315 expiry ages fit the bounded window`() {
        val ages = listOf(
            5_402L, 5_374L, 5_367L, 5_457L, 5_419L, 5_456L, 5_448L, 5_424L, 5_397L, 5_431L,
            5_127L, 5_068L, 5_102L, 5_114L, 5_090L,
        )
        ages.forEach { age -> assertTrue("logged age=$age", retain(withCache(age = age))) }
    }

    @Test
    fun `only two explicit waiting OCR statuses permit retention`() {
        listOf("TITLE_WAITING_OCR", "WAITING_OCR").forEach {
            assertTrue(it, retain(expiry.copy(status = it)))
        }
        listOf(
            "TITLE_CANDIDATE_MATCH", "TITLE_TRUSTED_MATCH", "CACHE_LOOKUP", "TITLE_FALLBACK",
            "NO_BUTTON_EVIDENCE", "NO_CATALOG_MATCH", "OCR_UNAVAILABLE",
            "INELIGIBLE_PAGE_OR_MOVE_DIALOG", "", "waiting_ocr",
        ).forEach { assertFalse(it, retain(expiry.copy(status = it))) }
    }

    @Test
    fun `every non event page rejects the same otherwise valid identity`() {
        LabyrinthEntryPageState.entries.filter { it != LabyrinthEntryPageState.EVENT_CHOICE }.forEach {
            assertFalse(it.name, retain(page = it))
        }
    }

    @Test
    fun `every visual identity field participates in equality`() {
        val previous = requireNotNull(identity(expiry))
        val changes = listOf(
            previous.copy(sceneEpoch = previous.sceneEpoch + 1),
            previous.copy(frameWidth = 1920),
            previous.copy(frameHeight = 1080),
            previous.copy(fingerprint = "another-title"),
            previous.copy(cropRect = crop.copy(left = 282)),
            previous.copy(layoutCount = 3),
        )
        changes.forEach { assertFalse(it.toString(), retain(lastTrusted = previous, current = it)) }
    }

    @Test
    fun `current diagnostic must agree with the supplied identity`() {
        val same = requireNotNull(identity(expiry))
        val changes = listOf(
            expiry.copy(fingerprint = "another-title"),
            expiry.copy(cropRect = crop.copy(top = 73)),
            expiry.copy(completeLayoutCount = 1, buttonConfidencesByOptionCount = mapOf(1 to listOf(0.8))),
            expiry.copy(completeLayoutCount = 3, buttonConfidencesByOptionCount = mapOf(3 to listOf(0.8, 0.8, 0.8))),
        )
        changes.forEach { assertFalse(retain(it, lastTrusted = same, current = same)) }
    }

    @Test
    fun `no cache status other than explicit expired permits retention`() {
        listOf(
            "HIT", "NO_CACHED_TEXT", "CLOCK_ROLLBACK", "CROP_CHANGED",
            "FINGERPRINT_CHANGED", "",
        ).forEach { status -> assertFalse(status, retain(withCache(status = status))) }
    }

    @Test
    fun `any fingerprint distance other than exact zero rejects retention`() {
        listOf<Int?>(null, -1, 1, 8, 9, Int.MAX_VALUE).forEach { distance ->
            assertFalse("distance=$distance", retain(expiry.copy(cache =
                requireNotNull(expiry.cache).copy(fingerprintDistance = distance))))
        }
    }

    @Test
    fun `cached crop must be present and equal to the current crop`() {
        listOf<EntryPixelRect?>(null, crop.copy(left = 282), crop.copy(width = 718)).forEach { rect ->
            assertFalse(retain(expiry.copy(cache = requireNotNull(expiry.cache).copy(cachedCropRect = rect))))
        }
    }

    @Test
    fun `empty failed unknown or missing result status is not a normal text refresh`() {
        listOf<String?>(null, "EMPTY_TEXT", "FAILED_OR_UNAVAILABLE", "TEXT_ERROR", "").forEach { status ->
            assertFalse(retain(expiry.copy(cache = requireNotNull(expiry.cache).copy(lastResultStatus = status))))
        }
    }

    @Test
    fun `expiry starts inclusively at lifetime and ends exclusively one timeout later`() {
        listOf(Long.MIN_VALUE, -1L, 0L, 4_999L, 10_000L, Long.MAX_VALUE).forEach { age ->
            assertFalse("age=$age", retain(withCache(age = age)))
        }
        listOf(5_000L, 5_001L, 9_999L).forEach { age ->
            assertTrue("age=$age", retain(withCache(age = age)))
        }
        assertFalse(retain(expiry.copy(cache = requireNotNull(expiry.cache).copy(ageMillis = null))))
    }

    @Test
    fun `window follows a positive reported lifetime without changing cache TTL`() {
        assertTrue(retain(withCache(age = 10_000L, lifetime = 10_000L)))
        assertTrue(retain(withCache(age = 14_999L, lifetime = 10_000L)))
        assertFalse(retain(withCache(age = 15_000L, lifetime = 10_000L)))
        assertFalse(retain(withCache(age = 5_000L, lifetime = 0L)))
        assertFalse(retain(withCache(age = 5_000L, lifetime = -1L)))
        assertFalse(retain(withCache(age = Long.MAX_VALUE, lifetime = Long.MIN_VALUE)))
    }

    @Test
    fun `upper bound arithmetic does not overflow long values`() {
        assertTrue(retain(withCache(age = Long.MAX_VALUE, lifetime = Long.MAX_VALUE)))
        assertTrue(retain(withCache(age = Long.MAX_VALUE, lifetime = Long.MAX_VALUE - 4_999L)))
        assertFalse(retain(withCache(age = Long.MAX_VALUE, lifetime = Long.MAX_VALUE - 5_000L)))
    }

    @Test
    fun `missing diagnostic cache or either identity always rejects retention`() {
        assertFalse(retain(diagnostic = null))
        assertFalse(retain(lastTrusted = null))
        assertFalse(retain(current = null))
        assertFalse(retain(lastTrusted = null, current = null))
        assertFalse(retain(expiry.copy(cache = null)))
    }

    @Test
    fun `factory requires nonblank title fingerprint crop and reported layout`() {
        assertNull(identity(null))
        listOf<String?>(null, "", "  ", "\t\n").forEach {
            assertNull(identity(expiry.copy(fingerprint = it)))
        }
        assertNull(identity(expiry.copy(cropRect = null)))
        listOf<Int?>(null, -1, 0, 4, Int.MAX_VALUE).forEach {
            assertNull(identity(expiry.copy(completeLayoutCount = it)))
        }
    }

    @Test
    fun `factory requires valid epoch frame dimensions and in frame crop`() {
        assertNull(identity(expiry, sceneEpoch = -1L))
        listOf(-1, 0).forEach { size ->
            assertNull(identity(expiry, frameWidth = size))
            assertNull(identity(expiry, frameHeight = size))
        }
        assertNull(identity(expiry.copy(cropRect = crop.copy(left = 1_000))))
        assertNull(identity(expiry.copy(cropRect = crop.copy(top = 700))))
        assertNull(identity(expiry.copy(cropRect = EntryPixelRect(Int.MAX_VALUE, 0, Int.MAX_VALUE, 1))))
        assertNull(identity(expiry.copy(cropRect = EntryPixelRect(0, Int.MAX_VALUE, 1, Int.MAX_VALUE))))
        assertTrue(identity(expiry.copy(cropRect = EntryPixelRect(0, 0, 1280, 720))) != null)
    }

    @Test
    fun `factory requires complete finite button evidence for reported layout`() {
        listOf(
            emptyMap(),
            mapOf(2 to emptyList()),
            mapOf(2 to listOf(0.8)),
            mapOf(2 to listOf(0.8, 0.8, 0.8)),
            mapOf(2 to listOf(0.8, 0.119)),
            mapOf(2 to listOf(0.8, Double.NaN)),
            mapOf(2 to listOf(0.8, Double.POSITIVE_INFINITY)),
            mapOf(2 to listOf(0.8, 1.1)),
        ).forEach { evidence -> assertNull(identity(expiry.copy(buttonConfidencesByOptionCount = evidence))) }
    }

    @Test
    fun `factory allows complete one two and three option layouts at the original threshold`() {
        (1..3).forEach { count ->
            val diagnostic = expiry.copy(
                completeLayoutCount = count,
                buttonConfidencesByOptionCount = mapOf(count to List(count) { 0.12 }),
            )
            assertEquals(count, requireNotNull(identity(diagnostic)).layoutCount)
            assertTrue(retain(diagnostic, lastTrusted = identity(diagnostic)))
        }
    }

    @Test
    fun `reported layout must match the existing conservative complete layout resolver`() {
        assertNull(identity(expiry.copy(buttonConfidencesByOptionCount = mapOf(
            2 to listOf(0.8, 0.8), 3 to listOf(0.8, 0.8, 0.8),
        ))))
        assertNull(identity(expiry.copy(buttonConfidencesByOptionCount = mapOf(
            2 to listOf(0.8, 0.8), 4 to listOf(0.8, 0.8, 0.8, 0.8),
        ))))
    }

    private fun identity(
        diagnostic: LabyrinthEventOcrDiagnostics?,
        sceneEpoch: Long = 243L,
        frameWidth: Int = 1280,
        frameHeight: Int = 720,
    ): LabyrinthEventVisualIdentity? =
        LabyrinthEventVisualIdentity.from(sceneEpoch, frameWidth, frameHeight, diagnostic)

    private fun retain(
        diagnostic: LabyrinthEventOcrDiagnostics? = expiry,
        lastTrusted: LabyrinthEventVisualIdentity? = identity(expiry),
        current: LabyrinthEventVisualIdentity? = identity(diagnostic),
        page: LabyrinthEntryPageState = LabyrinthEntryPageState.EVENT_CHOICE,
    ): Boolean = canRetainEventCalculationDuringOcrExpiry(lastTrusted, current, page, diagnostic)

    private fun withCache(
        status: String = "EXPIRED",
        age: Long = 5_127L,
        lifetime: Long = 5_000L,
    ): LabyrinthEventOcrDiagnostics = expiry.copy(cache = requireNotNull(expiry.cache).copy(
        status = status, ageMillis = age, cacheLifetimeMillis = lifetime,
    ))

    private fun expiredDiagnostic(
        fingerprint: String = "43759ca5d5c2138c",
        age: Long = 5_127L,
        request: Long = 185L,
        duration: Long = 64L,
        textLength: Int = 16,
    ): LabyrinthEventOcrDiagnostics = LabyrinthEventOcrDiagnostics(
        status = "TITLE_WAITING_OCR", cropRect = crop, fingerprint = fingerprint, completeLayoutCount = 2,
        buttonConfidencesByOptionCount = mapOf(
            1 to listOf(0.125), 2 to listOf(0.7890625, 0.78125),
            3 to listOf(0.0234375, 0.125, 0.1640625),
        ),
        cache = LabyrinthEventOcrCacheDiagnostics(
            status = "EXPIRED", ageMillis = age, fingerprintDistance = 0, cachedCropRect = crop,
            cachedRequestId = request, pendingRequestId = null, pendingAgeMillis = null,
            lastCompletedRequestId = request, lastRequestDurationMillis = duration,
            lastResultTextLength = textLength, lastResultStatus = "TEXT", cacheLifetimeMillis = 5_000L,
        ),
        requestScheduled = true,
    )
}
