package com.docscanner.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.docscanner.data.local.entity.TagEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TagDao {

    @Query("SELECT * FROM tags ORDER BY name COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<TagEntity>>

    @Query("SELECT * FROM tags WHERE name LIKE '%' || :query || '%' ORDER BY name COLLATE NOCASE ASC")
    fun search(query: String): Flow<List<TagEntity>>

    @Query("SELECT * FROM tags WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<Long>): List<TagEntity>

    @Query("SELECT * FROM tags")
    suspend fun getAll(): List<TagEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(tag: TagEntity): Long

    @Update
    suspend fun update(tag: TagEntity)

    @Query("DELETE FROM tags WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Query("SELECT COALESCE(MAX(colorIndex), -1) FROM tags")
    suspend fun getMaxColorIndex(): Int
}
