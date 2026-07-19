package com.docscanner.drive

import androidx.test.core.app.ApplicationProvider
import com.docscanner.util.MainCoroutineRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@ExperimentalCoroutinesApi
class DriveConnectivityCheckerTest {

    @get:Rule
    val coroutineRule = MainCoroutineRule()

    private val checker = DriveConnectivityChecker(
        ApplicationProvider.getApplicationContext(),
    )

    @Test
    fun `checker is not null`() {
        assertNotNull(checker)
    }
}
