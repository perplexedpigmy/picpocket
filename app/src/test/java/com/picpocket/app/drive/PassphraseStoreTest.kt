package com.picpocket.app.drive

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PassphraseStoreTest {

    private lateinit var store: PassphraseStore

    @Before
    fun setUp() {
        store = PassphraseStore(ApplicationProvider.getApplicationContext())
        store.clearPassphrase()
    }

    @Test
    fun `getPassphrase returns null when never stored`() {
        assertNull(store.getPassphrase())
    }

    @Test
    fun `hasPassphrase returns false when never stored`() {
        assertFalse(store.hasPassphrase())
    }

    @Test
    fun `save and retrieve passphrase`() {
        store.savePassphrase("my-secret-passphrase")
        assertTrue(store.hasPassphrase())
        assertEquals("my-secret-passphrase", store.getPassphrase())
    }

    @Test
    fun `clearPassphrase removes stored passphrase`() {
        store.savePassphrase("test")
        store.clearPassphrase()
        assertNull(store.getPassphrase())
        assertFalse(store.hasPassphrase())
    }

    @Test
    fun `save and retrieve empty passphrase`() {
        store.savePassphrase("")
        assertEquals("", store.getPassphrase())
    }
}
