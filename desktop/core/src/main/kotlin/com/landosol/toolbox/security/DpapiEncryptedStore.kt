package com.landosol.toolbox.security
import com.landosol.toolbox.core.AppContext
import java.util.Base64
import com.landosol.toolbox.desktop.shared.DesktopBindings
import com.landosol.toolbox.desktop.shared.SecretUnavailableException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal class DpapiEncryptedStore(context: AppContext, private val storageName: String) {
    private val preferences = context.preferences(storageName)
    private val mutex = Mutex()
    private fun entropy(key: String) = "LimingjieAssistant|v1|$storageName|$key".toByteArray(Charsets.UTF_8)
    suspend fun save(key: String, plaintext: ByteArray) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val blob = DesktopBindings.services.secretProtector.protect(plaintext, entropy(key))
            check(preferences.edit().putString(key, "v1:" + Base64.getEncoder().encodeToString(blob)).commit()) { "Encrypted storage failed" }
        }
    }
    suspend fun read(key: String): ByteArray? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val payload = preferences.getString(key, null) ?: return@withLock null
            if (!payload.startsWith("v1:")) throw SecretUnavailableException("凭据格式不受支持，请重新填写该账号的密码")
            DesktopBindings.services.secretProtector.unprotect(Base64.getDecoder().decode(payload.substring(3).filterNot(Char::isWhitespace)), entropy(key))
        }
    }
    suspend fun delete(key: String) = withContext(Dispatchers.IO) { mutex.withLock { check(preferences.edit().remove(key).commit()) { "Encrypted value deletion failed" } } }
    suspend fun clearAllForTest() = withContext(Dispatchers.IO) { mutex.withLock { check(preferences.edit().clear().commit()) { "Encrypted storage cleanup failed" } } }
}
