package com.landosol.toolbox.labyrinth.vision

import com.landosol.toolbox.clanbattle.recognition.PixelImage
import java.io.File
import org.junit.Assert.assertTrue

/** Shipped assets / committed fixtures only. ImageIO is JVM reflection, never a Bitmap stub. */
internal object Batch5FrameFixtures {
    val root: File = generateSequence(File(".").canonicalFile, File::getParentFile)
        .take(8).firstOrNull { File(it, "android/app/build.gradle.kts").isFile }
        ?: error("Cannot locate batch5 project root")

    fun source(relative: String): String = File(root, "android/app/src/main/java/com/landosol/toolbox/$relative").readText()

    fun templates(): LabyrinthEntryTemplateSet {
        // Read the Android loader's declarative path table, but do not instantiate Android classes.
        val text = source("labyrinth/vision/AndroidLabyrinthEntryFrameProcessor.kt")
        val pattern = Regex("EntryAnchorId\\.([A-Z_]+)\\s+to\\s+\"([^\"]+)\"")
        val values = pattern.findAll(text).mapNotNull { match ->
            val id = EntryAnchorId::class.java.getField(match.groupValues[1]).get(null) as String
            val path = match.groupValues[2].replace("\$ROOT", "resource-packs/cn-bilibili/vision")
            val file = File(root, "android/app/src/main/assets/$path")
            if (file.isFile) id to read(file) else null
        }.toMap()
        assertTrue("missing required assets: ${EntryAnchorId.required - values.keys}",
            values.keys.containsAll(EntryAnchorId.required))
        return LabyrinthEntryTemplateSet(values)
    }

    fun fixture(name: String): PixelImage = read(File(root, "android/app/src/test/resources/labyrinth/$name"))

    private fun read(file: File): PixelImage {
        assertTrue("missing committed fixture / asset: $file", file.isFile)
        val image = requireNotNull(Class.forName("javax.imageio.ImageIO")
            .getMethod("read", File::class.java).invoke(null, file))
        val width = image.javaClass.getMethod("getWidth").invoke(image) as Int
        val height = image.javaClass.getMethod("getHeight").invoke(image) as Int
        val pixels = IntArray(width * height)
        image.javaClass.getMethod("getRGB",
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            IntArray::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .invoke(image, 0, 0, width, height, pixels, 0, width)
        return PixelImage(width, height, pixels)
    }
}
