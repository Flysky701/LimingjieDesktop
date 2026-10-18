package com.landosol.toolbox.labyrinth.vision

import com.landosol.toolbox.clanbattle.recognition.PixelImage
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LabyrinthBattleTeamRecognitionTest {
    @Test
    fun `battle geometry follows moved borders even when portrait samples are unchanged`() {
        assertChangedGeometryIsRemeasured(
            opening = false,
            before = openingFrameWithThumb(0, 0, intArrayOf(365), rowColumnCounts = intArrayOf(5)),
            after = openingFrameWithThumb(0, 0, intArrayOf(425), rowColumnCounts = intArrayOf(5)),
        )
    }

    @Test
    fun `opening geometry follows moved borders even when portrait samples are unchanged`() {
        assertChangedGeometryIsRemeasured(
            opening = true,
            before = openingFrameWithThumb(0, 0, intArrayOf(365), rowColumnCounts = intArrayOf(5)),
            after = openingFrameWithThumb(0, 0, intArrayOf(425), rowColumnCounts = intArrayOf(5)),
        )
    }

    @Test
    fun `horizontal card movement invalidates geometry in both editors`() {
        for (opening in listOf(false, true)) {
            assertChangedGeometryIsRemeasured(
                opening,
                before = openingFrameWithThumb(0, 0, intArrayOf(365), rowColumnCounts = intArrayOf(5)),
                after = openingFrameWithThumb(0, 0, intArrayOf(365),
                    columnLefts = AVAILABLE_SLOT_X.map { it + 12 }.toIntArray(),
                    rowColumnCounts = intArrayOf(5)),
            )
        }
    }

    @Test
    fun `second row movement with the same scrollbar invalidates both editor layouts`() {
        for (opening in listOf(false, true)) {
            assertChangedGeometryIsRemeasured(
                opening,
                before = openingFrameWithThumb(367, 826, intArrayOf(283, 495),
                    rowColumnCounts = intArrayOf(8, 5)),
                after = openingFrameWithThumb(367, 826, intArrayOf(283, 555),
                    rowColumnCounts = intArrayOf(8, 5)),
            )
        }
    }

    @Test
    fun `filtered card count invalidates geometry even without a scrollbar change`() {
        for (opening in listOf(false, true)) {
            assertChangedGeometryIsRemeasured(
                opening,
                before = openingFrameWithThumb(0, 0, intArrayOf(365), rowColumnCounts = intArrayOf(5)),
                after = openingFrameWithThumb(0, 0, intArrayOf(365), rowColumnCounts = intArrayOf(2)),
            )
        }
    }

    @Test
    fun `frame size changes invalidate cached coordinates in both editors`() {
        val before = openingFrameWithThumb(0, 0, intArrayOf(365), rowColumnCounts = intArrayOf(5))
        val after = PixelImage(960, 540, IntArray(960 * 540) { index ->
            before[(index % 960) * 2, (index / 960) * 2]
        })
        for (opening in listOf(false, true)) {
            assertChangedGeometryIsRemeasured(opening, before, after)
        }
    }

    @Test
    fun `mutating the caller frame cannot mutate cached geometry evidence`() {
        for (opening in listOf(false, true)) {
            val before = openingFrameWithThumb(0, 0, intArrayOf(365), rowColumnCounts = intArrayOf(5))
            val after = openingFrameWithThumb(0, 0, intArrayOf(425), rowColumnCounts = intArrayOf(5))
            val recognizer = LabyrinthBattleTeamRecognizer()
            observeEditor(recognizer, before, opening)
            after.pixels.copyInto(before.pixels)
            val waiting = observeEditor(recognizer, before, opening)
            assertEquals(LabyrinthBattleTeamRecognitionState.WAITING_FOR_STABILITY, waiting.recognitionState)
            assertTrue(waiting.visibleCharacters.isEmpty())
            val stable = observeEditor(recognizer, before, opening)
            assertEquals(425, stable.visibleCharacters.first().screenRect.top)
            assertEquals(LabyrinthBattleTeamRecognitionState.STABLE, stable.recognitionState)
        }
    }

    @Test
    fun `identical copied frames retain stable geometry across periodic remeasurement`() {
        for (opening in listOf(false, true)) {
            val frame = openingFrameWithThumb(0, 0, intArrayOf(365), rowColumnCounts = intArrayOf(5))
            val recognizer = LabyrinthBattleTeamRecognizer()
            val initial = observeEditor(recognizer, frame, opening)
            repeat(14) {
                val actual = observeEditor(recognizer, frame.copy(pixels = frame.pixels.copyOf()), opening)
                assertEquals(LabyrinthBattleTeamRecognitionState.STABLE, actual.recognitionState)
                assertEquals(initial.viewportRevision, actual.viewportRevision)
                assertEquals(initial.visibleCharacters.map { it.screenRect }, actual.visibleCharacters.map { it.screenRect })
            }
        }
    }

    private fun assertChangedGeometryIsRemeasured(opening: Boolean, before: PixelImage, after: PixelImage) {
        val recognizer = LabyrinthBattleTeamRecognizer()
        val initial = observeEditor(recognizer, before, opening)
        val expected = observeEditor(LabyrinthBattleTeamRecognizer(), after, opening)
        assertTrue(initial.visibleCharacters.isNotEmpty())
        assertTrue(expected.visibleCharacters.isNotEmpty())
        assertTrue(initial.visibleCharacters.map { it.screenRect } != expected.visibleCharacters.map { it.screenRect })
        val waiting = observeEditor(recognizer, after, opening)
        assertEquals(LabyrinthBattleTeamRecognitionState.WAITING_FOR_STABILITY, waiting.recognitionState)
        assertTrue("changed geometry must not expose old click coordinates", waiting.visibleCharacters.isEmpty())
        val stable = observeEditor(recognizer, after, opening)
        assertEquals(LabyrinthBattleTeamRecognitionState.STABLE, stable.recognitionState)
        assertTrue(stable.viewportRevision > initial.viewportRevision)
        assertEquals(expected.visibleCharacters.map { it.screenRect }, stable.visibleCharacters.map { it.screenRect })
    }

    private fun observeEditor(recognizer: LabyrinthBattleTeamRecognizer, frame: PixelImage, opening: Boolean) =
        if (opening) recognizer.recognizeOpening(frame) else recognizer.recognize(frame)

    @Test
    fun `opening ordinal marker distinguishes selected badge from favourite star and pale artwork`() {
        val recognizer = LabyrinthBattleTeamRecognizer(requiredStableFrames = 1)
        val card = EntryPixelRect(0, 0, CARD_SIZE, CARD_SIZE)
        val selected = markerFrame(background = 0xff303050.toInt()).also { frame ->
            fillCircle(frame, centerX = 162, centerY = 39, radius = 35, color = 0xfff2f2f2.toInt())
            fillRect(frame, left = 158, top = 24, width = 8, height = 31, color = 0xff668ac0.toInt())
        }
        val favourite = markerFrame(background = 0xff503040.toInt()).also { frame ->
            fillCircle(frame, centerX = 162, centerY = 39, radius = 18, color = 0xffffd24a.toInt())
        }
        val paleArtwork = markerFrame(background = 0xffeeeeee.toInt()).also { frame ->
            fillRect(frame, left = 154, top = 22, width = 12, height = 36, color = 0xff7b9dcc.toInt())
        }

        assertTrue(recognizer.isOpeningCardSelected(selected, card))
        assertTrue(!recognizer.isOpeningCardSelected(favourite, card))
        assertTrue(!recognizer.isOpeningCardSelected(paleArtwork, card))
    }

    @Test
    fun `opening scrollbar distinguishes search top initial stop and true bottom`() {
        val recognizer = LabyrinthBattleTeamRecognizer(requiredStableFrames = 1)

        val top = recognizer.recognizeOpening(openingFrameWithThumb(290, 749)).scrollbar
        recognizer.reset()
        val initialStop = recognizer.recognizeOpening(openingFrameWithThumb(367, 826)).scrollbar
        recognizer.reset()
        val trueBottom = recognizer.recognizeOpening(openingFrameWithThumb(393, 852)).scrollbar

        assertTrue("top=$top", top.canScroll)
        assertEquals(0.0, top.position, 0.01)
        assertTrue("initialStop=$initialStop", initialStop.canScroll)
        assertEquals(0.75, initialStop.position, 0.02)
        assertTrue("trueBottom=$trueBottom", trueBottom.canScroll)
        assertEquals(1.0, trueBottom.position, 0.01)
    }

    @Test
    fun `opening fixed layouts clip opposite edge at initial stop and true bottom`() {
        val recognizer = LabyrinthBattleTeamRecognizer(requiredStableFrames = 1)
        val initial = recognizer.recognizeOpening(
            openingFrameWithThumb(
                top = 367,
                bottomExclusive = 826,
                rowTops = intArrayOf(282, 494, 706),
            ),
        ).visibleCharacters
        recognizer.reset()
        val bottom = recognizer.recognizeOpening(
            openingFrameWithThumb(
                top = 393,
                bottomExclusive = 852,
                rowTops = intArrayOf(243, 454, 665),
            ),
        ).visibleCharacters

        assertRowGeometry(initial, row = 1, top = 282, height = 195)
        assertRowGeometry(initial, row = 2, top = 494, height = 195)
        assertRowGeometry(initial, row = 3, top = 706, height = 169)
        assertRowGeometry(bottom, row = 1, top = 269, height = 169)
        assertRowGeometry(bottom, row = 2, top = 454, height = 195)
        assertRowGeometry(bottom, row = 3, top = 665, height = 195)
    }

    @Test
    fun `shared card grid follows displaced borders in opening and battle editors`() {
        val shiftedColumns = AVAILABLE_SLOT_X.map { it + 9 }.toIntArray()
        val recognizer = LabyrinthBattleTeamRecognizer(requiredStableFrames = 1)
        val opening = recognizer.recognizeOpening(
            openingFrameWithThumb(
                top = 367,
                bottomExclusive = 826,
                rowTops = intArrayOf(291, 503, 715),
                columnLefts = shiftedColumns,
            ),
        ).visibleCharacters
        recognizer.reset()
        val battle = recognizer.recognize(
            openingFrameWithThumb(
                top = 0,
                bottomExclusive = 0,
                rowTops = intArrayOf(365),
                columnLefts = shiftedColumns,
            ),
        ).visibleCharacters

        assertEquals(131, opening.first().screenRect.left)
        assertEquals(291, opening.first().screenRect.top)
        assertEquals(131, battle.first().screenRect.left)
        assertEquals(365, battle.first().screenRect.top)
    }

    @Test
    fun `opening attribute filter keeps a single visible card row`() {
        val recognizer = LabyrinthBattleTeamRecognizer(requiredStableFrames = 1)

        val matches = recognizer.recognizeOpening(
            openingFrameWithThumb(
                top = 393,
                bottomExclusive = 852,
                rowTops = intArrayOf(283),
                rowColumnCounts = intArrayOf(5),
            ),
        ).visibleCharacters

        assertEquals(5, matches.size)
        assertTrue(matches.all { it.slotId.startsWith("available_row_1_") })
        assertTrue(matches.all { it.screenRect.top == 283 })
        assertTrue(matches.all { it.screenRect.height == CARD_SIZE })
    }

    @Test
    fun `battle attribute filter keeps a single visible card row`() {
        val recognizer = LabyrinthBattleTeamRecognizer(requiredStableFrames = 1)

        val matches = recognizer.recognize(
            openingFrameWithThumb(
                top = 600,
                bottomExclusive = 735,
                rowTops = intArrayOf(420),
                rowColumnCounts = intArrayOf(5),
            ),
        ).visibleCharacters

        assertEquals(5, matches.size)
        assertTrue(matches.all { it.slotId.startsWith("available_row_1_") })
        assertTrue(matches.all { it.screenRect.top == 420 })
        assertTrue(matches.all { it.screenRect.height == CARD_SIZE })
    }

    @Test
    fun `battle checkmark refreshes selection without a viewport geometry change`() {
        val recognizer = LabyrinthBattleTeamRecognizer(requiredStableFrames = 1)
        val frame = openingFrameWithThumb(
            top = 0,
            bottomExclusive = 0,
            rowTops = intArrayOf(365),
            rowColumnCounts = intArrayOf(1),
        )
        val initial = recognizer.recognize(frame)
        val card = initial.visibleCharacters.single()
        assertTrue(!card.selected)

        fillRect(
            frame = frame,
            left = card.screenRect.left + 115,
            top = card.screenRect.top + 8,
            width = 52,
            height = 52,
            color = 0xffffc43d.toInt(),
        )
        fillRect(
            frame = frame,
            left = card.screenRect.left + 126,
            top = card.screenRect.top + 18,
            width = 30,
            height = 30,
            color = 0xfff7f7ee.toInt(),
        )
        val refreshed = recognizer.recognize(frame)

        assertEquals(initial.viewportRevision, refreshed.viewportRevision)
        assertTrue(refreshed.visibleCharacters.single().selected)
    }

    @Test
    fun `battle portrait content invalidates cached viewport even when geometry is unchanged`() {
        val recognizer = LabyrinthBattleTeamRecognizer(requiredStableFrames = 1)
        val frame = openingFrameWithThumb(
            top = 0,
            bottomExclusive = 0,
            rowTops = intArrayOf(365),
            rowColumnCounts = intArrayOf(1),
        )
        val initial = recognizer.recognize(frame)
        val rect = initial.visibleCharacters.single().screenRect

        // Simulate a scroll that settles another character into exactly the same card slot while
        // the scrollbar remains in the same coarse bucket.
        fillRect(
            frame = frame,
            left = rect.left + rect.width * 25 / 100,
            top = rect.top + rect.height * 20 / 100,
            width = rect.width * 50 / 100,
            height = rect.height * 50 / 100,
            color = 0xff18c87a.toInt(),
        )
        val changed = recognizer.recognize(frame)

        assertTrue("initial=${initial.viewportRevision} changed=${changed.viewportRevision}",
            changed.viewportRevision > initial.viewportRevision)
    }

    @Test
    fun `current member and roster use the same mask for level and right edge chrome`() {
        val portraits = (1..3).map { seed ->
            PixelImage(128, 128, IntArray(128 * 128) { index ->
                val x = index % 128 / 12
                val y = index / 128 / 10
                val red = 50 + (x * 37 + y * 19 + seed * 43) % 150
                val green = 50 + (x * 13 + y * 47 + seed * 61) % 150
                val blue = 50 + (x * 53 + y * 7 + seed * 29) % 150
                (0xff shl 24) or (red shl 16) or (green shl 8) or blue
            })
        }
        val frame = openingFrameWithThumb(0, 0, intArrayOf(365), rowColumnCounts = intArrayOf(1))
        val positions = listOf(122 to 365, 750 to 811)
        positions.forEach { (left, top) ->
            fillRect(frame, left, top, CARD_SIZE, CARD_SIZE, 0xffa05090.toInt())
            val size = CARD_SIZE - 14
            repeat(size) { y -> repeat(size) { x ->
                val color = if ((x < size * 0.42 && y < size * 0.36) || x > size * 0.85) {
                    // Deliberately unrelated label/right-edge artwork at both positions.
                    if ((x / 5 + y / 4) % 2 == 0) 0xff202020.toInt() else 0xfff5f5f5.toInt()
                } else portraits[0][(x * 127.0 / (size - 1)).roundToInt(), (y * 127.0 / (size - 1)).roundToInt()]
                frame.pixels[(top + 6 + y) * frame.width + left + 6 + x] = color
            } }
        }
        val recognizer = LabyrinthBattleTeamRecognizer(templates = portraits.mapIndexed { index, portrait ->
            LabyrinthBattleCharacterTemplate("role$index", "角色$index", "31", portrait)
        }, requiredStableFrames = 1)
        val observed = recognizer.recognize(frame)
        val upper = observed.visibleCharacters.single()
        val lower = observed.selectedCharacters.single()
        assertTrue("$upper", upper.trusted)
        assertTrue("$lower", lower.trusted)
        assertEquals("role0", upper.characterId)
        assertEquals(upper.characterId, lower.characterId)
        assertEquals(upper.confidence, lower.confidence, 0.001)
        val cached = recognizer.recognize(frame).selectedCharacters.single()
        assertEquals(lower.characterId, cached.characterId)
        assertEquals(lower.confidence, cached.confidence, 0.001)
    }

    @Test
    fun `battle single row follows detected cards while calibrated multi row remains compatible`() {
        val recognizer = LabyrinthBattleTeamRecognizer(requiredStableFrames = 1)
        val detected = EntryPixelRect(left = 122, top = 283, width = CARD_SIZE, height = CARD_SIZE)
        val calibrated = EntryPixelRect(left = 122, top = 243, width = CARD_SIZE, height = CARD_SIZE)

        assertEquals(
            detected,
            recognizer.rosterRecognitionRect(
                detectedRowCount = 1,
                detectedRect = detected,
                fallbackRect = calibrated,
            ),
        )
        assertEquals(
            calibrated,
            recognizer.rosterRecognitionRect(
                detectedRowCount = 3,
                detectedRect = detected,
                fallbackRect = calibrated,
            ),
        )
    }

    @Test
    fun `sparse effective roster cannot stretch a card crop into the empty area below`() {
        val recognizer = LabyrinthBattleTeamRecognizer(requiredStableFrames = 1)
        val detected = EntryPixelRect(
            left = 322,
            top = 224,
            width = CARD_SIZE,
            height = CARD_SIZE * 2 + 36,
        )
        val calibrated = EntryPixelRect(
            left = 320,
            top = 220,
            width = CARD_SIZE,
            height = CARD_SIZE,
        )

        val normalized = recognizer.normalizeRosterCardRect(detected, calibrated)

        assertEquals(detected.left, normalized.left)
        assertEquals(detected.top, normalized.top)
        assertEquals(CARD_SIZE, normalized.width)
        assertEquals(CARD_SIZE, normalized.height)
        assertEquals(
            normalized,
            recognizer.rosterRecognitionRect(
                detectedRowCount = 1,
                detectedRect = normalized,
                fallbackRect = calibrated,
            ),
        )
    }

    @Test
    fun `legitimately clipped roster card is not expanded by normalization`() {
        val recognizer = LabyrinthBattleTeamRecognizer(requiredStableFrames = 1)
        val detected = EntryPixelRect(
            left = 322,
            top = 224,
            width = CARD_SIZE,
            height = CARD_SIZE / 2,
        )
        val calibrated = EntryPixelRect(
            left = 320,
            top = 220,
            width = CARD_SIZE,
            height = CARD_SIZE,
        )

        assertEquals(detected, recognizer.normalizeRosterCardRect(detected, calibrated))
    }

    @Test
    fun `sparse effective roster is normalized even when no fallback slot exists`() {
        val recognizer = LabyrinthBattleTeamRecognizer(requiredStableFrames = 1)
        val detected = EntryPixelRect(
            left = 322,
            top = 224,
            width = CARD_SIZE,
            height = CARD_SIZE * 2 + 36,
        )

        val normalized = recognizer.normalizeRosterCardRect(
            detectedRect = detected,
            fallbackRect = null,
            expectedWidth = CARD_SIZE,
            expectedHeight = CARD_SIZE,
        )

        assertEquals(EntryPixelRect(322, 224, CARD_SIZE, CARD_SIZE), normalized)
    }

    @Test
    fun `sparse eight plus one grid ignores warm first-row tail when snapping second row`() {
        val frame = PixelImage(
            width = 1920,
            height = 1080,
            pixels = IntArray(1920 * 1080) { 0xfffafafa.toInt() },
        )
        AVAILABLE_SLOT_X.forEach { left ->
            fillRect(frame, left, 243, CARD_SIZE, CARD_SIZE, 0xff7a3f86.toInt())
            // Mimic warm rank/star chrome inside the lower part of the first-row card. The old
            // snap score could mistake this internal strip for an outer boundary of the next row.
            fillRect(frame, left, 410, CARD_SIZE, 5, 0xffc45b28.toInt())
        }
        fillRect(frame, AVAILABLE_SLOT_X.first(), 461, CARD_SIZE, CARD_SIZE, 0xff9a4c72.toInt())

        val slots = LabyrinthCharacterGridDetector().detect(
            frame = frame,
            viewportReference = EntryReferenceRect(60, 230, 1800, 516),
            referenceCardSize = CARD_SIZE,
            referenceColumnPitch = 212,
            referenceRowPitch = 218,
            maxColumns = 8,
        )
        val firstRow = slots.filter { it.fullRect.top in 240..246 }
        val secondRow = slots.filter { it.fullRect.top in 458..464 }

        assertEquals("slots=$slots", 9, slots.size)
        assertEquals("slots=$slots", 8, firstRow.size)
        assertEquals("slots=$slots", 1, secondRow.size)
        assertTrue("slots=$slots", slots.none { it.fullRect.top in 405..430 })
    }

    @Test
    fun `complete opening rows use measured crop even within old calibration tolerance`() {
        val recognizer = LabyrinthBattleTeamRecognizer(requiredStableFrames = 1)
        val detected = EntryPixelRect(131, 283, CARD_SIZE, CARD_SIZE)
        val calibrated = EntryPixelRect(122, 243, CARD_SIZE, CARD_SIZE)
        for (rows in 1..3) {
            assertEquals(detected, recognizer.rosterRecognitionRect(rows, detected, calibrated, preferDetected = true))
        }
    }

    @Test
    fun `shifted multi row opening identifies artwork using measured card bounds`() {
        fun artwork(seed: Int) = PixelImage(128, 128, IntArray(128 * 128) { index ->
            val x = index % 128 / 12
            val y = index / 128 / 10
            val red = 50 + (x * 37 + y * 19 + seed * 43) % 150
            val green = 50 + (x * 13 + y * 47 + seed * 61) % 150
            val blue = 50 + (x * 53 + y * 7 + seed * 29) % 150
            (0xff shl 24) or (red shl 16) or (green shl 8) or blue
        })
        val portraits = listOf(artwork(1), artwork(2))
        val rowTops = intArrayOf(322, 534)
        val columns = AVAILABLE_SLOT_X.map { it + 30 }.toIntArray()
        val frame = openingFrameWithThumb(367, 826, rowTops, columns, intArrayOf(8, 2))
        rowTops.forEachIndexed { row, top ->
            columns.take(if (row == 0) 8 else 2).forEach { left ->
                val size = CARD_SIZE - 14
                repeat(size) { y -> repeat(size) { x ->
                    frame.pixels[(top + 6 + y) * frame.width + left + 6 + x] = portraits[row][
                        (x * 127.0 / (size - 1)).roundToInt(), (y * 127.0 / (size - 1)).roundToInt()]
                } }
            }
        }
        val recognizer = LabyrinthBattleTeamRecognizer(templates = portraits.mapIndexed { index, portrait ->
            LabyrinthBattleCharacterTemplate("role$index", "角色$index", "31", portrait)
        }, requiredStableFrames = 1)
        val observed = recognizer.recognizeOpening(frame).visibleCharacters
        assertEquals(10, observed.size)
        observed.forEach { match ->
            val expected = if (match.slotId.startsWith("available_row_1_")) "role0" else "role1"
            assertTrue("$match", match.trusted)
            assertEquals(expected, match.characterId)
        }
    }

    @Test
    fun `clipped opening row can retain nearby full card calibration`() {
        val recognizer = LabyrinthBattleTeamRecognizer(requiredStableFrames = 1)
        val detected = EntryPixelRect(131, 250, CARD_SIZE, CARD_SIZE)
        val calibrated = EntryPixelRect(122, 243, CARD_SIZE, CARD_SIZE)
        assertEquals(calibrated, recognizer.rosterRecognitionRect(3, detected, calibrated, preferDetected = false))
    }

    @Test
    fun `multi row identity cannot be borrowed from a different card`() {
        val recognizer = LabyrinthBattleTeamRecognizer(requiredStableFrames = 1)
        val detected = EntryPixelRect(758, 665, CARD_SIZE, CARD_SIZE)
        for (calibrated in listOf(
            EntryPixelRect(334, 665, CARD_SIZE, CARD_SIZE),
            EntryPixelRect(758, 454, CARD_SIZE, CARD_SIZE),
        )) {
            assertEquals(detected, recognizer.rosterRecognitionRect(3, detected, calibrated))
        }
    }

    @Test
    fun `a free picker never inherits the joined roster from the previous battle`() {
        val truth = PixelImage(128, 128, IntArray(128 * 128) { index ->
            val x = index % 128 / 12
            val y = index / 128 / 10
            val red = 50 + (x * 37 + y * 19) % 150
            val green = 50 + (x * 13 + y * 47) % 150
            val blue = 50 + (x * 53 + y * 7) % 150
            (0xff shl 24) or (red shl 16) or (green shl 8) or blue
        })
        val twin = truth.copy(pixels = truth.pixels.map { it xor 0x00010101 }.toIntArray())
        val templates = listOf(
            LabyrinthBattleCharacterTemplate("new", "未获得的角色", "31", truth),
            LabyrinthBattleCharacterTemplate("owned", "已获得的相似角色", "31", twin),
        )
        val frame = openingFrameWithThumb(367, 826, intArrayOf(365), rowColumnCounts = intArrayOf(1))
        val size = CARD_SIZE - 14
        repeat(size) { y -> repeat(size) { x ->
            frame.pixels[(365 + 6 + y) * frame.width + 122 + 6 + x] = truth[
                (x * 127.0 / (size - 1)).roundToInt(), (y * 127.0 / (size - 1)).roundToInt()]
        } }
        val expected = LabyrinthBattleTeamRecognizer(templates, requiredStableFrames = 1)
            .recognizeOpening(frame).visibleCharacters.single()
        val reused = LabyrinthBattleTeamRecognizer(templates, requiredStableFrames = 1)
        reused.recognize(frame, rosterCharacterIds = setOf("owned"))
        assertEquals("a direct battle-to-picker transition must use the full icon pack", expected,
            reused.recognizeOpening(frame).visibleCharacters.single())
        reused.reset()
        val actual = reused.recognizeOpening(frame).visibleCharacters.single()
        assertEquals("the unknown role must not become a trusted already-owned look-alike", expected, actual)
    }

    private fun assertRowGeometry(
        matches: List<LabyrinthBattleCharacterMatch>,
        row: Int,
        top: Int,
        height: Int,
    ) {
        val rowMatches = matches.filter { it.slotId.startsWith("available_row_${row}_") }
        assertEquals(if (row == 3) 2 else 8, rowMatches.size)
        assertTrue("row=$row expectedTop=$top matches=$rowMatches", rowMatches.all { it.screenRect.top == top })
        assertTrue(
            "row=$row expectedHeight=$height matches=$rowMatches",
            rowMatches.all { it.screenRect.height == height },
        )
    }

    private fun openingFrameWithThumb(
        top: Int,
        bottomExclusive: Int,
        rowTops: IntArray = intArrayOf(),
        columnLefts: IntArray = AVAILABLE_SLOT_X,
        rowColumnCounts: IntArray? = null,
    ): PixelImage {
        val width = 1920
        val pixels = IntArray(width * 1080) { 0xfff4f4f4.toInt() }
        for (y in top until bottomExclusive) {
            for (x in 1825 until 1853) {
                pixels[y * width + x] = 0xff4f9fe8.toInt()
            }
        }
        rowTops.forEachIndexed { rowIndex, rowTop ->
            val columns = rowColumnCounts?.getOrNull(rowIndex)
                ?: if (rowIndex == rowTops.lastIndex) 2 else 8
            columnLefts.take(columns).forEach { cardLeft ->
                for (y in rowTop until rowTop + CARD_SIZE) {
                    for (x in cardLeft until cardLeft + CARD_SIZE) {
                        pixels[y * width + x] = 0xffa05090.toInt()
                    }
                }
            }
        }
        return PixelImage(width, 1080, pixels)
    }

    private fun markerFrame(background: Int): PixelImage =
        PixelImage(CARD_SIZE, CARD_SIZE, IntArray(CARD_SIZE * CARD_SIZE) { background })

    private fun fillCircle(
        frame: PixelImage,
        centerX: Int,
        centerY: Int,
        radius: Int,
        color: Int,
    ) {
        for (y in (centerY - radius).coerceAtLeast(0)..(centerY + radius).coerceAtMost(frame.height - 1)) {
            for (x in (centerX - radius).coerceAtLeast(0)..(centerX + radius).coerceAtMost(frame.width - 1)) {
                if ((x - centerX) * (x - centerX) + (y - centerY) * (y - centerY) <= radius * radius) {
                    frame.pixels[y * frame.width + x] = color
                }
            }
        }
    }

    private fun fillRect(
        frame: PixelImage,
        left: Int,
        top: Int,
        width: Int,
        height: Int,
        color: Int,
    ) {
        for (y in top until top + height) {
            for (x in left until left + width) {
                frame.pixels[y * frame.width + x] = color
            }
        }
    }

    private companion object {
        val AVAILABLE_SLOT_X = intArrayOf(122, 334, 545, 757, 969, 1181, 1393, 1605)
        const val CARD_SIZE = 195
    }
}
