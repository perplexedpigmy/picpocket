package com.picpocket.app.drive.sync

import android.content.Context
import com.picpocket.app.util.MainCoroutineRule
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@ExperimentalCoroutinesApi
class SyncMutexTest {

    @get:Rule
    val coroutineRule = MainCoroutineRule()

    private val context = mockk<Context>(relaxed = true)
    private val localDriveIndex = mockk<LocalDriveIndex>()

    private lateinit var mutex: SyncMutex

    @Before
    fun setUp() {
        mutex = SyncMutex(context, localDriveIndex)
    }

    @Test
    fun `acquire returns false when treeUri is blank`() = runTest {
        every { localDriveIndex.getRootTreeUri() } returns ""
        every { localDriveIndex.getLocalDeviceId() } returns "device-1"
        mutex.initialize()
        val result = mutex.acquire()
        assertFalse(result)
    }

    @Test
    fun `acquire returns false when deviceId is blank`() = runTest {
        every { localDriveIndex.getRootTreeUri() } returns "content://tree/primary"
        every { localDriveIndex.getLocalDeviceId() } returns ""
        mutex.initialize()
        val result = mutex.acquire()
        assertFalse(result)
    }

    @Test
    fun `acquire returns false when both treeUri and deviceId are blank`() = runTest {
        every { localDriveIndex.getRootTreeUri() } returns ""
        every { localDriveIndex.getLocalDeviceId() } returns ""
        mutex.initialize()
        val result = mutex.acquire()
        assertFalse(result)
    }

    @Test
    fun `heartbeat does nothing when treeUri is blank`() = runTest {
        every { localDriveIndex.getRootTreeUri() } returns ""
        every { localDriveIndex.getLocalDeviceId() } returns "device-1"
        mutex.initialize()
        mutex.heartbeat()
    }

    @Test
    fun `release does nothing when treeUri is blank`() = runTest {
        every { localDriveIndex.getRootTreeUri() } returns ""
        every { localDriveIndex.getLocalDeviceId() } returns "device-1"
        mutex.initialize()
        mutex.release()
    }

}
