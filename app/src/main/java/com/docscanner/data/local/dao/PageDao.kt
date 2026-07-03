package com.docscanner.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.docscanner.data.local.entity.PageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PageDao {

    @Query("SELECT * FROM pages WHERE documentId = :documentId ORDER BY pageNumber ASC")
    fun observeByDocumentId(documentId: Long): Flow<List<PageEntity>>

    @Query("SELECT * FROM pages WHERE documentId = :documentId ORDER BY pageNumber ASC")
    suspend fun getByDocumentId(documentId: Long): List<PageEntity>

    @Query("SELECT * FROM pages WHERE id = :id")
    suspend fun getById(id: Long): PageEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(page: PageEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(pages: List<PageEntity>): List<Long>

    @Update
    suspend fun update(page: PageEntity)

    @Delete
    suspend fun delete(page: PageEntity)

    @Query("DELETE FROM pages WHERE documentId = :documentId")
    suspend fun deleteByDocumentId(documentId: Long)

    @Query("SELECT MAX(pageNumber) FROM pages WHERE documentId = :documentId")
    suspend fun maxPageNumber(documentId: Long): Int?
}
