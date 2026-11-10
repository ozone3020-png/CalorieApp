package com.calorieapp.data.sync

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Turns a sync key (the user's "account") into:
 * - an account id the server can see (a one-way hash: the server can't recover the key from it), and
 * - an AES-256-GCM key that never leaves the phone. Every record is encrypted with it before upload, with the
 *   record's kind and id as authenticated data, so the server can neither read records nor swap them around.
 */
class SyncCrypto(syncKey: String) {
    val accountId: String = b64url(sha256("calorieapp-sync-account:$syncKey"))
    private val key = SecretKeySpec(sha256("calorieapp-sync-encrypt:$syncKey"), "AES")

    fun encrypt(plaintext: String, kind: String, id: String): String {
        val iv = ByteArray(IV_BYTES).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
            updateAAD("$kind:$id".toByteArray())
        }
        return Base64.getEncoder().encodeToString(iv + cipher.doFinal(plaintext.toByteArray()))
    }

    /** Throws [javax.crypto.AEADBadTagException] if the data was tampered with or the key is wrong. */
    fun decrypt(ciphertext: String, kind: String, id: String): String {
        val bytes = Base64.getDecoder().decode(ciphertext)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, bytes, 0, IV_BYTES))
            updateAAD("$kind:$id".toByteArray())
        }
        return String(cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES))
    }

    companion object {
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_BYTES = 12
        private const val TAG_BITS = 128
        private val random = SecureRandom()

        /** A new sync key: 256 random bits, as 43 URL-safe characters. */
        fun newKey(): String = b64url(ByteArray(32).also { random.nextBytes(it) })

        /** Whether [key] looks like a sync key (so a paste with spaces or a typo is caught early). */
        fun isValidKey(key: String) = Regex("^[A-Za-z0-9_-]{43}$").matches(key)

        private fun sha256(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
        private fun b64url(bytes: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}
