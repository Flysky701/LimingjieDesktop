package com.landosol.toolbox.desktop.device

import com.android.apksig.ApkVerifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/** APK bytes exist only during inspection; only a verified public certificate digest is cached. */
class DeviceIdentityReader(private val adb: AdbExecutor, private val dataDirectory: Path, private val gamePackages: Set<String>,
    private val onEvent: (String) -> Unit = {},
    private val verifiedCacheDirectory: Path = dataDirectory.resolve("cache/verified-packages")) {
    private data class CacheKey(val deviceKey: String, val packageName: String, val versionCode: Long, val updated: String,
        val sdk: Int, val apkPath: String, val apkStamp: String) {
        val fingerprint get() = digest("SHA-256", listOf(deviceKey, packageName, versionCode.toString(), updated, sdk.toString(), apkPath, apkStamp).joinToString("\u0000").toByteArray())
        val persistent get() = updated.isNotBlank() && apkStamp.matches(Regex("[0-9]+:[0-9]+"))
    }
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
            val source = DeviceParsers.baseApk(adb.shell(serial, listOf("pm", "path", pkg)).requireSuccess().text())
                ?: throw DeviceOperationException("无法读取游戏安装包：$pkg")
            val stamp = (adb.shell(serial, listOf("stat", "-c", "%s:%Y", source)) as? AdbResult.Success)?.text()?.trim().orEmpty()
            val cacheKey = CacheKey(key, pkg, code, updated, sdk, source, stamp)
            val cached = certificateCache[cacheKey] ?: readVerifiedCertificate(cacheKey)
            val certificate = cached ?: readCertificate(serial, source, sdk).also {
                saveVerifiedCertificate(cacheKey, it)
            }
            if (cached != null) onEvent("复用已校验的游戏签名：$pkg")
            certificate.also {
                certificateCache.keys.removeIf { old -> old.deviceKey == key && old.packageName == pkg }
                certificateCache[cacheKey] = it
            }
            InstalledPackage(pkg, name, code, certificate, updated)
        }
        return DeviceIdentity(key, brand, model, release, sdk, abis, androidId, maxOf(size.first, size.second), minOf(size.first, size.second), packages, System.currentTimeMillis())
    }

    private fun readVerifiedCertificate(key: CacheKey): String? = runCatching {
        if (!key.persistent) return null
        val file = verifiedCacheDirectory.resolve("${key.fingerprint}.json")
        if (!Files.isRegularFile(file) || Files.size(file) > 2048) return null
        val value = Json.parseToJsonElement(Files.readString(file)).jsonObject
        value["certificateMd5"]?.jsonPrimitive?.contentOrNull
            ?.takeIf { value["fingerprint"]?.jsonPrimitive?.contentOrNull == key.fingerprint && it.matches(Regex("[0-9a-f]{32}")) }
    }.getOrNull()

    private fun saveVerifiedCertificate(key: CacheKey, certificate: String) {
        if (!key.persistent) return
        runCatching {
            val directory = verifiedCacheDirectory
            Files.createDirectories(directory)
            val temporary = Files.createTempFile(directory, "verified-", ".writing")
            try {
                Files.writeString(temporary, buildJsonObject { put("fingerprint", key.fingerprint); put("certificateMd5", certificate) }.toString())
                val target = directory.resolve("${key.fingerprint}.json")
                try { Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
                catch (_: AtomicMoveNotSupportedException) { Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING) }
            } finally { Files.deleteIfExists(temporary) }
        } // A cache write failure cannot turn a verified package into a connection failure.
    }

    private suspend fun readCertificate(serial: String, source: String, sdk: Int): String {
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
