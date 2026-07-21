package com.picpocket.app.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.picpocket.app.data.local.dao.TagAutomationDao
import com.picpocket.app.data.local.dao.TagDao
import com.picpocket.app.data.local.entity.TagAutomationEntity
import com.picpocket.app.data.local.entity.TagEntity

@Database(
    entities = [TagEntity::class, TagAutomationEntity::class],
    version = 5,
    exportSchema = false,
)
abstract class PicPocketDatabase : RoomDatabase() {
    abstract fun tagDao(): TagDao
    abstract fun tagAutomationDao(): TagAutomationDao
}
