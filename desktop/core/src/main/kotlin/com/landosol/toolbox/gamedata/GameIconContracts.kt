package com.landosol.toolbox.gamedata

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class GameIconPackSource(
    val characters: String = "",
    val relics: String = "",
    val note: String = "",
)

@Serializable
data class GameIconCoverage(
    val catalogCharacters: Int = 0,
    val coveredCharacters: Int = 0,
    val characterIconFiles: Int = 0,
    val unmatchedCharacterSourceFiles: Int = 0,
    val catalogRelics: Int = 0,
    val coveredRelics: Int = 0,
    val relicIconFiles: Int = 0,
)

@Serializable
data class GameIconAsset(
    val ownerId: String = "",
    val variant: String = "default",
    val file: String = "",
    val format: String = "png",
    val width: Int = 0,
    val height: Int = 0,
    val sha256: String = "",
)

@Serializable
data class GameIconPackDocument(
    val schemaVersion: Int = 1,
    val source: GameIconPackSource = GameIconPackSource(),
    val coverage: GameIconCoverage = GameIconCoverage(),
    val characterIcons: List<GameIconAsset> = emptyList(),
    val relicIcons: List<GameIconAsset> = emptyList(),
)

sealed interface GameIconPackLoadResult {
    data class Success(val document: GameIconPackDocument) : GameIconPackLoadResult
    data class Failure(val reasons: List<String>) : GameIconPackLoadResult
}

class GameIconCatalog(document: GameIconPackDocument) {
    private val characters = document.characterIcons.groupBy(GameIconAsset::ownerId)
    private val relics = document.relicIcons.associateBy(GameIconAsset::ownerId)

    fun characterIcons(characterId: String): List<GameIconAsset> =
        characters[characterId].orEmpty().sortedBy(GameIconAsset::variant)

    fun relicIcon(relicId: String): GameIconAsset? = relics[relicId]
}

class GameIconPackLoader(
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    fun load(
        root: File,
        knownCharacterIds: Set<String>,
        knownRelicIds: Set<String>,
    ): GameIconPackLoadResult {
        val manifest = File(root, ICON_MANIFEST)
        if (!manifest.isFile) return GameIconPackLoadResult.Failure(listOf("缺少游戏图标清单：$ICON_MANIFEST"))
        return runCatching {
            val document = json.decodeFromString<GameIconPackDocument>(manifest.readText())
            val reasons = validate(root, document, knownCharacterIds, knownRelicIds)
            if (reasons.isEmpty()) GameIconPackLoadResult.Success(document)
            else GameIconPackLoadResult.Failure(reasons)
        }.getOrElse { error ->
            GameIconPackLoadResult.Failure(listOf("游戏图标 JSON 无法解析：${error.message ?: "未知错误"}"))
        }
    }

    private fun validate(
        root: File,
        document: GameIconPackDocument,
        knownCharacterIds: Set<String>,
        knownRelicIds: Set<String>,
    ): List<String> = buildList {
        if (document.schemaVersion != CURRENT_SCHEMA_VERSION) {
            add("不支持的游戏图标 Schema 版本：${document.schemaVersion}")
        }
        if (document.characterIcons.isEmpty() && document.relicIcons.isEmpty()) add("游戏图标清单为空")
        validateAssets(root, "角色", document.characterIcons, knownCharacterIds, allowVariants = true, ::add)
        validateAssets(root, "遗物", document.relicIcons, knownRelicIds, allowVariants = false, ::add)
        val allFiles = document.characterIcons.map(GameIconAsset::file) + document.relicIcons.map(GameIconAsset::file)
        allFiles.groupingBy { it }.eachCount().filter { (file, count) -> file.isNotBlank() && count > 1 }
            .keys.forEach { add("游戏图标路径重复：$it") }
        val coverage = document.coverage
        if (coverage.coveredCharacters != document.characterIcons.map(GameIconAsset::ownerId).distinct().size) {
            add("角色图标覆盖数与清单不一致")
        }
        if (coverage.characterIconFiles != document.characterIcons.size) add("角色图标文件数与清单不一致")
        if (coverage.coveredRelics != document.relicIcons.map(GameIconAsset::ownerId).distinct().size) {
            add("遗物图标覆盖数与清单不一致")
        }
        if (coverage.relicIconFiles != document.relicIcons.size) add("遗物图标文件数与清单不一致")
        if (coverage.catalogCharacters < coverage.coveredCharacters) add("角色图标覆盖数超过角色目录数")
        if (coverage.catalogRelics < coverage.coveredRelics) add("遗物图标覆盖数超过遗物目录数")
        if (coverage.catalogCharacters != knownCharacterIds.size) add("角色目录数与图标清单不一致")
        if (coverage.catalogRelics != knownRelicIds.size) add("遗物目录数与图标清单不一致")
        if (coverage.unmatchedCharacterSourceFiles < 0) add("未匹配角色图标数不能为负数")
    }

    private fun validateAssets(
        root: File,
        kind: String,
        assets: List<GameIconAsset>,
        knownOwnerIds: Set<String>,
        allowVariants: Boolean,
        addReason: (String) -> Unit,
    ) {
        assets.groupingBy { it.ownerId to it.variant }.eachCount()
            .filter { (key, count) -> key.first.isNotBlank() && count > 1 }
            .keys.forEach { (ownerId, variant) -> addReason("${kind}图标重复：$ownerId/$variant") }
        assets.forEach { asset ->
            if (asset.ownerId.isBlank()) addReason("${kind}图标缺少归属 ID")
            if (asset.ownerId !in knownOwnerIds) addReason("${kind}图标引用了不存在的 ID：${asset.ownerId}")
            if (asset.variant.isBlank()) addReason("${kind}图标缺少变体：${asset.ownerId}")
            if (!allowVariants && asset.variant != DEFAULT_VARIANT) {
                addReason("遗物图标变体必须为 $DEFAULT_VARIANT：${asset.ownerId}")
            }
            if (asset.file.isUnsafeResourcePath()) {
                addReason("${kind}图标路径不安全：${asset.file}")
                return@forEach
            }
            val file = File(root, asset.file)
            if (!file.isFile) {
                addReason("缺少${kind}图标：${asset.file}")
                return@forEach
            }
            if (asset.width <= 0 || asset.height <= 0) addReason("${kind}图标尺寸无效：${asset.ownerId}")
            if (asset.sha256.isBlank() || sha256(file) != asset.sha256.lowercase()) {
                addReason("${kind}图标校验失败：${asset.file}")
            }
            val dimensions = when (asset.format) {
                "png" -> pngDimensions(file)
                "webp" -> webpDimensions(file)
                else -> null
            }
            if (dimensions == null) {
                addReason("${kind}图标格式无效：${asset.file}")
            } else if (dimensions.first != asset.width || dimensions.second != asset.height) {
                addReason("${kind}图标尺寸不匹配：${asset.file}")
            }
            if (!asset.file.lowercase().endsWith(".${asset.format}")) {
                addReason("${kind}图标扩展名与格式不匹配：${asset.file}")
            }
        }
    }

    private fun sha256(file: File): String = file.inputStream().use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
        digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun pngDimensions(file: File): Pair<Int, Int>? {
        val header = ByteArray(24)
        val count = file.inputStream().use { input ->
            var offset = 0
            while (offset < header.size) {
                val read = input.read(header, offset, header.size - offset)
                if (read < 0) break
                offset += read
            }
            offset
        }
        if (count < header.size || !header.copyOfRange(0, 8).contentEquals(PNG_SIGNATURE)) return null
        if (!header.copyOfRange(12, 16).contentEquals(IHDR)) return null
        val buffer = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN)
        val width = buffer.getInt(16)
        val height = buffer.getInt(20)
        return if (width > 0 && height > 0) width to height else null
    }

    private fun webpDimensions(file: File): Pair<Int, Int>? {
        val header = ByteArray(25)
        val count = file.inputStream().use { input ->
            var offset = 0
            while (offset < header.size) {
                val read = input.read(header, offset, header.size - offset)
                if (read < 0) break
                offset += read
            }
            offset
        }
        if (count < header.size || !header.copyOfRange(0, 4).contentEquals(RIFF)) return null
        if (!header.copyOfRange(8, 12).contentEquals(WEBP) || !header.copyOfRange(12, 16).contentEquals(VP8L)) {
            return null
        }
        if (header[20] != 0x2f.toByte()) return null
        val first = header[21].toInt() and 0xff
        val second = header[22].toInt() and 0xff
        val third = header[23].toInt() and 0xff
        val fourth = header[24].toInt() and 0xff
        val width = 1 + first + ((second and 0x3f) shl 8)
        val height = 1 + (second shr 6) + (third shl 2) + ((fourth and 0x0f) shl 10)
        return if (width > 0 && height > 0) width to height else null
    }

    private companion object {
        const val CURRENT_SCHEMA_VERSION = 1
        const val ICON_MANIFEST = "icons.json"
        const val DEFAULT_VARIANT = "default"
        val PNG_SIGNATURE = byteArrayOf(
            0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(),
            0x0d, 0x0a, 0x1a, 0x0a,
        )
        val IHDR = byteArrayOf('I'.code.toByte(), 'H'.code.toByte(), 'D'.code.toByte(), 'R'.code.toByte())
        val RIFF = byteArrayOf('R'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), 'F'.code.toByte())
        val WEBP = byteArrayOf('W'.code.toByte(), 'E'.code.toByte(), 'B'.code.toByte(), 'P'.code.toByte())
        val VP8L = byteArrayOf('V'.code.toByte(), 'P'.code.toByte(), '8'.code.toByte(), 'L'.code.toByte())
    }
}
