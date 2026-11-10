package com.calorieapp.data.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.AEADBadTagException

class SyncCryptoTest {
    private val key = SyncCrypto.newKey()

    @Test fun `new keys are 43 url-safe characters and valid`() {
        assertEquals(43, key.length)
        assertTrue(SyncCrypto.isValidKey(key))
        assertFalse(SyncCrypto.isValidKey(key + " "))
        assertFalse(SyncCrypto.isValidKey("short"))
    }

    @Test fun `same key gives the same account on every phone, different keys different accounts`() {
        assertEquals(SyncCrypto(key).accountId, SyncCrypto(key).accountId)
        assertNotEquals(SyncCrypto(key).accountId, SyncCrypto(SyncCrypto.newKey()).accountId)
        assertEquals(43, SyncCrypto(key).accountId.length)
        assertFalse("the account id must not reveal the key", SyncCrypto(key).accountId.contains(key))
    }

    @Test fun `encrypt then decrypt round-trips, and ciphertext differs each time`() {
        val c = SyncCrypto(key)
        val a = c.encrypt("""{"name":"banana"}""", "meal", "id1")
        val b = c.encrypt("""{"name":"banana"}""", "meal", "id1")
        assertNotEquals(a, b)
        assertEquals("""{"name":"banana"}""", SyncCrypto(key).decrypt(a, "meal", "id1"))
    }

    @Test fun `wrong key or swapped record id is rejected`() {
        val data = SyncCrypto(key).encrypt("secret", "meal", "id1")
        assertThrows(AEADBadTagException::class.java) { SyncCrypto(SyncCrypto.newKey()).decrypt(data, "meal", "id1") }
        assertThrows(AEADBadTagException::class.java) { SyncCrypto(key).decrypt(data, "meal", "id2") }
    }
}
