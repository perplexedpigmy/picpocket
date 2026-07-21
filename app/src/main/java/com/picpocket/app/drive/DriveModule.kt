package com.picpocket.app.drive

import com.picpocket.app.drive.sync.DefaultSyncScheduler
import com.picpocket.app.drive.sync.SyncScheduler
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DriveModule {

    @Provides
    @Singleton
    fun provideSyncScheduler(impl: DefaultSyncScheduler): SyncScheduler {
        impl.schedulePeriodicSync(1)
        return impl
    }
}
