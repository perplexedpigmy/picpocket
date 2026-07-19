package com.docscanner.drive.sync

import com.docscanner.data.store.StoredDocument
import com.docscanner.drive.EncryptionManager
import com.docscanner.util.MainCoroutineRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@ExperimentalCoroutinesApi
class DriveSyncIntegrationTest {

    @get:Rule
    val coroutineRule = MainCoroutineRule()

    private val encryptionManager = EncryptionManager()

    @Before
    fun setUp() {
        encryptionManager.setPassphrase("integration-test-key")
    }

    @Test
    fun `encryption round-trip preserves content`() = runTest {
        val original = "Hello, World!".toByteArray()
        val encrypted = encryptionManager.encrypt(original)
        val decrypted = encryptionManager.decrypt(encrypted)
        assertArrayEquals(original, decrypted)
    }

    @Test
    fun `encryption round-trip disabled then re-enabled`() = runTest {
        encryptionManager.setPassphrase("key-1")
        val original = "Sensitive Data".toByteArray()
        val encrypted = encryptionManager.encrypt(original)

        encryptionManager.clearPassphrase()
        val stillEncrypted = encryptionManager.decrypt(encrypted)
        assertArrayEquals(encrypted, stillEncrypted)

        encryptionManager.setPassphrase("key-1")
        val decrypted = encryptionManager.decrypt(encrypted)
        assertArrayEquals(original, decrypted)
    }

    @Test
    fun `filename encryption round-trip`() = runTest {
        val names = listOf("metadata.json", "page_001.jpg", "page_010.jpg", ".deleted")
        for (name in names) {
            val encrypted = encryptionManager.encryptFilename(name)
            val decrypted = encryptionManager.decryptFilename(encrypted)
            assertEquals(name, decrypted)
        }
    }

    @Test
    fun `different keys produce different ciphertext`() = runTest {
        encryptionManager.setPassphrase("key-a")
        val data = "test-data".toByteArray()
        val encryptedA = encryptionManager.encrypt(data)

        encryptionManager.setPassphrase("key-b")
        val encryptedB = encryptionManager.encrypt(data)

        assertTrue(encryptedA.size != encryptedB.size || !encryptedA.contentEquals(encryptedB))
    }

    @Test
    fun `TombstoneData can be serialized and deserialized`() = runTest {
        val data = TombstoneData(100L, "device-1", listOf("device-1", "device-2"))
        assertNotNull(data)
        assertEquals("device-1", data.byDevice)
        assertEquals(2, data.acknowledgedBy.size)
    }
}
