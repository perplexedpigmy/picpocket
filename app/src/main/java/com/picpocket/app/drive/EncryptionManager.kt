package com.picpocket.app.drive

import android.util.Base64
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class EncryptionManager @Inject constructor() {

    private var cachedKey: SecretKey? = null
    private var cachedPassphrase: String? = null
    private var cachedSalt: ByteArray? = null

    val isEncryptionEnabled: Boolean
        get() = cachedKey != null

    fun setPassphrase(passphrase: String) {
        cachedPassphrase = if (passphrase.isBlank()) null else passphrase
        if (passphrase.isBlank()) {
            cachedKey = null
            cachedSalt = null
        } else {
            val salt = ByteArray(16)
            SecureRandom().nextBytes(salt)
            cachedSalt = salt
            val keyBytes = deriveKey(passphrase, salt)
            cachedKey = SecretKeySpec(keyBytes, "AES")
        }
    }

    fun clearPassphrase() {
        cachedKey = null
        cachedPassphrase = null
        cachedSalt = null
    }

    fun encrypt(data: ByteArray): ByteArray {
        val key = cachedKey ?: return data
        val salt = cachedSalt ?: return data
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(data)
        return salt + iv + ciphertext
    }

    fun decrypt(data: ByteArray): ByteArray {
        val passphrase = cachedPassphrase ?: return data
        if (data.size < 28) return data
        val salt = data.sliceArray(0..15)
        val body = data.sliceArray(16 until data.size)
        val keyBytes = deriveKey(passphrase, salt)
        val key = SecretKeySpec(keyBytes, "AES")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val iv = body.sliceArray(0..11)
        val ciphertext = body.sliceArray(12 until body.size)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        return cipher.doFinal(ciphertext)
    }

    fun encryptFilename(filename: String): String {
        val key = cachedKey ?: return filename
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        val iv = ByteArray(16)
        SecureRandom().nextBytes(iv)
        cipher.init(Cipher.ENCRYPT_MODE, key, IvParameterSpec(iv))
        val encrypted = cipher.doFinal(filename.toByteArray(Charsets.UTF_8))
        val combined = iv + encrypted
        return Base64.encodeToString(combined, Base64.NO_PADDING or Base64.NO_WRAP)
            .replace('+', '-')
            .replace('/', '_')
    }

    fun decryptFilename(encryptedFilename: String): String? {
        val key = cachedKey ?: return encryptedFilename
        val normalized = encryptedFilename.replace('-', '+').replace('_', '/')
        return try {
            val raw = Base64.decode(normalized, Base64.NO_PADDING or Base64.NO_WRAP)
            if (raw.size < 16) return null
            val iv = raw.sliceArray(0..15)
            val ciphertext = raw.sliceArray(16 until raw.size)
            val cipher = Cipher.getInstance("AES/CTR/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, IvParameterSpec(iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    private fun deriveKey(passphrase: String, salt: ByteArray): ByteArray {
        val params = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withSalt(salt)
            .withParallelism(4)
            .withMemoryAsKB(65536)
            .withIterations(3)
            .build()
        val generator = Argon2BytesGenerator()
        generator.init(params)
        val result = ByteArray(32)
        generator.generateBytes(passphrase.toCharArray(), result)
        return result
    }
}
