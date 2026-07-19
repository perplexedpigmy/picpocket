package com.docscanner.drive

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import javax.crypto.AEADBadTagException
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FileEncryptorTest {

    private lateinit var encryptor: FileEncryptor
    private lateinit var manager: EncryptionManager
    private val testPassphrase = "test-passphrase-123"

    @Before
    fun setUp() {
        manager = EncryptionManager()
        encryptor = FileEncryptor(manager)
    }

    @Test
    fun `encrypt returns same data when no passphrase set`() {
        val data = "hello".toByteArray()
        val result = encryptor.encrypt(data)
        assertArrayEquals(data, result)
    }

    @Test
    fun `decrypt returns same data when no passphrase set`() {
        val data = "hello".toByteArray()
        val result = encryptor.decrypt(data)
        assertArrayEquals(data, result)
    }

    @Test
    fun `encrypt round-trip with passphrase`() {
        manager.setPassphrase(testPassphrase)
        val original = "Hello, World!".toByteArray()
        val encrypted = encryptor.encrypt(original)
        assertNotEquals(original, encrypted)
        val decrypted = encryptor.decrypt(encrypted)
        assertArrayEquals(original, decrypted)
    }

    @Test
    fun `encrypt produces different ciphertext each time for same input`() {
        manager.setPassphrase(testPassphrase)
        val data = "same-data".toByteArray()
        val e1 = encryptor.encrypt(data)
        val e2 = encryptor.encrypt(data)
        assertNotEquals(e1.contentToString(), e2.contentToString())
    }

    @Test
    fun `encrypt round-trip with large data`() {
        manager.setPassphrase(testPassphrase)
        val original = ByteArray(1024 * 1024) { (it % 256).toByte() }
        val encrypted = encryptor.encrypt(original)
        val decrypted = encryptor.decrypt(encrypted)
        assertArrayEquals(original, decrypted)
    }

    @Test(expected = AEADBadTagException::class)
    fun `decrypt throws AEADBadTagException for tampered ciphertext`() {
        manager.setPassphrase(testPassphrase)
        val original = "important-data".toByteArray()
        val encrypted = encryptor.encrypt(original)
        val tampered = encrypted.copyOf().also { it[it.size - 1] = it[it.size - 1].inc() }
        encryptor.decrypt(tampered)
    }

    @Test
    fun `isEncrypted returns false when encryption disabled`() {
        val data = "plaintext".toByteArray()
        assertFalse(encryptor.isEncrypted(data))
    }

    @Test
    fun `isEncrypted returns true for encrypted data with passphrase set`() {
        manager.setPassphrase(testPassphrase)
        val encrypted = encryptor.encrypt("data".toByteArray())
        assertTrue(encryptor.isEncrypted(encrypted))
    }

    @Test
    fun `isEncrypted returns false for short data`() {
        manager.setPassphrase(testPassphrase)
        assertFalse(encryptor.isEncrypted(ByteArray(4)))
    }

    @Test
    fun `isEncrypted returns false after passphrase cleared`() {
        manager.setPassphrase(testPassphrase)
        val encrypted = encryptor.encrypt("data".toByteArray())
        manager.clearPassphrase()
        assertFalse(encryptor.isEncrypted(encrypted))
    }
}
