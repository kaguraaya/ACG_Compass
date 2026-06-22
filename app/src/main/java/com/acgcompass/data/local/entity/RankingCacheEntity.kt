package com.acgcompass.data.local.entity

import androidx.room.Entity

/**
 * 榜单结果本地缓存（P2-3，冷启动秒开）。仅缓存「某榜单范围的**有序作品 id**」这一派生顺序——
 * 作品（[WorkEntity]）与评分（[RatingEntity]）本身仍以 Room 为单一可信源，本表只记排名次序，
 * 重建卡片时按 [workId] 回查；作品被清理时该行可安全跳过（容错缓存，不做外键约束）。
 *
 * 由 DataStore Preferences 迁移而来（v4→v5，B-4）：改用结构化 Room 表，便于按位置排序、
 * 整范围事务覆盖写，并新增 [cachedAt] 以支持后续的新鲜度/过期策略。
 *
 * @property scopeKey 榜单范围稳定键（如 `OVERALL`/`YEAR`/`SEASON`），与 [position] 组成复合主键。
 * @property position 该范围内的 0 基排名次序（升序即真实榜单顺序）。
 * @property workId 该名次对应的本地 [WorkEntity] id。
 * @property cachedAt 本行写入时间（毫秒），供新鲜度判断 / 过期清理。
 */
@Entity(
    tableName = "ranking_cache",
    primaryKeys = ["scopeKey", "position"],
)
data class RankingCacheEntity(
    val scopeKey: String,
    val position: Int,
    val workId: String,
    val cachedAt: Long,
)
