package com.picpocket.app.debug

data class LogEntry(
    val timestamp: Long,
    val category: Category,
    val level: Level,
    val tag: String,
    val message: String,
    val throwable: Throwable? = null,
)
