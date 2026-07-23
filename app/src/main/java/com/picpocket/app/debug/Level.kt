package com.picpocket.app.debug

enum class Level {
    VERBOSE,
    DEBUG,
    INFO,
    WARN,
    ERROR;

    fun meetsMinimum(threshold: Level): Boolean =
        ordinal >= threshold.ordinal
}
