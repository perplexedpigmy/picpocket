package com.docscanner.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.docscanner.data.local.dao.DocumentDao
import com.docscanner.data.local.dao.PageDao
import com.docscanner.data.local.dao.TagAutomationDao
import com.docscanner.data.local.dao.TagDao
import com.docscanner.data.local.entity.DocumentEntity
import com.docscanner.data.local.entity.DocumentTagCrossRef
import com.docscanner.data.local.entity.PageEntity
import com.docscanner.data.local.entity.TagAutomationEntity
import com.docscanner.data.local.entity.TagEntity

@Database(
    entities = [DocumentEntity::class, PageEntity::class, TagEntity::class, DocumentTagCrossRef::class, TagAutomationEntity::class],
    version = 4,
    exportSchema = false,
)
abstract class DocScannerDatabase : RoomDatabase() {
    abstract fun documentDao(): DocumentDao
    abstract fun pageDao(): PageDao
    abstract fun tagDao(): TagDao
    abstract fun tagAutomationDao(): TagAutomationDao
}
