package com.docscanner.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.docscanner.data.local.dao.TagAutomationDao
import com.docscanner.data.local.dao.TagDao
import com.docscanner.data.local.entity.TagAutomationEntity
import com.docscanner.data.local.entity.TagEntity

@Database(
    entities = [TagEntity::class, TagAutomationEntity::class],
    version = 5,
    exportSchema = false,
)
abstract class DocScannerDatabase : RoomDatabase() {
    abstract fun tagDao(): TagDao
    abstract fun tagAutomationDao(): TagAutomationDao
}
