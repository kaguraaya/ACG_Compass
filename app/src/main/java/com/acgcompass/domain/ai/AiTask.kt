package com.acgcompass.domain.ai

import com.acgcompass.domain.model.AiTaskType
import kotlinx.serialization.KSerializer

/**
 * provider 无关的「一类 AI 任务」描述（RC.14.02 / design「四类 AI 任务」）。
 *
 * 一个 [AiTask] 把「任务类型 + 已组装的输入文本 + 目标输出类型」打包，供 `AiEngine`（task 23.3）
 * 组装系统提示词与目标 schema、调用 [com.acgcompass.data.remote.ai.AiProvider]、并把返回 JSON
 * 解析为强类型 [T]。
 *
 * 分层说明：本类位于领域层，**只**承载纯数据与 [kotlinx.serialization] 的 [serializer]
 * （纯 Kotlin，不依赖 Android）。具体的「系统提示词模板」与「JSON Schema 主体」属于数据层关注点，
 * 由 `AiEngine` 实现按 [type] / 子类解析（见 `data/remote/ai/prompt`），从而避免 domain → data 的反向依赖。
 *
 * @param T 该任务的固定输出类型（见 `AiOutputs.kt`）。
 * @property type           任务类型（用于缓存键、卡片标识与提示词 / schema 选择）。
 * @property workId         关联作品 id；非作品维度任务（如全局口味画像）可为占位串。
 * @property userContent    已组装好的任务输入文本（标题 / 标签 / 简介 / 评论摘要 / 候选列表等）。
 * @property dataSources    贡献数据来源标签（写入结果用于 AI 卡片展示，RC.14 16.7）。
 * @property serializer     输出类型 [T] 的 [KSerializer]，供引擎解析 JSON。
 * @property requiredFields 输出 JSON **顶层必需键**；引擎据此判断是否缺字段并触发修复二次请求（RC.14.03）。
 */
sealed class AiTask<T>(
    val type: AiTaskType,
    val workId: String,
    val userContent: String,
    val dataSources: List<String>,
    val serializer: KSerializer<T>,
    val requiredFields: List<String>,
) {

    /**
     * 防剧透雷达任务（RC.09 / RC.14.02）。
     *
     * @property summarizable 输入是否可被「仅分析摘要」截断（评论体量大时允许只送摘要，RC.14.05）。
     */
    class SpoilerRadar(
        workId: String,
        content: String,
        dataSources: List<String> = emptyList(),
        val summarizable: Boolean = true,
    ) : AiTask<SpoilerRadarOutput>(
        type = AiTaskType.SPOILER_RADAR,
        workId = workId,
        userContent = content,
        dataSources = dataSources,
        serializer = SpoilerRadarOutput.serializer(),
        // N9：仅要求核心字段「overallImpression」存在即可解析（其余字段均有默认值）。
        // 此前要求全部 9 个顶层字段齐全，模型偶尔省略某字段就判「结构异常」回退规则——放宽以让已配置 AI 真正生效。
        requiredFields = listOf("overallImpression"),
    )

    /**
     * 口味画像任务（RC.10 / RC.14.02）。通常为用户全局画像，[workId] 可为占位串。
     */
    class TasteProfile(
        content: String,
        dataSources: List<String> = emptyList(),
        workId: String = GLOBAL_WORK_ID,
    ) : AiTask<TasteProfileOutput>(
        type = AiTaskType.TASTE_PROFILE,
        workId = workId,
        userContent = content,
        dataSources = dataSources,
        serializer = TasteProfileOutput.serializer(),
        requiredFields = listOf(
            "highScoreTags", "lowScoreTags", "commonReviewWords",
            "droppedTypes", "scoringHabit", "titles", "confidence",
        ),
    )

    /**
     * 今晚推荐任务（RC.11 / RC.14.02）。
     *
     * @property indecisive 「不准纠结」模式：仅产出唯一 `pick`，否则产出 `safe`/`gamble`/`wildcard`（RC.11.05）。
     */
    class TonightRecommender(
        content: String,
        dataSources: List<String> = emptyList(),
        val indecisive: Boolean = false,
        workId: String = GLOBAL_WORK_ID,
    ) : AiTask<TonightRecommenderOutput>(
        type = AiTaskType.RECOMMENDER,
        workId = workId,
        userContent = content,
        dataSources = dataSources,
        serializer = TonightRecommenderOutput.serializer(),
        requiredFields = if (indecisive) {
            listOf("pick", "confidence")
        } else {
            listOf("safe", "gamble", "wildcard", "confidence")
        },
    )

    /**
     * 补番路线图任务（RC.12 / RC.14.02）。
     */
    class RouteMap(
        workId: String,
        content: String,
        dataSources: List<String> = emptyList(),
    ) : AiTask<RouteMapOutput>(
        type = AiTaskType.ROUTE_MAP,
        workId = workId,
        userContent = content,
        dataSources = dataSources,
        serializer = RouteMapOutput.serializer(),
        requiredFields = listOf("nodes", "confidence", "routeConfirmed"),
    )

    companion object {
        /** 非作品维度任务（口味画像 / 推荐）的占位 workId。 */
        const val GLOBAL_WORK_ID: String = "__global__"
    }

    /**
     * 口味匹配任务（E：AI 分析匹配度，RC.10.03 / RC.14.02）。基于作品标签/类型/评分/简介 +
     * 用户口味画像高低分标签，给出匹配度与可解释理由。
     */
    class TasteMatch(
        workId: String,
        content: String,
        dataSources: List<String> = emptyList(),
    ) : AiTask<TasteMatchOutput>(
        type = AiTaskType.TASTE_MATCH,
        workId = workId,
        userContent = content,
        dataSources = dataSources,
        serializer = TasteMatchOutput.serializer(),
        // P0-5：只把 matchScore 设为必需字段。此前要求 likedReasons/riskReasons/confidence 全部存在，
        // 当模型省略空的 riskReasons 或未给 confidence 时，两次解析都判定缺字段 → 低置信兜底「置信度不足，
        // 不展示」。这三者在 TasteMatchOutput 均有安全默认值（空列表 / 0f），改为可选后绝大多数有效响应
        // 都能正常展示；真正损坏的输出仍走低置信兜底（不编造）。
        requiredFields = listOf("matchScore"),
    )

    /**
     * N3 标签分维分类任务：把一批「本地规则兜底为题材」的未知社区标签交 AI 归入更精确的口味维度
     * （见 [com.acgcompass.domain.taste.TasteCategory]）。结果缓存于 `tag_dimensions`，供画像构建 / 评分复用；
     * AI 未配置或失败时调用方回退本地规则（不阻塞、不伪造，RC.14.01/03）。非作品维度任务，[workId] 为占位串。
     *
     * @property tags 本批待分类的原始标签清单（与 [userContent] 列出的顺序一致，供回写核对与缺项兜底）。
     */
    class TagClassify(
        content: String,
        val tags: List<String>,
        dataSources: List<String> = emptyList(),
    ) : AiTask<TagClassifyOutput>(
        type = AiTaskType.TAG_CLASSIFY,
        workId = GLOBAL_WORK_ID,
        userContent = content,
        dataSources = dataSources,
        serializer = TagClassifyOutput.serializer(),
        requiredFields = listOf("items"),
    )
}
