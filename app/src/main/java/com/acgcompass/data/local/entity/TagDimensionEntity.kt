package com.acgcompass.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * N3 标签分维分类缓存表（v10）：社区标签 → 口味维度（[com.acgcompass.domain.taste.TasteCategory] 的 `key`）。
 *
 * 只缓存本地规则「其余视为题材」兜底的**未知标签**的 AI 分维结果，把它们从笼统 `topic` 细化到
 * `device`/`xp`/`meme`/`source`/`time`/`noise` 等更精确维度；已被本地词典 / 交叉验证命中的标签不入表。
 * 画像构建与评分时把本表读成 `Map<清洗后标签, TasteCategory>` 覆盖兜底分类；表为空 / AI 未配置时完全回退
 * 本地规则（行为与今日一致，不阻塞、不伪造，RC.14.01/03）。
 *
 * - 主键 [tag]：清洗后小写标签（与 `TagClassifier.clean` 口径一致，作稳定缓存键）。
 * - [dimension]：维度 key；[source]：`AI` / `RULE`（预留）；[confidence]：AI 置信度；[updatedAt]：写入时刻。
 */
@Entity(tableName = "tag_dimensions")
data class TagDimensionEntity(
    @PrimaryKey
    val tag: String,
    val dimension: String,
    val source: String,
    val confidence: Float,
    val updatedAt: Long,
)
