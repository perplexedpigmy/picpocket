package com.picpocket.app.drive

import com.picpocket.app.util.MainCoroutineRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@ExperimentalCoroutinesApi
class DriveAuthManagerTest {

    @get:Rule
    val coroutineRule = MainCoroutineRule()

    private val manager = DriveAuthManager()

    @Test
    fun `initial state is disconnected`() {
        assertEquals(DriveAuthState.Disconnected, manager.authState.value)
    }

    @Test
    fun `setConnected transitions to Connected`() {
        manager.setConnected()
        assertEquals(DriveAuthState.Connected, manager.authState.value)
    }

    @Test
    fun `signOut transitions to Disconnected`() {
        manager.setConnected()
        manager.signOut()
        assertEquals(DriveAuthState.Disconnected, manager.authState.value)
    }
}
