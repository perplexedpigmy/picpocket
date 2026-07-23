package com.picpocket.app.drive

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FileEncryptor @Inject constructor(
    private val encryptionManager: EncryptionManager,
) {
    fun encrypt(data: ByteArray): ByteArray {
        return encryptionManager.encrypt(data)
    }

    fun decrypt(data: ByteArray): ByteArray {
        return encryptionManager.decrypt(data)
    }

    fun isEncrypted(data: ByteArray): Boolean {
        return encryptionManager.isEncrypted(data)
    }
}
