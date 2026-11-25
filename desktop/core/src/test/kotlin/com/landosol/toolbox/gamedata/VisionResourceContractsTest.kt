package com.landosol.toolbox.gamedata

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import com.landosol.toolbox.labyrinth.vision.EntryAnchorId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VisionResourceContractsTest {
    @Test
    fun `bundled entry flow anchors pass dimensions and hashes`() {
        val root = listOf(
            File("src/main/resources/assets/resource-packs/cn-bilibili"),
            File("desktop/core/src/main/resources/assets/resource-packs/cn-bilibili"),
        ).firstOrNull(File::isDirectory)
        assertTrue("bundled resource pack is missing", root != null)

        val result = VisionPackLoader().load(root!!)

        assertTrue("bundled vision pack failed: $result", result is VisionPackLoadResult.Success)
        val ids = (result as VisionPackLoadResult.Success).document.assets.mapTo(mutableSetOf()) { it.id }
        assertEquals(emptySet<String>(), EntryAnchorId.required - ids)
    }

    @Test
    fun `rejects a visual anchor with a wrong hash`() {
        val root = Files.createTempDirectory("vision-pack").toFile()
        try {
            File(root, "sample.bmp").writeBytes(bmpHeader(1, 1))
            File(root, "vision.json").writeText(
                """
                {"schemaVersion":1,"assets":[
                  {"id":"sample","file":"sample.bmp","kind":"template",
                   "width":1,"height":1,"sha256":"00","anchor":"test"}
                ]}
                """.trimIndent(),
            )

            val result = VisionPackLoader().load(root)

            assertTrue(result is VisionPackLoadResult.Failure)
            assertTrue((result as VisionPackLoadResult.Failure).reasons.any { it.contains("校验失败") })
        } finally {
            root.deleteRecursively()
        }
    }

    private fun bmpHeader(width: Int, height: Int): ByteArray = ByteBuffer.allocate(26)
        .order(ByteOrder.LITTLE_ENDIAN)
        .put('B'.code.toByte())
        .put('M'.code.toByte())
        .putInt(0)
        .putInt(0)
        .putInt(26)
        .putInt(12)
        .putInt(width)
        .putInt(height)
        .array()
}
