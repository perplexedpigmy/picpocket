package com.picpocket.app.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.picpocket.app.drive.sync.SyncManager
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Debug-build-only hook: an `am broadcast` from a test harness triggers a real
 * sync without driving the UI. This lives in the `debug` source set so release
 * builds never ship a trigger channel.
 *
 * Usage from adb:
 *   am broadcast -a com.picpocket.app.action.SYNC_NOW
 *
 * The sync runs off the main thread (same as the scheduler path) and is
 * observable via the SyncManager logcat lines the test suite waits on.
 */
class TestSyncTriggerReceiver : BroadcastReceiver() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface SyncManagerEntryPoint {
        fun syncManager(): SyncManager
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_SYNC_NOW) return
        val syncManager = EntryPointAccessors
            .fromApplication(context.applicationContext, SyncManagerEntryPoint::class.java)
            .syncManager()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            syncManager.performSync()
        }
    }

    companion object {
        const val ACTION_SYNC_NOW = "com.picpocket.app.action.SYNC_NOW"
    }
}