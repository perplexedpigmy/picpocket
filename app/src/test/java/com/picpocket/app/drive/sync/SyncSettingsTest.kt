package com.picpocket.app.drive.sync

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SyncSettingsTest {

    private lateinit var settings: SyncSettings

    @Before
    fun setUp() {
        settings = SyncSettings(ApplicationProvider.getApplicationContext())
    }

    @Test
    fun `syncEnabled defaults to false`() {
        assertFalse(settings.syncEnabled)
    }

    @Test
    fun `set syncEnabled to false`() {
        settings.syncEnabled = false
        assertFalse(settings.syncEnabled)
    }

    @Test
    fun `set syncEnabled to true`() {
        settings.syncEnabled = false
        settings.syncEnabled = true
        assertTrue(settings.syncEnabled)
    }

    @Test
    fun `syncIntervalHours defaults to 1`() {
        assertEquals(1, settings.syncIntervalHours)
    }

    @Test
    fun `set syncIntervalHours`() {
        settings.syncIntervalHours = 4
        assertEquals(4, settings.syncIntervalHours)
    }
}
