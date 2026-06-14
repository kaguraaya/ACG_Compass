package com.acgcompass.domain.model

/**
 * 四类 AI / 规则任务类型（RC.14.02）。包含 [UNKNOWN] 以兜底未知持久化值。
 */
enum class AiTaskType {
    SPOILER_RADAR,
    TASTE_PROFILE,
    RECOMMENDER,
    ROUTE_MAP,
    TASTE_MATCH,
    UNKNOWN,
    ;

    companion object {
        /** 从持久化字符串解析；未知 / `null` 回退为 [UNKNOWN]（RC.17.4）。 */
        fun fromStorage(raw: String?): AiTaskType =
            entries.firstOrNull { it.name == raw } ?: UNKNOWN
    }
}

/**
 * 结果生成方（RC.14.07）：AI 生成 vs 本地规则生成。无 AI key 时使用 [RULE] 回退（RC.09.03）。
 */
enum class AiGenerator {
    AI,
    RULE,
    ;

    companion object {
        /** 从持久化字符串解析；未知 / `null` 回退为 [RULE]（保守地视作本地规则，RC.17.4）。 */
        fun fromStorage(raw: String?): AiGenerator =
            entries.firstOrNull { it.name == raw } ?: RULE
    }
}

/**
 * 缓存的 AI / 规则结果（RC.14.07）。
 *
 * - [payloadJson]：结构化输出（固定 schema，RC.14.02）的 JSON 文本。
 * - [confidence]：置信度；低置信不编造（RC.14.03/04）。
 * - [dataSources]：贡献来源标签列表（RC.14 16.7）。
 * - [generator]：AI / RULE 标注（RC.14 16.7）。
 */
data class AiResult(
    val id: String,
    val workId: String,
    val taskType: AiTaskType,
    val generator: AiGenerator,
    val payloadJson: String,
    val confidence: Float,
    val dataSources: List<String> = emptyList(),
    val generatedAt: Long,
)
