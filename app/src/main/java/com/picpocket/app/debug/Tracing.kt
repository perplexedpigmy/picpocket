package com.picpocket.app.debug

import android.util.Log as AndroidLog

object Tracing {
    private var config: TracingConfig? = null

    fun initialize(config: TracingConfig) {
        this.config = config
        TracingBuffer.enabled = config.globalEnabled
    }

    private fun effectiveLevel(category: Category): Level =
        config?.getOverrideLevel(category) ?: category.defaultLevel

    private fun shouldEmit(category: Category, level: Level): Boolean {
        if (config == null) return false
        if (!config!!.globalEnabled) return false
        if (!level.meetsMinimum(effectiveLevel(category))) return false
        return true
    }

    fun v(category: Category, tag: String, message: String) {
        if (!shouldEmit(category, Level.VERBOSE)) return
        AndroidLog.v(tag, message)
        TracingBuffer.push(LogEntry(System.currentTimeMillis(), category, Level.VERBOSE, tag, message))
    }

    fun d(category: Category, tag: String, message: String) {
        if (!shouldEmit(category, Level.DEBUG)) return
        AndroidLog.d(tag, message)
        TracingBuffer.push(LogEntry(System.currentTimeMillis(), category, Level.DEBUG, tag, message))
    }

    fun i(category: Category, tag: String, message: String) {
        if (!shouldEmit(category, Level.INFO)) return
        AndroidLog.i(tag, message)
        TracingBuffer.push(LogEntry(System.currentTimeMillis(), category, Level.INFO, tag, message))
    }

    fun w(category: Category, tag: String, message: String) {
        if (!shouldEmit(category, Level.WARN)) return
        AndroidLog.w(tag, message)
        TracingBuffer.push(LogEntry(System.currentTimeMillis(), category, Level.WARN, tag, message))
    }

    fun e(category: Category, tag: String, message: String, throwable: Throwable? = null) {
        if (!shouldEmit(category, Level.ERROR)) return
        AndroidLog.e(tag, message, throwable)
        TracingBuffer.push(LogEntry(System.currentTimeMillis(), category, Level.ERROR, tag, message, throwable))
    }
}
