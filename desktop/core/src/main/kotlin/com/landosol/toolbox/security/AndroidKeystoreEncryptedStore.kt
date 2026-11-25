package com.landosol.toolbox.security
import android.content.Context
import android.util.Base64
import com.landosol.toolbox.desktop.shared.DesktopBindings
import com.landosol.toolbox.desktop.shared.SecretUnavailableException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal class AndroidKeystoreEncryptedStore(context: Context, private val storageName: String) {
    private val preferences = context.applicationContext.getSharedPreferences(storageName, Context.MODE_PRIVATE)
    private val mutex = Mutex()
    private fun entropy(key: String) = "LimingjieAssistant|v1|$storageName|$key".toByteArray(Charsets.UTF_8)
    suspend fun save(key: String, plaintext: ByteArray) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val blob = DesktopBindings.services.secretProtector.protect(plaintext, entropy(key))
            check(preferences.edit().putString(key, "v1:" + Base64.encodeToString(blob, Base64.NO_WRAP)).commit()) { "Encrypted storage failed" }
        }
    }
    suspend fun read(key: String): ByteArray? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val payload = preferences.getString(key, null) ?: return@withLock null
            if (!payload.startsWith("v1:")) throw SecretUnavailableException("凭据格式不受支持，请重新填写该账号的密码")
            DesktopBindings.services.secretProtector.unprotect(Base64.decode(payload.substring(3), Base64.NO_WRAP), entropy(key))
        }
    }
    suspend fun delete(key: String) = withContext(Dispatchers.IO) { mutex.withLock { check(preferences.edit().remove(key).commit()) { "Encrypted value deletion failed" } } }
    suspend fun clearAllForTest() = withContext(Dispatchers.IO) { mutex.withLock { check(preferences.edit().clear().commit()) { "Encrypted storage cleanup failed" } } }
}
