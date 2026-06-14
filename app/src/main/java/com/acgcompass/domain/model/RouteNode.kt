package com.acgcompass.domain.model

import com.acgcompass.domain.ai.RouteRecommendation

/**
 * 系列关联类型（补番路线图，RC.12.01）：续作 / 前传 / 外传 / OVA / 剧场版 / 总集篇 等。
 *
 * 这是**领域层的权威定义**，用于 [RouteNode.relationType]。枚举名作为持久化字符串
 * （[RouteNodeEntity-like] relationType 列），**不得**随意重命名以保证升级兼容（RC.00 1.8）。
 * 解析未知 / 缺失值时回退为 [OTHER]，避免损坏 / 升级数据导致崩溃（RC.17.4）。
 */
enum class RouteRelationType {
    /** 续作 / 续篇（第二季、后续故事）。 */
    SEQUEL,

    /** 前传（时间线更早的故事）。 */
    PREQUEL,

    /** 外传 / 衍生（番外、衍生作品）。 */
    SIDE_STORY,

    /** OVA / OAD。 */
    OVA,

    /** 剧场版 / 电影。 */
    MOVIE,

    /** 总集篇（回顾向，通常可跳过）。 */
    RECAP,

    /** 其它 / 未分类关联。 */
    OTHER,
    ;

    companion object {
        /** 从持久化字符串解析；未知 / `null` 一律回退为 [OTHER]，保证非空且不崩溃（RC.17.4）。 */
        fun fromStorage(raw: String?): RouteRelationType =
            entries.firstOrNull { it.name == raw } ?: OTHER
    }
}

/**
 * 补番路线图中的单个节点（RC.12 / 设计「Data Models · ROUTE_NODE」）—— 领域层模型，纯 Kotlin。
 *
 * 一个节点把某个系列（[seriesId]）下的关联作品（[workId]）与其关联类型 [relationType]、
 * 观看建议 [recommendation] 关联起来。
 *
 * **顺序留作 task 27.2**：[orderIndex] 与 [confirmed] 仅作为顺序逻辑的**存储钩子**，本任务（27.1，
 * 展示 + 加入待补池数据通路）不据此计算 / 编造推荐观看顺序。当资料不足以确定顺序时，
 * 上层应将 [confirmed] 置为 `false`（「路线待确认」）且不依赖 [orderIndex] 作为权威顺序
 * （RC.12.05 / Property 15，由 task 27.2 落实）。
 *
 * @property id             节点稳定主键（同一系列内唯一）。
 * @property seriesId       所属系列标识。
 * @property workId         节点对应的规范化作品 id（关联 [Work.id]）。
 * @property relationType   关联类型（续作 / 前传 / 外传 / OVA / 剧场版 / 总集篇 等，RC.12.01）。
 * @property recommendation 观看建议：必看 / 可选 / 可跳过 / 总集篇回顾（RC.12.02）。
 * @property orderIndex     建议观看顺序序号（存储钩子）；[confirmed] 为 `false` 时不具约束力。
 * @property confirmed      路线是否已确认（存储钩子）；`false` 表示「路线待确认」，不应据 [orderIndex] 误导用户。
 */
data class RouteNode(
    val id: String,
    val seriesId: String,
    val workId: String,
    val relationType: RouteRelationType,
    val recommendation: RouteRecommendation = RouteRecommendation.OPTIONAL,
    val orderIndex: Int = 0,
    val confirmed: Boolean = false,
)
