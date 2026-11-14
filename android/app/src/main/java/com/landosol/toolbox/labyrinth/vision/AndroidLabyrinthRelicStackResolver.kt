package com.landosol.toolbox.labyrinth.vision

import android.graphics.Bitmap

/** Choice-card current totals are calibration only; never block frame processing. */
class AndroidLabyrinthRelicStackResolver(
    submitTextRead: (Bitmap, (String?) -> Unit) -> Unit = AndroidChineseRoleNameOcr::readAsync,
) {
    private val ocr = AndroidRelicRoiOcr(submitTextRead, maximumAttempts = 3)
    private var evidenceVersion = Long.MIN_VALUE
    private var identities: List<String?> = emptyList()

    @Synchronized fun resolve(bitmap: Bitmap, result: LabyrinthEntryFrameResult,
        capturedAtMillis: Long = Long.MIN_VALUE, version: Long = 0): LabyrinthEntryFrameResult {
        if (evidenceVersion != version) { ocr.clear(); evidenceVersion = version }
        if (result.observation.state != LabyrinthEntryPageState.RELIC_CHOICE) {
            ocr.clear()
            identities = emptyList()
            return result
        }
        val selection = result.relicChoiceSelection ?: return result
        val currentIds = selection.choices.map { it.relicId }
        if (identities != currentIds) { ocr.clear(); identities = currentIds }
        val choices = selection.choices.map { choice ->
            if (!choice.recognized) return@map choice
            val rect = STACK_RECTS[choice.slotId]?.let {
                ReferenceFitMapper.map(bitmap.width, bitmap.height, STANDARD_REFERENCE, it)
            } ?: return@map choice
            val read = ocr.read(bitmap, choice.slotId, rect, capturedAtMillis = capturedAtMillis) ?: return@map choice
            choice.copy(currentMarkStacks = read.text?.let(::parseCurrentStacks),
                currentMarkStacksEvidenceText = read.text,
                currentMarkStacksEvidenceId = read.id,
                currentMarkStacksConfirmations = read.confirmations,
                currentMarkStacksCapturedAtMillis = read.capturedAtMillis)
        }
        return result.copy(relicChoiceSelection = selection.copy(choices = choices))
    }

    internal fun parseCurrentStacks(text: String): Int? {
        val compact = text.replace("\n", "").replace("\r", "")
        if ('+' in compact || '-' in compact || '%' in compact) return null
        val explicit = CURRENT_PATTERN.find(compact)?.groupValues?.get(1)?.toIntOrNull()
        if (explicit != null) return explicit.takeIf { it in 0..30 }
        return NUMBER_PATTERN.findAll(compact).map { it.value.toIntOrNull() }
            .toList().singleOrNull()?.takeIf { it in 0..30 }
    }

    private companion object {
        val STANDARD_REFERENCE = EntryReferenceSize(1920, 1080)
        val STACK_RECTS = mapOf(
            "relic_choice_1" to EntryReferenceRect(395, 630, 240, 95),
            "relic_choice_2" to EntryReferenceRect(950, 630, 240, 95),
            "relic_choice_3" to EntryReferenceRect(1505, 630, 240, 95),
        )
        val CURRENT_PATTERN = Regex("当前[^0-9]{0,5}([0-9]+)")
        val NUMBER_PATTERN = Regex("[0-9]+")
    }
}
