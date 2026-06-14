package com.acgcompass.domain.model

/**
 * 待补优先级（RC.08）。
 */
enum class Priority {
    HIGH,
    MEDIUM,
    LOW,
    ;

    companion object {
        /** 从持久化字符串解析；未知 / `null` 回退为 [MEDIUM]，保证非空且不崩溃（RC.17.4）。 */
        fun fromStorage(raw: String?): Priority =
            entries.firstOrNull { it.name == raw } ?: MEDIUM
    }
}

/**
 * 待补池记录（RC.08）。[workId] 同时是关联 [Work] 的外键，保证每个作品至多一条待补记录
 * （去重 RC.06.07 / Property 10）。
 *
 * - [moodTags] / [riskTags]：心情 / 风险标签（用于推荐器硬过滤，RC.11）。
 * - [dustDays] / [inDustMuseum]：吃灰天数与「吃灰博物馆」标记（RC.08）。
 */
data class BacklogItem(
    val workId: String,
    val priority: Priority = Priority.MEDIUM,
    val moodTags: List<String> = emptyList(),
    val riskTags: List<String> = emptyList(),
    val note: String? = null,
    val addedAt: Long,
    val dustDays: Int = 0,
    val inDustMuseum: Boolean = false,
)
