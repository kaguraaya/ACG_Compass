package com.acgcompass.feature.recommender

import com.acgcompass.core.designsystem.WorkCardUiModel
import com.acgcompass.domain.model.BacklogItem
import com.acgcompass.domain.model.CompletionCost
import com.acgcompass.domain.model.MediaType
import com.acgcompass.domain.model.SourceId
import com.acgcompass.domain.model.Work
import com.acgcompass.domain.repository.DrawCriteria

/**
 * 「今晚看什么」推荐器 UI 契约（RC.11.01/02/03/04/08 / Requirements 13.1–13.4, 13.8）。
 *
 * 本文件集中三类纯数据 / 纯函数：
 * 1. 三组输入选项（时间 / 标签 / 接受程度）：时间/接受程度为固定枚举；标签为动态真实 Bangumi 社区标签（P2-5，RC.11.01/02/03）。
 * 2. 三推荐（稳妥 / 赌一把 / 神经病）的种类与展示模型（RC.11.04）。
 * 3. 输入 → [DrawCriteria] 的映射（纯函数，便于单元测试）。底层抽取与硬过滤由
 *    `BacklogRepository.draw` 执行，保证「不推荐已完成、且满足全部硬性过滤」（RC.11.08 / Property 14）。
 *
 * 设计要点：
 * - **硬过滤交给仓库**：时间（可用分钟）、心情命中、风险白名单、未完结排除等硬性条件全部下沉到
 *   `DrawCriteria`，由仓库 `draw` 统一裁剪候选；表现层只负责把用户选择翻译成约束。
 * - **三推荐 = 三次不同约束的抽取**：稳妥严格按用户选择；赌一把放宽风险白名单；神经病忽略心情且
 *   容忍除「明确拒绝」外的全部风险。逐次累积 `excludeWorkIds` 保证三个推荐互不重复。
 * - **绝不伪造**：候选不足时对应种类缺省（ViewModel 给出空态），作品数据缺失时卡片以「暂无数据」兜底。
 *
 * 26.2 的「不准纠结 / 期末周保护 / 深夜提醒」模式仅预留钩子（见 [RecommenderInput] 注释与
 * [AcceptanceOption.NO_UNFINISHED] 的 finalsProtection 复用），完整实现见后续任务。
 */

// region 输入选项（RC.11.01/02/03）

/**
 * 时间预算选择（RC.11.01）：20 分钟 / 1 小时 / 2-3 小时 / 周末通宵。
 *
 * [availableMinutes] 映射到 [DrawCriteria.availableMinutes] 的硬过滤上限；`null` 表示不限时长
 * （周末通宵）。仓库按补完成本分桶（今晚≈120min / 周末≈600min / 长期坑=∞）做粗粒度裁剪。
 */
enum class TimeBudget(val availableMinutes: Int?) {
    TWENTY_MIN(20),
    ONE_HOUR(60),
    TWO_THREE_HOURS(180),
    WEEKEND(null),
    ;

    fun label(): String = when (this) {
        TWENTY_MIN -> "20 分钟"
        ONE_HOUR -> "1 小时"
        TWO_THREE_HOURS -> "2-3 小时"
        WEEKEND -> "周末通宵"
    }
}

/**
 * 接受程度选择（RC.11.03）：可慢热 / 可致郁 / 可党争 / 可长篇 / 不要剧透 / 不要太累 / 不要未完结。
 *
 * 语义分两类：
 * - 「可 X」放宽风险白名单（[toleratedRiskTag] 非空时把该风险加入 [DrawCriteria.riskTolerance]）。
 * - 「不要 X」为硬性拒绝：
 *   - [NO_SPOILER]：无剧透——本地规则推荐器无法对剧透做硬过滤，预留钩子（主要由 AI 路径处理，26.x）。
 *   - [NO_TIRING]：不要太累——拒绝「上头 / 烧脑」高耗能风险（即使放宽也不纳入白名单）。
 *   - [NO_UNFINISHED]：不要未完结——复用 [DrawCriteria.finalsProtection]，要求作品已完结
 *     （注意：仓库的 finalsProtection 同时排除「长期坑」，方向偏保守，符合「不要未完结」诉求）。
 */
enum class AcceptanceOption(val toleratedRiskTag: String?) {
    SLOW_BURN("慢热"),
    DEPRESSING("致郁"),
    SHIPPING_WAR("党争"),
    LONG_FORM(null),
    NO_SPOILER(null),
    NO_TIRING(null),
    NO_UNFINISHED(null),
    ;

    fun label(): String = when (this) {
        SLOW_BURN -> "可慢热"
        DEPRESSING -> "可致郁"
        SHIPPING_WAR -> "可党争"
        LONG_FORM -> "可长篇"
        NO_SPOILER -> "不要剧透"
        NO_TIRING -> "不要太累"
        NO_UNFINISHED -> "不要未完结"
    }
}

// endregion

// region 三推荐种类与展示模型（RC.11.04）

/**
 * I9（RC.40）：今晚看什么的候选池。
 * - [BACKLOG]：仅从待补池抽取（默认，原行为）。
 * - [ALL_WORKS]：从全部已知作品中按口味相似度推荐（参考 mzzbscore/sprout：用看过+评分过的作品标签共现）。
 */
enum class CandidatePool {
    BACKLOG,
    ALL_WORKS,
    ;

    fun label(): String = when (this) {
        BACKLOG -> "待补池"
        ALL_WORKS -> "全部作品"
    }
}

/**
 * 三推荐种类（RC.11.04）：稳妥选择 / 赌一把选择 / 神经病选择。
 */
enum class RecommendationKind {
    SAFE,
    GAMBLE,
    WILDCARD,
    ;

    fun label(): String = when (this) {
        SAFE -> "稳妥选择"
        GAMBLE -> "赌一把"
        WILDCARD -> "神经病选择"
    }

    /** 种类副标题（解释该推荐的取向）。 */
    fun tagline(): String = when (this) {
        SAFE -> "严格贴合你的选择，几乎不会踩雷"
        GAMBLE -> "放宽一点风险，也许有惊喜"
        WILDCARD -> "抛开标签限制，来点意料之外"
    }
}

/**
 * 单条推荐的展示模型（RC.11.04）。
 *
 * @property kind 推荐种类（稳妥 / 赌一把 / 神经病）。
 * @property workId 被推荐作品 id（点击进入详情）。
 * @property card 统一作品卡片模型（RC.03.09）。
 * @property reason 可解释理由（来自仓库抽番理由 + 种类取向，RC.11.04）。
 */
data class RecommendationUiModel(
    val kind: RecommendationKind,
    val workId: String,
    val card: WorkCardUiModel,
    val reason: String,
)

/**
 * 推荐器输入状态（RC.11.01/02/03）。
 *
 * @property time 时间预算；`null` 表示尚未选择（提交前提示选择）。
 * @property selectedTags 已选标签集合（P2-5：来自候选池作品的真实 Bangumi 社区标签，可多选，命中其一即可）。
 * @property acceptances 已选接受程度集合（可多选）。
 *
 * 注：26.2 的模式开关（不准纠结 / 期末周保护 / 深夜提醒）将作为独立字段加入本状态，当前未建模。
 */
data class RecommenderInput(
    val time: TimeBudget? = null,
    val selectedTags: Set<String> = emptySet(),
    val acceptances: Set<AcceptanceOption> = emptySet(),
    /** 不准纠结模式（RC.11.05）：仅给一个推荐 + 明确理由，替你做决定。 */
    val indecisionMode: Boolean = false,
    /** 期末周保护模式（RC.11.06）：过滤长篇 / 致郁 / 高上头 / 未完结。 */
    val finalsProtectionMode: Boolean = false,
    /** 深夜提醒模式（RC.11.07）：温柔提醒早点休息，不强行劝睡。 */
    val lateNightMode: Boolean = false,
    /** I9（RC.40）：候选池——待补池（默认）或全部作品。 */
    val candidatePool: CandidatePool = CandidatePool.BACKLOG,
) {
    /** 是否可提交：至少选择了时间（标签 / 接受程度可空，空即不施加该维度约束）。 */
    val canSubmit: Boolean get() = time != null
}

// endregion

// region 输入 → DrawCriteria 映射（纯函数）

/**
 * 全部可识别的风险标签词表（用于「神经病」推荐的风险白名单展开）。词表外的风险标签在任何
 * 推荐中都不会被自动容忍，需由用户通过「可 X」显式接受。
 */
internal val ALL_RISK_TAGS: Set<String> = setOf(
    "慢热", "致郁", "党争", "上头", "烧脑", "刀", "猎奇", "暗黑",
)

/** 「不要太累」拒绝的高耗能风险标签（即使展开白名单也不纳入）。 */
internal val TIRING_RISK_TAGS: Set<String> = setOf("上头", "烧脑")

/** 期末周保护模式额外过滤的高耗能 / 致郁风险标签（长篇 / 未完结由 finalsProtection 处理）。 */
internal val FINALS_FILTERED_RISK_TAGS: Set<String> = setOf("致郁", "上头", "烧脑")

/**
 * 把输入按推荐 [kind] 翻译为 [DrawCriteria]（纯函数，RC.11.04/08）。
 *
 * @param kind 推荐种类，决定约束的宽严。
 * @param exclude 需额外排除的作品 id（累积已抽中的推荐，保证三推荐互不重复）。
 *
 * 不变式：
 * - 三种类均施加相同的「硬性」约束：时间上限、未完结排除（若选「不要未完结」）、`exclude`。
 * - 风险白名单逐级放宽：稳妥 = 用户「可 X」；赌一把 = 用户「可 X」+ 温和展开（慢热）；
 *   神经病 = 全部风险减去「明确拒绝」的高耗能风险。
 * - 「不要太累」始终从白名单中剔除 [TIRING_RISK_TAGS]，任何种类都不会推荐高耗能作品。
 */
fun RecommenderInput.toDrawCriteria(
    kind: RecommendationKind,
    exclude: Set<String> = emptySet(),
): DrawCriteria {
    val refusedTiring = AcceptanceOption.NO_TIRING in acceptances
    val requireFinished = AcceptanceOption.NO_UNFINISHED in acceptances

    // 用户显式接受的风险（可慢热 / 可致郁 / 可党争）。
    val userTolerated: Set<String> = acceptances.mapNotNull { it.toleratedRiskTag }.toSet()

    val baseTolerance: Set<String> = when (kind) {
        RecommendationKind.SAFE -> userTolerated
        RecommendationKind.GAMBLE -> userTolerated + "慢热"
        RecommendationKind.WILDCARD -> ALL_RISK_TAGS
    }
    // 「不要太累」的高耗能风险任何种类都不容忍。
    var riskTolerance = if (refusedTiring) baseTolerance - TIRING_RISK_TAGS else baseTolerance
    // 期末周保护（RC.11.06）：强制剔除致郁 / 高上头 / 烧脑，并要求已完结（排除长篇 / 未完结）。
    if (finalsProtectionMode) {
        riskTolerance = riskTolerance - FINALS_FILTERED_RISK_TAGS
    }

    // 神经病忽略标签（软偏好）以制造意外感；稳妥 / 赌一把保留标签命中。
    val moodTags: Set<String> = when (kind) {
        RecommendationKind.WILDCARD -> emptySet()
        else -> selectedTags
    }

    return DrawCriteria(
        availableMinutes = time?.availableMinutes,
        moodTags = moodTags,
        riskTolerance = riskTolerance,
        // 「不要未完结」或期末周保护均要求已完结（仓库侧同时排除长期坑，RC.11.06/07）。
        finalsProtection = requireFinished || finalsProtectionMode,
        // 深夜提醒模式（RC.11.07）：透传给仓库 / 理由文案。
        lateNight = lateNightMode,
        excludeWorkIds = exclude,
    )
}

// endregion

// region 领域 → 卡片映射（纯函数）

/**
 * 把抽中的待补条目与（可能为 `null` 的）作品折叠为推荐展示模型（RC.03.09 / RC.11.04）。
 *
 * 缺失即标记、绝不伪造：作品缺失时标题回退为作品 id、类型与评分显示「暂无数据」（RC.01 3.7 / RC.17.4）。
 *
 * @param kind 推荐种类。
 * @param item 抽中的待补条目（来自 `BacklogRepository.draw`）。
 * @param work 关联作品（来自 `WorkRepository.observeWorks`）；可为 `null`。
 * @param drawReason 仓库给出的抽番理由（RC.08.06）。
 */
fun buildRecommendationUiModel(
    kind: RecommendationKind,
    item: BacklogItem,
    work: Work?,
    drawReason: String,
): RecommendationUiModel {
    val subtitle = buildString {
        val alias = work?.titles?.aliases?.firstOrNull()
        if (!alias.isNullOrBlank()) append(alias)
        val year = work?.year
        if (year != null) {
            if (isNotEmpty()) append(" · ")
            append(year.toString())
        }
    }
    val card = WorkCardUiModel(
        coverUrl = work?.coverUrl,
        title = work?.titles?.canonical ?: item.workId,
        subtitle = subtitle,
        type = work?.mediaType?.recommenderLabel() ?: "暂无数据",
        // 评分聚合在后续任务接入；恒为 null → 卡片显示「暂无数据」，绝不伪造分值。
        ratingText = null,
        sourceTags = listOfNotNull(work?.primarySource?.recommenderSourceLabel()),
        backlogStatus = if (item.dustDays > 0) "吃灰 ${item.dustDays} 天" else null,
        completionCost = work?.completionCost?.recommenderLabel(),
        moodRiskTags = item.moodTags + item.riskTags,
    )
    return RecommendationUiModel(
        kind = kind,
        workId = item.workId,
        card = card,
        reason = "${kind.tagline()}。$drawReason",
    )
}

private fun MediaType.recommenderLabel(): String = when (this) {
    MediaType.ANIME -> "动画"
    MediaType.MANGA -> "漫画"
    MediaType.NOVEL -> "小说"
    MediaType.GAME -> "游戏"
    MediaType.VN -> "视觉小说"
}

private fun SourceId.recommenderSourceLabel(): String = when (this) {
    SourceId.BANGUMI -> "Bangumi"
    SourceId.ANILIST -> "AniList"
    SourceId.JIKAN -> "Jikan"
    SourceId.MAL -> "MAL"
    SourceId.VNDB -> "VNDB"
}

private fun CompletionCost.recommenderLabel(): String = when (this) {
    CompletionCost.TONIGHT -> "今晚可看完"
    CompletionCost.WEEKEND -> "周末"
    CompletionCost.LONG_HAUL -> "长期坑"
}

// endregion
