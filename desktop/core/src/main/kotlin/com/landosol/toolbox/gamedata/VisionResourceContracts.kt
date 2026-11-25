package com.landosol.toolbox.gamedata

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class VisionPackDocument(
    val schemaVersion: Int = 1,
    val assets: List<VisionAssetResource> = emptyList(),
)

@Serializable
data class VisionAssetResource(
    val id: String = "",
    val file: String = "",
    val kind: String = "template",
    val width: Int = 0,
    val height: Int = 0,
    val sha256: String = "",
    val anchor: String = "",
)

sealed interface VisionPackLoadResult {
    data class Success(val document: VisionPackDocument) : VisionPackLoadResult
    data class Failure(val reasons: List<String>) : VisionPackLoadResult
}

/** Loads and verifies small, user-owned visual anchors without enabling clicks. */
class VisionPackLoader(
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    fun load(root: File): VisionPackLoadResult {
        val manifest = File(root, VISION_MANIFEST)
        if (!manifest.isFile) return VisionPackLoadResult.Failure(listOf("缺少视觉资源清单：$VISION_MANIFEST"))
        return runCatching {
            val document = json.decodeFromString<VisionPackDocument>(manifest.readText())
            val reasons = validate(root, document)
            if (reasons.isEmpty()) VisionPackLoadResult.Success(document)
            else VisionPackLoadResult.Failure(reasons)
        }.getOrElse { error ->
            VisionPackLoadResult.Failure(listOf("视觉资源 JSON 无法解析：${error.message ?: "未知错误"}"))
        }
    }

    private fun validate(root: File, document: VisionPackDocument): List<String> = buildList {
        if (document.schemaVersion != CURRENT_SCHEMA_VERSION) {
            add("不支持的视觉资源 Schema 版本：${document.schemaVersion}")
        }
        if (document.assets.isEmpty()) add("视觉资源清单为空")
        val ids = document.assets.map(VisionAssetResource::id)
        ids.filter(String::isBlank).forEach { add("视觉资源 ID 为空") }
        ids.groupingBy { it }.eachCount().filter { (id, count) -> id.isNotBlank() && count > 1 }
            .keys.forEach { add("视觉资源存在重复 ID：$it") }
        val files = document.assets.map(VisionAssetResource::file)
        files.groupingBy { it }.eachCount().filter { (file, count) -> file.isNotBlank() && count > 1 }
            .keys.forEach { add("视觉资源存在重复路径：$it") }
        document.assets.forEach { asset ->
            if (asset.file.isUnsafeVisionPath()) {
                add("视觉资源路径不安全：${asset.file}")
                return@forEach
            }
            val file = File(root, asset.file)
            if (!file.isFile) {
                add("缺少视觉资源文件：${asset.file}")
                return@forEach
            }
            if (asset.width <= 0 || asset.height <= 0) add("视觉资源尺寸无效：${asset.id}")
            if (asset.sha256.isBlank() || sha256(file) != asset.sha256.lowercase()) {
                add("视觉资源校验失败：${asset.file}")
            }
            val dimensions = imageDimensions(file)
            if (dimensions == null) {
                add("视觉资源不是有效图像：${asset.file}")
            } else if (dimensions.first != asset.width || dimensions.second != asset.height) {
                add("视觉资源尺寸不匹配：${asset.file}")
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

    private fun bmpDimensions(file: File): Pair<Int, Int>? {
        val header = ByteArray(26)
        val count = file.inputStream().use { input ->
            var offset = 0
            while (offset < header.size) {
                val read = input.read(header, offset, header.size - offset)
                if (read < 0) break
                offset += read
            }
            offset
        }
        if (count < header.size || header[0] != 'B'.code.toByte() || header[1] != 'M'.code.toByte()) return null
        val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val width = buffer.getInt(18)
        val height = buffer.getInt(22)
        if (width <= 0 || height == 0) return null
        return width to kotlin.math.abs(height)
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
        val signature = byteArrayOf(
            0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
        )
        if (count < header.size || !header.copyOfRange(0, signature.size).contentEquals(signature)) {
            return null
        }
        val width = ByteBuffer.wrap(header, 16, 4).order(ByteOrder.BIG_ENDIAN).int
        val height = ByteBuffer.wrap(header, 20, 4).order(ByteOrder.BIG_ENDIAN).int
        return if (width > 0 && height > 0) width to height else null
    }

    private fun imageDimensions(file: File): Pair<Int, Int>? = when (file.extension.lowercase()) {
        "bmp" -> bmpDimensions(file)
        "png" -> pngDimensions(file)
        else -> null
    }

    private companion object {
        const val CURRENT_SCHEMA_VERSION = 1
        const val VISION_MANIFEST = "vision.json"
    }
}

private fun String.isUnsafeVisionPath(): Boolean {
    val normalized = replace('\\', '/')
    val segments = normalized.split('/')
    return normalized.isBlank() ||
        normalized.startsWith('/') ||
        Regex("^[A-Za-z]:").containsMatchIn(normalized) ||
        segments.any { it == "." || it == ".." }
}
