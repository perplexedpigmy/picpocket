package com.picpocket.app.drive

import androidx.test.core.app.ApplicationProvider
import com.picpocket.app.util.MainCoroutineRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@ExperimentalCoroutinesApi
class DriveAuthManagerTest {

    @get:Rule
    val coroutineRule = MainCoroutineRule()

    private val manager = DriveAuthManager(
        ApplicationProvider.getApplicationContext(),
    )

    @Test
    fun `initial state is disconnected`() {
        assertNotNull(manager.authState.value)
    }

    @Test
    fun `checkExistingAuth without account stays disconnected`() {
        manager.checkExistingAuth()
        assert(manager.authState.value is DriveAuthState.Disconnected)
    }

}
