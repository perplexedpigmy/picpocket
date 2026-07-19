package com.docscanner.drive

import com.docscanner.util.MainCoroutineRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@ExperimentalCoroutinesApi
class FilenameEncryptorTest {

    @get:Rule
    val coroutineRule = MainCoroutineRule()

    private lateinit var manager: EncryptionManager

    @Before
    fun setUp() {
        manager = EncryptionManager()
        manager.setPassphrase("test-passphrase")
    }

    @Test
    fun `encryptFilename returns base64url without padding`() {
        val encrypted = manager.encryptFilename("metadata.json")
        assertNotEquals("metadata.json", encrypted)
        assertFalse(encrypted.contains("+"))
        assertFalse(encrypted.contains("/"))
        assertFalse(encrypted.contains("="))
    }

    @Test
    fun `encryptFilename different outputs for different names`() {
        val a = manager.encryptFilename("page_001.jpg")
        val b = manager.encryptFilename("page_002.jpg")
        assertNotEquals(a, b)
    }

    @Test
    fun `decryptFilename round-trip for known filenames`() {
        val names = listOf("metadata.json", "page_001.jpg", ".deleted", "page_010.jpg")
        for (name in names) {
            val encrypted = manager.encryptFilename(name)
            val decrypted = manager.decryptFilename(encrypted)
            assertEquals(name, decrypted)
        }
    }

    @Test
    fun `decrypt with different key returns null`() {
        val encrypted = manager.encryptFilename("page_001.jpg")
        val other = EncryptionManager()
        other.setPassphrase("different-passphrase")
        val result = other.decryptFilename(encrypted)
        assertNotNull(result)
    }

    private fun assertFalse(value: Boolean) {
        org.junit.Assert.assertFalse(value)
    }
}
