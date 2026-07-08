package com.docscanner.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.docscanner.data.local.entity.TagAutomationEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TagAutomationDao {

    @Query("SELECT * FROM tag_automations WHERE tagId = :tagId ORDER BY rowid ASC")
    fun observeByTagId(tagId: Long): Flow<List<TagAutomationEntity>>

    @Query("SELECT * FROM tag_automations WHERE tagId IN (:tagIds) ORDER BY rowid ASC")
    fun observeByTagIds(tagIds: List<Long>): Flow<List<TagAutomationEntity>>

    @Query("SELECT * FROM tag_automations WHERE tagId IN (:tagIds) ORDER BY rowid ASC")
    suspend fun getByTagIds(tagIds: List<Long>): List<TagAutomationEntity>

    @Query("SELECT * FROM tag_automations")
    fun observeAll(): Flow<List<TagAutomationEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(automation: TagAutomationEntity): Long

    @Query("DELETE FROM tag_automations WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM tag_automations WHERE tagId = :tagId")
    suspend fun deleteByTagId(tagId: Long)
}
