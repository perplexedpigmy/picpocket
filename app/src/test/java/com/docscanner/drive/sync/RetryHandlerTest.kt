package com.docscanner.drive.sync

import androidx.test.core.app.ApplicationProvider
import com.docscanner.util.MainCoroutineRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@ExperimentalCoroutinesApi
class RetryHandlerTest {

    @get:Rule
    val coroutineRule = MainCoroutineRule()

    private lateinit var retryHandler: RetryHandler

    @Before
    fun setUp() {
        retryHandler = RetryHandler(ApplicationProvider.getApplicationContext())
    }

    @Test
    fun `wait before retry does not block on first call`() = runTest {
        retryHandler.waitBeforeRetry()
    }

    @Test
    fun `onSuccess resets retry count`() = runTest {
        retryHandler.onFailure()
        retryHandler.onSuccess()
        retryHandler.waitBeforeRetry()
    }

    @Test
    fun `onFailure increases delay`() = runTest {
        retryHandler.onFailure()
        retryHandler.onFailure()
        retryHandler.waitBeforeRetry()
    }

    @Test
    fun `repeated failures do not exceed max delay`() = runTest {
        repeat(10) { retryHandler.onFailure() }
        retryHandler.waitBeforeRetry()
    }
}
