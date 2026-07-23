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

    private val decryptKeyCache = mutableMapOf<String, SecretKey>()

    private val MAGIC_HEADER = byteArrayOf(0x50, 0x4B, 0x45, 0x31)

    fun isEncrypted(data: ByteArray): Boolean {
        return cachedKey != null && data.size >= MAGIC_HEADER.size && data.copyOf(MAGIC_HEADER.size).contentEquals(MAGIC_HEADER)
    }

    val isEncryptionEnabled: Boolean
        get() = cachedKey != null

    fun setPassphrase(passphrase: String) {
        cachedPassphrase = if (passphrase.isBlank()) null else passphrase
        if (passphrase.isBlank()) {
            cachedKey = null
            cachedSalt = null
            decryptKeyCache.clear()
        } else {
            decryptKeyCache.clear()
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
        decryptKeyCache.clear()
    }

    fun encrypt(data: ByteArray): ByteArray {
        val key = cachedKey ?: return data
        val salt = cachedSalt ?: return data
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(data)
        return MAGIC_HEADER + salt + iv + ciphertext
    }

    fun decrypt(data: ByteArray): ByteArray {
        val passphrase = cachedPassphrase ?: return data
        if (!isEncrypted(data)) return data
        val body = data.sliceArray(MAGIC_HEADER.size until data.size)
        if (body.size < 28) return data
        val salt = body.sliceArray(0..15)
        val rest = body.sliceArray(16 until body.size)
        val saltKey = salt.joinToString("") { "%02x".format(it) }
        val key = decryptKeyCache.getOrPut(saltKey) {
            val keyBytes = deriveKey(passphrase, salt)
            SecretKeySpec(keyBytes, "AES")
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val iv = rest.sliceArray(0..11)
        val ciphertext = rest.sliceArray(12 until rest.size)
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
