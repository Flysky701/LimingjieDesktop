package com.landosol.toolbox.security

import android.content.Context
import com.landosol.toolbox.protocol.bilibili.SdkSession
import com.landosol.toolbox.protocol.bilibili.SdkSessionStore
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

class InMemorySdkSessionStore : SdkSessionStore {
    private val sessions = mutableMapOf<String, SdkSession>()

    override suspend fun save(key: String, session: SdkSession) {
        sessions[key] = SdkSession(session.uid, session.accessKey, session.updatedAt)
    }

    override suspend fun read(key: String): SdkSession? = sessions[key]?.let {
        SdkSession(it.uid, it.accessKey, it.updatedAt)
    }

    override suspend fun delete(key: String) {
        sessions.remove(key)
    }
}

class AndroidKeystoreSdkSessionStore(
    context: Context,
    storageName: String = "sdk-sessions",
) : SdkSessionStore {
    private val encryptedStore = AndroidKeystoreEncryptedStore(context, storageName)

    override suspend fun save(key: String, session: SdkSession) {
        encryptedStore.save(key, encode(session))
    }

    override suspend fun read(key: String): SdkSession? = encryptedStore.read(key)?.let(::decode)

    override suspend fun delete(key: String) = encryptedStore.delete(key)

    suspend fun clearAllForTest() = encryptedStore.clearAllForTest()

    private fun encode(session: SdkSession): ByteArray = ByteArrayOutputStream().use { buffer ->
        DataOutputStream(buffer).use { output ->
            output.writeUTF(session.uid)
            output.writeUTF(session.accessKey)
            output.writeLong(session.updatedAt)
        }
        buffer.toByteArray()
    }

    private fun decode(bytes: ByteArray): SdkSession = DataInputStream(ByteArrayInputStream(bytes)).use { input ->
        SdkSession(input.readUTF(), input.readUTF(), input.readLong())
    }
}
