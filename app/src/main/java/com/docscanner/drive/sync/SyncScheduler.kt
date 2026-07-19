package com.docscanner.drive.sync

interface SyncScheduler {
    fun requestImmediateSync()
    fun schedulePeriodicSync(intervalHours: Int)
    fun cancelPeriodicSync()
}
