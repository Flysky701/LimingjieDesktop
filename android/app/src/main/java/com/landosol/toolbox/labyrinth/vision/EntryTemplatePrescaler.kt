package com.landosol.toolbox.labyrinth.vision

import com.landosol.toolbox.clanbattle.recognition.PixelImage

/**
 * Entry-anchor templates pre-scaled for one concrete frame size.
 *
 * [frameWidth] and [frameHeight] are the dimensions the table was built for. The processor only
 * uses the table when the frame it is about to score has exactly this size; anything else falls
 * back to the plain template plus the runtime mapping, so a stale table can never be scored
 * against a frame it was not built for.
 */
data class PreScaledEntryTemplates(
    val frameWidth: Int,
    val frameHeight: Int,
    val templates: Map<EntryAnchorDefinition, PixelImage>,
)

/**
 * Shrinks every entry-anchor template to the pixel rect its own definition maps to.
 *
 * Why this exists: [GradientTemplateMatcher] maps template coordinates onto the target rect with
 * `value * (target - 1) / (source - 1)`. On a 1280x720 capture a 130x58 template has to fit an
 * 87x39 rect, so the mapping is not the identity: roughly every second template column truncates
 * onto the same frame column (the intermediate columns are never sampled), and every sample lands
 * with a systematic sub-pixel offset. Pre-scaling makes `template size == rect size`, which turns
 * that mapping into the identity and removes the whole class of error.
 *
 * The table is only built for captures that are not 1920x1080. At the baseline size the templates
 * were cut to match their rects already, and staying on the original path is what keeps every
 * existing score bit-identical.
 *
 * Cost: one pass over the template set, once per session start, before the first frame arrives.
 * Nothing is scaled per frame, and the processor does a map lookup afterwards.
 */
object EntryTemplatePrescaler {
    const val BASELINE_WIDTH = 1920
    const val BASELINE_HEIGHT = 1080

    /**
     * @return null when the original templates should be used unchanged (baseline size).
     */
    fun build(
        templates: LabyrinthEntryTemplateSet,
        frameWidth: Int,
        frameHeight: Int,
        definitions: List<EntryAnchorDefinition> = LabyrinthEntryFrameProcessor.DEFINITIONS,
    ): PreScaledEntryTemplates? {
        require(frameWidth > 0 && frameHeight > 0) { "frame size must be positive" }
        if (frameWidth == BASELINE_WIDTH && frameHeight == BASELINE_HEIGHT) return null

        val scaled = LinkedHashMap<EntryAnchorDefinition, PixelImage>()
        for (definition in definitions) {
            val template = templates.values[definition.id] ?: continue
            val rect = when (definition.mapping) {
                EntryReferenceMapping.COVER -> ReferenceCoverMapper.map(
                    frameWidth = frameWidth,
                    frameHeight = frameHeight,
                    referenceSize = definition.referenceSize,
                    referenceRect = definition.referenceRect,
                )
                EntryReferenceMapping.FIT -> ReferenceFitMapper.map(
                    frameWidth = frameWidth,
                    frameHeight = frameHeight,
                    referenceSize = definition.referenceSize,
                    referenceRect = definition.referenceRect,
                )
            } ?: continue
            // Downscale only. An upscaled copy would carry the holes a box filter leaves behind,
            // and a template that already fits its rect needs no copy at all.
            if (rect.width > template.width || rect.height > template.height) continue
            if (rect.width == template.width && rect.height == template.height) continue
            scaled[definition] = PixelImageScaler.scale(template, rect.width, rect.height)
        }
        return PreScaledEntryTemplates(frameWidth, frameHeight, scaled)
    }
}
