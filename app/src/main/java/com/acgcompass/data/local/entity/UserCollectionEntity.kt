package com.acgcompass.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 用户个人收藏记录（R45）。来自外部源（当前 Bangumi）的「我的」状态 / 评分 / 进度 / 短评 / 标签，
 * 同步入库后供我的页统计、详情页「我的记录」、口味画像、时光机消费。
 *
 * 与社区评分（[RatingEntity]）严格分离：本表是**用户个人数据**，绝不与社区数据混用（RC.07）。
 * 明文凭据绝不入库（RC.00 1.2），本表只存业务数据。
 *
 * @property id 主键：`<source>:<sourceItemId>`（如 `BANGUMI:12345`）。
 * @property source 来源（SourceId 名，如 `BANGUMI`）。
 * @property sourceItemId 源内条目 id（Bangumi subjectId）。
 * @property localWorkId 映射到的本地 [WorkEntity] id（Bangumi 来源即等于 subjectId）。
 * @property status 个人收藏状态：想看 / 在看 / 看过 / 搁置 / 抛弃；缺失为 `null`（不伪造）。
 * @property rating 个人评分（1–10）；缺失 / 未评为 `null`。
 * @property progress 进度（已看集数）；缺失为 `null`。
 * @property comment 个人短评；缺失为 `null`。
 * @property tags 个人标签（经 Converters 序列化）。
 * @property updatedAt 本地写入时间。
 * @property syncedAt 最近一次同步时间。
 * @property sourceUpdatedAt 源侧更新时间（Bangumi `updated_at`），用于冲突判断；缺失为 `null`。
 */
@Entity(
    tableName = "user_collections",
    indices = [Index(value = ["localWorkId"]), Index(value = ["source"])],
)
data class UserCollectionEntity(
    @PrimaryKey
    val id: String,
    val source: String,
    val sourceItemId: String,
    val localWorkId: String,
    val status: String?,
    val rating: Int?,
    val progress: Int?,
    val comment: String?,
    val tags: List<String>,
    val updatedAt: Long,
    val syncedAt: Long,
    val sourceUpdatedAt: String?,
)
