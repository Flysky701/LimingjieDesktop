package com.landosol.toolbox.desktop.shared

import android.graphics.Bitmap
import com.landosol.toolbox.gamedata.*
import com.landosol.toolbox.labyrinth.kernel.event.UnknownGameEventPolicy
import com.landosol.toolbox.labyrinth.vision.*
import java.io.File
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class EventDomainUnknownSlotOcrTest {
    @Test fun multiChoiceOcrBindsEachCostToItsOwnCurrentButton() {
        val ocr = Json.decodeFromString<LabyrinthEventOcrDocument>(
            File("src/main/assets/resource-packs/cn-bilibili/labyrinth-event-ocr.json").readText())
        val mapper = LabyrinthEventLayoutMapper(ocr.layouts)
        val width = 640
        val height = 360
        val pixels = IntArray(width * height) { -1 }
        fun paint(rect: EntryPixelRect, color: Int) {
            for (y in rect.top until rect.top + rect.height)
                pixels.fill(color, y * width + rect.left, y * width + rect.left + rect.width)
        }
        val bounds = mapper.optionBounds(2, width, height)
        // Distinct colored markers identify which calibrated crop the fake OCR backend received.
        paint(bounds[0], 0xff222222.toInt())
        paint(bounds[1], 0xffeeeeee.toInt())
        mapper.buttonRects(2, width, height).forEach { paint(it, 0xff63a6f7.toInt()) }
        val bitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        val page = LabyrinthEntryPageState.EVENT_CHOICE
        val frame = LabyrinthEntryFrameResult(LabyrinthEntryPageObservation(page, 1.0, mapOf(page to 1.0),
            LabyrinthAnchorScores(emptyMap())), emptyList(), 0, frameWidth = width, frameHeight = height)
        var requests = 0
        var readSlots = false
        val resolver = AndroidLabyrinthEventChoiceResolver(AndroidLabyrinthEventRecognitionData(listOf(
            EventResource(id = "other", name = "完全不同的故事", choices = listOf(EventChoiceResource("other-choice", "钓鱼吧", 1)))), ocr),
            submitTextRead = { crop, done ->
                requests++
                done(when {
                    crop.width > width / 2 -> "新的节日庆典"
                    crop.getPixel(0, 0) == 0xff222222.toInt() -> "消耗2000金币＋金币×300\n选择"
                    else -> "免费领取阿尔法金币×200\n选择"
                })
            }, readUnknownOptionText = { readSlots })
        try {
            repeat(3) { resolver.resolve(bitmap, frame) }
            assertEquals(1, requests)
            readSlots = true
            repeat(2) { resolver.resolve(bitmap, frame) }
            val obs = requireNotNull(resolver.resolve(bitmap, frame).eventChoiceSelection)
            assertTrue(obs.trusted && !obs.catalogMatched && obs.stableFrames >= 2)
            assertEquals(3, requests)
            assertFalse(UnknownGameEventPolicy.SAFE_PARSE_OR_WAIT.allows(obs.choices[0].choice))
            assertTrue(UnknownGameEventPolicy.SAFE_PARSE_OR_WAIT.allows(obs.choices[1].choice))
            assertTrue(obs.choices.all { it.enabledConfidence >= 0.35 })
        } finally { bitmap.recycle() }
    }
}
