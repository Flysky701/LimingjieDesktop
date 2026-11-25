package com.landosol.toolbox.desktop.device

import com.android.apksig.ApkSigner
import kotlinx.coroutines.*
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.HexFormat
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.security.auth.x500.X500Principal

class DeviceIdentityReaderTest {
    @get:Rule val directory = TemporaryFolder()
    @Test fun slowApkTransferVerifiesSignatureCachesUpdateKeyAndDeletesPulledApk() = runTest {
        // Disposable, locally generated signing identity. No real game APK or credential is used.
        val config = fixtureSigner()
        val expected = MessageDigest.getInstance("MD5").digest(config.certificates.single().encoded).joinToString("") { "%02x".format(it) }
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
            call.arguments.firstOrNull() == "pull" -> withContext(StandardTestDispatcher(testScheduler)) {
                // Advance virtual time: a healthy transfer may exceed the old one-minute limit.
                try { withTimeout(call.timeoutMillis) { delay(75_000) } }
                catch (_: TimeoutCancellationException) { return@withContext AdbResult.Failure(AdbFailureKind.Timeout, "synthetic slow transfer") }
                Files.copy(v23, Path.of(call.arguments[2]), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                FakeAdb.success("copied")
            }
            else -> fake.default(call)
        } }
        val reader = DeviceIdentityReader(fake, directory.root.toPath(), setOf(FakeAdb.GAME))
        repeat(2) { assertEquals(expected, reader.read("fixture", 1920 to 1080).packages[FakeAdb.GAME]!!.certificateMd5) }
        assertEquals(1, fake.calls.count { it.arguments.firstOrNull() == "pull" })
        val cacheDirectory = directory.root.toPath().resolve("cache/verified-packages")
        Files.list(cacheDirectory).use { files ->
            val entries = files.toList()
            assertEquals("Verification must persist before another worker connects", 1, entries.size)
        }
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

    private fun fixtureSigner(): ApkSigner.SignerConfig {
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val hex = HexFormat.of()
        val algorithm = hex.parseHex("300d06092a864886f70d01010c0500") // sha384WithRSAEncryption
        val versionAndSerial = hex.parseHex("a003020102020101") // X.509 v3, serial 1
        val name = X500Principal("CN=Synthetic Device Fixture").encoded
        val notBefore = der(0x17, "200101000000Z".toByteArray())
        val notAfter = der(0x18, "21200101000000Z".toByteArray())
        val validity = der(0x30, notBefore + notAfter)
        val body = der(0x30, versionAndSerial + algorithm + name + validity + name + keyPair.public.encoded)
        val signed = Signature.getInstance("SHA384withRSA").run {
            initSign(keyPair.private)
            update(body)
            sign()
        }
        val encoded = der(0x30, body + algorithm + der(0x03, byteArrayOf(0) + signed))
        val certificate = CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(encoded)) as X509Certificate
        return ApkSigner.SignerConfig.Builder("fixture", keyPair.private, listOf(certificate)).build()
    }

    private fun der(tag: Int, content: ByteArray): ByteArray {
        val size = content.size
        val length = when {
            size < 128 -> byteArrayOf(size.toByte())
            size < 256 -> byteArrayOf(0x81.toByte(), size.toByte())
            else -> byteArrayOf(0x82.toByte(), (size shr 8).toByte(), size.toByte())
        }
        return byteArrayOf(tag.toByte()) + length + content
    }

    @Test fun failedOrCancelledPullNeverCachesPartialApkAndDeletesTemporaryFile() = runBlocking {
        val fake = FakeAdb()
        var failure = AdbFailureKind.Timeout
        var cancel = false
        fake.handler = { call -> when {
            call.arguments == listOf("pm", "list", "packages") -> FakeAdb.success("package:${FakeAdb.GAME}")
            call.arguments == listOf("dumpsys", "package", FakeAdb.GAME) -> FakeAdb.success("versionName=1.0\nversionCode=1\nlastUpdateTime=2026-10-25 10:00:00")
            call.arguments == listOf("pm", "path", FakeAdb.GAME) -> FakeAdb.success("package:/data/app/fixture/base.apk")
            call.arguments.firstOrNull() == "stat" -> FakeAdb.success("12345:1761386400")
            call.arguments.firstOrNull() == "pull" -> {
                Files.writeString(Path.of(call.arguments[2]), "partial APK")
                if (cancel) throw CancellationException("synthetic cancellation")
                AdbResult.Failure(failure, "synthetic failed transfer")
            }
            else -> fake.default(call)
        } }
        val reader = DeviceIdentityReader(fake, directory.root.toPath(), setOf(FakeAdb.GAME))
        for (kind in listOf(AdbFailureKind.Timeout, AdbFailureKind.DeviceOffline)) {
            failure = kind
            val error = runCatching { reader.read("fixture", 1920 to 1080) }.exceptionOrNull()
            assertEquals(kind, (error as DeviceOperationException).adbFailure)
            Files.list(directory.root.toPath().resolve("cache/apk")).use { assertEquals(0L, it.count()) }
        }
        cancel = true
        assertTrue(runCatching { reader.read("fixture", 1920 to 1080) }.exceptionOrNull() is CancellationException)
        assertEquals(3, fake.calls.count { it.arguments.firstOrNull() == "pull" })
        assertFalse(Files.exists(directory.root.toPath().resolve("cache/verified-packages")))
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
