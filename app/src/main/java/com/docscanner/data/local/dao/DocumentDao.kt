package com.docscanner.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.docscanner.data.local.entity.DocumentEntity
import kotlinx.coroutines.flow.Flow

data class DocumentStats(
    val id: Long,
    val name: String,
    val outputUri: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val pageCount: Int,
    val totalFileSize: Long,
)

@Dao
interface DocumentDao {

    @Query("SELECT * FROM documents ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<DocumentEntity>>

    @Query("""
        SELECT d.id, d.name, d.outputUri, d.createdAt, d.updatedAt,
               (SELECT COUNT(*) FROM pages WHERE documentId = d.id) AS pageCount,
               (SELECT COALESCE(SUM(fileSizeBytes), 0) FROM pages WHERE documentId = d.id) AS totalFileSize
        FROM documents d ORDER BY updatedAt DESC
    """)
    fun observeAllWithStats(): Flow<List<DocumentStats>>

    @Query("""
        SELECT d.id, d.name, d.outputUri, d.createdAt, d.updatedAt,
               (SELECT COUNT(*) FROM pages WHERE documentId = d.id) AS pageCount,
               (SELECT COALESCE(SUM(fileSizeBytes), 0) FROM pages WHERE documentId = d.id) AS totalFileSize
        FROM documents d WHERE d.id = :id
    """)
    fun observeByIdWithStats(id: Long): Flow<DocumentStats?>

    @Query("""
        SELECT d.id, d.name, d.outputUri, d.createdAt, d.updatedAt,
               (SELECT COUNT(*) FROM pages WHERE documentId = d.id) AS pageCount,
               (SELECT COALESCE(SUM(fileSizeBytes), 0) FROM pages WHERE documentId = d.id) AS totalFileSize
        FROM documents d WHERE d.id = :id
    """)
    suspend fun getByIdWithStats(id: Long): DocumentStats?

    @Query("SELECT * FROM documents WHERE name = :name")
    suspend fun findByName(name: String): List<DocumentEntity>

    @Query("SELECT * FROM documents WHERE id = :id")
    suspend fun getById(id: Long): DocumentEntity?

    @Query("SELECT * FROM documents WHERE id = :id")
    fun observeById(id: Long): Flow<DocumentEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(document: DocumentEntity): Long

    @Update
    suspend fun update(document: DocumentEntity)

    @Delete
    suspend fun delete(document: DocumentEntity)

    @Query("DELETE FROM documents WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Query("UPDATE documents SET name = :name, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateName(id: Long, name: String, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE documents SET outputUri = :uri, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateOutputUri(id: Long, uri: String, updatedAt: Long = System.currentTimeMillis())
}
