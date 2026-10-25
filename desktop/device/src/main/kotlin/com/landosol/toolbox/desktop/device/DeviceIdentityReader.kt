package com.landosol.toolbox.desktop.device

import com.android.apksig.ApkVerifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/** APK bytes exist only during inspection. Cache keys include the device and package update time. */
class DeviceIdentityReader(private val adb: AdbExecutor, private val dataDirectory: Path, private val gamePackages: Set<String>) {
    private data class CacheKey(val deviceKey: String, val packageName: String, val versionCode: Long, val updated: String)
    private val certificateCache = ConcurrentHashMap<CacheKey, String>()

    suspend fun read(serial: String, size: Pair<Int, Int>): DeviceIdentity {
        suspend fun property(name: String) = adb.shell(serial, listOf("getprop", name)).requireSuccess().text().trim()
        val serialNo = property("ro.serialno")
        val androidId = adb.shell(serial, listOf("settings", "get", "secure", "android_id")).requireSuccess().text().trim().takeUnless { it == "null" }.orEmpty()
        val key = digest("SHA-256", "$serialNo\u0000$androidId".toByteArray(Charsets.UTF_8))
        val brand = property("ro.product.brand")
        val model = property("ro.product.model")
        val release = property("ro.build.version.release")
        val sdk = property("ro.build.version.sdk").toIntOrNull() ?: throw DeviceOperationException("无法读取 Android 版本")
        val abis = property("ro.product.cpu.abilist").split(',').filter(String::isNotBlank)
        val installed = adb.shell(serial, listOf("pm", "list", "packages")).requireSuccess().text().lineSequence()
            .map(String::trim).filter { it.startsWith("package:") }.map { it.removePrefix("package:") }.filter { it in gamePackages }.toList()
        val packages = installed.associateWith { pkg ->
            val (name, code, updated) = DeviceParsers.packageVersion(adb.shell(serial, listOf("dumpsys", "package", pkg)).requireSuccess().text())
            val cacheKey = CacheKey(key, pkg, code, updated)
            val certificate = certificateCache[cacheKey] ?: readCertificate(serial, pkg, sdk).also {
                certificateCache.keys.removeIf { old -> old.deviceKey == key && old.packageName == pkg }
                certificateCache[cacheKey] = it
            }
            InstalledPackage(pkg, name, code, certificate, updated)
        }
        return DeviceIdentity(key, brand, model, release, sdk, abis, androidId, maxOf(size.first, size.second), minOf(size.first, size.second), packages, System.currentTimeMillis())
    }

    private suspend fun readCertificate(serial: String, packageName: String, sdk: Int): String {
        val source = DeviceParsers.baseApk(adb.shell(serial, listOf("pm", "path", packageName)).requireSuccess().text())
            ?: throw DeviceOperationException("无法读取游戏安装包：$packageName")
        return withContext(Dispatchers.IO) {
            val directory = dataDirectory.resolve("cache/apk")
            Files.createDirectories(directory)
            val apk = Files.createTempFile(directory, "certificate-", ".apk")
            try {
                adb.execute(listOf("pull", source, apk.toString()), serial, timeoutMillis = 60_000).requireSuccess()
                certificateMd5(apk, sdk)
            } finally { Files.deleteIfExists(apk) }
        }
    }

    companion object {
        /** ApkVerifier chooses the current signer for the actual device's Android version. */
        fun certificateMd5(apk: Path, sdk: Int): String {
            val result = ApkVerifier.Builder(apk.toFile()).setMinCheckedPlatformVersion(sdk).setMaxCheckedPlatformVersion(sdk).build().verify()
            if (!result.isVerified) throw DeviceOperationException("游戏安装包签名无法验证")
            val certificate = result.signerCertificates.firstOrNull() ?: throw DeviceOperationException("游戏安装包没有可用签名")
            return digest("MD5", certificate.encoded)
        }
        private fun digest(algorithm: String, bytes: ByteArray): String = MessageDigest.getInstance(algorithm).digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
