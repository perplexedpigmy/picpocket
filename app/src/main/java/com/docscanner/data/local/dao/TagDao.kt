package com.docscanner.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.docscanner.data.local.entity.DocumentTagCrossRef
import com.docscanner.data.local.entity.TagEntity
import kotlinx.coroutines.flow.Flow

data class DocumentTagRow(
    val documentId: Long,
    val tagId: Long,
    val tagName: String,
    val tagColorIndex: Int,
)

@Dao
interface TagDao {

    @Query("SELECT * FROM tags ORDER BY name COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<TagEntity>>

    @Query("""
        SELECT t.* FROM tags t
        INNER JOIN document_tags dt ON t.id = dt.tagId
        WHERE dt.documentId = :documentId
        ORDER BY t.name COLLATE NOCASE ASC
    """)
    fun observeDocumentTags(documentId: Long): Flow<List<TagEntity>>

    @Query("SELECT * FROM tags WHERE name LIKE '%' || :query || '%' ORDER BY name COLLATE NOCASE ASC")
    fun search(query: String): Flow<List<TagEntity>>

    @Query("SELECT * FROM tags WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<Long>): List<TagEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(tag: TagEntity): Long

    @Update
    suspend fun update(tag: TagEntity)

    @Query("DELETE FROM tags WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDocumentTag(crossRef: DocumentTagCrossRef)

    @Delete
    suspend fun deleteDocumentTag(crossRef: DocumentTagCrossRef)

    @Query("DELETE FROM document_tags WHERE documentId = :documentId")
    suspend fun deleteAllDocumentTags(documentId: Long)

    @Query("SELECT COALESCE(MAX(colorIndex), -1) FROM tags")
    suspend fun getMaxColorIndex(): Int

    @Query("""
        SELECT dt.documentId AS documentId, t.id AS tagId, t.name AS tagName, t.colorIndex AS tagColorIndex
        FROM document_tags dt
        INNER JOIN tags t ON t.id = dt.tagId
        ORDER BY t.name ASC
    """)
    fun observeAllDocumentTags(): Flow<List<DocumentTagRow>>
}
