package com.picpocket.app.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.picpocket.app.drive.sync.RetryHandler
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
 *
 * An explicitly triggered sync is always meant to run NOW, so the retry
 * backoff (exponential sleep before each sync after failures) is reset first;
 * otherwise a backoff sleep would hold `isSyncing` and silently swallow the
 * broadcast ("already syncing"). Debug-only: production syncs keep their
 * backoff semantics.
 */
class TestSyncTriggerReceiver : BroadcastReceiver() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface SyncManagerEntryPoint {
        fun syncManager(): SyncManager
        fun retryHandler(): RetryHandler
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_SYNC_NOW) return
        val entryPoint = EntryPointAccessors
            .fromApplication(context.applicationContext, SyncManagerEntryPoint::class.java)
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            entryPoint.retryHandler().reset()
            entryPoint.syncManager().performSync()
        }
    }

    companion object {
        const val ACTION_SYNC_NOW = "com.picpocket.app.action.SYNC_NOW"
    }
}