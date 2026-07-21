package com.picpocket.app.drive.sync

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CheckpointStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences("sync_checkpoint", 0)

    var cursor: Int
        get() = prefs.getInt(KEY_CURSOR, 0)
        set(value) = prefs.edit().putInt(KEY_CURSOR, value).apply()

    fun reset() {
        prefs.edit().remove(KEY_CURSOR).apply()
    }

    companion object {
        private const val KEY_CURSOR = "cursor"
    }
}
