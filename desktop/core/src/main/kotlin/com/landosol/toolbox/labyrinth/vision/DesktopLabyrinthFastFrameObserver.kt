package com.landosol.toolbox.labyrinth.vision

import com.landosol.toolbox.core.AppContext
import com.landosol.toolbox.automation.capture.CapturedFrame
import com.landosol.toolbox.vision.PixelImage

/** T2 page / cheap-ROI observer. This instance must not be shared by concurrently running calls. */
class DesktopLabyrinthFastFrameObserver private constructor(
    private val observer: LabyrinthFastFrameObserver,
) {
    fun process(frame: CapturedFrame): LabyrinthFastFrameObservation {
        val started = System.nanoTime()
        return observer.process(
            frame = capturedFramePixelImage(frame),
            frameSequence = frame.frameSequence,
            timestampMillis = frame.timestampMillis,
        ).copy(elapsedMillis = (System.nanoTime() - started) / 1_000_000)
    }

    companion object {
        fun create(
            context: AppContext,
            captureFrameSizeGetter: () -> Pair<Int, Int>? = { null },
        ): DesktopLabyrinthFastFrameObserver {
            val templates = LabyrinthEntryTemplateLoader(context).load()
            val prescaled = captureFrameSizeGetter()?.let { (width, height) ->
                EntryTemplatePrescaler.build(templates, width, height)
            }
            return DesktopLabyrinthFastFrameObserver(LabyrinthFastFrameObserver(templates, prescaled))
        }
    }
}

/** Both observers borrow the captured pixels while the frame is leased. */
internal fun capturedFramePixelImage(frame: CapturedFrame): PixelImage {
    val bitmap = frame.bitmap
    require(!bitmap.isRecycled) { "截图 Bitmap 已释放" }
    return labyrinthFramePixelImage(bitmap.width, bitmap.height, frame.argbPixels) {
        bitmap.copyToPixelImage()
    }
}
