package com.docscanner.di

import android.content.Context
import androidx.room.Room
import com.docscanner.data.local.DocScannerDatabase
import com.docscanner.data.local.dao.DocumentDao
import com.docscanner.data.local.dao.PageDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): DocScannerDatabase {
        return Room.databaseBuilder(
            context,
            DocScannerDatabase::class.java,
            "docscanner.db",
        ).fallbackToDestructiveMigration().build()
    }

    @Provides
    fun provideDocumentDao(database: DocScannerDatabase): DocumentDao = database.documentDao()

    @Provides
    fun providePageDao(database: DocScannerDatabase): PageDao = database.pageDao()
}
