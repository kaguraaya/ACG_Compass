package com.acgcompass.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Backlog (待补池) record for a work. The primary key is also the foreign key to
 * [WorkEntity], guaranteeing at most one backlog entry per work (RC.06.07 dedupe
 * / Property 10). [dustDays] and [inDustMuseum] back the "吃灰博物馆" feature.
 *
 * moodTags / riskTags are serialized List<String> via Converters.
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
)
