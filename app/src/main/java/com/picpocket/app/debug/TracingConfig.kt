package com.picpocket.app.debug

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TracingConfig @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences("tracing", 0)

    companion object {
        private const val KEY_GLOBAL_ENABLED = "tracing_enabled"
        private const val KEY_OVERRIDE_PREFIX = "tracing_override_"
    }

    val globalEnabled: Boolean
        get() = prefs.getBoolean(KEY_GLOBAL_ENABLED, false)

    fun setGlobalEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_GLOBAL_ENABLED, enabled).apply()
        TracingBuffer.enabled = enabled
    }

    fun getOverrideLevel(category: Category): Level? {
        val ordinal = prefs.getInt(KEY_OVERRIDE_PREFIX + category.name, -1)
        return if (ordinal in Level.entries.indices) Level.entries[ordinal] else null
    }

    fun setOverrideLevel(category: Category, level: Level?) {
        prefs.edit().apply {
            if (level != null) {
                putInt(KEY_OVERRIDE_PREFIX + category.name, level.ordinal)
            } else {
                remove(KEY_OVERRIDE_PREFIX + category.name)
            }
            apply()
        }
    }
}
