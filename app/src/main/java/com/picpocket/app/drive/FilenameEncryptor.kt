package com.picpocket.app.drive

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FilenameEncryptor @Inject constructor(
    private val encryptionManager: EncryptionManager,
) {
    private val mappingCache = mutableMapOf<String, String>()

    fun encrypt(original: String): String {
        val cached = mappingCache[original]
        if (cached != null) return cached
        val encrypted = encryptionManager.encryptFilename(original)
        mappingCache[original] = encrypted
        mappingCache[encrypted] = original
        return encrypted
    }

    fun decrypt(encrypted: String): String? {
        val cached = mappingCache[encrypted]
        if (cached != null) return cached
        val decrypted = encryptionManager.decryptFilename(encrypted)
        if (decrypted != null) {
            mappingCache[decrypted] = encrypted
            mappingCache[encrypted] = decrypted
        }
        return decrypted
    }

    fun clearCache() {
        mappingCache.clear()
    }
}
