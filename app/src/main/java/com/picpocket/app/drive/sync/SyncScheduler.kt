package com.picpocket.app.drive.sync

interface SyncScheduler {
    fun requestImmediateSync()
    fun schedulePeriodicSync(intervalHours: Int)
    fun cancelPeriodicSync()
}
