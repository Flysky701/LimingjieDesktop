package com.landosol.toolbox.desktop.device

import java.io.ByteArrayInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.imageio.ImageIO
import javax.imageio.stream.MemoryCacheImageInputStream

class DeviceOperationException(message: String, val adbFailure: AdbFailureKind? = null) : IOException(message)

internal fun AdbResult.requireSuccess(): AdbResult.Success = when (this) {
    is AdbResult.Success -> this
    is AdbResult.Failure -> throw DeviceOperationException(detail, kind)
}

/** Collect only bytes actually received. Screenshot dimensions remain untrusted until shell exit and length validation. */
internal class RawCapturePayload {
    private val pages = mutableListOf<ByteArray>()
    var size = 0
        private set

    fun readPacket(length: Int, readFully: (ByteArray, Int, Int) -> Unit) {
        var remaining = length
        while (remaining > 0) {
            val offset = size % PAGE_SIZE
            if (offset == 0) pages += ByteArray(PAGE_SIZE)
            val count = minOf(PAGE_SIZE - offset, remaining)
            readFully(pages.last(), offset, count)
            size += count
            remaining -= count
        }
    }

    fun decode(timestampMillis: Long): DeviceFrame = ScreenCapturer.decodeRaw(pages, size, timestampMillis)

    private companion object { const val PAGE_SIZE = 64 * 1024 }
}

class ScreenCapturer(private val adb: AdbExecutor, private val serial: String) {
    suspend fun capture(mode: CaptureMode): DeviceFrame {
        // The built-in executor can decode paged socket output. Other executors retain their binary stdout contract.
        if (mode == CaptureMode.Raw && adb is ProcessAdbExecutor) return adb.captureRaw(serial)
        val args = if (mode == CaptureMode.Raw) listOf("exec-out", "screencap") else listOf("exec-out", "screencap", "-p")
        val result = adb.execute(args, serial, timeoutMillis = 10_000, screenshot = true).requireSuccess()
        return if (mode == CaptureMode.Raw) decodeRaw(result.stdout) else decodePng(result.stdout)
    }
    suspend fun benchmark(): Pair<List<CaptureTiming>, CaptureMode> {
        val measurements = mutableListOf<CaptureTiming>()
        for (mode in CaptureMode.entries) {
            val times = mutableListOf<Long>()
            try {
                repeat(5) {
                    val start = System.nanoTime()
                    capture(mode)
                    times += (System.nanoTime() - start) / 1_000_000
                }
                val sorted = times.sorted()
                measurements += CaptureTiming(mode, sorted[2], sorted.last())
            } catch (_: IOException) { /* FB-02: a mode with any failed sample is ineligible. */ }
        }
        val best = measurements.minByOrNull { it.p50Millis } ?: throw DeviceOperationException("Raw 和 PNG 截图均失败，请检查模拟器的 ADB 与渲染设置")
        return measurements to best.mode
    }

    companion object {
        fun decodeRaw(bytes: ByteArray, timestampMillis: Long = System.currentTimeMillis()): DeviceFrame =
            decodeRaw(listOf(bytes), bytes.size, timestampMillis)

        /** Pages begin on pixel boundaries. Only the last page may have unused capacity. */
        internal fun decodeRaw(pages: List<ByteArray>, size: Int, timestampMillis: Long): DeviceFrame {
            if (size < 12) throw DeviceOperationException("Raw 截图头部不完整")
            val header = ByteBuffer.wrap(pages.first()).order(ByteOrder.LITTLE_ENDIAN)
            val width = header.int
            val height = header.int
            val format = header.int
            val pixelCount = width.toLong() * height
            if (width <= 0 || height <= 0 || pixelCount > Int.MAX_VALUE / 4) throw DeviceOperationException("Raw 截图尺寸无效")
            val offset = size.toLong() - pixelCount * 4
            if (offset != 12L && offset != 16L) throw DeviceOperationException("Raw 截图数据长度不符")
            if (format !in setOf(1, 2, 5)) throw DeviceOperationException("Raw 截图像素格式不受支持：$format")
            val pixels = IntArray(pixelCount.toInt())
            var written = 0
            var skip = offset.toInt()
            for (page in pages) {
                val count = minOf((page.size - skip) / 4, pixels.size - written)
                ByteBuffer.wrap(page, skip, count * 4).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(pixels, written, count)
                written += count
                skip = 0
            }
            // Little-endian BGRA words already match ARGB. RGBA/RGBX need the red and blue bytes swapped.
            if (format != 5) {
                val forceAlpha = if (format == 2) -0x1000000 else 0
                for (i in pixels.indices) {
                    val abgr = pixels[i]
                    pixels[i] = (abgr and 0xff00ff00.toInt()) or ((abgr and 255) shl 16) or ((abgr ushr 16) and 255) or forceAlpha
                }
            }
            return DeviceFrame(width, height, pixels, timestampMillis)
        }
        fun decodePng(bytes: ByteArray, timestampMillis: Long = System.currentTimeMillis()): DeviceFrame {
            val image = MemoryCacheImageInputStream(ByteArrayInputStream(bytes)).use { input ->
                val reader = ImageIO.getImageReaders(input).asSequence().firstOrNull()
                    ?: throw DeviceOperationException("PNG 截图无法解码")
                try {
                    reader.setInput(input, true, true)
                    reader.read(0)
                } finally { reader.dispose() }
            }
            return DeviceFrame(image.width, image.height, image.getRGB(0, 0, image.width, image.height, null, 0, image.width), timestampMillis)
        }
    }
}
