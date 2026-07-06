package com.acgcompass.feature.recommender

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.acgcompass.core.common.AppError
import com.acgcompass.core.ui.Cta
import com.acgcompass.core.ui.UiState
import com.acgcompass.core.designsystem.WorkCardUiModel
import com.acgcompass.data.datastore.SettingsDataStore
import com.acgcompass.data.local.dao.RecommendationExposureDao
import com.acgcompass.data.local.dao.UserCollectionDao
import com.acgcompass.data.local.entity.RecommendationExposureEntity
import com.acgcompass.data.taste.TasteEngine
import com.acgcompass.domain.model.MediaType
import com.acgcompass.domain.model.SourceId
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
import com.acgcompass.domain.usecase.TasteTagTaxonomy
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
    private val tasteEngine: TasteEngine,
    private val exposureDao: RecommendationExposureDao,
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

    init {
        // N4：进入推荐页即在后台一次性建好画像（联网补齐 work_features），使后续提交能命中 12 维引擎，
        // 且**不阻塞**任何一次提交（提交热路径只读缓存）。失败静默（不崩、不伪造）。
        viewModelScope.launch { runCatching { tasteEngine.ensureReady() } }
    }

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

    /**
     * I9：选择候选池（待补池 / 全部作品）。#9：切换池**保留已选标签**——此前会清空，导致用户在「待补池」
     * 选好标签切到「全部作品」后标签全没了、需重选。标签是「命中其一即可」的软筛选，且 UI 的 displayTags
     * 会并入已选标签照常展示/高亮；新池若无该题材作品，仅该档推荐变少，不会出错。
     */
    fun onSelectCandidatePool(pool: CandidatePool) {
        _input.update { it.copy(candidatePool = pool) }
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

    /**
     * 统计候选池作品的真实社区标签作为「想看的标签」筛选项（频次降序，取前 [MAX_TAG_OPTIONS]）。
     * C 轮：仅保留**题材**标签（[TasteTagTaxonomy.isSelectableGenre] 白名单），剔除厂商/人物名/梗/
     * 声优/时间等噪声——用户只想按「战斗、奇幻」这类题材筛选；同时减少 chip 数量，缓解展开/下滑掉帧。
     */
    private suspend fun computeAvailableTags(pool: CandidatePool): List<String> =
        loadCandidates(pool)
            .flatMap { w -> w.tags.map { it.name } }
            .map { TagNoise.clean(it) }
            .filter { TasteTagTaxonomy.isSelectableGenre(it) }
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
        // N4：热路径**不联网**——只用已缓存特征快速重建画像，避免「今晚看什么」提交时被 refreshFull 的
        // 大量联网补齐（最多 60 次串行）阻塞成「转半天」。真正的联网补齐由 init 后台任务一次性完成；本轮若
        // 画像/特征尚未就绪则回退本地 PersonalTasteScorer（仍个性化、瞬时返回）。
        runCatching { tasteEngine.rebuildFromCache() }
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
        // N5：chip 标签是清洗后的社区标签，而作品原始标签未清洗——两侧口径不同会导致「选了却命不中」。
        // 统一清洗后再比较（下方硬筛 / 覆盖率均用清洗后的标签）。
        val selectedTags = input.selectedTags.map { TagNoise.clean(it) }.filter { it.isNotEmpty() }.toSet()
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
                // N5：与所选标签同口径清洗，保证「chip 是清洗后名、作品是原始名」也能命中。
                val tagNamesClean = tagNames.map { TagNoise.clean(it) }
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
                val matched = tagNamesClean.filter { t -> selectedTags.any { sel -> t == sel || t.contains(sel) } }
                if (tagSelected && matched.isEmpty()) return@mapNotNull null
                // B：soft-AND 覆盖率——命中的「所选标签」去重计数（非作品标签命中数），驱动「尽量多命中所选标签」。
                val hitSelected = selectedTags.count { sel -> tagNamesClean.any { t -> t == sel || t.contains(sel) } }
                Triple(w, matched.take(3), hitSelected)
            }
            .toList()

        // P2-5：标签/接受程度为硬性筛选——筛完无候选则返回空态，提示放宽条件（不硬塞不相干作品）。
        if (prefiltered.isEmpty()) return UiState.Empty(NO_CANDIDATE_CTA)

        data class Scored(
            val work: Work,
            val matchedSelected: List<String>,
            val hitSelected: Int,
            val matchedHigh: List<String>,
            val personal: Float,
            val mean10: Float?,
            val combined: Float,
            val tasteFraction: Float?,
            val tasteAvailable: Boolean,
        )

        // P5：重复推荐冷却——最近 14 天已推过的作品本轮回避；若回避后候选为空则忽略冷却（绝不无结果）。
        val recentlyExposed = runCatching {
            exposureDao.exposedSince(System.currentTimeMillis() - REPEAT_COOLDOWN_MS).toSet()
        }.getOrDefault(emptySet())
        val workingSet = prefiltered.filterNot { it.first.id in recentlyExposed }.ifEmpty { prefiltered }

        // 第二步：精排打分。优先用最终版 12 维口味引擎（已校准 + 分数拉开 + 已评分偏置）的匹配度（0–100）；
        // 引擎对该候选不可用（无特征 / 非 Bangumi / 画像未建）时回退共享 [PersonalTasteScorer] 旧标签重合估计。
        // 社区评分仅作轻量质量护栏（贝叶斯均分，弱化低评分人数的高分），随机扰动保证多次提交的多样性。
        val engineScores: Map<String, com.acgcompass.domain.taste.TasteMatchResult> = runCatching {
            tasteEngine.scoreBatch(
                workingSet.mapNotNull { subjectIdOf(it.first) },
                networkBudget = TONIGHT_FEATURE_BUDGET,
            )
        }.getOrDefault(emptyMap())

        val ranked = workingSet.map { (w, matchedSelected, hitSelected) ->
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
            val communityNudge = (bayes?.div(10f) ?: 0.5f).coerceIn(0f, 1f)
            // B：意图加成——用户选了标签即强当下意图，按「命中所选标签的覆盖率」soft-AND 加权（命中越全越高），
            // 取代旧「命中数 × 0.6」（绝对计数无法区分 2/2 全中与 2/5 擦边）。未选标签时为 0，回到纯口味个性化。
            val intentBonus = if (tagSelected && selectedTags.isNotEmpty()) {
                (hitSelected.toFloat() / selectedTags.size) * INTENT_COVERAGE_WEIGHT
            } else {
                0f
            }
            val engine = subjectIdOf(w)?.let { engineScores[it] }
            if (engine != null) {
                // 引擎匹配度 0–100 → 0~1 作为主导个人口味分量（已拉开差距）。
                val frac = (engine.score / 100f).coerceIn(0f, 1f)
                val combined = frac * 3f + intentBonus + communityNudge * 0.6f +
                    (Math.random().toFloat() * 0.8f)
                Scored(w, matchedSelected, hitSelected, engine.reasons.take(3).map { it.label }, frac, mean, combined, frac, true)
            } else {
                val ts = personalTasteScorer.score(w, taste, bayes)
                val combined = ts.personal * 3f + intentBonus + communityNudge * 0.6f +
                    (Math.random().toFloat() * 0.8f)
                Scored(
                    w, matchedSelected, hitSelected, ts.matchedHighTags.take(3), ts.personal, mean, combined,
                    tasteFraction = if (ts.available) ts.fraction else null,
                    tasteAvailable = ts.available,
                )
            }
        }

        // N5 质量护栏 + 兜底（修「关了口味限制、且选了待补池里确有的标签，仍一个都推不出」）：
        // 1) 社区分下限：有评分则须 >= minScore；无评分（null）放行。
        // 2) 口味下限：**尊重用户关闭**——阈值=0（关闭）时不施加任何口味下限（此前强加 0.45 使关闭形同虚设）。
        // 3) 逐级放宽兜底：命中所选标签的候选不得被护栏清空——先按双下限筛，空则去掉口味下限，再空则仅用标签硬筛结果。
        val tasteFloor = tasteThreshold.coerceAtLeast(0f)
        fun passesCommunity(s: Scored): Boolean = s.mean10 == null || s.mean10 >= minScore
        fun passesTaste(s: Scored): Boolean =
            tasteFloor <= 0f || !s.tasteAvailable || s.tasteFraction == null || s.tasteFraction >= tasteFloor
        val filtered = ranked.filter { passesCommunity(it) && passesTaste(it) }
            .ifEmpty { ranked.filter { passesCommunity(it) } }
            .ifEmpty { ranked }
            .sortedByDescending { it.combined }
        if (filtered.isEmpty()) return UiState.Empty(NO_CANDIDATE_CTA)

        // 从综合分 top 段随机抽取，制造多样性；抽中后再按综合分排序（稳妥档=其中最贴合的一部）。
        val poolSize = if (input.indecisionMode) 6 else 12
        val topPool = filtered.take(poolSize)
        val picks = topPool.shuffled()
            .take(if (input.indecisionMode) 1 else 3)
            .sortedByDescending { it.combined }
        if (picks.isEmpty()) return UiState.Empty(NO_CANDIDATE_CTA)

        recordExposure(picks.map { it.work })

        val kinds = listOf(RecommendationKind.SAFE, RecommendationKind.GAMBLE, RecommendationKind.WILDCARD)
        val recommendations = picks.mapIndexed { idx, s ->
            val kind = kinds.getOrElse(idx) { RecommendationKind.WILDCARD }
            val ratingPart = s.mean10?.let { "，社区均分约 %.1f".format(it) }.orEmpty()
            // 理由聚焦「为什么这部贴合你」：优先所选标签，其次你高分常见标签，再退化为综合口碑。
            val reason = when {
                s.matchedSelected.isNotEmpty() -> {
                    val cov = if (selectedTags.size > 1) "（命中 ${s.hitSelected}/${selectedTags.size}）" else ""
                    "贴合你想看的标签：${s.matchedSelected.joinToString("、")}$cov$ratingPart"
                }
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

    /** P5：候选作品的 Bangumi subjectId（主源为 Bangumi 时即数字 work.id；否则 null → 引擎跳过、回退旧打分）。 */
    private fun subjectIdOf(w: Work): String? =
        if (w.primarySource == SourceId.BANGUMI) w.id.takeIf { it.toIntOrNull() != null } else null

    /** P5：记录本轮「今晚看什么」曝光（支撑重复推荐冷却与后续点击统计）。best-effort，失败静默不影响结果。 */
    private suspend fun recordExposure(works: List<Work>) {
        runCatching {
            val now = System.currentTimeMillis()
            exposureDao.upsertAll(
                works.map { w ->
                    RecommendationExposureEntity(
                        id = "tonight:${w.id}",
                        subjectId = subjectIdOf(w) ?: w.id,
                        context = "tonight",
                        exposedAt = now,
                        clickedAt = null,
                        dismissedAt = null,
                    )
                },
            )
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
            MediaType.OTHER -> "其他"
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

        /**
         * B：意图覆盖率加成权重。用户主动选标签 = 强当下意图，按命中所选标签的覆盖率（0~1）× 此值加分，
         * 全命中 +2.0（约等于口味 frac×3 的 0.67），实现 soft-AND「尽量多命中所选标签」而非命中 1 个即满足。
         */
        const val INTENT_COVERAGE_WEIGHT = 2.0f

        /**
         * N4：今晚精排**不再**在提交热路径联网补齐 work_features（此前 24 次串行联网 → 每次提交都「转半天」）。
         * 只用已缓存特征打分；未缓存者回退本地 PersonalTasteScorer。特征由 init 后台 ensureReady 与导入 / 同步补齐。
         */
        const val TONIGHT_FEATURE_BUDGET = 0

        /** P5：重复推荐冷却窗口（最近此时长内推过的作品本轮回避）。 */
        const val REPEAT_COOLDOWN_MS = 14L * 24 * 3600 * 1000

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
