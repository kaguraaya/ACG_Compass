package com.acgcompass.feature.detail

import com.acgcompass.domain.ai.SpoilerRadarResult
import com.acgcompass.domain.model.AiGenerator
import com.acgcompass.domain.model.CollectionState
import com.acgcompass.domain.model.CompletionCost
import com.acgcompass.domain.model.Consensus
import com.acgcompass.domain.model.MediaType
import com.acgcompass.domain.model.RatingAggregate
import com.acgcompass.domain.model.RatingEntry
import com.acgcompass.domain.model.ReleaseStatus
import com.acgcompass.domain.model.SourceId
import com.acgcompass.domain.model.Tag
import com.acgcompass.domain.model.TagCategory
import com.acgcompass.domain.model.TasteProfile
import com.acgcompass.domain.model.Units
import com.acgcompass.domain.model.Work
import com.acgcompass.domain.usecase.AggregateRatingsUseCase
import com.acgcompass.domain.usecase.PersonalTasteScorer
import com.acgcompass.domain.usecase.TasteScore
import kotlin.math.roundToInt

/**
 * 作品详情页（Detail_Screen）的 UI 契约（RC.07.01 / RC.07.02 / RC.07.03）。
 *
 * 把领域层的 [Work] 与 [RatingAggregate] 折叠为表现层可直接渲染的纯数据模型。映射逻辑为
 * **纯函数**（无 Android / IO 依赖），便于单元测试，并集中体现两条核心不变式：
 *
 * - **缺失即标记、绝不伪造**：任一字段 / 平台缺失时显示「[NO_DATA]」而非隐藏整块或编造数值
 *   （RC.01 3.7 / RC.07 9.2 / 9.3）。
 * - **样本不足不下结论**：社区共识为 `null` 时呈现 [ConsensusUiModel.Insufficient]（低置信 / 暂无），
 *   不给出确定性的客观结论（RC.07 9.4）。
 *
 * 个人区（RC.07.04）、决策区（RC.07.05）与详情 Tab（RC.07.06/07.07）由 task 19.2 / 19.3 实现。
 * 本契约覆盖顶部信息区、评分区 / 社区共识卡、个人区、决策区、详情 Tab 与补完成本分类。
 */
data class DetailUiState(
    val header: DetailHeader,
    val ratings: List<PlatformRatingUiModel>,
    val consensus: ConsensusUiModel,
    val personal: PersonalUiModel,
    val decision: DecisionUiModel,
    val tabs: List<DetailTabUiModel>,
    val completionCost: CompletionCostUiModel,
    /** H：结构化观看路线（可点击作品行）。 */
    val routeEntries: List<RouteEntryUi> = emptyList(),
    /** P2-4：Bangumi 社区标签（按热度序），用于详情页「标签」区展示。空表示暂无、不展示该区。 */
    val tags: List<String> = emptyList(),
)

/**
 * 顶部信息区（RC.07.01）：封面、主标题（中文 / 规范化名）与信息行集合
 * （原名 / 别名 / 类型 / 年份 / 状态 / 集数·卷数·游玩时长）。
 */
data class DetailHeader(
    val coverUrl: String?,
    val title: String,
    val infoRows: List<DetailInfoRow>,
)

/**
 * 顶部信息区的一行键值。[isMissing] 为 `true` 时 [value] 即「[NO_DATA]」，UI 据此弱化展示
 * （RC.07.01 缺失即标记）。
 */
data class DetailInfoRow(
    val label: String,
    val value: String,
    val isMissing: Boolean,
)

/**
 * 单平台评分（RC.07.02）。[scoreText] 为 `null` 表示该平台「暂无数据」，UI 仍渲染该行而**不隐藏**
 * 整个评分区（RC.07.03 partial-missing）。
 *
 * @property platform 平台展示名（如「Bangumi」「MAL·Jikan」）。
 * @property scoreText 评分文案（已按平台量纲格式化）；缺失时为 `null`。
 * @property voteText 评分人数文案；缺失时为 `null`。
 */
data class PlatformRatingUiModel(
    val platform: String,
    val scoreText: String?,
    val voteText: String?,
) {
    /** 该平台是否有有效评分；为 `false` 时 UI 显示「[NO_DATA]」。 */
    val available: Boolean get() = scoreText != null
}

/**
 * 社区共识卡（RC.07.03 / 9.4）。样本充足时呈现稳定度 / 争议度 / 优先级；不足时为 [Insufficient]，
 * 不伪造客观结论。
 */
sealed interface ConsensusUiModel {

    /** 样本充足，给出三项共识指标（均为已格式化的展示文案）。 */
    data class Available(
        val stability: ConsensusMetric,
        val controversy: ConsensusMetric,
        val priority: ConsensusMetric,
    ) : ConsensusUiModel

    /** 有效样本不足，低置信 / 暂无共识（不下结论）。 */
    data object Insufficient : ConsensusUiModel
}

/**
 * 单项共识指标：百分比文案 + 定性描述 + 归一化到 `[0,1]` 的进度值（供进度条渲染）。
 */
data class ConsensusMetric(
    val label: String,
    val percentText: String,
    val qualitativeText: String,
    val fraction: Float,
)

/**
 * 个人区（RC.07.04 / 9.5）：我的状态 / 评分 / 进度 / 短评 / 标签 + 待补池开关。
 *
 * 各文本字段缺失时为 `null`，UI 渲染「[NO_DATA]」占位（绝不伪造个人记录，RC.17.4）。
 * [inBacklog] 决定「加入 / 移出待补池」按钮的形态（RC.07.04）。
 *
 * @property statusText 我的收藏 / 观看状态（如 想看 / 在看 / 看过）。
 * @property ratingText 我的评分文案（如「8 / 10」）。
 * @property progressText 我的进度文案（如「已看 12 集」）。
 * @property reviewText 我的短评。
 * @property tags 我的标签 / 作品标签名集合（可空）。
 * @property inBacklog 该作品是否已在待补池。
 * @property inDustMuseum 该作品是否已落入吃灰区（待补池中长期未动的分区，F4）。
 * @property inLibrary 该作品是否已纳入我的库（有任意个人收藏状态记录即视为在库，F4）。
 */
data class PersonalUiModel(
    val statusText: String?,
    val ratingText: String?,
    val progressText: String?,
    val reviewText: String?,
    val tags: List<String>,
    val inBacklog: Boolean,
    val inDustMuseum: Boolean = false,
    val inLibrary: Boolean = false,
    /** I6：作品总集数（用于进度编辑显示「共 N 话」并限制上限）；缺失为 null。 */
    val totalEpisodes: Int? = null,
    /** M5：进度是否可编辑/上传——仅动画（书籍/漫画/游戏不显示进度）。 */
    val progressEditable: Boolean = true,
    /** N16：是否 Bangumi 关联（有 Bangumi 词条/评分）。否则只展示「加入待补池」，不展示我的状态/评分。 */
    val recordEditable: Boolean = true,
)

/**
 * 决策区（RC.07.05 / 9.6）：口味匹配度、推荐 / 不推荐理由、无剧透评价雷达入口、适合心情与补完成本。
 *
 * @property tasteMatch 口味匹配度（来自口味画像；未生成画像时为 [TasteMatchUiModel.Unavailable]）。
 * @property recommendReasons 推荐理由（基于已有信号派生，可空集合）。
 * @property notRecommendReasons 不推荐 / 需注意理由（可空集合）。
 * @property spoilerRadarSummary 无剧透评价雷达摘要；未接入时为 [NO_DATA]（保留入口）。
 * @property suitableMoods 适合心情标签（来自作品 MOOD 标签）。
 * @property completionCostText 补完成本一句话摘要。
 */
data class DecisionUiModel(
    val tasteMatch: TasteMatchUiModel,
    val recommendReasons: List<String>,
    val notRecommendReasons: List<String>,
    val spoilerRadarSummary: String,
    val suitableMoods: List<String>,
    val completionCostText: String,
    /** 雷达生成方式标注：「AI 总结」/「规则生成」；未生成时为 `null`（RC.09.04）。 */
    val spoilerRadarGenerator: String? = null,
    /** 实际生效剧透等级标签（当前恒为「无剧透」）；未生成时为 `null`（RC.09.05）。 */
    val spoilerRadarLevel: String? = null,
    /** 摘要来源标注（短评 / Reviews / 标签 / AI）；未生成时为空（RC.09.06）。 */
    val spoilerRadarSources: List<String> = emptyList(),
    /** 雷达置信度文案；低样本时偏低，未生成时为 `null`（不伪造，RC.09.03）。 */
    val spoilerRadarConfidenceText: String? = null,
    /** F8：补番优先级等级（低/中/高）；无足够信号时为 `null`（不伪造）。 */
    val priorityLevel: String? = null,
    /** F8：补番优先级理由（综合口味匹配/社区评价/补完成本/情绪风险）。 */
    val priorityReason: String? = null,
)

/**
 * 口味匹配度（RC.07.05 / RC.10.03）。措辞采用「可能 / 倾向于」而非绝对结论；未生成口味画像时
 * 为 [Unavailable]，不伪造匹配（RC.10.03 / RC.17.4）。
 */
sealed interface TasteMatchUiModel {

    /**
     * 已有口味画像，给出非绝对措辞的匹配度。
     *
     * @property percentText 匹配度百分比文案。
     * @property qualitativeText 定性描述（如「可能会喜欢」）。
     * @property fraction 归一化 `[0,1]` 进度值（供进度条渲染）。
     * @property reason 匹配理由（基于高分标签重合等，非绝对措辞）。
     */
    data class Available(
        val percentText: String,
        val qualitativeText: String,
        val fraction: Float,
        val reason: String,
    ) : TasteMatchUiModel

    /** 尚未生成口味画像，或样本/标签不足以计算匹配度（[message] 说明具体原因，不伪造）。 */
    data class Unavailable(val message: String) : TasteMatchUiModel
}

/**
 * 详情 Tab 的单页（RC.07.06 / 9.7）：简介 / 评论摘要 / 角色·Staff / 关联作品 / 观看路线 / 平台数据 / 我的记录。
 *
 * 尚未接入数据源的 Tab 以 [body] = [NO_DATA] 占位（绝不伪造，RC.07 9.3）。
 *
 * @property title Tab 标题。
 * @property body Tab 正文（缺失时为「暂无数据」）。
 * @property available 该 Tab 是否有可展示内容。
 */
data class DetailTabUiModel(
    val title: String,
    val body: String,
) {
    /** 是否有有效内容（非「暂无数据」占位）。 */
    val available: Boolean get() = body != NO_DATA
}

/**
 * 补完成本分类（RC.07.07 / 9.8）：按集数 × 单集时长 / 卷数 / 估计游玩时长归类为「今晚 / 周末 / 长期坑」。
 *
 * 无足够单位信息计算时 [available] 为 `false`，[bucketLabel] / [detailText] 为「[NO_DATA]」。
 *
 * @property available 是否成功计算出分类。
 * @property bucketLabel 分类标签（今晚可看完 / 周末补完 / 长期坑）。
 * @property detailText 估算明细（如「预计约 11 小时」）。
 */
data class CompletionCostUiModel(
    val available: Boolean,
    val bucketLabel: String,
    val detailText: String,
)

/** 字段 / 平台缺失时统一展示的文案（RC.01 3.7 / RC.07 9.3）。 */
const val NO_DATA: String = "暂无数据"

/**
 * F7：详情页「角色·Staff / 关联作品 / 观看路线」的预渲染正文（由 ViewModel 从各源端点拉取后折叠为
 * 可读文本，保持本契约纯函数、不依赖数据层 DTO）。缺失时为 [NO_DATA]，绝不伪造。
 */
data class DetailExtras(
    val charactersStaffBody: String = NO_DATA,
    val relationsBody: String = NO_DATA,
    val watchRouteBody: String = NO_DATA,
    /** H：结构化观看路线（可点击作品行，按主线/可选/可跳过分区）。空表示无关联或路线待确认。 */
    val routeEntries: List<RouteEntryUi> = emptyList(),
    /** M1（L5）：真实他人短评展示行（「昵称 · N分：短评」），空表示暂无。 */
    val comments: List<String> = emptyList(),
    /** P2-4：Bangumi 社区标签（按热度序，最多 15 个），用于详情页「标签」区展示。空表示暂无。 */
    val tags: List<String> = emptyList(),
)

/** H/G14：观看路线分区。MAIN/OPTIONAL/SKIPPABLE 仅限动画；DERIVED 为非动画衍生作/原作（游戏/画集/广播剧等）。 */
enum class RouteSection { MAIN, OPTIONAL, SKIPPABLE, DERIVED }

/** H：观看路线单条（可点击进入该作品详情）。 */
data class RouteEntryUi(
    val workId: String,
    val title: String,
    val relationLabel: String,
    val section: RouteSection,
)

/**
 * E：「AI 分析匹配度」按钮的 UI 状态（RC.10.03 / RC.14）。
 *
 * 点击后经 [AiEngine] 运行 [com.acgcompass.domain.ai.AiTask.TasteMatch]，结果结构化为
 * matchScore / likedReasons / riskReasons / confidence；失败或未配置时回退本地模型并说明原因，
 * 页面不得无响应。绝不伪造（RC.17.4）。
 */
sealed interface AiMatchUi {

    /** 初始 / 未触发：仅展示「AI 分析匹配度」按钮。 */
    data object Idle : AiMatchUi

    /** 分析中：展示 loading / 思考中。 */
    data object Loading : AiMatchUi

    /** 未配置 AI：提示可用本地模型估计（[message]）。 */
    data class NotConfigured(val message: String) : AiMatchUi

    /**
     * 分析成功：结构化匹配度结果。
     *
     * @property matchPercentText 匹配度百分比文案（如「78%」）。
     * @property fraction 归一化 `[0,1]`（供进度条）。
     * @property likedReasons 可能喜欢的理由。
     * @property riskReasons 可能不喜欢 / 需注意的理由。
     * @property confidenceText 置信度文案。
     * @property dataSources 贡献数据来源标签。
     */
    data class Result(
        val matchPercentText: String,
        val fraction: Float,
        val likedReasons: List<String>,
        val riskReasons: List<String>,
        val confidenceText: String,
        val dataSources: List<String>,
    ) : AiMatchUi

    /** 失败回退：展示失败原因 + 已回退本地模型说明（[message]）。 */
    data class Error(val message: String) : AiMatchUi
}

/** 观看路线无法从关联资料可靠推导时的提示文案（绝不编造顺序，RC.12.05 / Property 15）。 */
const val ROUTE_UNCONFIRMED: String = "路线待确认：关联资料不足，暂不推荐确定观看顺序（不编造）。"

/**
 * 角色署名（F7「角色 · Staff」Tab）：角色名 + 关系（主角 / 配角…）+ 声优 CV。
 * 缺失字段为 `null`，渲染时省略而非伪造。
 */
data class CharacterCredit(
    val name: String,
    val role: String?,
    val cv: String?,
)

/** Staff 署名（F7「角色 · Staff」Tab）：人物名 + 职位（导演 / 原作…）。 */
data class StaffCredit(
    val name: String,
    val job: String?,
)

/** 关联作品（F7「关联作品 / 观看路线」Tab）：关系类型 + 标题。 */
data class RelatedWork(
    val relation: String?,
    val title: String,
)

/**
 * 详情页关联内容容器（F7）：从主源（Bangumi）拉取的角色 / Staff / 关联作品。
 * 拉取失败或缺失时为空集合 → 对应 Tab 显示「暂无数据」，绝不伪造（RC.01 3.7）。
 */
data class DetailRelatedContent(
    val characters: List<CharacterCredit> = emptyList(),
    val staff: List<StaffCredit> = emptyList(),
    val relations: List<RelatedWork> = emptyList(),
)

// region 领域 → UI 映射（纯函数）

/**
 * 把 [Work]、[RatingAggregate]、待补状态、个人记录与口味画像折叠为 [DetailUiState]（纯函数）。
 *
 * @param work 作品（来自 `WorkRepository.observeWork`）。
 * @param ratings 评分聚合（来自 `WorkRepository.aggregateRatings`）；缺失时四平台「暂无数据」、
 *   共识 [ConsensusUiModel.Insufficient]。
 * @param inBacklog 该作品是否已在待补池（决定加入 / 移出按钮形态，RC.07.04）。
 * @param collectionState 个人记录（我的状态 / 评分 / 短评 / 进度）；`null` / 缺失字段显示「暂无数据」。
 * @param tasteProfile 口味画像；`null` 时口味匹配度为 [TasteMatchUiModel.Unavailable]（不伪造，RC.10.03）。
 */
fun buildDetailUiState(
    work: Work,
    ratings: RatingAggregate?,
    inBacklog: Boolean = false,
    collectionState: CollectionState? = null,
    tasteProfile: TasteProfile? = null,
    radar: SpoilerRadarResult? = null,
    inDustMuseum: Boolean = false,
    extras: DetailExtras = DetailExtras(),
): DetailUiState {
    val consensus = ratings?.consensus.toConsensusUiModel()
    val tasteMatch = work.toTasteMatch(tasteProfile, ratings)
    val completionCost = work.toCompletionCostUiModel()
    // N16：是否 Bangumi 关联——主源为 Bangumi 或聚合中存在 Bangumi 评分。否则我的记录不可编辑/同步。
    val bangumiBacked = work.primarySource == SourceId.BANGUMI ||
        ratings?.perSource?.get(SourceId.BANGUMI) != null
    val personal = work.toPersonalUiModel(collectionState, inBacklog, inDustMuseum, bangumiBacked)
    return DetailUiState(
        header = work.toHeader(),
        ratings = ratings.toPlatformRatings(),
        consensus = consensus,
        personal = personal,
        decision = work.toDecisionUiModel(consensus, tasteMatch, completionCost, radar),
        tabs = work.toTabs(ratings, personal, extras, radar),
        completionCost = completionCost,
        routeEntries = extras.routeEntries,
        // P2-4：优先用 loadExtras 拉取的 Bangumi 社区标签；首帧未就绪时回退作品自带标签
        //（Bangumi 主源作品 work.tags 即社区标签），都空则不展示「标签」区。
        tags = extras.tags.ifEmpty { work.tags.map { it.name }.filter { it.isNotBlank() } },
    )
}

private fun Work.toHeader(): DetailHeader {
    val rows = buildList {
        add(infoRow(label = "原名", value = titles.originalName()))
        add(infoRow(label = "别名", value = titles.aliases.takeIf { it.isNotEmpty() }?.joinToString("、")))
        add(DetailInfoRow(label = "类型", value = mediaType.label(), isMissing = false))
        add(infoRow(label = "年份", value = year?.toString()))
        // I16：开播 / 发行日期，精确到天（yyyy-MM-dd）；缺失显示「暂无数据」。
        add(infoRow(label = "开播日期", value = airDate))
        add(statusRow(status))
        add(infoRow(label = "集数 · 卷数 · 游玩时长", value = units.describe(mediaType)))
    }
    return DetailHeader(
        coverUrl = coverUrl,
        title = titles.canonical,
        infoRows = rows,
    )
}

/** 原文名优先级：日文 → 罗马音 → 英文（RC.07.01）。 */
private fun com.acgcompass.domain.model.Titles.originalName(): String? =
    ja ?: romaji ?: en

private fun infoRow(label: String, value: String?): DetailInfoRow =
    if (value.isNullOrBlank()) {
        DetailInfoRow(label = label, value = NO_DATA, isMissing = true)
    } else {
        DetailInfoRow(label = label, value = value, isMissing = false)
    }

private fun statusRow(status: ReleaseStatus): DetailInfoRow {
    val label = "状态"
    return when (status) {
        ReleaseStatus.UNKNOWN -> DetailInfoRow(label, NO_DATA, isMissing = true)
        ReleaseStatus.NOT_RELEASED -> DetailInfoRow(label, "未发布", isMissing = false)
        ReleaseStatus.RELEASING -> DetailInfoRow(label, "连载 / 放送中", isMissing = false)
        ReleaseStatus.FINISHED -> DetailInfoRow(label, "已完结", isMissing = false)
        ReleaseStatus.ON_HIATUS -> DetailInfoRow(label, "休刊 / 暂停", isMissing = false)
        ReleaseStatus.CANCELLED -> DetailInfoRow(label, "已取消", isMissing = false)
    }
}

private fun MediaType.label(): String = when (this) {
    MediaType.ANIME -> "动画"
    MediaType.MANGA -> "漫画"
    MediaType.NOVEL -> "小说"
    MediaType.GAME -> "游戏"
    MediaType.VN -> "视觉小说"
}

/** 体量 / 时长描述（按媒介各取所需）；全部缺失返回 `null`（→「暂无数据」）。 */
private fun com.acgcompass.domain.model.Units.describe(mediaType: MediaType): String? {
    val parts = buildList {
        episodes?.let { add("$it 集") }
        episodeMinutes?.let { add("单集约 $it 分钟") }
        volumes?.let { add("$it 卷") }
        estPlayMinutes?.let { add("预计游玩约 ${(it / 60f).roundToInt()} 小时") }
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

/**
 * 评分区四个平台分组（固定顺序，RC.07.02）：Bangumi、AniList、MAL·Jikan、VNDB。
 * MAL·Jikan 合并为一项：优先 MAL 官方，缺失则回退 Jikan；两者皆缺显示「暂无数据」。
 */
private fun RatingAggregate?.toPlatformRatings(): List<PlatformRatingUiModel> {
    val perSource = this?.perSource ?: emptyMap()
    return listOf(
        platformRating("Bangumi", perSource.validEntry(SourceId.BANGUMI)),
        platformRating("AniList", perSource.validEntry(SourceId.ANILIST)),
        platformRating(
            "MAL·Jikan",
            perSource.validEntry(SourceId.MAL) ?: perSource.validEntry(SourceId.JIKAN),
        ),
        platformRating("VNDB", perSource.validEntry(SourceId.VNDB)),
    )
}

/** 取该源的有效评分；缺失 / 无效（0 / 负 / NaN / 越界）返回 `null`（不参与展示且不回填）。 */
private fun Map<SourceId, RatingEntry?>.validEntry(source: SourceId): SourcedRating? {
    val entry = this[source] ?: return null
    if (!AggregateRatingsUseCase.isValidScore(source, entry.score)) return null
    return SourcedRating(source, entry)
}

private fun platformRating(platform: String, rating: SourcedRating?): PlatformRatingUiModel =
    if (rating == null) {
        PlatformRatingUiModel(platform = platform, scoreText = null, voteText = null)
    } else {
        PlatformRatingUiModel(
            platform = platform,
            scoreText = formatScore(rating.source, rating.entry.score),
            voteText = rating.entry.voteCount
                .takeIf { it > 0 }
                ?.let { "%,d 人评分".format(it) },
        )
    }

/** 按源量纲格式化分数并带上分母（R77）：Bangumi/Jikan/MAL 为 x/10，AniList/VNDB 为 x/100。 */
private fun formatScore(source: SourceId, score: Float): String = when (source) {
    SourceId.BANGUMI, SourceId.JIKAN, SourceId.MAL -> "%.1f/10".format(score)
    SourceId.ANILIST, SourceId.VNDB -> "${score.roundToInt()}/100"
}

private fun Consensus?.toConsensusUiModel(): ConsensusUiModel =
    if (this == null) {
        ConsensusUiModel.Insufficient
    } else {
        ConsensusUiModel.Available(
            stability = metric(
                label = "评分稳定度",
                fraction = stability,
                high = "较稳定",
                mid = "一般",
                low = "分歧较大",
            ),
            controversy = metric(
                label = "争议程度",
                fraction = controversy,
                high = "争议很大",
                mid = "存在分歧",
                low = "争议较小",
            ),
            priority = metric(
                label = "补番优先级",
                fraction = priority,
                high = "建议优先",
                mid = "中等",
                low = "可往后排",
            ),
        )
    }

private fun metric(
    label: String,
    fraction: Float,
    high: String,
    mid: String,
    low: String,
): ConsensusMetric {
    val clamped = fraction.coerceIn(0f, 1f)
    val qualitative = when {
        clamped >= 0.66f -> high
        clamped >= 0.33f -> mid
        else -> low
    }
    return ConsensusMetric(
        label = label,
        percentText = "${(clamped * 100).roundToInt()}%",
        qualitativeText = qualitative,
        fraction = clamped,
    )
}

/** 内部：携带来源的有效评分，用于按源量纲格式化分数。 */
private data class SourcedRating(val source: SourceId, val entry: RatingEntry)

// endregion

// region 个人区 / 决策区 / 详情 Tab / 补完成本（RC.07.04–07.07，纯函数）

/** 个人区（RC.07.04）：从本地个人记录折叠；缺失字段保持 `null` → UI 显示「暂无数据」。 */
private fun Work.toPersonalUiModel(
    collectionState: CollectionState?,
    inBacklog: Boolean,
    inDustMuseum: Boolean = false,
    bangumiBacked: Boolean = true,
): PersonalUiModel = PersonalUiModel(
    statusText = collectionState?.status?.takeIf { it.isNotBlank() },
    ratingText = collectionState?.rating?.let { "$it / 10" },
    progressText = collectionState?.progress?.let { progressLabel(it) },
    reviewText = collectionState?.shortReview?.takeIf { it.isNotBlank() },
    tags = tags.map { it.name },
    inBacklog = inBacklog,
    inDustMuseum = inDustMuseum,
    // 我的库归属（F4）：有任意个人收藏记录（状态/评分/进度/短评其一）即视为已入库。
    inLibrary = collectionState != null && (
        !collectionState.status.isNullOrBlank() ||
            collectionState.rating != null ||
            collectionState.progress != null ||
            !collectionState.shortReview.isNullOrBlank()
        ),
    totalEpisodes = units.episodes?.takeIf { it > 0 },
    progressEditable = mediaType == MediaType.ANIME,
    recordEditable = bangumiBacked,
)

/** 进度文案按媒介类型选择量词（动画/漫画/小说=集·话，其余统一「进度」）。 */
private fun Work.progressLabel(progress: Int): String = when (mediaType) {
    MediaType.ANIME -> "已看 $progress 集"
    MediaType.MANGA, MediaType.NOVEL -> "已读 $progress 话"
    MediaType.GAME, MediaType.VN -> "进度 $progress"
}

/** 决策区（RC.07.05）：综合口味匹配、社区共识与补完成本派生推荐 / 不推荐理由与适合心情。 */
private fun Work.toDecisionUiModel(
    consensus: ConsensusUiModel,
    tasteMatch: TasteMatchUiModel,
    completionCost: CompletionCostUiModel,
    radar: SpoilerRadarResult?,
): DecisionUiModel {
    val recommend = buildList {
        if (tasteMatch is TasteMatchUiModel.Available && tasteMatch.fraction >= 0.66f) {
            add("口味匹配度较高，可能合你的胃口")
        }
        if (consensus is ConsensusUiModel.Available && consensus.priority.fraction >= 0.66f) {
            add("社区补番优先级较高")
        }
        if (completionCost.available && completionCost.bucketLabel == "今晚可看完") {
            add("篇幅短，今晚即可看完")
        }
        // I14：用雷达好评点补充推荐理由（基于评论/标签分析，无剧透）。
        radar?.output?.pros?.filter { it.isNotBlank() && !it.startsWith("（") }?.take(2)?.forEach { add(it) }
        // N10：社区共识较稳 → 口碑较稳定（最小数据兜底）。
        if (consensus is ConsensusUiModel.Available && consensus.stability.fraction >= 0.6f) {
            add("社区评分较稳定，口碑分歧较小")
        }
    }.distinct().ifEmpty {
        // N10：完全无上述信号时，用作品题材标签给出可参考的推荐线索（不伪造结论）。
        val genreTags = tags
            .filter { it.category == TagCategory.CONTENT || it.category == TagCategory.MOOD }
            .map(Tag::name).distinct().take(3)
        if (genreTags.isNotEmpty()) {
            listOf("题材偏向：${genreTags.joinToString("、")}，可据此判断是否合口味")
        } else {
            emptyList()
        }
    }
    val notRecommend = buildList {
        if (tasteMatch is TasteMatchUiModel.Available && tasteMatch.fraction < 0.33f) {
            add("口味匹配度较低，可能不太合胃口")
        }
        if (consensus is ConsensusUiModel.Available && consensus.controversy.fraction >= 0.66f) {
            add("社区争议较大，口碑分歧明显")
        }
        if (completionCost.available && completionCost.bucketLabel == "长期坑") {
            add("体量较大，需要长期投入")
        }
        // I14：情绪风险标签 → 需注意；雷达争议/雷点补充。
        tags.filter { it.category == TagCategory.RISK }.map(Tag::name).take(2)
            .forEach { add("注意：$it") }
        radar?.output?.controversies?.filter { it.isNotBlank() && !it.startsWith("（") }?.take(1)?.forEach { add(it) }
        radar?.output?.pitfalls?.filter { it.isNotBlank() && !it.startsWith("（") }?.take(1)?.forEach { add(it) }
    }.distinct()
    // I14：适合心情——优先 MOOD 标签；缺失时从内容标签按关键词推断；仍空则直接展示高频社区标签（Q10）。
    val moodTags = tags.filter { it.category == TagCategory.MOOD }.map(Tag::name)
    val moods = (moodTags + inferMoodsFromTags(tags)).distinct().ifEmpty {
        tags.filter { it.category == TagCategory.CONTENT }.map(Tag::name).distinct().take(6)
    }
    val (priorityLevel, priorityReason) = computeBacklogPriority(tasteMatch, consensus, completionCost, tags)
    return DecisionUiModel(
        tasteMatch = tasteMatch,
        recommendReasons = recommend,
        notRecommendReasons = notRecommend,
        // 无剧透评价雷达（RC.09.04/05/06）：已生成时展示无剧透摘要、生成方式、剧透等级与来源标注；
        // 未生成（如配置 AI 但成本未确认）时仍标记「暂无数据」，绝不伪造（RC.07 9.3）。
        spoilerRadarSummary = radar.toRadarSummary(),
        suitableMoods = moods,
        completionCostText = if (completionCost.available) {
            "${completionCost.bucketLabel} · ${completionCost.detailText}"
        } else {
            NO_DATA
        },
        spoilerRadarGenerator = radar?.let {
            if (it.generator == AiGenerator.AI) "AI 总结" else "规则生成"
        },
        spoilerRadarLevel = radar?.effectiveLevel?.label,
        spoilerRadarSources = radar?.summarySources?.map { it.label }.orEmpty(),
        spoilerRadarConfidenceText = radar?.let {
            "置信度 ${(it.confidence * 100).roundToInt()}%"
        },
        priorityLevel = priorityLevel,
        priorityReason = priorityReason,
    )
}

/**
 * F8：补番优先级模型（可解释）。综合口味匹配 + 社区共识优先级 + 补完成本 + 情绪风险标签，
 * 输出 低/中/高 + 理由。无任何有效信号（无口味、无社区共识）时返回 (null, null)，不伪造。
 */
private fun computeBacklogPriority(
    tasteMatch: TasteMatchUiModel,
    consensus: ConsensusUiModel,
    completionCost: CompletionCostUiModel,
    tags: List<Tag>,
): Pair<String?, String?> {
    var weighted = 0f
    var weight = 0f
    val reasons = mutableListOf<String>()

    if (tasteMatch is TasteMatchUiModel.Available) {
        weighted += tasteMatch.fraction * 0.4f
        weight += 0.4f
        if (tasteMatch.fraction >= 0.6f) reasons += "口味匹配较高"
    }
    if (consensus is ConsensusUiModel.Available) {
        weighted += consensus.priority.fraction * 0.4f
        weight += 0.4f
        if (consensus.priority.fraction >= 0.6f) reasons += "社区评价较高"
    }
    if (completionCost.available) {
        val costScore = when (completionCost.bucketLabel) {
            "今晚可看完" -> 1f
            "周末补完" -> 0.6f
            else -> 0.25f
        }
        weighted += costScore * 0.2f
        weight += 0.2f
        if (completionCost.bucketLabel == "今晚可看完") reasons += "篇幅短易补完"
        if (completionCost.bucketLabel == "长期坑") reasons += "体量较大需长期投入"
    }
    if (weight <= 0f) return null to null

    var score = (weighted / weight).coerceIn(0f, 1f)
    // 情绪风险标签作为减项（致郁/压抑/高上头等），不归零，仅下调。
    val riskTags = tags.filter { it.category == TagCategory.RISK }.map(Tag::name)
    if (riskTags.isNotEmpty()) {
        score = (score - 0.1f).coerceIn(0f, 1f)
        reasons += "注意情绪风险（${riskTags.take(2).joinToString("、")}）"
    }

    val level = when {
        score >= 0.66f -> "高"
        score >= 0.4f -> "中"
        else -> "低"
    }
    val reason = if (reasons.isEmpty()) "综合口味与社区信号的初步估计，仅供参考" else reasons.joinToString(" · ")
    return level to reason
}

/**
 * I14：从作品内容标签按关键词推断「适合心情」。匹配不到任何关键词时返回空（不伪造）。
 * 关键词 → 心情标签的映射覆盖常见题材/情绪信号。
 */
private fun inferMoodsFromTags(tags: List<Tag>): List<String> {
    val names = tags.map { it.name }
    fun hit(keys: List<String>): Boolean = names.any { n -> keys.any { n.contains(it) } }
    val result = linkedSetOf<String>()

    // L8/L14：强情绪信号（催泪/致郁）优先级最高，先判定，避免被「日常/校园」等中性题材稀释
    // （如 clannad after story 同时含「日常」与「催泪」，应识别为催泪而非日常番）。
    val strongEmotion = hit(listOf("催泪", "感动", "致郁", "虐", "悲剧", "眼泪", "感人"))
    if (strongEmotion) result += "催泪/致郁"

    // 其余题材/情绪信号（顺序即展示优先级）。
    val rules = listOf(
        listOf("热血", "战斗", "运动", "燃") to "热血",
        listOf("恋爱", "爱情", "纯爱", "百合", "后宫") to "恋爱",
        listOf("搞笑", "喜剧", "欢乐", "吐槽") to "搞笑",
        listOf("悬疑", "推理", "烧脑", "悬念", "惊悚", "恐怖") to "悬疑/烧脑",
        listOf("科幻", "未来", "机战", "机甲") to "科幻",
        listOf("奇幻", "魔法", "异世界", "冒险") to "奇幻冒险",
        listOf("音乐", "乐队", "偶像") to "音乐",
        listOf("催眠", "电波", "意识流", "实验") to "电波",
    )
    for ((keys, mood) in rules) {
        if (hit(keys)) result += mood
    }

    // 「治愈/放松」：含明确治愈信号即可；但若仅由「日常/轻松」触发且已判定强情绪，则不加入
    // （强情绪番不应被标为放松向，修复「催泪番被当日常番」）。
    val explicitHealing = hit(listOf("治愈", "温馨"))
    val casualOnly = hit(listOf("日常", "轻松"))
    if (explicitHealing || (casualOnly && !strongEmotion)) result += "治愈/放松"

    return result.toList()
}

/** 把雷达结果折叠为一句话无剧透摘要；空结果或空文本回退「暂无数据」（不伪造，RC.09.03）。 */
private fun SpoilerRadarResult?.toRadarSummary(): String {
    if (this == null) return NO_DATA
    val impression = output.overallImpression.trim()
    val timing = output.watchTiming.trim()
    return when {
        impression.isNotEmpty() && timing.isNotEmpty() -> "$impression（$timing）"
        impression.isNotEmpty() -> impression
        timing.isNotEmpty() -> timing
        else -> NO_DATA
    }
}

/** 文件级单例：个性化口味评分器（无状态、纯函数）。详情页与「今晚看什么」推荐共用同一打分口径。 */
private val personalTasteScorer = PersonalTasteScorer()

/**
 * 口味匹配度（RC.07.05 / RC.10.03）：委托共享 [PersonalTasteScorer]，与推荐器同一口径。
 * 以作品标签与口味画像高/低分标签的**加权重合**为主导（题材标签全权重、年份/厂商等元数据弱化），
 * 社区评分仅作轻量先验（较此前再降权）。无画像 / 数据不足时返回 [TasteMatchUiModel.Unavailable]（不伪造）。
 */
private fun Work.toTasteMatch(tasteProfile: TasteProfile?, ratings: RatingAggregate? = null): TasteMatchUiModel {
    val community10 = ratings.representativeScore10()
    val score = personalTasteScorer.score(this, tasteProfile, community10)
    when (score.basis) {
        TasteScore.Basis.NO_PROFILE ->
            return TasteMatchUiModel.Unavailable("尚未生成口味画像，先在「我的 → 口味画像」从 Bangumi 同步生成")
        TasteScore.Basis.INSUFFICIENT ->
            return TasteMatchUiModel.Unavailable(
                "该作品暂无标签与社区评分，数据不足，无法计算口味匹配度（口味画像已生成）",
            )
        else -> Unit
    }

    val fraction = score.fraction
    val qualitative = when {
        fraction >= 0.7f -> "很可能合你的胃口"
        fraction >= 0.55f -> "可能会喜欢"
        fraction >= 0.4f -> "可能感觉一般"
        else -> "可能不太喜欢"
    }
    // 可解释：优先用「命中的高/低分标签」（长期口味）说明；无标签退化时用社区相对分补一句。
    val baseReason = when {
        score.matchedHighTags.isNotEmpty() ->
            "主要依据你的长期口味：命中你高分作品常见标签「${score.matchedHighTags.take(3).joinToString("、")}」" +
                (if (score.matchedLowTags.isNotEmpty()) "，但也含低分标签「${score.matchedLowTags.take(2).joinToString("、")}」" else "")
        score.matchedLowTags.isNotEmpty() ->
            "含 ${score.matchedLowTags.size} 个你低分作品常见的标签「${score.matchedLowTags.take(3).joinToString("、")}」，可能不太合胃口（负信号已加权）"
        score.basis == TasteScore.Basis.COMMUNITY_FALLBACK && community10 != null ->
            "该作品暂无可匹配标签，参考社区评分 %.1f/10 相对你的口味粗估（仅辅助）".format(community10)
        else -> "与你的口味画像重合较少，仅供参考"
    }
    val reason = if (score.lowConfidence) {
        "$baseReason。注意：你的口味画像样本较少，置信度低，以上仅供参考"
    } else {
        baseReason
    }
    return TasteMatchUiModel.Available(
        percentText = "${(fraction * 100).roundToInt()}%",
        qualitativeText = if (score.lowConfidence) "$qualitative（低置信）" else qualitative,
        fraction = fraction,
        reason = reason,
    )
}

/** 取各源有效评分归一化到 10 分制后的平均值；无任一有效源返回 null。 */
private fun RatingAggregate?.representativeScore10(): Float? {
    val perSource = this?.perSource ?: return null
    val normalized = perSource.mapNotNull { (source, entry) ->
        if (entry == null || !AggregateRatingsUseCase.isValidScore(source, entry.score)) {
            null
        } else {
            when (source) {
                SourceId.BANGUMI, SourceId.JIKAN, SourceId.MAL -> entry.score
                SourceId.ANILIST, SourceId.VNDB -> entry.score / 10f
            }
        }
    }
    return normalized.takeIf { it.isNotEmpty() }?.average()?.toFloat()
}

/** 详情 Tab（RC.07.06）：可填充的从已有数据生成，未接入数据源的 Tab 以「暂无数据」占位。 */
private fun Work.toTabs(
    ratings: RatingAggregate?,
    personal: PersonalUiModel,
    extras: DetailExtras = DetailExtras(),
    radar: SpoilerRadarResult? = null,
): List<DetailTabUiModel> = listOf(
    // F7：简介来自 Work.summary（Bangumi 等主源条目 summary，已入库）；缺失「暂无数据」。
    DetailTabUiModel("简介", summary?.takeIf { it.isNotBlank() } ?: NO_DATA),
    // H9 / M1：评论摘要 = 真实他人短评（next.bgm.tv/p1）+ 无剧透评价雷达折叠的口碑概览；都缺失才「暂无数据」。
    DetailTabUiModel("评论摘要", reviewSummaryWithComments(extras.comments, radar)),
    DetailTabUiModel("角色 · Staff", extras.charactersStaffBody),
    DetailTabUiModel("关联作品", extras.relationsBody),
    // G14：移除重复的「观看路线」文字 Tab——结构化「观看路线（智能选择主线）」区块已单独展示可点击作品行。
    DetailTabUiModel("平台数据", platformDataSummary(ratings)),
    DetailTabUiModel("我的记录", personalRecordSummary(personal)),
)

/**
 * I13（细化 H9 / RC.44）：评论摘要 = 对大众口碑/评论的**内容性**摘要（好评点、争议点、适合人群、观看时机），
 * 来自无剧透评价雷达（AI 或本地规则，基于评论/标签分析），**不展示评分数值**充当评论。
 * 无任何可用的内容性摘要时返回 [NO_DATA]（绝不用评分替代，绝不伪造，RC.44 / RC.09.03）。
 */
/**
 * M1（L5）：评论摘要 = 真实他人短评（最多展示数条）+ 雷达内容性口碑摘要。两者都空才「暂无数据」。
 */
private fun reviewSummaryWithComments(comments: List<String>, radar: SpoilerRadarResult?): String {
    val parts = mutableListOf<String>()
    val realComments = comments.filter { it.isNotBlank() }.take(6)
    if (realComments.isNotEmpty()) {
        parts += "近期短评：\n" + realComments.joinToString("\n") { "· $it" }
    }
    val summary = reviewSummary(radar)
    if (summary != NO_DATA) {
        parts += summary
    }
    return if (parts.isEmpty()) NO_DATA else parts.joinToString("\n\n")
}

private fun reviewSummary(radar: SpoilerRadarResult?): String {
    val sections = mutableListOf<String>()
    val out = radar?.output
    if (out != null) {
        out.overallImpression.trim().takeIf { it.isNotEmpty() }?.let { sections += "总体印象：$it" }
        out.pros.filter { it.isNotBlank() }.takeIf { it.isNotEmpty() }
            ?.let { sections += "好评点：\n" + it.joinToString("\n") { p -> "· $p" } }
        out.controversies.filter { it.isNotBlank() }.takeIf { it.isNotEmpty() }
            ?.let { sections += "争议 / 吐槽点：\n" + it.joinToString("\n") { c -> "· $c" } }
        out.suitableFor.filter { it.isNotBlank() }.takeIf { it.isNotEmpty() }
            ?.let { sections += "适合：" + it.joinToString("、") }
        out.notSuitableFor.filter { it.isNotBlank() }.takeIf { it.isNotEmpty() }
            ?.let { sections += "可能不适合：" + it.joinToString("、") }
        out.watchTiming.trim().takeIf { it.isNotEmpty() }?.let { sections += "观看时机：$it" }
    }
    if (sections.isEmpty()) return NO_DATA
    // 标注来源，避免被误读为逐条原始评论。
    val source = when (radar?.generator) {
        AiGenerator.AI -> "（来源：AI 对评论 / 标签的无剧透分析）"
        else -> "（来源：本地规则汇总，基于社区标签与口碑信号）"
    }
    return sections.joinToString("\n\n") + "\n\n" + source
}

/** 「平台数据」Tab：汇总各平台是否有评分；全部缺失则「暂无数据」。 */
private fun platformDataSummary(ratings: RatingAggregate?): String {
    val rows = ratings.toPlatformRatings()
    val available = rows.filter { it.available }
    if (available.isEmpty()) return NO_DATA
    return available.joinToString("\n") { row ->
        val vote = row.voteText?.let { "（$it）" }.orEmpty()
        "${row.platform}：${row.scoreText}$vote"
    }
}

/** 「我的记录」Tab：汇总个人状态 / 评分 / 进度 / 短评；全部缺失则「暂无数据」。 */
private fun personalRecordSummary(personal: PersonalUiModel): String {
    val parts = buildList {
        personal.statusText?.let { add("状态：$it") }
        personal.ratingText?.let { add("我的评分：$it") }
        personal.progressText?.let { add("进度：$it") }
        personal.reviewText?.let { add("短评：$it") }
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString("\n") ?: NO_DATA
}

/**
 * 补完成本分类 UI（RC.07.07）：优先用 [Work.completionCost]，缺失时由 [computeCompletionCost] 计算。
 */
private fun Work.toCompletionCostUiModel(): CompletionCostUiModel {
    val cost = completionCost ?: computeCompletionCost(units, mediaType)
        ?: return CompletionCostUiModel(available = false, bucketLabel = NO_DATA, detailText = NO_DATA)
    val minutes = estimateTotalMinutes(units, mediaType)
    val detail = minutes
        ?.let { "预计约 ${(it / 60f).roundToInt().coerceAtLeast(1)} 小时" }
        ?: units.describe(mediaType)
        ?: NO_DATA
    return CompletionCostUiModel(
        available = true,
        bucketLabel = cost.bucketLabel(),
        detailText = detail,
    )
}

private fun CompletionCost.bucketLabel(): String = when (this) {
    CompletionCost.TONIGHT -> "今晚可看完"
    CompletionCost.WEEKEND -> "周末补完"
    CompletionCost.LONG_HAUL -> "长期坑"
}

/**
 * **纯函数**：按集数 × 单集时长 / 卷数 / 估计游玩时长计算 [CompletionCost] 分桶（RC.07.07 / 9.8）。
 *
 * 估算口径见 [estimateTotalMinutes]；无足够单位信息时返回 `null`（成本未知，UI 显示「暂无数据」）。
 * 阈值：≤ [TONIGHT_MAX_MINUTES] 今晚、≤ [WEEKEND_MAX_MINUTES] 周末，其余长期坑。
 *
 * @param units 体量 / 时长单位。
 * @param mediaType 媒介类型（决定使用哪些单位）。
 */
fun computeCompletionCost(units: Units, mediaType: MediaType): CompletionCost? {
    val minutes = estimateTotalMinutes(units, mediaType) ?: return null
    return when {
        minutes <= TONIGHT_MAX_MINUTES -> CompletionCost.TONIGHT
        minutes <= WEEKEND_MAX_MINUTES -> CompletionCost.WEEKEND
        else -> CompletionCost.LONG_HAUL
    }
}

/**
 * 估算补完总时长（分钟）。各媒介各取所需，缺失关键单位返回 `null`：
 * - 动画：集数 × 单集时长（单集时长缺失时按 [DEFAULT_EPISODE_MINUTES] 估）。
 * - 游戏 / VN：估计游玩时长。
 * - 漫画 / 小说：卷数 × [MINUTES_PER_VOLUME]。
 */
private fun estimateTotalMinutes(units: Units, mediaType: MediaType): Int? = when (mediaType) {
    MediaType.ANIME ->
        units.episodes?.let { eps -> eps * (units.episodeMinutes ?: DEFAULT_EPISODE_MINUTES) }
    MediaType.GAME, MediaType.VN -> units.estPlayMinutes
    MediaType.MANGA, MediaType.NOVEL -> units.volumes?.let { it * MINUTES_PER_VOLUME }
}

/** 「今晚可看完」上限（分钟）：约一个晚上。 */
private const val TONIGHT_MAX_MINUTES = 180

/** 「周末补完」上限（分钟）：约一个周末（~20 小时）。 */
private const val WEEKEND_MAX_MINUTES = 1_200

/** 单集时长缺省估值（分钟）：常见 TV 动画单集约 24 分钟。 */
private const val DEFAULT_EPISODE_MINUTES = 24

/** 单卷漫画 / 小说阅读时长估值（分钟）。 */
private const val MINUTES_PER_VOLUME = 60

// endregion
