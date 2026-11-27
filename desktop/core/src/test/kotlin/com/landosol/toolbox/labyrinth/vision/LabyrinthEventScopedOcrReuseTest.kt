package com.landosol.toolbox.labyrinth.vision

import com.landosol.toolbox.gamedata.EventChoiceResource
import com.landosol.toolbox.gamedata.EventResource
import com.landosol.toolbox.gamedata.LabyrinthEventOcrDocument
import com.landosol.toolbox.vision.NativeBitmap
import java.io.File
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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

    @Test
    fun `full ocr fallback for non custom two choice event tolerates animation jitter and transient waiting ocr`() {
        val ocr = Json.decodeFromString<LabyrinthEventOcrDocument>(
            File("src/main/resources/assets/resource-packs/cn-bilibili/labyrinth-event-ocr.json").readText(),
        )
        val mapper = LabyrinthEventLayoutMapper(ocr.layouts)
        val width = 640
        val height = 360
        val pixels = IntArray(width * height) { -1 }
        fun paint(rect: EntryPixelRect, color: Int) {
            for (y in rect.top until rect.top + rect.height) {
                pixels.fill(color, y * width + rect.left, y * width + rect.left + rect.width)
            }
        }
        mapper.buttonRects(2, width, height).forEach { paint(it, 0xff63a6f7.toInt()) }
        val fullCrop = requireNotNull(mapper.ocrBounds(2, width, height))
        // Paint a high-contrast left-to-right gradient in the crop so shifting one sample column changes dHash.
        for (y in fullCrop.top + 80 until fullCrop.top + 120) {
            for (x in fullCrop.left until fullCrop.left + fullCrop.width) {
                val gray = ((x - fullCrop.left) * 255 / fullCrop.width.coerceAtLeast(1)).coerceIn(0, 255)
                pixels[y * width + x] = (0xff shl 24) or (gray shl 16) or (gray shl 8) or gray
            }
        }

        val event3315 = EventResource(
            id = "3315",
            name = "完美！不过食材还有很多哦！",
            choices = listOf(
                EventChoiceResource("33152", "这么多 根本吃不完吧！", 1, "随机职能的 选择印记×2"),
                EventChoiceResource("33151", "诶嘿嘿～ 再一下就好……", 2, "有50％的概率获得全体选择印记×２"),
            ),
        )
        val event1108 = EventResource(
            id = "1108",
            name = "在地下城迷路了！要走哪边？",
            choices = listOf(
                EventChoiceResource("11081", "难道不是左边吗？", 1, "阿尔法金币×1000"),
                EventChoiceResource("11082", "当然是右边啊！", 2, "50％概率获得★1迷宫遗物×1 50％概率获得随机职能的 随机印记×2"),
            ),
        )
        var holdPending = false
        var deferredDone: ((String?) -> Unit)? = null
        val fullText3315 = "完美！不过食材还有很多哦！\n" +
            "这么多 根本吃不完吧！随机职能的 选择印记×2\n" +
            "诶嘿嘿～ 再一下就好……有50％的概率获得全体选择印记×２"
        val resolver = LabyrinthEventChoiceResolver(
            data = LabyrinthEventRecognitionData(listOf(event3315, event1108), ocr),
            submitTextRead = { _, done ->
                if (holdPending) {
                    deferredDone = done
                } else {
                    done(fullText3315)
                }
            },
            eventChoiceOverrides = mapOf("在地下城迷路了！要走哪边？" to "难道不是左边吗？"),
            eventCustomizationEnabled = true,
        )
        val unknownFrame = LabyrinthEntryFrameResult(
            LabyrinthEntryPageObservation(LabyrinthEntryPageState.UNKNOWN, 1.0, emptyMap(), LabyrinthAnchorScores(emptyMap())),
            emptyList(),
            0,
            frameWidth = width,
            frameHeight = height,
        )
        val eventPageFrame = LabyrinthEntryFrameResult(
            LabyrinthEntryPageObservation(
                LabyrinthEntryPageState.EVENT_CHOICE,
                1.0,
                mapOf(LabyrinthEntryPageState.EVENT_CHOICE to 1.0),
                LabyrinthAnchorScores(emptyMap()),
            ),
            emptyList(),
            0,
            frameWidth = width,
            frameHeight = height,
        )
        val bitmapA = NativeBitmap.create(pixels, width, height)
        val sampleY2 = fullCrop.top + ((2.0 * (fullCrop.height - 1)) / 7.0).toInt()
        val sampleX4 = fullCrop.left + ((4.0 * (fullCrop.width - 1)) / 8.0).toInt()
        val pixelsChangedOnce = pixels.copyOf().also { copy ->
            for (y in (sampleY2 - 4).coerceAtLeast(fullCrop.top)..(sampleY2 + 4).coerceAtMost(fullCrop.top + fullCrop.height - 1)) {
                copy.fill(
                    0xff000000.toInt(),
                    y * width + (sampleX4 - 4).coerceAtLeast(fullCrop.left),
                    y * width + (sampleX4 + 4).coerceAtMost(fullCrop.left + fullCrop.width),
                )
            }
        }
        val bitmapSmallJitter = NativeBitmap.create(pixelsChangedOnce, width, height)
        val pixelsMajorShift = pixels.copyOf().also { copy ->
            for (y in fullCrop.top until fullCrop.top + fullCrop.height) {
                for (x in fullCrop.left until fullCrop.left + fullCrop.width) {
                    val v = if ((x + y) % 16 < 8) 0xff101010.toInt() else 0xfff0f0f0.toInt()
                    copy[y * width + x] = v
                }
            }
            for (rect in mapper.buttonRects(2, width, height)) {
                for (y in rect.top until rect.top + rect.height) {
                    copy.fill(0xff63a6f7.toInt(), y * width + rect.left, y * width + rect.left + rect.width)
                }
            }
        }
        val bitmapMajorShift = NativeBitmap.create(pixelsMajorShift, width, height)
        try {
            // 1) Initial title-only probe misses custom titles -> TITLE_FALLBACK switches to fullOcrFallback.
            assertEquals("TITLE_WAITING_OCR", resolver.resolve(bitmapA, unknownFrame).eventOcrDiagnostics?.status)
            assertEquals("TITLE_FALLBACK", resolver.resolve(bitmapA, unknownFrame).eventOcrDiagnostics?.status)
            // 2) Full OCR fallback caches full text and produces first candidate match for 3315.
            assertEquals("WAITING_OCR", resolver.resolve(bitmapA, unknownFrame).eventOcrDiagnostics?.status)
            val firstMatch = resolver.resolve(bitmapA, unknownFrame)
            assertEquals("CANDIDATE_MATCH", firstMatch.eventOcrDiagnostics?.status)
            assertEquals("3315", firstMatch.eventChoiceSelection?.event?.id)
            assertEquals(1, firstMatch.eventChoiceSelection?.stableFrames)

            // 3) Transient WAITING_OCR frame (major crop shift forces cache miss) on EVENT_CHOICE preserves stableFrames=1,
            //    and once OCR completes again the next frame reaches stableFrames=2, trusted=true.
            holdPending = true
            val waiting = resolver.resolve(bitmapMajorShift, eventPageFrame)
            assertEquals("WAITING_OCR", waiting.eventOcrDiagnostics?.status)
            deferredDone?.invoke(fullText3315)
            holdPending = false
            val secondAfterWait = resolver.resolve(bitmapMajorShift, eventPageFrame)
            assertEquals("TRUSTED_MATCH", secondAfterWait.eventOcrDiagnostics?.status)
            assertEquals(2, secondAfterWait.eventChoiceSelection?.stableFrames)
            assertTrue(secondAfterWait.eventChoiceSelection?.trusted == true)

            // 4) Non-custom fullOcrFallback also accepts 1..8 bit crop jitter when reusing text directly.
            holdPending = true
            resolver.resolve(bitmapA, eventPageFrame)
            deferredDone?.invoke(fullText3315)
            holdPending = false
            val anchor = resolver.resolve(bitmapA, eventPageFrame)
            val reused = resolver.resolve(bitmapSmallJitter, eventPageFrame)
            val distance = reused.eventOcrDiagnostics?.cache?.fingerprintDistance
            assertEquals("HIT", reused.eventOcrDiagnostics?.cache?.status)
            assertTrue("expected distance in 1..8, got $distance", distance in 1..8)
            assertEquals("TRUSTED_MATCH", reused.eventOcrDiagnostics?.status)
            assertEquals(
                requireNotNull(anchor.eventChoiceSelection).stableFrames + 1,
                reused.eventChoiceSelection?.stableFrames,
            )
        } finally {
            bitmapA.recycle()
            bitmapSmallJitter.recycle()
            bitmapMajorShift.recycle()
        }
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
