package com.landosol.toolbox.labyrinth.node.batch4baseline

import com.landosol.toolbox.labyrinth.node.*
import com.landosol.toolbox.labyrinth.vision.EntryPixelRect
import com.landosol.toolbox.labyrinth.vision.GradientTemplateMatcher
import com.landosol.toolbox.clanbattle.recognition.PixelImage
import kotlin.math.roundToInt

/** Frozen batch3 classifier before batch4 allocation changes.
 * Shared unchanged DTOs, color helpers, NodeTemplateSet and GradientTemplateMatcher deliberately
 * remain production dependencies: this oracle isolates only the two changed allocation sites.
 * Source SHA256: 15b0b8bde9ae9ad949c0eb326e7d1beee1fc09f7c7be5fa2dcd7bf28e0e41d13
 */
private enum class NodeSearchMode(val label: String) {
    DIRECTED("directed"),
    TYPED("typed"),
    FULL("full"),
}

class Batch4NodeBaselineReference(
    private val matcher: GradientTemplateMatcher = GradientTemplateMatcher(maxSamples = 2_400),
    /**
     * A matcher whose prepared frame features may be borrowed for the frame being scanned. The
     * entry frame processor prepares each frame once for its page anchors; when it has done so
     * for this exact frame, the scan shares that map instead of computing an identical one.
     * Template sampling density is per matcher and is not shared.
     */
    private val sharedFrameFeatures: GradientTemplateMatcher? = null,
) {
    private val proposalMatcher = GradientTemplateMatcher(maxSamples = 240)
    private val refinementMatcher = GradientTemplateMatcher(maxSamples = 700)
    private data class ViewportSignature(val width: Int, val height: Int, val colors: IntArray)
    private var previousFrame: ViewportSignature? = null
    private var previousTemplates: NodeTemplateSet? = null
    private var previousNodes: List<NodeClassification> = emptyList()
    private var trackedFrames = 0
    private data class ScanMemo(
        val signature: ViewportSignature,
        val mode: NodeSearchMode,
        val hint: NodeSearchHint?,
        val templates: NodeTemplateSet,
        val result: List<NodeClassification>,
    )
    private var scanMemo: ScanMemo? = null
    private var scanMemoFrames = 0
    private var hintedTargetBlockId: Long? = null
    private var directedMissFrames: Int = 0
    private var typedMissFrames: Int = 0
    private var fullMissFrames: Int = 0
    var lastSearchMode: String = "full"
        private set
    /** Candidate windows scored by [classifyNode] during the most recent [classifyMapNodes]. */
    var lastSearchWindowCount: Int = 0
        private set

    fun resetTracking() {
        previousFrame = null
        previousTemplates = null
        previousNodes = emptyList()
        trackedFrames = 0
        hintedTargetBlockId = null
        directedMissFrames = 0
        typedMissFrames = 0
        fullMissFrames = 0
        matcher.clearPreparedFrame()
        proposalMatcher.clearPreparedFrame()
        refinementMatcher.clearPreparedFrame()
    }
    /**
     * 识别指定区域的节点类型
     */
    fun classifyNode(
        frame: PixelImage,
        iconRect: EntryPixelRect,
        templates: NodeTemplateSet,
    ): NodeClassification? {
        if (iconRect.width < 10 || iconRect.height < 10) return null

        val scores = templates.gradientTemplates.mapValues { (_, template) ->
            matcher.score(
                frame = frame,
                rect = iconRect,
                template = template,
                minimumChannelScore = MIN_CHANNEL_SCORE,
            )
        }

        val cyanGlowEvidence = activeCyanGlowEvidence(frame, iconRect)
        val cyanGlowScore = cyanGlowEvidence.score
        val purpleGlowEvidence = activePurpleGlowEvidence(frame, iconRect)
        val purpleGlowScore = purpleGlowEvidence.score
        val hasPurpleActivation = labyrinthNodePurpleActivation(
            score = purpleGlowScore,
            sideScore = purpleGlowEvidence.sideScore,
            lowerScore = purpleGlowEvidence.lowerScore,
            rowCoverage = purpleGlowEvidence.rowCoverage,
        )
        val hasActiveGlow = cyanGlowScore >= ACTIVE_GLOW_MIN_RATIO || hasPurpleActivation
        val colorCandidates = buildSet {
            scores.entries
                .asSequence()
                .filter { it.value >= MIN_CONFIDENCE }
                .sortedByDescending { it.value }
                .take(COLOR_GATE_TOP_TEMPLATE_COUNT)
                .forEach { add(it.key) }
            scores
                .filterKeys { templateId ->
                    blockTypeForTemplate(templateId)?.let { type ->
                        isNodeTemplateClickable(templateId, type)
                    } == true
                }
                .maxByOrNull { it.value }
                ?.takeIf { it.value >= MIN_CONFIDENCE }
                ?.let { add(it.key) }
        }
        val colorScores = colorCandidates.associateWith { templateId ->
            stableNodeColorRoiScore(
                frame = frame,
                rect = iconRect,
                template = requireNotNull(templates.templates[templateId]),
            )
        }
        val gatedScores = scores.filter { (templateId, gradientScore) ->
            if (templateId !in colorCandidates) return@filter false
            val blockType = blockTypeForTemplate(templateId) ?: return@filter false
            val activeCapable = isNodeTemplateClickable(templateId, blockType)
            val minimumGradientScore = when {
                blockType == LabyrinthNodeTypes.BOSS -> MIN_BOSS_GRADIENT_CONFIDENCE
                activeCapable -> MIN_CONFIDENCE
                else -> MIN_INACTIVE_GRADIENT_CONFIDENCE
            }
            if (gradientScore < minimumGradientScore) return@filter false
            val colorScore = colorScores[templateId]
            val minimumColorScore = if (activeCapable) {
                MIN_ACTIVE_COLOR_ROI_SCORE
            } else {
                MIN_INACTIVE_COLOR_ROI_SCORE
            }
            colorScore == null || colorScore >= minimumColorScore
        }
        val strongestStructuralActive = gatedScores
            .filterKeys { templateId ->
                blockTypeForTemplate(templateId)?.let { blockType ->
                    isNodeTemplateClickable(templateId, blockType)
                } == true
            }
            .maxByOrNull { it.value }
        val activeColorEvidence = activeNodeColorEvidence(frame, iconRect)
        val activeTypeEvidence = activeColorEvidence?.let(::inferActiveNodeType)
        if (hasActiveGlow && strongestStructuralActive != null) {
            val activeTypeTemplates = activeTypeEvidence?.let { evidence ->
                templates.templates.filterKeys { templateId ->
                    blockTypeForTemplate(templateId) == evidence.blockType &&
                        isNodeTemplateClickable(templateId, evidence.blockType)
                }
            }.orEmpty()
            if (activeTypeEvidence != null && activeTypeTemplates.isNotEmpty()) {
                val bestTemplate = activeTypeTemplates.keys.maxByOrNull { templateId ->
                    scores[templateId] ?: 0.0
                } ?: return null
                val ownScore = scores[bestTemplate] ?: 0.0
                val ownColorScore = stableNodeColorRoiScore(
                    frame, iconRect, requireNotNull(templates.templates[bestTemplate]),
                ) ?: 0.0
                // A colour override must have structural support from its own class. A road
                // beside a shop cannot borrow a battle template's score to become a LINK.
                if (ownScore < 0.28 || ownColorScore < MIN_ACTIVE_COLOR_ROI_SCORE) return null
                return NodeClassification(
                    column = 0,
                    row = 0,
                    blockType = activeTypeEvidence.blockType,
                    // Geometry stays ranked by the proven structural match. Color evidence may
                    // correct semantics but must never let a glow-leaking neighbor win NMS.
                    confidence = strongestStructuralActive.value,
                    typeConfidence = minOf(ownScore, ownColorScore, activeTypeEvidence.confidence),
                    isClickable = true,
                    screenRect = iconRect,
                    templateId = bestTemplate,
                    cyanGlowScore = cyanGlowScore,
                    cyanGlowRowCoverage = cyanGlowEvidence.rowCoverage,
                    purpleGlowScore = purpleGlowScore,
                    purpleGlowRowCoverage = purpleGlowEvidence.rowCoverage,
                    purpleGlowSideScore = purpleGlowEvidence.sideScore,
                    purpleGlowLowerScore = purpleGlowEvidence.lowerScore,
                    colorRoiScore = stableNodeColorRoiScore(
                        frame = frame,
                        rect = iconRect,
                        template = requireNotNull(templates.templates[bestTemplate]),
                    ) ?: 0.0,
                )
            }
        }
        // Stacked reachable battles partially cover each other, so their 280x350 templates can
        // fall just below the broad structural threshold even though the small icon/platform ROI
        // is unambiguous. Keep this fallback deliberately limited to NORMAL, purple EX, yellow
        // EVENT and an exceptionally strict blue-cube RELIC signature. Cyan scenery is otherwise
        // too easy to confuse with RELIC/LINK when a search crop is shifted.
        if (
            strongestStructuralActive == null &&
            activeColorEvidence != null &&
            activeTypeEvidence != null &&
            activeTypeEvidence.blockType in SEMANTIC_FALLBACK_NODE_TYPES
        ) {
            val fallbackTemplates = templates.templates.filterKeys { templateId ->
                blockTypeForTemplate(templateId) == activeTypeEvidence.blockType &&
                    isNodeTemplateClickable(templateId, activeTypeEvidence.blockType)
            }
            val fallback = fallbackTemplates.mapNotNull { (templateId, template) ->
                val gradientScore = scores[templateId] ?: return@mapNotNull null
                val colorScore = stableNodeColorRoiScore(frame, iconRect, template)
                    ?: return@mapNotNull null
                SemanticFallbackMatch(templateId, gradientScore, colorScore)
            }.maxByOrNull { match ->
                match.gradientScore * SEMANTIC_FALLBACK_GRADIENT_WEIGHT +
                    match.colorScore * SEMANTIC_FALLBACK_COLOR_WEIGHT
            }
            val fallbackAccepted = fallback?.let { match ->
                when (activeTypeEvidence.blockType) {
                    LabyrinthNodeTypes.NORMAL_BATTLE ->
                        cyanGlowScore >= NORMAL_FALLBACK_CYAN_GLOW_MIN_RATIO &&
                            activeColorEvidence.darkRatio >= NORMAL_FALLBACK_DARK_MIN_RATIO &&
                            activeColorEvidence.redRatio >= NORMAL_FALLBACK_RED_MIN_RATIO &&
                            match.gradientScore >= NORMAL_FALLBACK_GRADIENT_MIN_SCORE &&
                            match.colorScore >= NORMAL_FALLBACK_COLOR_MIN_SCORE

                    LabyrinthNodeTypes.EX_BATTLE ->
                        hasPurpleActivation &&
                            purpleGlowScore >= EX_FALLBACK_PURPLE_GLOW_MIN_RATIO &&
                            activeColorEvidence.lowerMagentaRatio >= EX_FALLBACK_LOWER_MAGENTA_MIN_RATIO &&
                            activeColorEvidence.magentaRatio >= EX_FALLBACK_MAGENTA_MIN_RATIO &&
                            activeColorEvidence.redRatio >= EX_FALLBACK_RED_MIN_RATIO &&
                            match.gradientScore >= EX_FALLBACK_GRADIENT_MIN_SCORE &&
                            match.colorScore >= EX_FALLBACK_COLOR_MIN_SCORE

                    LabyrinthNodeTypes.EVENT ->
                        cyanGlowScore >= EVENT_FALLBACK_CYAN_GLOW_MIN_RATIO &&
                            cyanGlowEvidence.rowCoverage >= EVENT_FALLBACK_CYAN_ROW_MIN_COVERAGE &&
                            activeColorEvidence.yellowRatio >= EVENT_FALLBACK_YELLOW_MIN_RATIO &&
                            match.gradientScore >= EVENT_FALLBACK_GRADIENT_MIN_SCORE &&
                            match.colorScore >= EVENT_FALLBACK_COLOR_MIN_SCORE

                    LabyrinthNodeTypes.RELIC ->
                        cyanGlowScore >= RELIC_FALLBACK_CYAN_GLOW_MIN_RATIO &&
                            cyanGlowEvidence.rowCoverage >= RELIC_FALLBACK_CYAN_ROW_MIN_COVERAGE &&
                            activeColorEvidence.cyanRatio >= RELIC_FALLBACK_ICON_CYAN_MIN_RATIO &&
                            activeColorEvidence.darkRatio <= RELIC_FALLBACK_ICON_DARK_MAX_RATIO &&
                            match.gradientScore >= RELIC_FALLBACK_GRADIENT_MIN_SCORE &&
                            match.colorScore >= RELIC_FALLBACK_COLOR_MIN_SCORE

                    else -> false
                }
            } == true
            if (fallbackAccepted && fallback != null) {
                return NodeClassification(
                    column = 0,
                    row = 0,
                    blockType = activeTypeEvidence.blockType,
                    confidence = (
                        fallback.gradientScore * SEMANTIC_FALLBACK_GRADIENT_WEIGHT +
                            fallback.colorScore * SEMANTIC_FALLBACK_COLOR_WEIGHT +
                            if (activeTypeEvidence.blockType == LabyrinthNodeTypes.EVENT) {
                                activeColorEvidence.yellowRatio * EVENT_FALLBACK_YELLOW_CONFIDENCE_WEIGHT
                            } else {
                                0.0
                            }
                        ).coerceAtLeast(MIN_CONFIDENCE),
                    isClickable = true,
                    screenRect = iconRect,
                    templateId = fallback.templateId,
                    cyanGlowScore = cyanGlowScore,
                    cyanGlowRowCoverage = cyanGlowEvidence.rowCoverage,
                    purpleGlowScore = purpleGlowScore,
                    purpleGlowRowCoverage = purpleGlowEvidence.rowCoverage,
                    purpleGlowSideScore = purpleGlowEvidence.sideScore,
                    purpleGlowLowerScore = purpleGlowEvidence.lowerScore,
                    colorRoiScore = fallback.colorScore,
                )
            }
        }
        val strongest = gatedScores.maxByOrNull { it.value } ?: return null
        val strongestActive = gatedScores
            .filterKeys { templateId ->
                blockTypeForTemplate(templateId)?.let { type ->
                    isNodeTemplateClickable(templateId, type)
                } == true
            }
            .maxByOrNull { it.value }
        val strongestNonExplicitActive = gatedScores
            .filterKeys { templateId -> !isExplicitActiveNodeTemplate(templateId) }
            .maxByOrNull { it.value }
        // Animated backgrounds can make an inactive crop score a few thousandths higher than
        // the active crop of the same node. Prefer the active interpretation only for close ties
        // and only when the crop contains the activation frame. A nominally active match without
        // glow is scenery; if available, retain a strict inactive interpretation for topology.
        val best = if (hasActiveGlow) {
            strongestActive
                ?.takeIf { active ->
                    hasPurpleActivation || strongest.value - active.value <= ACTIVE_TEMPLATE_TIE_MARGIN
                }
                ?: strongest
        } else {
            strongest.takeUnless { isExplicitActiveNodeTemplate(it.key) }
                ?: strongestNonExplicitActive
                ?: return null
        }
        if (best.value < MIN_CONFIDENCE) return null

        val blockType = blockTypeForTemplate(best.key) ?: return null
        if (hasActiveGlow && blockType == LabyrinthNodeTypes.LINK &&
            activeTypeEvidence?.blockType != LabyrinthNodeTypes.LINK
        ) return null
        val templateAllowsClick = isNodeTemplateClickable(best.key, blockType)
        // EX nodes use a magenta/purple active frame. The large node template can still be
        // misclassified as EVENT/LINK because it contains changing character art, so strong
        // local purple evidence is allowed to bypass the inactive-template gate. The route
        // topology and destination-page confirmation remain the safety checks after this point.
        return NodeClassification(
            column = 0, // 由调用方设置
            row = 0,    // 由调用方设置
            blockType = blockType,
            confidence = best.value,
            // Require both an active-capable template and local cyan glow. The template gate
            // prevents a neighboring node's glow from making an inactive crop clickable; the
            // glow gate prevents animated background fragments from becoming active templates.
            // A strong purple frame is an explicit exception for EX because it is the active
            // visual state of that node, not the cyan state used by regular nodes. It may also
            // preserve an EVENT/LINK visual misclassification as a clickable topology candidate.
            isClickable = (templateAllowsClick || hasPurpleActivation) && (
                hasActiveGlow || blockType == LabyrinthNodeTypes.BOSS
                ),
            screenRect = iconRect,
            templateId = best.key,
            cyanGlowScore = cyanGlowScore,
            cyanGlowRowCoverage = cyanGlowEvidence.rowCoverage,
            purpleGlowScore = purpleGlowScore,
            purpleGlowRowCoverage = purpleGlowEvidence.rowCoverage,
            purpleGlowSideScore = purpleGlowEvidence.sideScore,
            purpleGlowLowerScore = purpleGlowEvidence.lowerScore,
            colorRoiScore = colorScores[best.key] ?: 1.0,
        )
    }

    /**
     * 批量识别多个节点
     */
    fun classifyNodes(
        frame: PixelImage,
        iconDefs: List<NodeIconDef>,
        templates: NodeTemplateSet,
    ): List<NodeClassification> {
        return iconDefs.mapNotNull { def ->
            val rect = NodeAnchorDefinitions.getNodeIconRect(def, frame.width, frame.height)
                ?: return@mapNotNull null
            classifyNode(frame, rect, templates)?.copy(column = def.column, row = def.row)
        }
    }

    private fun updateHintMissState(hint: NodeSearchHint?, mode: NodeSearchMode) {
        if (hint == null) return
        // Only the next frame's protocol-bound feedback can prove target acquisition.
        // Nearby visual proposals, including tracked ones, cannot clear target misses.
        when (mode) {
            NodeSearchMode.DIRECTED -> directedMissFrames++
            NodeSearchMode.TYPED -> typedMissFrames++
            NodeSearchMode.FULL -> fullMissFrames++
        }
    }

    /**
     * 在地图候选锚点附近寻找节点。地图纵向会滚动，不能只在一个固定矩形上匹配；
     * 这里保留列锚点，再对每个候选做小范围偏移搜索，并用中心距离做非极大值抑制。
     */
    fun classifyMapNodes(
        frame: PixelImage,
        templates: NodeTemplateSet,
        searchHint: NodeSearchHint? = null,
    ): List<NodeClassification> {
        if (templates.templates.isEmpty()) return emptyList()
        if (searchHint?.targetBlockId != hintedTargetBlockId) {
            hintedTargetBlockId = searchHint?.targetBlockId
            directedMissFrames = 0
            typedMissFrames = 0
            fullMissFrames = 0
        }
        val targetConfirmed = searchHint != null && searchHint.matchedTargetBlockId == searchHint.targetBlockId
        if (targetConfirmed) {
            directedMissFrames = 0
            typedMissFrames = 0
            fullMissFrames = 0
        }
        // The ladder climbs once. It used to reset back to directed after two full scans, which
        // turned a target it could not acquire into an endless directed/typed/full cycle costing
        // ~25 s a lap (2026-09-20 bundle 212345: one 普通战斗#10402 hop looped three times for
        // 99.8 s, 39% of the whole run's node budget, and was finally acquired by an ordinary
        // scan once the frame changed). Re-climbing buys nothing: the scan is a pure function of
        // (frame, templates, hint), so a rung that missed on this frame will miss again on the
        // next identical frame no matter how many times the ladder walks past it.
        //
        // The escape from a stuck full scan is therefore not a cheaper rung, it is not scanning
        // at all — see the stable-frame memo below, which caps a stalled ladder at one real scan
        // per MAX_SCAN_MEMO_FRAMES instead of one per frame.
        val searchMode = when {
            searchHint == null -> NodeSearchMode.FULL
            // No camera prediction yet (area entry, tracker not fitted): still restrict the
            // template set to the route's neighbourhood so the first, most expensive frame of a
            // viewport is not a full 16-anchor by all-template scan.
            searchHint.expectedCenterX == null ->
                if (typedMissFrames < TYPED_MISS_FRAMES_BEFORE_FULL) NodeSearchMode.TYPED else NodeSearchMode.FULL
            directedMissFrames < DIRECTED_MISS_FRAMES_BEFORE_EXPAND ->
                NodeSearchMode.DIRECTED
            typedMissFrames < TYPED_MISS_FRAMES_BEFORE_FULL -> NodeSearchMode.TYPED
            else -> NodeSearchMode.FULL
        }
        val previous = previousFrame
        val trackedNodesStillCoverHint = searchHint == null || targetConfirmed
        if (previous != null && previousTemplates === templates && previousNodes.isNotEmpty() &&
            trackedNodesStillCoverHint &&
            trackedFrames < 5 && isStableViewport(previous, frame)
        ) {
            val refreshed = previousNodes.mapNotNull { node ->
                val rect = node.screenRect ?: return@mapNotNull null
                classifyNode(frame, rect, templates)?.copy(column = node.column, row = node.row)
            }
            lastSearchWindowCount = refreshed.size
            if (refreshed.size == previousNodes.size && refreshed.zip(previousNodes).all { (now, old) ->
                    now.blockType == old.blockType && now.isClickable == old.isClickable
                }) {
                lastSearchMode = "tracked"
                trackedFrames++
                previousNodes = refreshed
                return refreshed
            }
        }
        // Nothing about this scan depends on wall-clock time: same frame, same templates, same
        // hint and same rung means the same detections. During a stall the map is static and the
        // run pays for an identical answer every frame. Serve it from the last real scan instead,
        // and let any real change in the viewport — including the activation glow's own pulse,
        // which is what usually ends such a stall — invalidate it.
        val memo = scanMemo
        if (
            memo != null &&
            memo.mode == searchMode &&
            memo.hint == searchHint &&
            memo.templates === templates &&
            scanMemoFrames < MAX_SCAN_MEMO_FRAMES &&
            isStableViewport(memo.signature, frame)
        ) {
            scanMemoFrames++
            lastSearchMode = searchMode.label
            lastSearchWindowCount = 0
            updateHintMissState(searchHint, searchMode)
            return memo.result
        }
        scanMemoFrames = 0
        lastSearchMode = searchMode.label
        trackedFrames = 0
        var scoredWindows = 0
        // Reuse the frame features the entry frame processor already prepared for this frame
        // instead of paying for a second identical full-frame pass.
        val borrowed = sharedFrameFeatures?.takeIf { it.hasPreparedFrame(frame) }
        try {
            if (borrowed != null) matcher.sharePreparedFrame(borrowed) else matcher.prepareFrame(frame)
            proposalMatcher.sharePreparedFrame(matcher)
            refinementMatcher.sharePreparedFrame(matcher)
            val expectedTypes = searchHint?.expectedTypes.orEmpty()
            val regularTemplates = templates.filterKeys { templateId ->
                isRegularNodeTemplate(templateId) &&
                    (searchMode == NodeSearchMode.FULL || expectedTypes.isEmpty() ||
                        blockTypeForTemplate(templateId) in expectedTypes)
            }
            // Directed search keeps every anchor at its reference position and keeps the full
            // offset sweep, so a prediction that is off by a node width still reaches the target.
            // The saving comes from dropping the windows that land far from the prediction: the
            // remaining budget concentrates on the predicted column and its two neighbours.
            // Only DIRECTED aims at the route prediction; every other mode scans the full width.
            val restrictedCenterX = searchHint?.expectedCenterX
                ?.takeIf { searchMode == NodeSearchMode.DIRECTED }
            val restrictedToleranceX =
                labyrinthReferenceColumnPitch(frame.height) * DIRECTED_CENTER_PITCH_TOLERANCE
            val offsets = NodeAnchorDefinitions.searchOffsets(
                frameWidth = frame.width,
                frameHeight = frame.height,
            )
            // Anchors overlap by design: each one sweeps +/-320 reference pixels, which is more
            // than the distance to its neighbour, so the same rectangle is proposed from several
            // anchors. Each of those repeats used to pay for a full 2400-sample classification.
            // The result depends only on (frame, rect, templates), all fixed for this pass, so one
            // memo per frame removes the repeats without changing a single decision.
            val classified = HashMap<EntryPixelRect, Optional>(1024)
            fun classifyOnce(rect: EntryPixelRect): NodeClassification? =
                classified.getOrPut(rect) { Optional(classifyNode(frame, rect, regularTemplates)) }.value
            val detections = NodeAnchorDefinitions.NODE_ICON_RECTS.flatMap { definition ->
                val rectangles = offsets.asSequence()
                    .mapNotNull { (offsetX, offsetY) ->
                        val rect = NodeAnchorDefinitions.getNodeIconRectAtOffset(
                            def = definition,
                            frameWidth = frame.width,
                            frameHeight = frame.height,
                            offsetX = offsetX,
                            offsetY = offsetY,
                        )
                        if (rect == null || !isInsideRegularNodeRecognitionRegion(rect, frame.width)) {
                            null
                        } else if (
                            restrictedCenterX != null &&
                            kotlin.math.abs(rect.left + rect.width / 2 - restrictedCenterX) > restrictedToleranceX
                        ) {
                            null
                        } else {
                            rect
                        }
                    }
                    .toList()
                // Locate the platform/label at low sample density before expensive whole-node
                // classification. Keep alternatives per type so a single common silhouette cannot
                // crowd out a rarer icon. Proposals never directly authorize a click.
                // Proposal counts are per template, so with a broad type hint they, not the
                // rectangle pool, dominate the window budget. A directed search has already
                // concentrated the rectangles on the predicted column, so far fewer alternatives
                // per template are needed to keep the target among the proposals.
                val platformProposalsPerTemplate = when (searchMode) {
                    NodeSearchMode.DIRECTED -> DIRECTED_PLATFORM_PROPOSALS_PER_TEMPLATE
                    else -> 8
                }
                val silhouetteProposalsPerTemplate = when (searchMode) {
                    NodeSearchMode.DIRECTED -> DIRECTED_SILHOUETTE_PROPOSALS_PER_TEMPLATE
                    else -> 3
                }
                val platformProposals = regularTemplates.platformTemplates.values
                    .flatMap { template ->
                        rectangles.topRectsByScore(platformProposalsPerTemplate) { rect ->
                            proposalMatcher.score(frame, rect, template)
                        }
                    }.distinct()
                val silhouetteProposals = regularTemplates.gradientTemplates.values.flatMap { template ->
                    rectangles.topRectsByScore(silhouetteProposalsPerTemplate) { rect ->
                        proposalMatcher.score(frame, rect, template)
                    }
                }
                val proposals = (platformProposals + silhouetteProposals).distinct()
                val refined = proposals.flatMap { rect ->
                    val possibleType = activeNodeColorEvidence(frame, rect)?.let(::inferActiveNodeType)?.blockType
                    val variants = regularTemplates.gradientTemplates.entries.map {
                        it to proposalMatcher.score(frame, rect, it.value)
                    }.sortedByDescending { it.second }.map { it.first }
                    // The colour-inferred alternative guards against a common silhouette winning
                    // the gradient vote. Directed search already restricts templates to the route
                    // neighbourhood, so the best variant alone is enough there.
                    val colourAlternative = if (searchMode == NodeSearchMode.DIRECTED) {
                        emptyList()
                    } else {
                        variants.firstOrNull { blockTypeForTemplate(it.key) == possibleType }?.let(::listOf).orEmpty()
                    }
                    val candidates = (variants.take(1) + colourAlternative).distinctBy { it.key }
                    candidates.map { (_, template) ->
                        val step = maxOf(2, (7.0 * frame.height / 1080).roundToInt())
                        val local = (-2..2).flatMap { dy ->
                            (-2..2).mapNotNull { dx -> rect.offsetInside(dx * step, dy * step, frame.width, frame.height) }
                        }
                        val best = local.maxByOrNull { refinementMatcher.score(frame, it, template) } ?: rect
                        val fine = maxOf(1, step / 2)
                        (-1..1).flatMap { dy -> (-1..1).mapNotNull { dx ->
                            best.offsetInside(dx * fine, dy * fine, frame.width, frame.height)
                        } }.maxByOrNull { refinementMatcher.score(frame, it, template) } ?: best
                    }
                }
                val candidateWindows = (proposals + refined).distinct()
                scoredWindows += candidateWindows.count { it !in classified }
                val candidateDetections = candidateWindows.mapNotNull(::classifyOnce)
                selectSpatiallyDistinct(candidateDetections, MAX_DETECTIONS_PER_SEARCH_ANCHOR)
                    .map { it.copy(column = definition.column, row = definition.row) }
            }
            val specialDetections = NodeAnchorDefinitions.visibleSpecialNodeRects(
                frame.width,
                frame.height,
            ).flatMap { candidate ->
                val specialTemplates = templates.filterKeys { templateId ->
                    when (candidate.id) {
                        "boss" -> templateId == "node.boss.yellow" ||
                            templateId == "node.boss.dragon"
                        "boss.platform" -> templateId == "node.boss.platform"
                        "clear" -> templateId == "node.clear"
                        else -> false
                    }
                }
                if (specialTemplates.templates.isEmpty()) return@flatMap emptyList()
                NodeAnchorDefinitions.specialSearchOffsets(candidate, frame.width, frame.height)
                    .asSequence()
                    .mapNotNull { (offsetX, offsetY) ->
                        val rect = candidate.rect.offsetInside(offsetX, offsetY, frame.width, frame.height)
                            ?: return@mapNotNull null
                        scoredWindows++
                        classifyNode(frame, rect, specialTemplates)
                    }
                    .maxByOrNull(NodeClassification::confidence)
                    ?.copy(column = candidate.column, row = 1)
                    ?.let(::listOf)
                    .orEmpty()
            }
            val spatialDetections = suppressOverlaps(detections + specialDetections)
            val result = assignVisualRows(
                rejectWeakIsolatedFarLeftDetections(spatialDetections, frame.width),
            )
            updateHintMissState(searchHint, searchMode)
            val signature = viewportSignature(frame)
            previousFrame = signature
            previousTemplates = templates
            previousNodes = result
            scanMemo = ScanMemo(signature, searchMode, searchHint, templates, result)
            lastSearchWindowCount = scoredWindows
            return result
        } finally {
            matcher.clearPreparedFrame()
            proposalMatcher.clearPreparedFrame()
            refinementMatcher.clearPreparedFrame()
        }
    }

    // Retain only the pixels used by the stability check, not an entire 1080p capture (~8 MiB).
    private fun viewportSignature(frame: PixelImage): ViewportSignature {
        val step = maxOf(8, frame.height / 45)
        val colors = ArrayList<Int>()
        for (y in frame.height / 7 until frame.height * 6 / 7 step step) {
            for (x in frame.width / 5 until frame.width * 19 / 20 step step) {
                colors.add(frame[x, y])
            }
        }
        return ViewportSignature(frame.width, frame.height, colors.toIntArray())
    }

    /** Pixel-only viewport identity; remains available even when no node can be classified. */
    fun viewportSignatureKey(frame: PixelImage): String {
        val signature = viewportSignature(frame)
        return buildString {
            append(signature.width).append('x').append(signature.height).append(':')
            append(signature.colors.contentHashCode().toUInt().toString(16))
        }
    }

    /** Compare against the full-scan frame, not the previous frame: slow scrolling accumulates. */
    private fun isStableViewport(before: ViewportSignature, now: PixelImage): Boolean {
        if (before.width != now.width || before.height != now.height) return false
        var changed = 0
        var samples = 0
        val step = maxOf(8, now.height / 45)
        for (y in now.height / 7 until now.height * 6 / 7 step step) {
            for (x in now.width / 5 until now.width * 19 / 20 step step) {
                val a = before.colors[samples]
                val b = now[x, y]
                val delta = kotlin.math.abs((a ushr 16 and 255) - (b ushr 16 and 255)) +
                    kotlin.math.abs((a ushr 8 and 255) - (b ushr 8 and 255)) +
                    kotlin.math.abs((a and 255) - (b and 255))
                if (delta > 90) changed++
                samples++
            }
        }
        return samples > 0 && changed.toDouble() / samples < 0.06
    }

    /**
     * Lower scan anchors can overlap the current node, CLEAR marker and the floating control at
     * the far left. A real three-row destination column has vertical peers; a genuine isolated
     * destination still has a strong structural/glow match. Drop only isolated weak candidates,
     * leaving one-, two- and three-node columns available to the topology mapper.
     */
    private fun rejectWeakIsolatedFarLeftDetections(
        detections: List<NodeClassification>,
        frameWidth: Int,
    ): List<NodeClassification> = detections.filter { candidate ->
        val rect = candidate.screenRect ?: return@filter false
        val centerX = rect.left + rect.width / 2
        if (centerX >= frameWidth * FAR_LEFT_CENTER_X_RATIO) return@filter true
        val hasVerticalPeer = detections.any { other ->
            if (other === candidate) return@any false
            val otherRect = other.screenRect ?: return@any false
            val otherCenterX = otherRect.left + otherRect.width / 2
            val otherCenterY = otherRect.top + otherRect.height / 2
            val centerY = rect.top + rect.height / 2
            kotlin.math.abs(centerX - otherCenterX) <= rect.width * FAR_LEFT_PEER_X_RATIO &&
                kotlin.math.abs(centerY - otherCenterY) >= rect.height * FAR_LEFT_PEER_MIN_Y_RATIO
        }
        hasVerticalPeer || (
            candidate.isClickable &&
                candidate.confidence >= FAR_LEFT_ISOLATED_MIN_CONFIDENCE &&
                maxOf(candidate.cyanGlowScore, candidate.purpleGlowScore) >=
                FAR_LEFT_ISOLATED_MIN_GLOW_SCORE
            )
    }

    /** Candidate anchors are search windows; the visual row is the final
     * top-to-bottom order after offsets and overlap suppression. */
    private fun assignVisualRows(
        detections: List<NodeClassification>,
    ): List<NodeClassification> = detections
        .groupBy(NodeClassification::column)
        .flatMap { (column, columnDetections) ->
            columnDetections
                .sortedBy { it.screenRect?.top ?: Int.MAX_VALUE }
                .mapIndexed { index, detection ->
                    detection.copy(column = column, row = index + 1)
                }
        }
        .sortedWith(compareBy(NodeClassification::column, NodeClassification::row))

    private fun suppressOverlaps(
        detections: List<NodeClassification>,
    ): List<NodeClassification> = selectSpatiallyDistinct(detections).sortedWith(
        compareBy(NodeClassification::column, NodeClassification::row),
    )

    private fun selectSpatiallyDistinct(
        detections: List<NodeClassification>,
        limit: Int = Int.MAX_VALUE,
    ): List<NodeClassification> {
        val kept = mutableListOf<NodeClassification>()
        // Search offsets deliberately overlap. For one real node a locally glowing crop is more
        // trustworthy than a higher-scoring inactive crop from a neighboring offset; sorting
        // by confidence alone was the cause of taps landing to the right of link nodes. Keep an
        // active-template crop ahead of an inactive crop for diagnostics, but only a glowing
        // crop can enter the route action mapping.
        detections.sortedWith(
            compareByDescending<NodeClassification> { it.isClickable }
                .thenByDescending {
                    it.templateId?.let(::isExplicitActiveNodeTemplate) == true
                }
                // A shifted crop can include only the lower part of a neighboring glowing node
                // and still score well as LINK. Prefer the crop whose cyan rails span more rows;
                // it is better aligned to the physical node and yields a safer base coordinate.
                // Keep coarse confidence first so a weak background crop cannot beat a materially
                // stronger node merely because the background contains many cyan rows.
                .thenByDescending { (it.confidence * 10.0).roundToInt() }
                .thenByDescending(NodeClassification::cyanGlowRowCoverage)
                .thenByDescending { it.confidence },
        ).forEach { candidate ->
            if (kept.size >= limit) return@forEach
            val candidateRect = candidate.screenRect ?: return@forEach
            val candidateCenterX = candidateRect.left + candidateRect.width / 2
            val candidateCenterY = candidateRect.top + candidateRect.height / 2
            val overlaps = kept.any { accepted ->
                val acceptedRect = accepted.screenRect ?: return@any false
                val acceptedCenterX = acceptedRect.left + acceptedRect.width / 2
                val acceptedCenterY = acceptedRect.top + acceptedRect.height / 2
                kotlin.math.abs(candidateCenterX - acceptedCenterX) < candidateRect.width * 1.10 &&
                    // Adjacent map rows are about 300 reference pixels apart, while a shifted
                    // search crop is 350 pixels high. A 0.75-height gate incorrectly suppresses
                    // the middle row when its low-confidence crop starts just below the top row.
                    // Keep same-node offset duplicates suppressed, but allow adjacent rows to
                    // survive for route-aware mapping.
                    kotlin.math.abs(candidateCenterY - acceptedCenterY) < candidateRect.height * 0.55
            }
            if (!overlaps) kept += candidate
        }
        return kept
    }

    /** Reachable regular nodes have two long cyan rails plus a cyan lower platform. */
    private fun activeCyanGlowEvidence(frame: PixelImage, rect: EntryPixelRect): CyanGlowEvidence {
        var cyan = 0
        var sampled = 0
        var cyanRows = 0
        var sampledRows = 0
        var y = rect.top
        while (y < rect.top + rect.height) {
            var rowCyan = 0
            var rowSamples = 0
            var x = rect.left
            while (x < rect.left + rect.width) {
                val color = frame[x, y]
                val red = color shr 16 and 0xff
                val green = color shr 8 and 0xff
                val blue = color and 0xff
                if (blue >= 180 && green >= 150 && blue - red >= 55 && green - red >= 25) {
                    cyan++
                    rowCyan++
                }
                sampled++
                rowSamples++
                x += ACTIVE_GLOW_SAMPLE_STEP
            }
            if (rowSamples > 0) {
                if (rowCyan.toDouble() / rowSamples >= CYAN_GLOW_ROW_MIN_RATIO) cyanRows++
                sampledRows++
            }
            y += ACTIVE_GLOW_SAMPLE_STEP
        }
        if (sampled == 0 || sampledRows == 0) return CyanGlowEvidence(0.0, 0.0)
        val pixelRatio = cyan.toDouble() / sampled
        val rowCoverage = cyanRows.toDouble() / sampledRows
        val continuityWeight = (rowCoverage / CYAN_GLOW_FULL_COVERAGE_RATIO).coerceIn(0.0, 1.0)
        return CyanGlowEvidence(
            score = pixelRatio * continuityWeight,
            rowCoverage = rowCoverage,
        )
    }

    /**
     * EX uses a *bright* magenta activation frame instead of the cyan frame used by regular nodes.
     * The area-3 background and even an inactive EX contain broad, dark purple regions, so a raw
     * purple-pixel ratio is not reachability evidence. A reachable EX has all three pieces of the
     * frame at once: continuous upper side rails and the bright lower platform/label band.
     */
    private fun activePurpleGlowEvidence(frame: PixelImage, rect: EntryPixelRect): PurpleGlowEvidence {
        val sideBand = maxOf(12, rect.width * PURPLE_GLOW_SIDE_BAND_PERCENT / 100)
        val lowerBandStart = rect.top + rect.height * PURPLE_GLOW_LOWER_BAND_PERCENT / 100
        var activationPurple = 0
        var activationSamples = 0
        var upperSidePurple = 0
        var upperSideSamples = 0
        var lowerPurple = 0
        var lowerSamples = 0
        var purpleSideRows = 0
        var sampledSideRows = 0
        var y = rect.top
        while (y < rect.top + rect.height) {
            var rowSidePurple = 0
            var rowSideSamples = 0
            var x = rect.left
            while (x < rect.left + rect.width) {
                val localX = x - rect.left
                val inSideBand = localX < sideBand || localX >= rect.width - sideBand
                val inLowerBand = y >= lowerBandStart
                val inActivationBand = inSideBand || inLowerBand
                if (inActivationBand) {
                    val color = frame[x, y]
                    val red = color ushr 16 and 0xff
                    val green = color ushr 8 and 0xff
                    val blue = color and 0xff
                    val isBrightPurple =
                        red >= PURPLE_MIN_RED &&
                            blue >= PURPLE_MIN_BLUE &&
                            red - green >= PURPLE_MIN_RED_GREEN_DELTA &&
                            blue - green >= PURPLE_MIN_BLUE_GREEN_DELTA
                    if (isBrightPurple) activationPurple++
                    activationSamples++
                    if (inSideBand && !inLowerBand) {
                        if (isBrightPurple) {
                            upperSidePurple++
                            rowSidePurple++
                        }
                        upperSideSamples++
                        rowSideSamples++
                    }
                    if (inLowerBand) {
                        if (isBrightPurple) lowerPurple++
                        lowerSamples++
                    }
                }
                x += ACTIVE_GLOW_SAMPLE_STEP
            }
            if (y < lowerBandStart && rowSideSamples > 0) {
                if (rowSidePurple.toDouble() / rowSideSamples >= PURPLE_GLOW_ROW_MIN_RATIO) {
                    purpleSideRows++
                }
                sampledSideRows++
            }
            y += ACTIVE_GLOW_SAMPLE_STEP
        }
        return PurpleGlowEvidence(
            score = activationPurple.toDouble() / activationSamples.coerceAtLeast(1),
            rowCoverage = purpleSideRows.toDouble() / sampledSideRows.coerceAtLeast(1),
            sideScore = upperSidePurple.toDouble() / upperSideSamples.coerceAtLeast(1),
            lowerScore = lowerPurple.toDouble() / lowerSamples.coerceAtLeast(1),
        )
    }

    companion object {
        // Current map crops retain different animated backgrounds, so luminance correlation is
        // not stable across frames. Route/type/row matching, stable frames and page-transition
        // confirmation provide the action safety gates after this gradient-led detection.
        const val MIN_CONFIDENCE = 0.40
        /**
         * Directed frames allowed before the ladder drops the horizontal restriction.
         *
         * Measured over two live runs, a directed scan acquired the route target on 1 of 53 and
         * 1 of 50 frames — 2% in both — while typed acquired 40-79%. Per acquisition that is ~80 s
         * of directed against ~12 s of typed. One cheap aimed frame is still worth trying when
         * the camera fit is available; a second one just delays the rung that works.
         */
        private const val DIRECTED_MISS_FRAMES_BEFORE_EXPAND = 1
        private const val TYPED_MISS_FRAMES_BEFORE_FULL = 2
        /**
         * Full frames to spend before re-testing the camera-directed scan.
         *
         * Two full frames (~12 s) is long enough that a genuinely off-screen target has been
         * looked for properly, and short enough that a stuck run re-tests the cheap path every
         * few frames instead of never.
         */
        /**
         * How many frames a stalled ladder may serve from the last real scan before re-scanning.
         *
         * The memo is exact while the viewport is unchanged, but [isStableViewport] samples the
         * frame rather than comparing it whole, so a bounded re-scan keeps a change it sampled
         * past from stalling the run forever. At roughly 1.3 frames per second this holds for
         * about nine seconds.
         */
        private const val MAX_SCAN_MEMO_FRAMES = 12
        /**
         * Windows whose centre is farther than this many column pitches from the tracker
         * prediction are dropped. Half a pitch keeps the predicted column plus the inner edge of
         * each neighbour, which absorbs a prediction error of about one node width.
         */
        private const val DIRECTED_CENTER_PITCH_TOLERANCE = 0.55
        /**
         * Proposal budget for the aiming modes, measured on the two node fixtures.
         *
         * 3+1 was tuned against the coarse offset grid. On the gap-free grid it finds the nodes
         * but localizes them badly: on node-target-missed the lower EVENT came back at y=612
         * where every richer mode agrees on 650, and on node-link at (664,188) against (689,176).
         * A crop offset by that much shifts the tap by the same amount, which is exactly the
         * failure that cost bundle 163307 three rejected taps on 连结#20602.
         *
         * 4+2 reproduces the expensive modes' positions exactly on both fixtures (~225 ms vs
         * ~180 ms). 6+2 and a colour alternative were also measured and changed neither the
         * detections nor their positions, so the budget stops here.
         */
        private const val DIRECTED_PLATFORM_PROPOSALS_PER_TEMPLATE = 4
        private const val DIRECTED_SILHOUETTE_PROPOSALS_PER_TEMPLATE = 2
        private const val MIN_CHANNEL_SCORE = 0.0
        private const val MAX_DETECTIONS_PER_SEARCH_ANCHOR = 3
        private const val COLOR_GATE_TOP_TEMPLATE_COUNT = 4
        // Active nodes vary more because their glow and character/reward art animate. Inactive
        // candidates do not need that tolerance and are useful to topology only when both their
        // structure and color are clear; this keeps dark scenery out of the visual columns.
        private const val MIN_ACTIVE_COLOR_ROI_SCORE = 0.75
        private const val MIN_INACTIVE_COLOR_ROI_SCORE = 0.84
        private const val MIN_INACTIVE_GRADIENT_CONFIDENCE = 0.60
        private const val MIN_BOSS_GRADIENT_CONFIDENCE = 0.60
        private const val ACTIVE_TEMPLATE_TIE_MARGIN = 0.06
        private const val ACTIVE_GLOW_MIN_RATIO = 0.12
        private const val ACTIVE_GLOW_SAMPLE_STEP = 6
        private const val CYAN_GLOW_ROW_MIN_RATIO = 0.08
        private const val CYAN_GLOW_FULL_COVERAGE_RATIO = 0.30
        private const val PURPLE_GLOW_SIDE_BAND_PERCENT = 18
        private const val PURPLE_GLOW_LOWER_BAND_PERCENT = 68
        private const val PURPLE_GLOW_ROW_MIN_RATIO = 0.04
        private const val PURPLE_MIN_RED = 220
        private const val PURPLE_MIN_BLUE = 160
        private const val PURPLE_MIN_RED_GREEN_DELTA = 70
        private const val PURPLE_MIN_BLUE_GREEN_DELTA = 30
        private val SEMANTIC_FALLBACK_NODE_TYPES = setOf(
            LabyrinthNodeTypes.NORMAL_BATTLE,
            LabyrinthNodeTypes.EX_BATTLE,
            LabyrinthNodeTypes.EVENT,
            LabyrinthNodeTypes.RELIC,
        )
        private const val SEMANTIC_FALLBACK_GRADIENT_WEIGHT = 0.75
        private const val SEMANTIC_FALLBACK_COLOR_WEIGHT = 0.25
        private const val NORMAL_FALLBACK_CYAN_GLOW_MIN_RATIO = 0.14
        private const val NORMAL_FALLBACK_DARK_MIN_RATIO = 0.15
        private const val NORMAL_FALLBACK_RED_MIN_RATIO = 0.03
        private const val NORMAL_FALLBACK_GRADIENT_MIN_SCORE = 0.34
        private const val NORMAL_FALLBACK_COLOR_MIN_SCORE = 0.75
        private const val EX_FALLBACK_PURPLE_GLOW_MIN_RATIO = 0.08
        private const val EX_FALLBACK_LOWER_MAGENTA_MIN_RATIO = 0.25
        private const val EX_FALLBACK_MAGENTA_MIN_RATIO = 0.15
        private const val EX_FALLBACK_RED_MIN_RATIO = 0.10
        private const val EX_FALLBACK_GRADIENT_MIN_SCORE = 0.20
        private const val EX_FALLBACK_COLOR_MIN_SCORE = 0.62
        private const val EVENT_FALLBACK_CYAN_GLOW_MIN_RATIO = 0.12
        private const val EVENT_FALLBACK_CYAN_ROW_MIN_COVERAGE = 0.30
        private const val EVENT_FALLBACK_YELLOW_MIN_RATIO = 0.20
        private const val EVENT_FALLBACK_GRADIENT_MIN_SCORE = 0.28
        private const val EVENT_FALLBACK_COLOR_MIN_SCORE = 0.76
        private const val EVENT_FALLBACK_YELLOW_CONFIDENCE_WEIGHT = 0.30
        private const val RELIC_FALLBACK_CYAN_GLOW_MIN_RATIO = 0.20
        private const val RELIC_FALLBACK_CYAN_ROW_MIN_COVERAGE = 0.55
        private const val RELIC_FALLBACK_ICON_CYAN_MIN_RATIO = 0.40
        private const val RELIC_FALLBACK_ICON_DARK_MAX_RATIO = 0.05
        private const val RELIC_FALLBACK_GRADIENT_MIN_SCORE = 0.18
        private const val RELIC_FALLBACK_COLOR_MIN_SCORE = 0.86
        private const val FAR_LEFT_CENTER_X_RATIO = 0.31
        private const val FAR_LEFT_PEER_X_RATIO = 0.75
        private const val FAR_LEFT_PEER_MIN_Y_RATIO = 0.45
        private const val FAR_LEFT_ISOLATED_MIN_CONFIDENCE = 0.55
        private const val FAR_LEFT_ISOLATED_MIN_GLOW_SCORE = 0.18
        private fun blockTypeForTemplate(templateId: String): Int? = when {
            templateId.startsWith("node.ex_battle.") -> LabyrinthNodeTypes.EX_BATTLE
            templateId.startsWith("node.normal_battle.") -> LabyrinthNodeTypes.NORMAL_BATTLE
            templateId.startsWith("node.link.") -> LabyrinthNodeTypes.LINK
            templateId.startsWith("node.relic.") -> LabyrinthNodeTypes.RELIC
            templateId.startsWith("node.event.") -> LabyrinthNodeTypes.EVENT
            templateId.startsWith("node.shop.") -> LabyrinthNodeTypes.SHOP
            templateId.startsWith("node.boss.") -> LabyrinthNodeTypes.BOSS
            templateId == "node.clear" -> LabyrinthNodeTypes.CLEAR
            else -> null
        }
    }
}

private data class SemanticFallbackMatch(
    val templateId: String,
    val gradientScore: Double,
    val colorScore: Double,
)

private data class CyanGlowEvidence(
    val score: Double,
    val rowCoverage: Double,
)

private data class PurpleGlowEvidence(
    val score: Double,
    val rowCoverage: Double,
    val sideScore: Double,
    val lowerScore: Double,
)

private class Optional(val value: NodeClassification?)

private fun EntryPixelRect.offsetInside(
    offsetX: Int,
    offsetY: Int,
    frameWidth: Int,
    frameHeight: Int,
): EntryPixelRect? {
    val left = left + offsetX
    val top = top + offsetY
    if (left < 0 || top < 0 || left + width > frameWidth || top + height > frameHeight) return null
    return EntryPixelRect(left, top, width, height)
}
