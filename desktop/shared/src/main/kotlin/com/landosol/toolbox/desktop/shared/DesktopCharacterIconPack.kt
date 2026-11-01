package com.landosol.toolbox.desktop.shared

import android.content.Context
import com.landosol.toolbox.labyrinth.LabyrinthCharacterAttribute
import com.landosol.toolbox.labyrinth.vision.AndroidLabyrinthBattleTeamTemplateLoader
import com.landosol.toolbox.labyrinth.vision.IconSampleGrid
import com.landosol.toolbox.labyrinth.vision.IconSampleShape
import com.landosol.toolbox.labyrinth.vision.LABYRINTH_CHARACTER_ICON_SAFE_CONFIDENCE
import com.landosol.toolbox.labyrinth.vision.LABYRINTH_CHARACTER_ICON_SAFE_RIVAL_MARGIN
import com.landosol.toolbox.labyrinth.vision.LabyrinthCharacterIconMatcher
import com.landosol.toolbox.labyrinth.vision.LabyrinthCharacterNameCandidate
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** The generator and application use the same decoder, sampling grids and on-disk format. */
internal object DesktopCharacterIconPack {
    const val RESOURCE = "desktop/character-icon-samples.bin.gz"
    private const val SIGNATURE = 0x49434f31 // ICO1
    private const val MAX_ALIASES = 256

    fun compile(context: Context, output: OutputStream) =
        write(AndroidLabyrinthBattleTeamTemplateLoader(context).sampleImages(emptyMap()), output)

    fun write(matcher: LabyrinthCharacterIconMatcher, output: OutputStream) {
        DataOutputStream(GZIPOutputStream(output).buffered()).use { data ->
            data.writeInt(SIGNATURE)
            data.writeInt(matcher.pack.size)
            for (template in matcher.pack) {
                data.writeUTF(template.character.characterId)
                data.writeUTF(template.character.displayName)
                require(template.character.aliases.size <= MAX_ALIASES) { "Too many aliases for ${template.character.characterId}" }
                data.writeInt(template.character.aliases.size)
                for (alias in template.character.aliases) data.writeUTF(alias)
                data.writeUTF(template.iconVariant)
                data.writeInt(template.width)
                data.writeInt(template.height)
                writeGrid(data, template.coarse)
                writeGrid(data, template.fine)
            }
        }
    }

    fun load(attributes: Map<String, LabyrinthCharacterAttribute?>): LabyrinthCharacterIconMatcher {
        val resource = checkNotNull(javaClass.classLoader.getResourceAsStream(RESOURCE)) { "Missing compiled character icons: $RESOURCE" }
        return resource.use { read(it, attributes) }
    }

    fun read(stream: InputStream, attributes: Map<String, LabyrinthCharacterAttribute?>): LabyrinthCharacterIconMatcher =
        DataInputStream(GZIPInputStream(stream).buffered()).use { data ->
            check(data.readInt() == SIGNATURE) { "Unsupported compiled character icon format" }
            val count = data.readInt()
            check(count >= 0) { "Invalid character icon count" }
            val samples = (0 until count).asSequence().map {
                val id = data.readUTF()
                val name = data.readUTF()
                val aliasCount = data.readInt()
                check(aliasCount in 0..MAX_ALIASES) { "Invalid character alias count: $aliasCount" }
                val aliases = List(aliasCount) { data.readUTF() }
                val variant = data.readUTF()
                val width = data.readInt()
                val height = data.readInt()
                check(width > 0 && height > 0) { "Invalid character icon dimensions" }
                val hasSamples = height >= IconSampleShape.MIN_SOURCE_HEIGHT
                LabyrinthCharacterIconMatcher.SampledTemplate(
                    LabyrinthCharacterNameCandidate(id, name, aliases), variant, attributes[id], width, height,
                    readGrid(data, IconSampleShape.COARSE, hasSamples), readGrid(data, IconSampleShape.FINE, hasSamples),
                )
            }
            val matcher = LabyrinthCharacterIconMatcher(
                LABYRINTH_CHARACTER_ICON_SAFE_CONFIDENCE, LABYRINTH_CHARACTER_ICON_SAFE_RIVAL_MARGIN, samples,
            )
            check(data.read() == -1) { "Unexpected trailing character icon data" }
            matcher
        }

    private fun writeGrid(output: DataOutputStream, grid: IconSampleGrid) {
        val cells = grid.firstOrNull()?.size ?: 0
        output.writeByte(grid.size)
        output.writeShort(cells)
        val bytes = ByteBuffer.allocate(grid.size * cells * Short.SIZE_BYTES)
        val shorts = bytes.asShortBuffer()
        for (plane in grid) shorts.put(plane)
        output.write(bytes.array())
    }

    private fun readGrid(input: DataInputStream, shape: IconSampleShape, hasSamples: Boolean): IconSampleGrid {
        val planes = input.readUnsignedByte()
        val cells = input.readUnsignedShort()
        val expectedPlanes = if (hasSamples) shape.planes else 0
        val expectedCells = if (hasSamples) shape.cells else 0
        check(planes == expectedPlanes && cells == expectedCells) { "Invalid $shape grid: $planes x $cells" }
        val bytes = ByteArray(planes * cells * Short.SIZE_BYTES)
        input.readFully(bytes)
        val shorts = ByteBuffer.wrap(bytes).asShortBuffer()
        return Array(planes) { ShortArray(cells).also { shorts.get(it) } }
    }
}
