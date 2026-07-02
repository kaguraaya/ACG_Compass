package com.acgcompass.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Canonical, locally-normalized ACG work. Single source of truth for the UI
 * (RC.00 local-first). External sources are linked via [SourceLinkEntity] and
 * per-source ratings live in [RatingEntity].
 *
 * Enum-like fields (mediaType, status, completionCostBucket, primarySource) are
 * stored as String to match the ER diagram; enum mapping is done in the mapper.
 */
@Entity(tableName = "works")
data class WorkEntity(
    @PrimaryKey
    val id: String,
    val canonicalTitle: String,
    val titleJa: String?,
    val titleRomaji: String?,
    val titleEn: String?,
    /** D2：中文标题（Bangumi name_cn），用于卡片/列表中文优先展示；可空。v8 新增。 */
    val titleCn: String? = null,
    /** Serialized List<String> via Converters. */
    val aliases: List<String>,
    val mediaType: String,
    val year: Int?,
    val status: String,
    val episodes: Int?,
    val episodeMinutes: Int?,
    val volumes: Int?,
    val estPlayMinutes: Int?,
    val coverUrl: String?,
    val primarySource: String,
    val completionCostBucket: String?,
    /** Work synopsis / 梗概 (e.g. Bangumi `summary`); nullable, shown as「暂无数据」when absent (F7). */
    val summary: String? = null,
    /** I16: precise air/release date (`yyyy-MM-dd`, e.g. Bangumi `date`); nullable. */
    val airDate: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
)
