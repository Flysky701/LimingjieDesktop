package com.landosol.toolbox.desktop.device

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList
import javax.imageio.ImageIO

internal class FakeAdb : AdbExecutor {
    data class Call(val arguments: List<String>, val serial: String?, val shell: Boolean, val timeoutMillis: Long, val screenshot: Boolean)
    override val executable = "C:/模拟器 安装目录/adb.exe"
    val calls = CopyOnWriteArrayList<Call>()
    var handler: suspend (Call) -> AdbResult = { default(it) }
    var window = "mCurrentFocus=Window{123 u0 jp.co.cygames.princessconnectredive/.MainActivity}"
    var failCapture = false
    override suspend fun execute(arguments: List<String>, serial: String?, timeoutMillis: Long, screenshot: Boolean): AdbResult = call(Call(arguments, serial, false, timeoutMillis, screenshot))
    override suspend fun shell(serial: String, arguments: List<String>, timeoutMillis: Long): AdbResult = call(Call(arguments, serial, true, timeoutMillis, false))
    private suspend fun call(call: Call): AdbResult { calls += call; return handler(call) }
    override fun close() = Unit
    fun default(call: Call): AdbResult {
        val args = call.arguments
        return when {
            args.take(2) == listOf("exec-out", "screencap") -> if (failCapture) AdbResult.Failure(AdbFailureKind.NonZeroExit, "synthetic capture failure") else success(if ("-p" in args) png() else raw())
            args == listOf("getprop", "sys.boot_completed") -> success("1")
            args == listOf("getprop", "ro.build.version.sdk") -> success("28")
            args == listOf("getprop", "ro.product.cpu.abilist") -> success("x86_64,arm64-v8a")
            args == listOf("getprop", "ro.serialno") -> success("synthetic-serial")
            args == listOf("getprop", "ro.product.model") -> success("Synthetic emulator")
            args == listOf("getprop", "ro.product.brand") -> success("Synthetic")
            args == listOf("getprop", "ro.build.version.release") -> success("9")
            args == listOf("wm", "size") -> success("Physical size: 1920x1080")
            args == listOf("cat", "/proc/sys/kernel/random/boot_id") -> success("synthetic-boot")
            args == listOf("settings", "get", "secure", "android_id") -> success("synthetic-android-id")
            args == listOf("dumpsys", "power") -> success("mWakefulness=Awake")
            args == listOf("dumpsys", "window") -> success(window)
            args == listOf("get-state") -> success("device")
            else -> success("")
        }
    }
    companion object {
        const val GAME = "jp.co.cygames.princessconnectredive"
        fun success(text: String) = success(text.toByteArray(Charsets.UTF_8))
        fun success(bytes: ByteArray) = AdbResult.Success(0, bytes, "")
        fun raw(headerBytes: Int = 12, format: Int = 1): ByteArray = ByteBuffer.allocate(headerBytes + 8).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(2); putInt(1); putInt(format); if (headerBytes == 16) putInt(0)
            put(byteArrayOf(0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, 0x7f))
        }.array()
        fun png(): ByteArray {
            val image = BufferedImage(2, 1, BufferedImage.TYPE_INT_ARGB)
            image.setRGB(0, 0, 0x44112233); image.setRGB(1, 0, 0x7f556677)
            return ByteArrayOutputStream().apply { ImageIO.write(image, "png", this) }.toByteArray()
        }
    }
}
