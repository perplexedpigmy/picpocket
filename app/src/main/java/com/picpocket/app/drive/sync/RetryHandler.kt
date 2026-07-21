package com.picpocket.app.drive.sync

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RetryHandler @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val prefs = context.getSharedPreferences("sync_retry", 0)
    private val baseDelayMs = 30_000L
    private val maxDelayMs = 3_600_000L

    private var consecutiveFailures: Int
        get() = prefs.getInt(KEY_RETRY_COUNT, 0)
        set(value) = prefs.edit().putInt(KEY_RETRY_COUNT, value).apply()

    fun onSuccess() {
        consecutiveFailures = 0
    }

    fun onFailure() {
        consecutiveFailures++
    }

    fun reset() {
        consecutiveFailures = 0
    }

    suspend fun waitBeforeRetry() {
        val count = consecutiveFailures
        if (count == 0) return
        val delayMs = (baseDelayMs * (1L shl (count - 1))).coerceAtMost(maxDelayMs)
        delay(delayMs)
    }

    companion object {
        private const val KEY_RETRY_COUNT = "consecutive_failures"
    }
}
