package com.acgcompass.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import com.acgcompass.data.local.entity.TagEntity
import kotlinx.coroutines.flow.Flow

/**
 * DAO for the [TagEntity] taxonomy (PRD 第 9 节). The (category, name) pair is
 * unique; [getByCategoryAndName] supports idempotent taxonomy seeding.
 */
@Dao
interface TagDao {

    @Query("SELECT * FROM tags ORDER BY category, name")
    fun observeAll(): Flow<List<TagEntity>>

    /** One-shot snapshot of every tag — used by backup export (RC.16.01). */
    @Query("SELECT * FROM tags")
    suspend fun getAll(): List<TagEntity>

    @Query("SELECT * FROM tags WHERE category = :category ORDER BY name")
    fun observeByCategory(category: String): Flow<List<TagEntity>>

    @Query("SELECT * FROM tags WHERE id = :id")
    suspend fun getById(id: String): TagEntity?

    @Query("SELECT * FROM tags WHERE category = :category AND name = :name LIMIT 1")
    suspend fun getByCategoryAndName(category: String, name: String): TagEntity?

    @Query(
        "SELECT t.* FROM tags t INNER JOIN work_tags wt ON t.id = wt.tagId " +
            "WHERE wt.workId = :workId ORDER BY t.category, t.name",
    )
    fun observeTagsForWork(workId: String): Flow<List<TagEntity>>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(tag: TagEntity)

    @Upsert
    suspend fun upsert(tag: TagEntity)

    @Upsert
    suspend fun upsertAll(tags: List<TagEntity>)

    @Update
    suspend fun update(tag: TagEntity)

    @Delete
    suspend fun delete(tag: TagEntity)

    @Query("DELETE FROM tags WHERE id = :id")
    suspend fun deleteById(id: String)
}
