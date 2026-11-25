package com.landosol.toolbox.desktop.app.adapters.storage

import com.landosol.toolbox.core.config.AtomicConfigStore
import com.landosol.toolbox.core.config.select
import com.landosol.toolbox.desktop.platform.AppConfig
import com.landosol.toolbox.desktop.platform.DesktopSettings
import com.landosol.toolbox.desktop.platform.WindowsDpapi
import com.landosol.toolbox.desktop.shared.AtomicFiles
import com.landosol.toolbox.desktop.shared.DeviceIdentitySnapshot
import com.landosol.toolbox.desktop.shared.DeviceIdentityStore
import com.landosol.toolbox.desktop.shared.SecretProtector
import com.landosol.toolbox.desktop.shared.SecretUnavailableException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

class AppConfigStore(dataDirectory: Path, onRecovered: (Path) -> Unit = {}) {
    private val directory = dataDirectory.resolve("settings")
    private val store = AtomicConfigStore(
        directory.resolve("app.json"), AppConfig.serializer(), ::AppConfig,
        onRecovered = onRecovered,
        migrateLegacy = {
            val environment = AtomicConfigStore.readLegacy(directory.resolve("desktop.json"), onRecovered) {
                legacyJson.decodeFromString(DesktopSettings.serializer(), it)
            }
            val ui = AtomicConfigStore.readLegacy(directory.resolve("ui.json"), onRecovered) {
                legacyJson.decodeFromString(DesktopPreferences.serializer(), it)
            }
            if (environment == null && ui == null) null else AppConfig(
                environment ?: DesktopSettings(), ui ?: DesktopPreferences(), uiPreferencesSaved = ui != null,
            )
        },
    )
    val state = store.state
    internal fun update(change: (AppConfig) -> AppConfig) = store.update(change)

    private companion object {
        val legacyJson = Json { ignoreUnknownKeys = true; coerceInputValues = true }
    }
}

class DesktopSettingsStore(private val app: AppConfigStore) {
    constructor(dataDirectory: Path, onRecovered: (Path) -> Unit = {}) : this(AppConfigStore(dataDirectory, onRecovered))

    val state = app.state.select(AppConfig::environment)
    fun save(settings: DesktopSettings) = update { settings }
    fun update(transform: (DesktopSettings) -> DesktopSettings) {
        app.update { it.copy(environment = transform(it.environment)) }
    }
}

class FileDeviceIdentityStore(dataDirectory: Path, private val onProblem: (String) -> Unit = {}) : DeviceIdentityStore {
    private val directory = dataDirectory.resolve("device")
    private val currentFile = directory.resolve("current.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    @Volatile private var latest: DeviceIdentitySnapshot? = load()
    private fun load(): DeviceIdentitySnapshot? {
        if (!Files.exists(currentFile)) return null
        return try { json.decodeFromString<DeviceIdentitySnapshot>(Files.readString(currentFile)) }
        catch (_: SerializationException) {
            onProblem("已保存的模拟器设备信息无法读取，请重新连接模拟器。")
            null
        }
    }
    override fun current(): DeviceIdentitySnapshot? = latest
    @Synchronized override fun save(snapshot: DeviceIdentitySnapshot) {
        val bytes = json.encodeToString(snapshot).toByteArray(Charsets.UTF_8)
        val safeKey = MessageDigest.getInstance("SHA-256").digest(snapshot.deviceKey.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        AtomicFiles.write(directory.resolve("identity-$safeKey.json"), bytes)
        AtomicFiles.write(currentFile, bytes)
        latest = snapshot
    }
}

class WindowsSecretProtector : SecretProtector {
    override fun protect(plaintext: ByteArray, entropy: ByteArray): ByteArray = try { WindowsDpapi.protect(plaintext, entropy) }
    catch (failure: Exception) { throw SecretUnavailableException(if (WindowsDpapi.available) "凭据加密失败，请检查当前 Windows 用户环境" else "凭据存储仅支持 Windows", failure) }
    override fun unprotect(blob: ByteArray, entropy: ByteArray): ByteArray = try { WindowsDpapi.unprotect(blob, entropy) }
    catch (failure: Exception) { throw SecretUnavailableException(if (WindowsDpapi.available) "凭据无法解密：数据目录可能来自其他电脑或 Windows 用户，请重新填写该账号的密码" else "凭据存储仅支持 Windows", failure) }
}
