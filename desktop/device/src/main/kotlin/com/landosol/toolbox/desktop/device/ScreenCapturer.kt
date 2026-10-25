package com.landosol.toolbox.desktop.device

import java.io.ByteArrayInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.imageio.ImageIO

class DeviceOperationException(message: String, val adbFailure: AdbFailureKind? = null) : IOException(message)

internal fun AdbResult.requireSuccess(): AdbResult.Success = when (this) {
    is AdbResult.Success -> this
    is AdbResult.Failure -> throw DeviceOperationException(detail, kind)
}

class ScreenCapturer(private val adb: AdbExecutor, private val serial: String) {
    suspend fun capture(mode: CaptureMode): DeviceFrame {
        val args = if (mode == CaptureMode.Raw) listOf("exec-out", "screencap") else listOf("exec-out", "screencap", "-p")
        val result = adb.execute(args, serial, timeoutMillis = 10_000, screenshot = true).requireSuccess()
        val timestamp = System.currentTimeMillis()
        return if (mode == CaptureMode.Raw) decodeRaw(result.stdout, timestamp) else decodePng(result.stdout, timestamp)
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
        fun decodeRaw(bytes: ByteArray, timestampMillis: Long = System.currentTimeMillis()): DeviceFrame {
            if (bytes.size < 12) throw DeviceOperationException("Raw 截图头部不完整")
            val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val width = header.int
            val height = header.int
            val format = header.int
            val pixelCount = width.toLong() * height
            if (width <= 0 || height <= 0 || pixelCount > Int.MAX_VALUE / 4) throw DeviceOperationException("Raw 截图尺寸无效")
            val offset = bytes.size.toLong() - pixelCount * 4
            if (offset != 12L && offset != 16L) throw DeviceOperationException("Raw 截图数据长度不符")
            if (format !in setOf(1, 2, 5)) throw DeviceOperationException("Raw 截图像素格式不受支持：$format")
            val pixels = IntArray(pixelCount.toInt())
            var at = offset.toInt()
            for (i in pixels.indices) {
                val first = bytes[at].toInt() and 255
                val green = bytes[at + 1].toInt() and 255
                val third = bytes[at + 2].toInt() and 255
                val alpha = if (format == 2) 255 else bytes[at + 3].toInt() and 255
                val red = if (format == 5) third else first
                val blue = if (format == 5) first else third
                pixels[i] = (alpha shl 24) or (red shl 16) or (green shl 8) or blue
                at += 4
            }
            return DeviceFrame(width, height, pixels, timestampMillis)
        }
        fun decodePng(bytes: ByteArray, timestampMillis: Long = System.currentTimeMillis()): DeviceFrame {
            val image = ImageIO.read(ByteArrayInputStream(bytes)) ?: throw DeviceOperationException("PNG 截图无法解码")
            return DeviceFrame(image.width, image.height, image.getRGB(0, 0, image.width, image.height, null, 0, image.width), timestampMillis)
        }
    }
}
