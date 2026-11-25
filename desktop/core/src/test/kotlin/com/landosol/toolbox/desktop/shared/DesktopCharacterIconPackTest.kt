package com.landosol.toolbox.desktop.shared

import com.landosol.toolbox.labyrinth.LabyrinthCharacterAttribute
import com.landosol.toolbox.labyrinth.vision.AndroidLabyrinthBattleTeamTemplateLoader
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.zip.GZIPOutputStream

class DesktopCharacterIconPackTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun `rejects invalid alias and grid lengths before reading their payload`() {
        fun header(aliasCount: Int, planes: Int, cells: Int): ByteArray {
            val bytes = ByteArrayOutputStream()
            DataOutputStream(GZIPOutputStream(bytes)).use { data ->
                data.writeInt(0x49434f31)
                data.writeInt(1)
                data.writeUTF("1001")
                data.writeUTF("name")
                data.writeInt(aliasCount)
                if (aliasCount == 0) {
                    data.writeUTF("01")
                    data.writeInt(128)
                    data.writeInt(128)
                    data.writeByte(planes)
                    data.writeShort(cells)
                }
            }
            return bytes.toByteArray()
        }

        for (bad in listOf(header(1000, 0, 0), header(0, 4, 108), header(0, 3, 109))) {
            assertThrows(IllegalStateException::class.java) {
                DesktopCharacterIconPack.read(bad.inputStream(), emptyMap())
            }
        }
    }

    @Test fun `compiled pack preserves every ordered icon and binds current attributes`() {
        val loader = AndroidLabyrinthBattleTeamTemplateLoader(DesktopContext(temp.root))
        val attributes = mapOf("1001" to LabyrinthCharacterAttribute.FIRE, "1002" to LabyrinthCharacterAttribute.WIND)
        val reference = loader.sampleImages(attributes)
        val compiled = loader.loadMatcher(attributes)
        assertTrue(reference.pack.isNotEmpty())
        assertEquals(reference.characterNames(), compiled.characterNames())
        assertEquals(reference.pack.size, compiled.pack.size)
        for (index in reference.pack.indices) {
            val expected = reference.pack[index]
            val actual = compiled.pack[index]
            assertEquals(expected.character, actual.character)
            assertEquals(expected.iconVariant, actual.iconVariant)
            assertEquals(expected.attribute, actual.attribute)
            assertEquals(expected.width, actual.width)
            assertEquals(expected.height, actual.height)
            for ((before, after) in listOf(expected.coarse to actual.coarse, expected.fine to actual.fine)) {
                assertEquals(before.size, after.size)
                for (channel in before.indices) assertArrayEquals(before[channel], after[channel])
            }
        }
        val reopened = loader.loadMatcher(mapOf("1001" to LabyrinthCharacterAttribute.DARK))
        for (template in reopened.pack) {
            assertEquals(if (template.character.characterId == "1001") LabyrinthCharacterAttribute.DARK else null, template.attribute)
        }
    }
}
