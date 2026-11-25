package com.landosol.toolbox.labyrinth.vision

import com.landosol.toolbox.clanbattle.recognition.PixelImage
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

internal data class LabyrinthDetectedGridSlot(
    val rowIndex: Int,
    val columnIndex: Int,
    /** Full square card geometry; it may extend beyond the roster viewport at a clipped edge. */
    val fullRect: EntryPixelRect,
    val clippedAtTop: Boolean,
    val clippedAtBottom: Boolean,
)

internal data class LabyrinthGridBandDiagnostic(
    val start: Int,
    val endExclusive: Int,
)

internal data class LabyrinthGridSnapCandidateDiagnostic(
    val top: Int,
    val boundaryScore: Double,
    val alignmentScore: Double,
    val totalScore: Double,
)

internal data class LabyrinthGridRowDiagnostic(
    val approximate: LabyrinthGridBandDiagnostic,
    val snapped: LabyrinthGridBandDiagnostic,
    val topCandidates: List<LabyrinthGridSnapCandidateDiagnostic>,
)

internal data class LabyrinthGridDiagnostics(
    val cardSize: Int,
    val rawRows: List<LabyrinthGridBandDiagnostic>,
    val regularizedRows: List<LabyrinthGridBandDiagnostic>,
    val rowDiagnostics: List<LabyrinthGridRowDiagnostic>,
)

/**
 * Finds the regular character-card grid from the pixels that are currently visible.
 *
 * Reference coordinates define only the roster search area and expected size tolerances. Card
 * positions are recovered from the white gaps between colourful card bands, then snapped to the
 * strongest nearby outer edges. This keeps both opening and battle-team boxes attached to the
 * actual cards when a search row expands, collapses, or a scrollbar reaches an end stop.
 */
internal class LabyrinthCharacterGridDetector {
    internal fun diagnoseRows(
        frame: PixelImage,
        viewportReference: EntryReferenceRect,
        referenceCardSize: Int,
        referenceColumnPitch: Int,
        referenceRowPitch: Int,
        maxColumns: Int,
        rowTopHints: List<Int> = emptyList(),
    ): LabyrinthGridDiagnostics {
        val viewport = ReferenceFitMapper.map(
            frameWidth = frame.width,
            frameHeight = frame.height,
            referenceSize = STANDARD_REFERENCE,
            referenceRect = viewportReference,
        ) ?: return LabyrinthGridDiagnostics(0, emptyList(), emptyList(), emptyList())
        val cardSize = mappedLength(frame, referenceCardSize).coerceAtLeast(MIN_CARD_PIXELS)
        val columnPitch = mappedLength(frame, referenceColumnPitch).coerceAtLeast(cardSize + 2)
        val rowPitch = mappedLength(frame, referenceRowPitch).coerceAtLeast(cardSize + 2)
        val measuredColumns = detectColumnBands(frame, viewport, cardSize)
        val columns = regularizeBands(
            bands = measuredColumns,
            expectedSize = cardSize,
            expectedPitch = columnPitch,
            maximumCount = maxColumns,
            lowerBound = viewport.left,
            upperBoundExclusive = viewport.left + viewport.width,
        )
        if (columns.size < MIN_GRID_COLUMNS) {
            return LabyrinthGridDiagnostics(cardSize, emptyList(), emptyList(), emptyList())
        }
        val rawRows = detectRowBands(
            frame,
            viewport,
            rowSupportColumns(measuredColumns, columns, cardSize),
            cardSize,
        )
        val regularized = regularizeRows(
            frame = frame,
            viewport = viewport,
            columns = columns,
            bands = rawRows,
            cardSize = cardSize,
            rowPitch = rowPitch,
        )
        val hintedRows = rowTopHints.distinct().sorted().map { Band(it, it + cardSize) }
            .filter { row -> rowHasCard(frame, viewport, columns, row, cardSize) }
        val rows = regularized.ifEmpty { hintedRows }
        val snappedColumns = columns.map { band -> snapColumn(frame, viewport, rows, band, cardSize) }
        val radius = max(2, (cardSize * EDGE_SEARCH_RADIUS_RATIO).roundToInt())
        val viewportBottom = viewport.top + viewport.height
        val diagnostics = rows.map { row ->
            val clippedTop = row.start < viewport.top
            val clippedBottom = row.endExclusive > viewportBottom
            val candidates = if (clippedTop) {
                (row.endExclusive - radius..row.endExclusive + radius).map { candidateBottom ->
                    val boundary = horizontalTrailingBoundaryScore(frame, candidateBottom, viewport, snappedColumns)
                    val alignment = rowAlignmentScore(
                        frame,
                        viewport,
                        snappedColumns,
                        Band(candidateBottom - cardSize, candidateBottom),
                    )
                    LabyrinthGridSnapCandidateDiagnostic(
                        top = candidateBottom - cardSize + OUTSIDE_EDGE_OFFSET,
                        boundaryScore = boundary,
                        alignmentScore = alignment,
                        totalScore = boundary + alignment * ROW_ALIGNMENT_WEIGHT,
                    )
                }
            } else {
                (row.start - radius..row.start + radius).map { candidateTop ->
                    val boundary = horizontalLeadingBoundaryScore(frame, candidateTop, viewport, snappedColumns)
                    val alignment = rowAlignmentScore(
                        frame,
                        viewport,
                        snappedColumns,
                        Band(candidateTop, candidateTop + cardSize),
                    )
                    LabyrinthGridSnapCandidateDiagnostic(
                        top = candidateTop + if (clippedBottom) INSIDE_EDGE_OFFSET else INSIDE_EDGE_OFFSET,
                        boundaryScore = boundary,
                        alignmentScore = alignment,
                        totalScore = boundary + alignment * ROW_ALIGNMENT_WEIGHT,
                    )
                }
            }
            LabyrinthGridRowDiagnostic(
                approximate = row.toDiagnostic(),
                snapped = snapRow(frame, viewport, snappedColumns, row, cardSize).toDiagnostic(),
                topCandidates = candidates.sortedByDescending { it.totalScore }.take(8),
            )
        }
        return LabyrinthGridDiagnostics(
            cardSize = cardSize,
            rawRows = rawRows.map(Band::toDiagnostic),
            regularizedRows = regularized.map(Band::toDiagnostic),
            rowDiagnostics = diagnostics,
        )
    }

    fun detect(
        frame: PixelImage,
        viewportReference: EntryReferenceRect,
        referenceCardSize: Int,
        referenceColumnPitch: Int,
        referenceRowPitch: Int,
        maxColumns: Int,
        rowTopHints: List<Int> = emptyList(),
    ): List<LabyrinthDetectedGridSlot> {
        val viewport = ReferenceFitMapper.map(
            frameWidth = frame.width,
            frameHeight = frame.height,
            referenceSize = STANDARD_REFERENCE,
            referenceRect = viewportReference,
        ) ?: return emptyList()
        val cardSize = mappedLength(frame, referenceCardSize).coerceAtLeast(MIN_CARD_PIXELS)
        val columnPitch = mappedLength(frame, referenceColumnPitch).coerceAtLeast(cardSize + 2)
        val rowPitch = mappedLength(frame, referenceRowPitch).coerceAtLeast(cardSize + 2)

        val columnBands = detectColumnBands(frame, viewport, cardSize)
        val columns = regularizeBands(
            bands = columnBands,
            expectedSize = cardSize,
            expectedPitch = columnPitch,
            maximumCount = maxColumns,
            lowerBound = viewport.left,
            upperBoundExclusive = viewport.left + viewport.width,
        )
        if (columns.size < MIN_GRID_COLUMNS) return emptyList()

        val hintedRows = rowTopHints
            .distinct()
            .sorted()
            .map { top -> Band(top, top + cardSize) }
            .filter { row -> rowHasCard(frame, viewport, columns, row, cardSize) }
        val detectedRows = regularizeRows(
            frame = frame,
            viewport = viewport,
            columns = columns,
            bands = detectRowBands(
                frame,
                viewport,
                rowSupportColumns(columnBands, columns, cardSize),
                cardSize,
            ),
            cardSize = cardSize,
            rowPitch = rowPitch,
        )
        // Hints describe known multi-row end stops; they are not observations. With an attribute
        // filter a single real card row can overlap two hinted squares, which used to duplicate the
        // row and stretch recognition into blank space. Prefer pixel-measured rows whenever any are
        // available. Hints remain the last-resort path for frames whose clipped artwork is too weak
        // for the row projection.
        val rows = detectedRows.ifEmpty { hintedRows }
        if (rows.isEmpty()) return emptyList()

        val snappedColumns = columns.map { band ->
            snapColumn(frame, viewport, rows, band, cardSize)
        }
        val snappedRows = rows.map { row ->
            snapRow(frame, viewport, snappedColumns, row, cardSize)
        }

        return snappedRows.flatMapIndexed { rowIndex, row ->
            snappedColumns.mapIndexedNotNull { columnIndex, column ->
                val fullRect = EntryPixelRect(column.start, row.start, column.length, row.length)
                val visibleRect = intersect(fullRect, viewport) ?: return@mapIndexedNotNull null
                if (cardPresence(frame, visibleRect) < CARD_PRESENCE_MIN_SCORE) {
                    return@mapIndexedNotNull null
                }
                LabyrinthDetectedGridSlot(
                    rowIndex = rowIndex,
                    columnIndex = columnIndex,
                    fullRect = fullRect,
                    clippedAtTop = fullRect.top < viewport.top,
                    clippedAtBottom = fullRect.top + fullRect.height > viewport.top + viewport.height,
                )
            }
        }
    }

    private fun detectColumnBands(
        frame: PixelImage,
        viewport: EntryPixelRect,
        cardSize: Int,
    ): List<Band> {
        val values = DoubleArray(viewport.width)
        val window = min(cardSize, viewport.height)
        for (offsetX in values.indices) {
            val x = viewport.left + offsetX
            val prefix = IntArray(viewport.height + 1)
            for (offsetY in 0 until viewport.height) {
                prefix[offsetY + 1] = prefix[offsetY] + if (
                    isCardContent(frame[x, viewport.top + offsetY])
                ) {
                    1
                } else {
                    0
                }
            }
            var best = 0
            for (start in 0..viewport.height - window) {
                best = max(best, prefix[start + window] - prefix[start])
            }
            values[offsetX] = best.toDouble() / window
        }
        val smoothed = movingAverage(values, max(1, cardSize / PROJECTION_SMOOTHING_DIVISOR))
        return extractBands(
            values = smoothed,
            origin = viewport.left,
            expectedSize = cardSize,
            minimumLengthRatio = COLUMN_MIN_LENGTH_RATIO,
        )
    }

    /**
     * The columns allowed to vote on where the rows are.
     *
     * [regularizeBands] extrapolates a full grid from however few column bands were actually
     * measured, and the final card geometry needs that grid. Those predicted columns are not
     * observations, though, so letting them carry row evidence allows unrelated chrome inside the
     * viewport to masquerade as roster content. The battle editor's 用角色名搜索 box spans several
     * predicted columns, and the row projection scores each row by its two strongest columns: on a
     * roster filtered down to a single card the box alone lifted the projection peak to 1.0, which
     * put the threshold above the one real card's 0.48 and left the detector with no rows at all
     * (2026-09-19 live: 有效效果 listed one card, nothing resolved, and the scan rewound the list
     * every 17 seconds for the rest of the session).
     *
     * Falling back to the regularized grid keeps the previous behaviour whenever no measured band
     * survives the size filter.
     */
    private fun rowSupportColumns(
        measured: List<Band>,
        regularized: List<Band>,
        cardSize: Int,
    ): List<Band> = measured
        .filter { band ->
            band.length in (cardSize * BAND_MIN_SIZE_RATIO).roundToInt()..
                (cardSize * BAND_MAX_SIZE_RATIO).roundToInt()
        }
        .ifEmpty { regularized }

    private fun detectRowBands(
        frame: PixelImage,
        viewport: EntryPixelRect,
        columns: List<Band>,
        cardSize: Int,
    ): List<Band> {
        val values = DoubleArray(viewport.height)
        val sampleStep = max(1, cardSize / ROW_SAMPLE_DIVISOR)
        for (offsetY in values.indices) {
            val y = viewport.top + offsetY
            val columnScores = columns.map { column ->
                var content = 0
                var samples = 0
                var x = column.start + sampleStep
                val end = column.endExclusive - sampleStep
                while (x < end) {
                    if (isCardContent(frame[x, y])) content++
                    samples++
                    x += sampleStep
                }
                if (samples == 0) 0.0 else content.toDouble() / samples
            }.sortedDescending()
            values[offsetY] = columnScores.take(ROW_SUPPORT_COLUMNS).averageOrZero()
        }
        val smoothed = movingAverage(values, max(1, cardSize / PROJECTION_SMOOTHING_DIVISOR))
        return extractBands(
            values = smoothed,
            origin = viewport.top,
            expectedSize = cardSize,
            minimumLengthRatio = ROW_MIN_LENGTH_RATIO,
        )
    }

    private fun regularizeBands(
        bands: List<Band>,
        expectedSize: Int,
        expectedPitch: Int,
        maximumCount: Int,
        lowerBound: Int,
        upperBoundExclusive: Int,
    ): List<Band> {
        val usable = bands.filter { band ->
            band.length in (expectedSize * BAND_MIN_SIZE_RATIO).roundToInt()..
                (expectedSize * BAND_MAX_SIZE_RATIO).roundToInt()
        }
        if (usable.isEmpty()) return emptyList()

        val pitchSamples = usable.zipWithNext().mapNotNull { (left, right) ->
            (right.center - left.center).takeIf { distance ->
                distance in (expectedPitch * PITCH_MIN_RATIO).roundToInt()..
                    (expectedPitch * PITCH_MAX_RATIO).roundToInt()
            }
        }
        val pitch = pitchSamples.medianOrNull() ?: expectedPitch
        val firstCenter = usable.first().center
        val centers = buildList {
            // Extrapolate leftward first when an edge/dimmed column 1 band was missed,
            // bounded by maximumCount, before extrapolating rightward.
            val leftCenters = mutableListOf<Int>()
            var left = firstCenter - pitch
            while (leftCenters.size < maximumCount - usable.size && left - expectedSize / 2 >= lowerBound) {
                leftCenters.add(left)
                left -= pitch
            }
            leftCenters.asReversed().forEach(::add)
            var center = firstCenter
            while (size < maximumCount && center - expectedSize / 2 < upperBoundExclusive) {
                if (center + expectedSize / 2 > lowerBound) add(center)
                center += pitch
            }
        }
        return centers.map { predictedCenter ->
            val detected = usable.minByOrNull { band -> abs(band.center - predictedCenter) }
                ?.takeIf { band -> abs(band.center - predictedCenter) <= pitch * CENTER_SNAP_RATIO }
            val center = detected?.center ?: predictedCenter
            Band(center - expectedSize / 2, center - expectedSize / 2 + expectedSize)
        }.filter { band -> band.start >= lowerBound && band.endExclusive <= upperBoundExclusive }
    }

    private fun regularizeRows(
        frame: PixelImage,
        viewport: EntryPixelRect,
        columns: List<Band>,
        bands: List<Band>,
        cardSize: Int,
        rowPitch: Int,
    ): List<Band> {
        val usable = bands.filter { band ->
            band.length in (cardSize * ROW_MIN_LENGTH_RATIO).roundToInt()..
                (cardSize * BAND_MAX_SIZE_RATIO).roundToInt()
        }
        if (usable.isEmpty()) return emptyList()

        val projectedRows = usable.map { band ->
            val touchesTop = band.start <= viewport.top + cardSize * CLIPPED_EDGE_MARGIN_RATIO
            val touchesBottom = band.endExclusive >=
                viewport.top + viewport.height - cardSize * CLIPPED_EDGE_MARGIN_RATIO
            val start = when {
                touchesTop && band.length < cardSize * FULL_CARD_MIN_RATIO -> band.endExclusive - cardSize
                touchesBottom && band.length < cardSize * FULL_CARD_MIN_RATIO -> band.start
                else -> band.center - cardSize / 2
            }
            Band(start, start + cardSize)
        }.sortedBy(Band::start)

        val overlapTolerance = max(
            2,
            (cardSize * ROW_OVERLAP_TOLERANCE_RATIO).roundToInt(),
        )
        val rows = mutableListOf<Band>()
        projectedRows.forEach { projected ->
            val previous = rows.lastOrNull()
            if (previous == null || !rowsOverlapBeyondTolerance(previous, projected, overlapTolerance)) {
                rows += projected
                return@forEach
            }

            // A complete detected row may never substantially overlap the preceding complete row.
            // If projection supplied such a row, recover once from the known grid pitch rather
            // than accepting the overlapping geometry or dropping a real sparse tail outright.
            val recovered = Band(previous.start + rowPitch, previous.start + rowPitch + cardSize)
            if (
                !rowsOverlapBeyondTolerance(previous, recovered, overlapTolerance) &&
                rowHasCard(frame, viewport, columns, recovered, cardSize)
            ) {
                rows += recovered
            }
        }

        // A clipped tail can have too little artwork for the projection threshold. Extend the
        // regular grid only when the predicted row still contains at least one real card.
        fun maybeAdd(candidateStart: Int) {
            val candidate = Band(candidateStart, candidateStart + cardSize)
            val visible = intersectVertical(candidate, viewport) ?: return
            if (visible.length < cardSize * MIN_VISIBLE_CARD_RATIO) return
            val hasCard = columns.any { column ->
                cardPresence(
                    frame,
                    EntryPixelRect(column.start, visible.start, column.length, visible.length),
                ) >= CARD_PRESENCE_MIN_SCORE
            }
            val overlapsExisting = rows.any { existing ->
                rowsOverlapBeyondTolerance(existing, candidate, overlapTolerance)
            }
            if (hasCard && !overlapsExisting) {
                rows += candidate
            }
        }
        rows.firstOrNull()?.let { first -> maybeAdd(first.start - rowPitch) }
        rows.lastOrNull()?.let { last -> maybeAdd(last.start + rowPitch) }
        return rows.sortedBy(Band::start)
    }

    private fun rowsOverlapBeyondTolerance(left: Band, right: Band, tolerance: Int): Boolean {
        val overlap = min(left.endExclusive, right.endExclusive) - max(left.start, right.start)
        return overlap > tolerance
    }

    private fun rowHasCard(
        frame: PixelImage,
        viewport: EntryPixelRect,
        columns: List<Band>,
        row: Band,
        cardSize: Int,
    ): Boolean {
        val visible = intersectVertical(row, viewport) ?: return false
        if (visible.length < cardSize * MIN_VISIBLE_CARD_RATIO) return false
        return columns.any { column ->
            cardPresence(
                frame,
                EntryPixelRect(column.start, visible.start, column.length, visible.length),
            ) >= CARD_PRESENCE_MIN_SCORE
        }
    }

    private fun snapColumn(
        frame: PixelImage,
        viewport: EntryPixelRect,
        rows: List<Band>,
        approximate: Band,
        cardSize: Int,
    ): Band {
        val radius = max(2, (cardSize * COLUMN_EDGE_SEARCH_RADIUS_RATIO).roundToInt())
        val transitionLeft = strongestCoordinate(approximate.start, radius) { candidateLeft ->
            verticalLeadingBoundaryScore(frame, candidateLeft, viewport, rows)
        }
        val left = transitionLeft + INSIDE_EDGE_OFFSET
        return Band(left, left + cardSize)
    }

    private fun snapRow(
        frame: PixelImage,
        viewport: EntryPixelRect,
        columns: List<Band>,
        approximate: Band,
        cardSize: Int,
    ): Band {
        val viewportBottom = viewport.top + viewport.height
        val clippedTop = approximate.start < viewport.top
        val clippedBottom = approximate.endExclusive > viewportBottom
        val radius = max(2, (cardSize * EDGE_SEARCH_RADIUS_RATIO).roundToInt())
        val top = when {
            clippedTop -> {
                val transitionBottom = strongestCoordinate(
                    approximate.endExclusive,
                    radius,
                ) { candidateBottom ->
                    horizontalTrailingBoundaryScore(frame, candidateBottom, viewport, columns) +
                        rowAlignmentScore(
                            frame = frame,
                            viewport = viewport,
                            columns = columns,
                            row = Band(candidateBottom - cardSize, candidateBottom),
                        ) * ROW_ALIGNMENT_WEIGHT
                }
                val bottom = transitionBottom + OUTSIDE_EDGE_OFFSET
                bottom - cardSize
            }

            clippedBottom -> strongestCoordinate(approximate.start, radius) { candidateTop ->
                horizontalLeadingBoundaryScore(frame, candidateTop, viewport, columns) +
                    rowAlignmentScore(
                        frame = frame,
                        viewport = viewport,
                        columns = columns,
                        row = Band(candidateTop, candidateTop + cardSize),
                    ) * ROW_ALIGNMENT_WEIGHT
            } + INSIDE_EDGE_OFFSET

            else -> strongestCoordinate(approximate.start, radius) { candidateTop ->
                horizontalLeadingBoundaryScore(frame, candidateTop, viewport, columns) +
                    rowAlignmentScore(
                        frame = frame,
                        viewport = viewport,
                        columns = columns,
                        row = Band(candidateTop, candidateTop + cardSize),
                    ) * ROW_ALIGNMENT_WEIGHT
            } + INSIDE_EDGE_OFFSET
        }
        return Band(top, top + cardSize)
    }

    private fun rowAlignmentScore(
        frame: PixelImage,
        viewport: EntryPixelRect,
        columns: List<Band>,
        row: Band,
    ): Double {
        val visible = intersectVertical(row, viewport) ?: return 0.0
        return columns.maxOfOrNull { column ->
            verticalCardShapeSupport(
                frame,
                EntryPixelRect(column.start, visible.start, column.length, visible.length),
            )
        } ?: 0.0
    }

    /**
     * Measures whether a candidate slot contains one vertically continuous card-shaped region.
     * A few colourful rows from the preceding card (rank/star chrome, etc.) must not be able to
     * support a complete second row merely because generic cardPresence is non-zero.
     */
    private fun verticalCardShapeSupport(frame: PixelImage, rect: EntryPixelRect): Double {
        val top = rect.top + PRESENCE_INSET
        val bottom = rect.top + rect.height - PRESENCE_INSET
        val left = rect.left + PRESENCE_INSET
        val right = rect.left + rect.width - PRESENCE_INSET
        if (bottom <= top || right <= left) return 0.0

        var longestRun = 0
        var currentRun = 0
        var sampledRows = 0
        var y = top
        while (y < bottom) {
            var content = 0
            var samples = 0
            var x = left
            while (x < right) {
                if (isCardContent(frame[x, y])) content++
                samples++
                x += PRESENCE_SAMPLE_STEP
            }
            val rowContentRatio = if (samples == 0) 0.0 else content.toDouble() / samples
            if (rowContentRatio >= ROW_SHAPE_MIN_CONTENT_RATIO) {
                currentRun++
                longestRun = max(longestRun, currentRun)
            } else {
                currentRun = 0
            }
            sampledRows++
            y += ROW_SHAPE_SAMPLE_STEP
        }
        return if (sampledRows == 0) 0.0 else longestRun.toDouble() / sampledRows
    }

    private fun verticalLeadingBoundaryScore(
        frame: PixelImage,
        x: Int,
        viewport: EntryPixelRect,
        rows: List<Band>,
    ): Double {
        if (x < OUTSIDE_EDGE_OFFSET || x + INSIDE_EDGE_OFFSET >= frame.width) return 0.0
        return rows.map { row ->
            val visible = intersectVertical(row, viewport) ?: return@map 0.0
            var total = 0.0
            var samples = 0
            var y = visible.start + CORNER_SKIP_PIXELS
            while (y < visible.endExclusive - CORNER_SKIP_PIXELS) {
                total += leadingBoundaryEvidence(
                    outside = frame[x - OUTSIDE_EDGE_OFFSET, y],
                    inside = frame[x + INSIDE_EDGE_OFFSET, y],
                    rewardWarmFrame = false,
                )
                samples++
                y += EDGE_SAMPLE_STEP
            }
            if (samples == 0) 0.0 else total / samples
        }.sortedDescending().take(EDGE_SUPPORT_ROWS).averageOrZero()
    }

    private fun horizontalLeadingBoundaryScore(
        frame: PixelImage,
        y: Int,
        viewport: EntryPixelRect,
        columns: List<Band>,
    ): Double {
        if (y < OUTSIDE_EDGE_OFFSET || y + INSIDE_EDGE_OFFSET >= frame.height) return 0.0
        return columns.map { column ->
            val left = max(column.start + CORNER_SKIP_PIXELS, viewport.left)
            val right = min(column.endExclusive - CORNER_SKIP_PIXELS, viewport.left + viewport.width)
            var total = 0.0
            var samples = 0
            var x = left
            while (x < right) {
                total += leadingBoundaryEvidence(
                    outside = frame[x, y - OUTSIDE_EDGE_OFFSET],
                    inside = frame[x, y + INSIDE_EDGE_OFFSET],
                    rewardWarmFrame = true,
                )
                samples++
                x += EDGE_SAMPLE_STEP
            }
            if (samples == 0) 0.0 else total / samples
        }.sortedDescending().take(EDGE_SUPPORT_COLUMNS).averageOrZero()
    }

    private fun horizontalTrailingBoundaryScore(
        frame: PixelImage,
        y: Int,
        viewport: EntryPixelRect,
        columns: List<Band>,
    ): Double {
        if (y < INSIDE_EDGE_OFFSET || y + OUTSIDE_EDGE_OFFSET >= frame.height) return 0.0
        return columns.map { column ->
            val left = max(column.start + CORNER_SKIP_PIXELS, viewport.left)
            val right = min(column.endExclusive - CORNER_SKIP_PIXELS, viewport.left + viewport.width)
            var total = 0.0
            var samples = 0
            var x = left
            while (x < right) {
                total += leadingBoundaryEvidence(
                    outside = frame[x, y + OUTSIDE_EDGE_OFFSET],
                    inside = frame[x, y - INSIDE_EDGE_OFFSET],
                    rewardWarmFrame = true,
                )
                samples++
                x += EDGE_SAMPLE_STEP
            }
            if (samples == 0) 0.0 else total / samples
        }.sortedDescending().take(EDGE_SUPPORT_COLUMNS).averageOrZero()
    }

    private fun leadingBoundaryEvidence(
        outside: Int,
        inside: Int,
        rewardWarmFrame: Boolean,
    ): Double {
        val transition = colorDistance(outside, inside) / MAX_COLOR_DISTANCE
        val structure = if (!isCardContent(outside) && isCardContent(inside)) 1.0 else 0.0
        val cardFrame = if (
            rewardWarmFrame &&
            !isCardContent(outside) &&
            isWarmCardFrame(inside)
        ) {
            1.0
        } else {
            0.0
        }
        return structure * STRUCTURE_SCORE_WEIGHT +
            transition * TRANSITION_SCORE_WEIGHT +
            cardFrame * CARD_FRAME_SCORE_WEIGHT
    }

    private fun isWarmCardFrame(color: Int): Boolean {
        val red = color ushr 16 and 0xff
        val green = color ushr 8 and 0xff
        val blue = color and 0xff
        return red >= WARM_FRAME_MIN_RED &&
            red - green >= WARM_FRAME_MIN_RED_GREEN_DELTA &&
            red - blue >= WARM_FRAME_MIN_RED_BLUE_DELTA
    }

    private fun strongestCoordinate(
        expected: Int,
        radius: Int,
        score: (Int) -> Double,
    ): Int {
        val candidates = (expected - radius..expected + radius).map { coordinate ->
            coordinate to score(coordinate)
        }
        val best = candidates.maxByOrNull { (_, value) -> value } ?: return expected
        return best.first.takeIf { best.second >= MIN_BOUNDARY_SCORE } ?: expected
    }

    private fun extractBands(
        values: DoubleArray,
        origin: Int,
        expectedSize: Int,
        minimumLengthRatio: Double,
    ): List<Band> {
        if (values.isEmpty()) return emptyList()
        val sorted = values.sorted()
        val baseline = sorted[(sorted.lastIndex * BASELINE_PERCENTILE).roundToInt()]
        val peak = sorted[(sorted.lastIndex * PEAK_PERCENTILE).roundToInt()]
        if (peak - baseline < MIN_PROJECTION_CONTRAST) return emptyList()
        val threshold = baseline + (peak - baseline) * PROJECTION_THRESHOLD_RATIO
        val active = BooleanArray(values.size) { index -> values[index] >= threshold }
        closeSmallGaps(active, max(1, (expectedSize * MAX_INTERNAL_GAP_RATIO).roundToInt()))

        val minimumLength = (expectedSize * minimumLengthRatio).roundToInt()
        val bands = mutableListOf<Band>()
        var index = 0
        while (index < active.size) {
            while (index < active.size && !active[index]) index++
            val start = index
            while (index < active.size && active[index]) index++
            if (index - start >= minimumLength) bands += Band(origin + start, origin + index)
        }
        return bands
    }

    private fun closeSmallGaps(active: BooleanArray, maximumGap: Int) {
        var index = 0
        while (index < active.size) {
            while (index < active.size && active[index]) index++
            val gapStart = index
            while (index < active.size && !active[index]) index++
            val bounded = gapStart > 0 && index < active.size
            if (bounded && index - gapStart <= maximumGap) {
                for (fill in gapStart until index) active[fill] = true
            }
        }
    }

    private fun movingAverage(values: DoubleArray, radius: Int): DoubleArray {
        if (radius <= 0) return values
        val prefix = DoubleArray(values.size + 1)
        values.indices.forEach { index -> prefix[index + 1] = prefix[index] + values[index] }
        return DoubleArray(values.size) { index ->
            val start = max(0, index - radius)
            val end = min(values.size, index + radius + 1)
            (prefix[end] - prefix[start]) / (end - start)
        }
    }

    private fun isCardContent(color: Int): Boolean {
        val red = color ushr 16 and 0xff
        val green = color ushr 8 and 0xff
        val blue = color and 0xff
        val maximum = maxOf(red, green, blue)
        val minimum = minOf(red, green, blue)
        val average = (red + green + blue) / 3
        return maximum - minimum >= CONTENT_MIN_CHROMA || average <= CONTENT_MAX_NEUTRAL_VALUE
    }

    private fun cardPresence(frame: PixelImage, rect: EntryPixelRect): Double {
        var content = 0
        var samples = 0
        var y = rect.top + PRESENCE_INSET
        while (y < rect.top + rect.height - PRESENCE_INSET) {
            var x = rect.left + PRESENCE_INSET
            while (x < rect.left + rect.width - PRESENCE_INSET) {
                if (isCardContent(frame[x, y])) content++
                samples++
                x += PRESENCE_SAMPLE_STEP
            }
            y += PRESENCE_SAMPLE_STEP
        }
        return if (samples == 0) 0.0 else content.toDouble() / samples
    }

    private fun intersect(rect: EntryPixelRect, viewport: EntryPixelRect): EntryPixelRect? {
        val left = max(rect.left, viewport.left)
        val top = max(rect.top, viewport.top)
        val right = min(rect.left + rect.width, viewport.left + viewport.width)
        val bottom = min(rect.top + rect.height, viewport.top + viewport.height)
        if (right <= left || bottom <= top) return null
        return EntryPixelRect(left, top, right - left, bottom - top)
    }

    private fun intersectVertical(band: Band, viewport: EntryPixelRect): Band? {
        val start = max(band.start, viewport.top)
        val end = min(band.endExclusive, viewport.top + viewport.height)
        return Band(start, end).takeIf { it.length > 0 }
    }

    private fun mappedLength(frame: PixelImage, referenceLength: Int): Int =
        ReferenceFitMapper.map(
            frameWidth = frame.width,
            frameHeight = frame.height,
            referenceSize = STANDARD_REFERENCE,
            referenceRect = EntryReferenceRect(0, 0, referenceLength, referenceLength),
        )?.width ?: referenceLength

    private fun colorDistance(left: Int, right: Int): Double =
        abs((left ushr 16 and 0xff) - (right ushr 16 and 0xff)).toDouble() +
            abs((left ushr 8 and 0xff) - (right ushr 8 and 0xff)) +
            abs((left and 0xff) - (right and 0xff))

    private fun List<Double>.averageOrZero(): Double = if (isEmpty()) 0.0 else average()

    private fun List<Int>.medianOrNull(): Int? =
        sorted().let { values -> values.getOrNull(values.size / 2) }

    private data class Band(val start: Int, val endExclusive: Int) {
        val length: Int get() = endExclusive - start
        val center: Int get() = start + length / 2

        fun toDiagnostic(): LabyrinthGridBandDiagnostic =
            LabyrinthGridBandDiagnostic(start = start, endExclusive = endExclusive)
    }

    private companion object {
        val STANDARD_REFERENCE = EntryReferenceSize(1920, 1080)
        const val MIN_CARD_PIXELS = 64
        const val MIN_GRID_COLUMNS = 2
        const val PROJECTION_SMOOTHING_DIVISOR = 80
        const val ROW_SAMPLE_DIVISOR = 48
        const val ROW_SUPPORT_COLUMNS = 2
        const val EDGE_SUPPORT_ROWS = 2
        const val EDGE_SUPPORT_COLUMNS = 2
        const val BASELINE_PERCENTILE = 0.10
        const val PEAK_PERCENTILE = 0.90
        const val PROJECTION_THRESHOLD_RATIO = 0.45
        const val MIN_PROJECTION_CONTRAST = 0.06
        const val MAX_INTERNAL_GAP_RATIO = 0.035
        const val COLUMN_MIN_LENGTH_RATIO = 0.48
        const val ROW_MIN_LENGTH_RATIO = 0.48
        const val BAND_MIN_SIZE_RATIO = 0.48
        const val BAND_MAX_SIZE_RATIO = 1.25
        const val PITCH_MIN_RATIO = 0.82
        const val PITCH_MAX_RATIO = 1.18
        const val CENTER_SNAP_RATIO = 0.08
        const val CLIPPED_EDGE_MARGIN_RATIO = 0.08
        const val FULL_CARD_MIN_RATIO = 0.88
        const val MIN_VISIBLE_CARD_RATIO = 0.68
        const val COLUMN_EDGE_SEARCH_RADIUS_RATIO = 0.10
        const val EDGE_SEARCH_RADIUS_RATIO = 0.30
        const val OUTSIDE_EDGE_OFFSET = 1
        const val INSIDE_EDGE_OFFSET = 2
        const val EDGE_SAMPLE_STEP = 3
        const val CORNER_SKIP_PIXELS = 10
        const val MAX_COLOR_DISTANCE = 765.0
        const val STRUCTURE_SCORE_WEIGHT = 0.75
        const val TRANSITION_SCORE_WEIGHT = 0.25
        const val CARD_FRAME_SCORE_WEIGHT = 1.25
        const val MIN_BOUNDARY_SCORE = 0.05
        const val ROW_ALIGNMENT_WEIGHT = 2.0
        const val ROW_SHAPE_MIN_CONTENT_RATIO = 0.35
        const val ROW_SHAPE_SAMPLE_STEP = 3
        const val ROW_OVERLAP_TOLERANCE_RATIO = 0.015
        const val WARM_FRAME_MIN_RED = 90
        const val WARM_FRAME_MIN_RED_GREEN_DELTA = 10
        const val WARM_FRAME_MIN_RED_BLUE_DELTA = 8
        const val CONTENT_MIN_CHROMA = 18
        const val CONTENT_MAX_NEUTRAL_VALUE = 224
        const val CARD_PRESENCE_MIN_SCORE = 0.055
        const val PRESENCE_INSET = 8
        const val PRESENCE_SAMPLE_STEP = 6
    }
}
