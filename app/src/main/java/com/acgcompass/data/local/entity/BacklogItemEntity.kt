package com.acgcompass.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Backlog (待补池) record for a work. The primary key is also the foreign key to
 * [WorkEntity], guaranteeing at most one backlog entry per work (RC.06.07 dedupe
 * / Property 10). [dustDays] and [inDustMuseum] back the "吃灰博物馆" feature.
 *
 * moodTags / riskTags are serialized List<String> via Converters.
 *
 * [prevStatus]（C 轮 / v6）：归档进吃灰馆**之前**的 Bangumi 收藏状态（想看/在看/看过/抛弃等）。
 * 吃灰归档会把状态置为「搁置」（H5：吃灰池 = Bangumi 搁置），此字段记住归档前的原状态，
 * 以便「移出吃灰馆」时忠实还原（修复「移出后仍停留在搁置」）。非吃灰条目为 `null`。
 */
@Entity(tableName = "backlog_items")
data class BacklogItemEntity(
    @PrimaryKey
    val workId: String,
    val priority: String,
    val moodTags: List<String>,
    val riskTags: List<String>,
    val note: String?,
    val addedAt: Long,
    val dustDays: Int,
    val inDustMuseum: Boolean,
    val prevStatus: String? = null,
)
