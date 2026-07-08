package com.docscanner.di

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.docscanner.data.local.DocScannerDatabase
import com.docscanner.data.local.dao.DocumentDao
import com.docscanner.data.local.dao.PageDao
import com.docscanner.data.local.dao.TagAutomationDao
import com.docscanner.data.local.dao.TagDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    private val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS `tag_automations` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `tagId` INTEGER NOT NULL,
                    `type` TEXT NOT NULL,
                    `configJson` TEXT NOT NULL,
                    `triggerEvent` TEXT NOT NULL,
                    FOREIGN KEY (`tagId`) REFERENCES `tags`(`id`) ON DELETE CASCADE
                )
            """)
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_tag_automations_tagId` ON `tag_automations`(`tagId`)")
        }
    }

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): DocScannerDatabase {
        return Room.databaseBuilder(
            context,
            DocScannerDatabase::class.java,
            "docscanner.db",
        ).addMigrations(MIGRATION_3_4).build()
    }

    @Provides
    fun provideDocumentDao(database: DocScannerDatabase): DocumentDao = database.documentDao()

    @Provides
    fun providePageDao(database: DocScannerDatabase): PageDao = database.pageDao()

    @Provides
    fun provideTagDao(database: DocScannerDatabase): TagDao = database.tagDao()

    @Provides
    fun provideTagAutomationDao(database: DocScannerDatabase): TagAutomationDao = database.tagAutomationDao()
}
