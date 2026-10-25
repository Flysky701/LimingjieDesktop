package com.landosol.toolbox.desktop.shared

import com.landosol.toolbox.protocol.bilibili.CaptchaChallenge
import kotlinx.serialization.Serializable

fun interface OcrPort { fun submit(width: Int, height: Int, pixels: IntArray, onResult: (String?) -> Unit) }
interface SecretProtector {
    fun protect(plaintext: ByteArray, entropy: ByteArray): ByteArray
    fun unprotect(blob: ByteArray, entropy: ByteArray): ByteArray
}
class SecretUnavailableException(message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause)
@Serializable data class GamePackageIdentity(val versionName: String, val versionCode: Long, val certificateMd5: String, val lastUpdateTime: String = "")
@Serializable data class DeviceIdentitySnapshot(
    val deviceKey: String, val brand: String, val model: String, val release: String, val sdkInt: Int,
    val supportedAbis: List<String>, val androidId: String, val displayWidth: Int, val displayHeight: Int,
    val packages: Map<String, GamePackageIdentity>, val capturedAtMillis: Long,
)
interface DeviceIdentityStore { fun current(): DeviceIdentitySnapshot?; fun save(snapshot: DeviceIdentitySnapshot) }
interface CaptchaSession : AutoCloseable { val url: String }
interface CaptchaHost : AutoCloseable {
    fun open(challenge: CaptchaChallenge, onSolved: (String) -> Unit, onError: (String) -> Unit): CaptchaSession
    fun browse(url: String): Boolean
}
data class DesktopServices(
    val ocr: OcrPort, val secretProtector: SecretProtector, val deviceIdentityStore: DeviceIdentityStore,
    val captchaHost: CaptchaHost, val processLogSource: () -> ByteArray,
)
object DesktopBindings {
    @Volatile private var installed: DesktopServices? = null
    @Synchronized fun install(services: DesktopServices) { check(installed == null) { "DesktopBindings already installed" }; installed = services }
    @Synchronized fun release(services: DesktopServices) { if (installed === services) installed = null }
    val services: DesktopServices get() = checkNotNull(installed) { "DesktopBindings not installed" }
    fun identity(): DeviceIdentitySnapshot = services.deviceIdentityStore.current()
        ?: throw IllegalArgumentException("尚未读取模拟器设备信息，请先在『设备』页连接模拟器")
}
