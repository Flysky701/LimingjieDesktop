package com.landosol.toolbox.desktop.device

import com.android.apksig.ApkSigner
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class DeviceIdentityReaderTest {
    @get:Rule val directory = TemporaryFolder()
    @Test fun realApkSignatureUsesCertificateDerCachesUpdateKeyAndDeletesPulledApk() = runBlocking {
        // Disposable, locally generated signing identity. No real game APK or credential is used.
        val keyStoreFile = directory.root.toPath().resolve("synthetic.p12")
        val keytool = Path.of(System.getProperty("java.home"), "bin", if (System.getProperty("os.name").startsWith("Windows")) "keytool.exe" else "keytool")
        val process = ProcessBuilder(keytool.toString(), "-genkeypair", "-alias", "fixture", "-keystore", keyStoreFile.toString(), "-storepass", "fixture-only", "-keypass", "fixture-only", "-keyalg", "RSA", "-keysize", "2048", "-dname", "CN=Synthetic Device Fixture", "-validity", "3650").redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        assertEquals(output, 0, process.waitFor())
        val store = KeyStore.getInstance("PKCS12").apply { Files.newInputStream(keyStoreFile).use { load(it, "fixture-only".toCharArray()) } }
        val cert = store.getCertificate("fixture") as X509Certificate
        val expected = MessageDigest.getInstance("MD5").digest(cert.encoded).joinToString("") { "%02x".format(it) }
        val config = ApkSigner.SignerConfig.Builder("fixture", store.getKey("fixture", "fixture-only".toCharArray()) as PrivateKey, listOf(cert)).build()
        val unsigned = directory.root.toPath().resolve("unsigned.apk")
        ZipOutputStream(Files.newOutputStream(unsigned)).use { zip ->
            zip.putNextEntry(ZipEntry("AndroidManifest.xml")); zip.write(binaryManifest()); zip.closeEntry()
            zip.putNextEntry(ZipEntry("classes.dex")); zip.write("synthetic test data, not an installable program".toByteArray()); zip.closeEntry()
        }
        val v23 = directory.root.toPath().resolve("v23.apk")
        ApkSigner.Builder(listOf(config)).setInputApk(unsigned.toFile()).setOutputApk(v23.toFile()).setMinSdkVersion(26)
            .setV1SigningEnabled(false).setV2SigningEnabled(true).setV3SigningEnabled(true).setV4SigningEnabled(false).build().sign()
        val v1 = directory.root.toPath().resolve("v1.apk")
        ApkSigner.Builder(listOf(config)).setInputApk(unsigned.toFile()).setOutputApk(v1.toFile()).setMinSdkVersion(21)
            .setV1SigningEnabled(true).setV2SigningEnabled(false).setV3SigningEnabled(false).setV4SigningEnabled(false).build().sign()
        assertEquals(expected, DeviceIdentityReader.certificateMd5(v23, 28))
        assertEquals(expected, DeviceIdentityReader.certificateMd5(v23, 26))
        assertEquals(expected, DeviceIdentityReader.certificateMd5(v1, 26))
        val fake = FakeAdb(); var updated = "2026-10-25 10:00:00"; var apkStamp = "12345:1761386400"
        fake.handler = { call -> when {
            call.arguments == listOf("pm", "list", "packages") -> FakeAdb.success("package:${FakeAdb.GAME}\npackage:com.unrelated")
            call.arguments == listOf("dumpsys", "package", FakeAdb.GAME) -> FakeAdb.success("versionName=1.0\nversionCode=1 minSdk=26\nlastUpdateTime=$updated")
            call.arguments == listOf("pm", "path", FakeAdb.GAME) -> FakeAdb.success("package:/data/app/fixture/split_config.apk\npackage:/data/app/fixture/base.apk")
            call.arguments.firstOrNull() == "stat" -> FakeAdb.success(apkStamp)
            call.arguments.firstOrNull() == "pull" -> { Files.copy(v23, Path.of(call.arguments[2]), java.nio.file.StandardCopyOption.REPLACE_EXISTING); FakeAdb.success("copied") }
            else -> fake.default(call)
        } }
        val reader = DeviceIdentityReader(fake, directory.root.toPath(), setOf(FakeAdb.GAME))
        repeat(2) { assertEquals(expected, reader.read("fixture", 1920 to 1080).packages[FakeAdb.GAME]!!.certificateMd5) }
        assertEquals(1, fake.calls.count { it.arguments.firstOrNull() == "pull" })
        // A recreated worker must reuse the previous verification, not pull the APK again.
        DeviceIdentityReader(fake, directory.root.toPath().resolve("new-worker"), setOf(FakeAdb.GAME),
            verifiedCacheDirectory = directory.root.toPath().resolve("cache/verified-packages")).read("fixture", 1920 to 1080)
        assertEquals(1, fake.calls.count { it.arguments.firstOrNull() == "pull" })
        assertTrue(fake.calls.filter { it.arguments.firstOrNull() == "pull" }.all { it.arguments[1].endsWith("/base.apk") })
        updated = "2026-10-25 11:00:00"
        reader.read("fixture", 1920 to 1080)
        assertEquals(2, fake.calls.count { it.arguments.firstOrNull() == "pull" })
        apkStamp = "12346:1761390000"
        DeviceIdentityReader(fake, directory.root.toPath(), setOf(FakeAdb.GAME)).read("fixture", 1920 to 1080)
        assertEquals(3, fake.calls.count { it.arguments.firstOrNull() == "pull" })
        Files.list(directory.root.toPath().resolve("cache/verified-packages")).use { files ->
            files.forEach { Files.writeString(it, "corrupt-cache") }
        }
        DeviceIdentityReader(fake, directory.root.toPath(), setOf(FakeAdb.GAME)).read("fixture", 1920 to 1080)
        assertEquals(4, fake.calls.count { it.arguments.firstOrNull() == "pull" })
        Files.list(directory.root.toPath().resolve("cache/apk")).use { assertEquals(0L, it.count()) }
    }

    /** Minimal valid Android binary manifest, with only manifest/package strings. */
    private fun binaryManifest(): ByteArray {
        val strings = listOf("manifest", "package", "com.example.synthetic")
        val encoded = strings.map { byteArrayOf(it.length.toByte(), it.length.toByte()) + it.toByteArray() + byteArrayOf(0) }
        val stringBytes = encoded.sumOf { it.size }; val poolSize = (28 + strings.size * 4 + stringBytes + 3) / 4 * 4
        val buffer = ByteBuffer.allocate(8 + poolSize + 56 + 24).order(ByteOrder.LITTLE_ENDIAN)
        fun chunk(type: Int, headerSize: Int, size: Int) { buffer.putShort(type.toShort()); buffer.putShort(headerSize.toShort()); buffer.putInt(size) }
        chunk(3, 8, buffer.capacity())
        chunk(1, 28, poolSize); buffer.putInt(strings.size); buffer.putInt(0); buffer.putInt(0x100); buffer.putInt(28 + strings.size * 4); buffer.putInt(0)
        var offset = 0; encoded.forEach { buffer.putInt(offset); offset += it.size }; encoded.forEach { buffer.put(it) }
        while (buffer.position() < 8 + poolSize) buffer.put(0)
        chunk(0x0102, 16, 56); buffer.putInt(1); buffer.putInt(-1); buffer.putInt(-1); buffer.putInt(0)
        buffer.putShort(20); buffer.putShort(20); buffer.putShort(1); repeat(3) { buffer.putShort(0) }
        buffer.putInt(-1); buffer.putInt(1); buffer.putInt(2); buffer.putShort(8); buffer.put(0); buffer.put(3); buffer.putInt(2)
        chunk(0x0103, 16, 24); buffer.putInt(1); buffer.putInt(-1); buffer.putInt(-1); buffer.putInt(0)
        return buffer.array()
    }
}
