package com.acgcompass.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Cached AI / rule-engine result for a work (RC.14.07). [taskType] is one of the
 * four AI tasks (spoiler radar / taste / recommender / route map); [generator]
 * records AI vs RULE. [payloadJson] holds the structured output; [dataSources]
 * is a String list of contributing source tags.
 */
@Entity(
    tableName = "ai_results",
    indices = [Index(value = ["workId"]), Index(value = ["workId", "taskType"])],
)
data class AiResultEntity(
    @PrimaryKey
    val id: String,
    val workId: String,
    val taskType: String,
    val generator: String,
    val payloadJson: String,
    val confidence: Float,
    val dataSources: String?,
    val generatedAt: Long,
)
