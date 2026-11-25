package com.landosol.toolbox.labyrinth.vision

import com.landosol.toolbox.vision.PixelImage
import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LabyrinthNodeMoveConfirmationDetectorTest {
    @Test
    fun `two button movement dialog exposes only the right confirm button`() {
        val frame = dialogFrame()

        val observation = requireNotNull(LabyrinthNodeMoveConfirmationDetector().detect(frame))

        assertEquals(EntryPixelRect(975, 690, 415, 105), observation.confirmButtonRect)
        assertTrue(observation.confidence >= 0.80)
        // Cancel is the only control that closes this modal without moving, and swipes cannot
        // close it at all, so its rect has to be reported alongside confirm.
        assertEquals(EntryPixelRect(545, 690, 415, 105), observation.cancelButtonRect)
    }

    @Test
    fun `map without dialog is rejected`() {
        val frame = PixelImage(1920, 1080, IntArray(1920 * 1080) { rgb(20, 35, 75) })

        assertNull(LabyrinthNodeMoveConfirmationDetector().detect(frame))
    }

    @Test
    fun `real initial map fixture is not a movement confirmation`() {
        val root = locateProjectRoot()
        val file = File(root, "素材/ui/黎明界进入/当前版本_节点选择.png")
        assumeTrue("local diagnostic fixture is unavailable: ${file.absolutePath}", file.isFile)
        val frame = readImage(file)

        assertNull(LabyrinthNodeMoveConfirmationDetector().detect(frame))
    }

    private fun dialogFrame(): PixelImage {
        val width = 1920
        val height = 1080
        val pixels = IntArray(width * height) { rgb(20, 35, 75) }
        fill(pixels, width, EntryPixelRect(485, 260, 950, 65), rgb(60, 145, 235))
        fill(pixels, width, EntryPixelRect(500, 330, 920, 350), rgb(245, 245, 245))
        fill(pixels, width, EntryPixelRect(540, 690, 410, 105), rgb(245, 245, 245))
        fill(pixels, width, EntryPixelRect(975, 690, 415, 105), rgb(55, 145, 240))
        return PixelImage(width, height, pixels)
    }

    private fun fill(pixels: IntArray, frameWidth: Int, rect: EntryPixelRect, color: Int) {
        repeat(rect.height) { row ->
            val offset = (rect.top + row) * frameWidth + rect.left
            pixels.fill(color, offset, offset + rect.width)
        }
    }

    private fun readImage(file: File): PixelImage {
        val imageIo = Class.forName("javax.imageio.ImageIO")
        val image = requireNotNull(
            imageIo.getMethod("read", File::class.java).invoke(null, file),
        ) { "Cannot read $file" }
        val width = image.javaClass.getMethod("getWidth").invoke(image) as Int
        val height = image.javaClass.getMethod("getHeight").invoke(image) as Int
        val pixels = IntArray(width * height)
        image.javaClass.getMethod(
            "getRGB",
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            IntArray::class.java,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
        ).invoke(image, 0, 0, width, height, pixels, 0, width)
        return PixelImage(width, height, pixels)
    }

    private fun locateProjectRoot(): File {
        var current = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        repeat(8) {
            if (File(current, "desktop/core/build.gradle.kts").isFile) return current
            current = current.parentFile ?: return@repeat
        }
        error("Cannot locate project root from ${System.getProperty("user.dir")}")
    }

    private companion object {
        fun rgb(red: Int, green: Int, blue: Int): Int =
            (0xff shl 24) or (red shl 16) or (green shl 8) or blue
    }
}
