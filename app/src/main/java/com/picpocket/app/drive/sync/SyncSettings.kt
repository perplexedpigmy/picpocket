package com.picpocket.app.drive.sync

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SyncSettings @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences("sync_settings", 0)

    var syncEnabled: Boolean
        get() = prefs.getBoolean(KEY_SYNC_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_SYNC_ENABLED, value).apply()

    var syncIntervalHours: Int
        get() = prefs.getInt(KEY_SYNC_INTERVAL, 1)
        set(value) = prefs.edit().putInt(KEY_SYNC_INTERVAL, value).apply()

    companion object {
        private const val KEY_SYNC_ENABLED = "sync_enabled"
        private const val KEY_SYNC_INTERVAL = "sync_interval_hours"
    }
}
