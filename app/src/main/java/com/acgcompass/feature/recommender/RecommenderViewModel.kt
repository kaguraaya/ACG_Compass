package com.acgcompass.feature.recommender

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.acgcompass.core.common.AppError
import com.acgcompass.core.ui.Cta
import com.acgcompass.core.ui.UiState
import com.acgcompass.core.designsystem.WorkCardUiModel
import com.acgcompass.data.datastore.SettingsDataStore
import com.acgcompass.data.local.dao.UserCollectionDao
import com.acgcompass.domain.model.MediaType
import com.acgcompass.domain.model.TagNoise
import com.acgcompass.domain.model.TasteProfile
import com.acgcompass.domain.model.Work
import com.acgcompass.domain.repository.BacklogRepository
import com.acgcompass.domain.repository.BacklogFilter
import com.acgcompass.domain.repository.BacklogSort
import com.acgcompass.domain.repository.DrawResult
import com.acgcompass.domain.repository.TasteProfileRepository
import com.acgcompass.domain.repository.WorkRepository
import com.acgcompass.domain.usecase.PersonalTasteScorer
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException

/**
 * 「今晚看什么」推荐器 ViewModel（RC.11.01/02/03/04/08 / Requirements 13.1–13.4, 13.8）。
 * MVVM + Hilt + StateFlow。本地规则推荐路径（无 AI key 时的默认实现，RC.09.03 / RC.14.01）。
 *
 * 职责：
 * - 持有三组输入（时间 / 心情 / 接受程度）的可变状态（RC.11.01/02/03）。
 * - 提交时调用 [BacklogRepository.draw] **最多三次**，以「稳妥 / 赌一把 / 神经病」三档逐级放宽的
 *   [com.acgcompass.domain.repository.DrawCriteria] 抽取，并逐次累积 `excludeWorkIds` 保证三推荐互不重复
 *   （RC.11.04）。
 * - 硬过滤与「不推荐已完成」由仓库 `draw` 保证：候选池为待补池（按定义不含已完成作品），
 *   且全部 `DrawCriteria` 硬性约束（时间 / 风险白名单 / 未完结）在仓库侧裁剪（RC.11.08 / Property 14）。
 *
 * 韧性：抽取异常兜底为 [UiState.Error]，绝不让页面崩溃（RC.03.04 / RC.17.4）。
 *
 * 后续（26.2）：不准纠结 / 期末周保护 / 深夜提醒模式将在此扩展（见各处 TODO 钩子）。
 */
@HiltViewModel
class RecommenderViewModel @Inject constructor(
    private val backlogRepository: BacklogRepository,
    private val workRepository: WorkRepository,
    private val tasteProfileRepository: TasteProfileRepository,
    private val personalTasteScorer: PersonalTasteScorer,
    private val userCollectionDao: UserCollectionDao,
    private val settingsDataStore: SettingsDataStore,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    // P2-8：首页「今日状态」可携带预填标签进入（导航参数 presetTags，逗号分隔）。Navigation 已解码，直接切分。
    private val presetTags: Set<String> =
        savedStateHandle.get<String>(RECOMMENDER_ARG_TAGS)
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.toSet()
            .orEmpty()

    private val _input = MutableStateFlow(RecommenderInput(selectedTags = presetTags))
    val input: StateFlow<RecommenderInput> = _input.asStateFlow()

    private val _result = MutableStateFlow<UiState<List<RecommendationUiModel>>>(INITIAL_STATE)
    val result: StateFlow<UiState<List<RecommendationUiModel>>> = _result.asStateFlow()

    /**
     * P2-5：候选池作品的真实 Bangumi 社区标签（频次降序、过滤元数据噪声），作为「想看的标签」筛选项的
     * 动态来源。随候选池切换重算；候选无标签时为空（UI 提示补充待补池 / 切换全部作品）。
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val availableTags: StateFlow<List<String>> =
        _input
            .map { it.candidatePool }
            .distinctUntilChanged()
            .mapLatest { pool -> computeAvailableTags(pool) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // region 输入选择（RC.11.01/02/03）

    /** 选择时间预算（单选，RC.11.01）。 */
    fun onSelectTime(time: TimeBudget) {
        _input.update { it.copy(time = time) }
    }

    /** P2-5：切换标签（多选，RC.11.02）。标签来自候选池作品的真实 Bangumi 社区标签（[availableTags]）。 */
    fun onToggleTag(tag: String) {
        _input.update { current ->
            val next = if (tag in current.selectedTags) current.selectedTags - tag else current.selectedTags + tag
            current.copy(selectedTags = next)
        }
    }

    /** 切换接受程度（多选，RC.11.03）。 */
    fun onToggleAcceptance(option: AcceptanceOption) {
        _input.update { current ->
            val next = if (option in current.acceptances) {
                current.acceptances - option
            } else {
                current.acceptances + option
            }
            current.copy(acceptances = next)
        }
    }

    /** 切换不准纠结模式（RC.11.05）：开启后仅给一个推荐。 */
    fun onToggleIndecisionMode() {
        _input.update { it.copy(indecisionMode = !it.indecisionMode) }
    }

    /** 切换期末周保护模式（RC.11.06）：过滤长篇 / 致郁 / 高上头 / 未完结。 */
    fun onToggleFinalsProtection() {
        _input.update { it.copy(finalsProtectionMode = !it.finalsProtectionMode) }
    }

    /** 切换深夜提醒模式（RC.11.07）：温柔提醒早点休息。 */
    fun onToggleLateNight() {
        _input.update { it.copy(lateNightMode = !it.lateNightMode) }
    }

    /** I9：选择候选池（待补池 / 全部作品）。P2-5：切换池后可用标签集变化，清空已选标签避免无效残留。 */
    fun onSelectCandidatePool(pool: CandidatePool) {
        _input.update { it.copy(candidatePool = pool, selectedTags = emptySet()) }
    }

    // endregion

    // region 提交 → 三推荐（RC.11.04 / RC.11.08）

    /**
     * 提交输入，生成三推荐（RC.11.04）。未选择时间则保持初始空态提示。
     *
     * 逐档抽取：稳妥 → 赌一把 → 神经病，逐次把已抽中作品加入排除集，保证互不重复。
     * 若某档无满足约束的候选则跳过；三档均无候选时呈现空态（RC.11.08 不伪造）。
     */
    fun onSubmit() {
        val current = _input.value
        if (!current.canSubmit) {
            _result.value = INITIAL_STATE
            return
        }
        _result.value = UiState.Loading
        viewModelScope.launch {
            try {
                val candidates = loadCandidates(current.candidatePool)
                _result.value = recommendFromWorks(current, candidates)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _result.value = UiState.Error(AppError.Server())
            }
        }
    }

    // endregion

    /** P2-5：按候选池加载候选作品（跨源去重、优先 Bangumi 代表）。供推荐与动态标签统计共用。 */
    private suspend fun loadCandidates(pool: CandidatePool): List<Work> {
        val allWorks = workRepository.observeWorks().first()
        return when (pool) {
            CandidatePool.ALL_WORKS ->
                dedupePreferBangumi(allWorks.filter { it.mediaType == MediaType.ANIME })
            CandidatePool.BACKLOG -> {
                val backlogIds = backlogRepository
                    .observeBacklog(BacklogFilter.NONE, BacklogSort.ADDED_DESC)
                    .first()
                    .map { it.workId }
                    .toSet()
                dedupePreferBangumi(allWorks.filter { it.id in backlogIds })
            }
        }
    }

    /** P2-5：统计候选池作品的真实社区标签（频次降序、过滤噪声），取前 [MAX_TAG_OPTIONS] 作为筛选项。 */
    private suspend fun computeAvailableTags(pool: CandidatePool): List<String> =
        loadCandidates(pool)
            .flatMap { w -> w.tags.map { it.name } }
            .map { TagNoise.clean(it) }
            .filter { it.length >= 2 && !TagNoise.isNoise(it) }
            .groupingBy { it }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
            .map { it.key }
            .take(MAX_TAG_OPTIONS)

    /**
     * 标签感知 + 个性化推荐（两个候选池共用）。综合分 = **个人口味（主导）** + 所选标签命中加成 +
     * 社区评分（轻量护栏）+ 随机扰动。个人口味由共享 [PersonalTasteScorer] 计算（作品标签 × 你高/低分
     * 标签的加权重合），故**即使不选任何标签**也能按你的长期口味个性化排序，而非只推社区高分热门。
     *
     * 选了标签时仍硬筛（必须命中至少一个）；排除已看过/抛弃；按综合分降序取前 3（不准纠结取 1）。
     */
    private suspend fun recommendFromWorks(
        input: RecommenderInput,
        candidates: List<Work>,
    ): UiState<List<RecommendationUiModel>> {
        val taste: TasteProfile? = tasteProfileRepository.observeTasteProfile().first()
        // Phase④：用户可调的推荐社区分下限与口味匹配度阈值（默认 6.0 / 关闭）。
        val minScore = settingsDataStore.recommendMinCommunityScore.first()
        val tasteThreshold = settingsDataStore.tasteMatchThreshold.first()
        val excludeIds = runCatching {
            userCollectionDao.getAll()
                .filter { it.status == "看过" || it.status == "抛弃" }
                .map { it.localWorkId }
                .toSet()
        }.getOrDefault(emptySet())

        // P2-5：用户直接选择真实标签（来自候选池作品的 Bangumi 社区标签）；命中即匹配。
        val selectedTags = input.selectedTags
        val tagSelected = selectedTags.isNotEmpty()

        // 接受程度「不要X」→ 硬排除关键词（不要太累→烧脑/上头/意识流/电波）；「不要未完结」单独处理。
        val hardExcludeKeywords = input.acceptances.flatMap { ACCEPTANCE_EXCLUDE[it].orEmpty() }.toSet()
        val excludeUnfinished = AcceptanceOption.NO_UNFINISHED in input.acceptances

        // 第一步：硬过滤（仅裁剪，不打分）——媒介/已看过抛弃/期末保护/未完结/不要X/所选标签必命中。
        val prefiltered = candidates.asSequence()
            .filter { w ->
                w.mediaType == MediaType.ANIME && w.id !in excludeIds &&
                    // Q3：期末保护模式——排除长篇/未完结/高耗能（致郁·上头·烧脑）。
                    (!input.finalsProtectionMode || passesFinalsProtection(w))
            }
            .mapNotNull { w ->
                val tagNames = w.tags.map { it.name }
                // 硬排除：未完结（不要未完结）/ 含「不要X」关键词的作品直接淘汰。
                if (excludeUnfinished && w.status != com.acgcompass.domain.model.ReleaseStatus.FINISHED) {
                    return@mapNotNull null
                }
                if (hardExcludeKeywords.isNotEmpty() &&
                    tagNames.any { t -> hardExcludeKeywords.any { t.contains(it) } }
                ) {
                    return@mapNotNull null
                }
                // P2-5 标签硬筛：选了标签 → 必须命中至少一个（等值或包含）；否则淘汰。
                val matched = tagNames.filter { t -> selectedTags.any { sel -> t == sel || t.contains(sel) } }
                if (tagSelected && matched.isEmpty()) return@mapNotNull null
                w to matched.take(3)
            }
            .toList()

        // P2-5：标签/接受程度为硬性筛选——筛完无候选则返回空态，提示放宽条件（不硬塞不相干作品）。
        if (prefiltered.isEmpty()) return UiState.Empty(NO_CANDIDATE_CTA)

        data class Scored(
            val work: Work,
            val matchedSelected: List<String>,
            val matchedHigh: List<String>,
            val personal: Float,
            val mean10: Float?,
            val combined: Float,
            val tasteFraction: Float?,
            val tasteAvailable: Boolean,
        )

        // 第二步：个性化打分。用共享 [PersonalTasteScorer]——以「作品标签 × 你高/低分标签的加权重合」为主导，
        // 社区评分仅作轻量质量护栏（贝叶斯均分，弱化低评分人数的高分），随机扰动保证多次提交的多样性。
        val ranked = prefiltered.map { (w, matchedSelected) ->
            val agg = workRepository.aggregateRatingsCached(w.id)
            val mean = agg.mean10()
            val votes = agg.totalVotes()
            val prior = 6.5f
            val m = 300f
            val bayes = if (mean != null && votes > 0f) {
                (votes / (votes + m)) * mean + (m / (votes + m)) * prior
            } else {
                mean
            }
            val ts = personalTasteScorer.score(w, taste, bayes)
            val communityNudge = (bayes?.div(10f) ?: 0.5f).coerceIn(0f, 1f)
            // 综合分：个人口味主导（personal 0~1 ×3），所选标签命中加成，社区仅轻量护栏，随机扰动多样化。
            val combined = ts.personal * 3f +
                matchedSelected.size * 0.6f +
                communityNudge * 0.6f +
                (Math.random().toFloat() * 0.8f)
            Scored(
                w, matchedSelected, ts.matchedHighTags.take(3), ts.personal, mean, combined,
                tasteFraction = if (ts.available) ts.fraction else null,
                tasteAvailable = ts.available,
            )
        }

        // 明显低分（< 下限）的不推；都低则不强过滤（避免空结果）。
        val filtered = ranked
            .filter { it.mean10 == null || it.mean10 >= minScore }
            .filter { !it.tasteAvailable || it.tasteFraction == null || it.tasteFraction >= tasteThreshold }
            .ifEmpty {
                // Phase④：口味阈值过严导致空池 → 放宽阈值，仅保留社区分下限，避免空结果（仍按综合分排序）。
                ranked.filter { it.mean10 == null || it.mean10 >= minScore }.ifEmpty { ranked }
            }
            .sortedByDescending { it.combined }

        // 从综合分 top 段随机抽取，制造多样性；抽中后再按综合分排序（稳妥档=其中最贴合的一部）。
        val poolSize = if (input.indecisionMode) 6 else 12
        val topPool = filtered.take(poolSize)
        val picks = topPool.shuffled()
            .take(if (input.indecisionMode) 1 else 3)
            .sortedByDescending { it.combined }
        if (picks.isEmpty()) return UiState.Empty(NO_CANDIDATE_CTA)

        val kinds = listOf(RecommendationKind.SAFE, RecommendationKind.GAMBLE, RecommendationKind.WILDCARD)
        val recommendations = picks.mapIndexed { idx, s ->
            val kind = kinds.getOrElse(idx) { RecommendationKind.WILDCARD }
            val ratingPart = s.mean10?.let { "，社区均分约 %.1f".format(it) }.orEmpty()
            // 理由聚焦「为什么这部贴合你」：优先所选标签，其次你高分常见标签，再退化为综合口碑。
            val reason = when {
                s.matchedSelected.isNotEmpty() -> "贴合你想看的标签：${s.matchedSelected.joinToString("、")}$ratingPart"
                s.matchedHigh.isNotEmpty() -> "契合你的长期口味：常给高分的「${s.matchedHigh.joinToString("、")}」$ratingPart"
                else -> "综合口碑与你的口味挑选$ratingPart"
            }
            RecommendationUiModel(
                kind = kind,
                workId = s.work.id,
                card = s.work.toRecommendCard(),
                reason = "${kind.tagline()}。$reason",
            )
        }
        return UiState.Success(recommendations)
    }

    /**
     * L7：跨源去重，优先 Bangumi 代表。Room 中同一部番常有多源条目（多为 Jikan），
     * 导致「全部作品」推荐全是 Jikan。按规范化标题聚类，每簇优先取 Bangumi 源，
     * 其次按来源优先级（Bangumi>AniList>MAL>Jikan>VNDB）与标签丰富度选代表，避免同番多源刷屏与偏 Jikan。
     */
    private fun dedupePreferBangumi(works: List<Work>): List<Work> {
        fun sourceRank(s: com.acgcompass.domain.model.SourceId): Int = when (s) {
            com.acgcompass.domain.model.SourceId.BANGUMI -> 0
            com.acgcompass.domain.model.SourceId.ANILIST -> 1
            com.acgcompass.domain.model.SourceId.MAL -> 2
            com.acgcompass.domain.model.SourceId.JIKAN -> 3
            com.acgcompass.domain.model.SourceId.VNDB -> 4
        }
        return works
            .groupBy { w ->
                val key = w.titles.ja?.takeIf { it.isNotBlank() } ?: w.titles.canonical
                com.acgcompass.domain.matching.normalizeCompact(key)
            }
            .mapNotNull { (key, group) ->
                if (key.isBlank()) return@mapNotNull null
                group.minByOrNull { sourceRank(it.primarySource) * 1000 - it.tags.size }
            } + works.filter { w ->
                com.acgcompass.domain.matching.normalizeCompact(
                    w.titles.ja?.takeIf { it.isNotBlank() } ?: w.titles.canonical,
                ).isBlank()
            }
    }

    /** Q3：期末保护——排除长篇坑/未完结/高耗能（致郁·上头·烧脑）作品。 */
    private fun passesFinalsProtection(w: Work): Boolean {
        if (w.completionCost == com.acgcompass.domain.model.CompletionCost.LONG_HAUL) return false
        if (w.status != com.acgcompass.domain.model.ReleaseStatus.FINISHED) return false
        val heavy = setOf("致郁", "上头", "烧脑")
        if (w.tags.any { it.name in heavy }) return false
        return true
    }

    /** 由 [Work] 构建推荐卡片模型（全部作品池无聚合评分，评分位留空 → UI 显示「暂无数据」）。 */
    private fun Work.toRecommendCard(): WorkCardUiModel = WorkCardUiModel(
        coverUrl = coverUrl,
        title = titles.canonical,
        subtitle = listOfNotNull(
            year?.toString(),
            airDate?.takeIf { it.isNotBlank() },
        ).joinToString(" · "),
        type = when (mediaType) {
            MediaType.ANIME -> "动画"
            MediaType.MANGA -> "漫画"
            MediaType.NOVEL -> "小说"
            MediaType.GAME -> "游戏"
            MediaType.VN -> "视觉小说"
        },
        ratingText = null,
        sourceTags = listOf(primarySource.name),
        moodRiskTags = tags.take(3).map { it.name },
    )

    private companion object {
        /** 三推荐生成顺序（逐档放宽），用于累积排除保证互不重复（RC.11.04）。 */
        val RECOMMENDATION_ORDER = listOf(
            RecommendationKind.SAFE,
            RecommendationKind.GAMBLE,
            RecommendationKind.WILDCARD,
        )

        /** 初始 / 未提交态：引导用户选择条件并提交。 */
        val INITIAL_STATE = UiState.Empty(Cta(label = "选择条件后生成推荐", action = "submit"))

        /** 无满足条件候选时的空态：引导用户调整条件或补充待补池。 */
        val NO_CANDIDATE_CTA = Cta(label = "换个条件，或先去补充待补池", action = "adjust")

        /** K2：全部作品池融入评分时，先按口味取的候选规模（之后用本地缓存评分二次排序）。 */
        const val RATING_BLEND_POOL = 30

        /** P2-5：动态标签筛选项最多展示个数（按频次取前 N，避免选项过多）。 */
        const val MAX_TAG_OPTIONS = 30

        /** P0-4：接受程度「不要X」→ 硬排除的题材关键词（不要太累→烧脑/上头/意识流/电波）。 */
        val ACCEPTANCE_EXCLUDE: Map<AcceptanceOption, List<String>> = mapOf(
            AcceptanceOption.NO_TIRING to listOf("烧脑", "上头", "意识流", "电波"),
        )
    }
}

/** K2：从多源评分聚合算「社区均分（0~10）」；各源按量纲归一，无有效评分返回 null（不伪造）。 */
private fun com.acgcompass.domain.model.RatingAggregate.mean10(): Float? {
    val values = perSource.entries.mapNotNull { (src, entry) ->
        val score = entry?.score ?: return@mapNotNull null
        if (com.acgcompass.domain.usecase.AggregateRatingsUseCase.isValidScore(src, score)) {
            com.acgcompass.domain.usecase.AggregateRatingsUseCase.normalizeToTen(src, score)
        } else {
            null
        }
    }
    return if (values.isEmpty()) null else values.average().toFloat()
}

/** Q2：社区评分总人数（各源有效评分人数之和），用于贝叶斯加权（人数少的高分被拉回先验）。 */
private fun com.acgcompass.domain.model.RatingAggregate.totalVotes(): Float =
    perSource.values.sumOf { (it?.voteCount ?: 0).coerceAtLeast(0).toLong() }.toFloat()
