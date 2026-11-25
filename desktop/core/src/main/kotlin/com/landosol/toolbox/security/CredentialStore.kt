package com.landosol.toolbox.security

class AccountCredentials(
    val loginId: String,
    val password: String,
) {
    override fun equals(other: Any?): Boolean =
        other is AccountCredentials && loginId == other.loginId && password == other.password

    override fun hashCode(): Int = 31 * loginId.hashCode() + password.hashCode()

    override fun toString(): String = "AccountCredentials(REDACTED)"
}

interface CredentialStore {
    suspend fun save(key: String, credentials: AccountCredentials)
    suspend fun read(key: String): AccountCredentials?
    suspend fun delete(key: String)
}

class InMemoryCredentialStore : CredentialStore {
    private val values = mutableMapOf<String, AccountCredentials>()

    override suspend fun save(key: String, credentials: AccountCredentials) {
        values[key] = AccountCredentials(credentials.loginId, credentials.password)
    }

    override suspend fun read(key: String): AccountCredentials? = values[key]?.let {
        AccountCredentials(it.loginId, it.password)
    }

    override suspend fun delete(key: String) {
        values.remove(key)
    }
}
