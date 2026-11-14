package com.landosol.toolbox.labyrinth.vision

import android.content.Context
import com.landosol.toolbox.automation.capture.CapturedFrame
import com.landosol.toolbox.clanbattle.recognition.AndroidPixelImageAdapter
import com.landosol.toolbox.clanbattle.recognition.PixelImage

/** T2 page / cheap-ROI observer. This instance must not be shared by concurrently running calls. */
class AndroidLabyrinthFastFrameObserver private constructor(
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
            context: Context,
            captureFrameSizeGetter: () -> Pair<Int, Int>? = { null },
        ): AndroidLabyrinthFastFrameObserver {
            val templates = AndroidLabyrinthEntryTemplateLoader(context).load()
            val prescaled = captureFrameSizeGetter()?.let { (width, height) ->
                EntryTemplatePrescaler.build(templates, width, height)
            }
            return AndroidLabyrinthFastFrameObserver(LabyrinthFastFrameObserver(templates, prescaled))
        }
    }
}

/** Both Android adapters borrow the same frame snapshot, with the legacy safety checks intact. */
internal fun capturedFramePixelImage(frame: CapturedFrame): PixelImage {
    val bitmap = frame.bitmap
    require(!bitmap.isRecycled) { "截图 Bitmap 已释放" }
    return labyrinthFramePixelImage(bitmap.width, bitmap.height, frame.argbPixels) {
        AndroidPixelImageAdapter.from(bitmap)
    }
}
