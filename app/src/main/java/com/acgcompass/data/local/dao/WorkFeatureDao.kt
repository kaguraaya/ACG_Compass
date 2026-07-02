package com.acgcompass.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.acgcompass.data.local.entity.WorkFeatureEntity

/**
 * DAO：作品结构化特征缓存（v7 / work_features）。供口味画像构建与今晚推荐读取，写入由
 * `WorkFeatureRepository` 在同步 / 详情拉取后 best-effort 落库。
 */
@Dao
interface WorkFeatureDao {

    @Query("SELECT * FROM work_features WHERE subjectId = :subjectId")
    suspend fun getById(subjectId: String): WorkFeatureEntity?

    @Query("SELECT * FROM work_features WHERE subjectId IN (:subjectIds)")
    suspend fun getByIds(subjectIds: List<String>): List<WorkFeatureEntity>

    @Query("SELECT subjectId FROM work_features")
    suspend fun getAllIds(): List<String>

    @Upsert
    suspend fun upsert(entity: WorkFeatureEntity)

    @Upsert
    suspend fun upsertAll(entities: List<WorkFeatureEntity>)
}
