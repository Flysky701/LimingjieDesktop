package com.landosol.toolbox.security

import android.content.Context
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

class AndroidKeystoreCredentialStore(
    context: Context,
    storageName: String = "account-credentials",
) : CredentialStore {
    private val encryptedStore = AndroidKeystoreEncryptedStore(context, storageName)

    override suspend fun save(key: String, credentials: AccountCredentials) {
        encryptedStore.save(key, encode(credentials))
    }

    override suspend fun read(key: String): AccountCredentials? = encryptedStore.read(key)?.let(::decode)

    override suspend fun delete(key: String) = encryptedStore.delete(key)

    suspend fun clearAllForTest() = encryptedStore.clearAllForTest()

    private fun encode(credentials: AccountCredentials): ByteArray = ByteArrayOutputStream().use { buffer ->
        DataOutputStream(buffer).use { output ->
            output.writeUTF(credentials.loginId)
            output.writeUTF(credentials.password)
        }
        buffer.toByteArray()
    }

    private fun decode(bytes: ByteArray): AccountCredentials = DataInputStream(ByteArrayInputStream(bytes)).use { input ->
        AccountCredentials(input.readUTF(), input.readUTF())
    }
}
