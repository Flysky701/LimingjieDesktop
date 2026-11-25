package com.landosol.toolbox.security

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InMemoryCredentialStoreTest {
    @Test
    fun `credentials can be replaced and deleted`() = runTest {
        val store = InMemoryCredentialStore()

        store.save("account-1", AccountCredentials("first", "secret-1"))
        store.save("account-1", AccountCredentials("second", "secret-2"))

        assertEquals(AccountCredentials("second", "secret-2"), store.read("account-1"))
        store.delete("account-1")
        assertNull(store.read("account-1"))
    }
}
