package com.picpocket.app.drive

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class EncryptionManagerTest {

    private lateinit var manager: EncryptionManager
    private val testPassphrase = "test-passphrase-123"

    @Before
    fun setUp() {
        manager = EncryptionManager()
    }

    @Test
    fun `initially encryption is disabled`() {
        assertFalse(manager.isEncryptionEnabled)
    }

    @Test
    fun `setPassphrase enables encryption`() {
        manager.setPassphrase(testPassphrase)
        assertTrue(manager.isEncryptionEnabled)
    }

    @Test
    fun `clearPassphrase disables encryption`() {
        manager.setPassphrase(testPassphrase)
        manager.clearPassphrase()
        assertFalse(manager.isEncryptionEnabled)
    }

    @Test
    fun `encrypt returns same data when no passphrase set`() {
        val data = "hello".toByteArray()
        val result = manager.encrypt(data)
        assertArrayEquals(data, result)
    }

    @Test
    fun `decrypt returns same data when no passphrase set`() {
        val data = "hello".toByteArray()
        val result = manager.decrypt(data)
        assertArrayEquals(data, result)
    }

    @Test
    fun `encrypt round-trip with passphrase`() {
        manager.setPassphrase(testPassphrase)
        val original = "Hello, World!".toByteArray()
        val encrypted = manager.encrypt(original)
        assertNotEquals(original, encrypted)
        val decrypted = manager.decrypt(encrypted)
        assertArrayEquals(original, decrypted)
    }

    @Test
    fun `encrypt produces different ciphertext each time for same input`() {
        manager.setPassphrase(testPassphrase)
        val data = "same-data".toByteArray()
        val e1 = manager.encrypt(data)
        val e2 = manager.encrypt(data)
        assertNotEquals(e1.contentToString(), e2.contentToString())
    }

    @Test
    fun `encryptFilename returns original when no passphrase`() {
        val name = "page_001.jpg"
        assertEquals(name, manager.encryptFilename(name))
    }

    @Test
    fun `decryptFilename returns original when no passphrase`() {
        val name = "page_001.jpg"
        assertEquals(name, manager.decryptFilename(name))
    }

    @Test
    fun `encryptFilename round-trip with passphrase`() {
        manager.setPassphrase(testPassphrase)
        val original = "page_001.jpg"
        val encrypted = manager.encryptFilename(original)
        assertNotEquals(original, encrypted)
        val decrypted = manager.decryptFilename(encrypted)
        assertEquals(original, decrypted)
    }

    @Test
    fun `decryptFilename returns null for invalid input`() {
        manager.setPassphrase(testPassphrase)
        val result = manager.decryptFilename("not-valid-base64url!!")
        assertNull(result)
    }

    @Test
    fun `blank passphrase keeps encryption disabled`() {
        manager.setPassphrase("")
        assertFalse(manager.isEncryptionEnabled)
    }
}
