package com.acgcompass.feature.recommender

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.acgcompass.core.common.AppError
import com.acgcompass.core.ui.Cta
import com.acgcompass.core.ui.UiState
import com.acgcompass.core.designsystem.WorkCardUiModel
import com.acgcompass.data.local.dao.UserCollectionDao
import com.acgcompass.domain.model.MediaType
import com.acgcompass.domain.model.TagBucket
import com.acgcompass.domain.model.TasteProfile
import com.acgcompass.domain.model.Work
import com.acgcompass.domain.repository.BacklogRepository
import com.acgcompass.domain.repository.BacklogFilter
import com.acgcompass.domain.repository.BacklogSort
import com.acgcompass.domain.repository.DrawResult
import com.acgcompass.domain.repository.TasteProfileRepository
import com.acgcompass.domain.repository.WorkRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
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
    private val userCollectionDao: UserCollectionDao,
) : ViewModel() {

    private val _input = MutableStateFlow(RecommenderInput())
    val input: StateFlow<RecommenderInput> = _input.asStateFlow()

    private val _result = MutableStateFlow<UiState<List<RecommendationUiModel>>>(INITIAL_STATE)
    val result: StateFlow<UiState<List<RecommendationUiModel>>> = _result.asStateFlow()

    // region 输入选择（RC.11.01/02/03）

    /** 选择时间预算（单选，RC.11.01）。 */
    fun onSelectTime(time: TimeBudget) {
        _input.update { it.copy(time = time) }
    }

    /** 切换心情标签（多选，RC.11.02）。 */
    fun onToggleMood(mood: MoodOption) {
        _input.update { current ->
            val next = if (mood in current.moods) current.moods - mood else current.moods + mood
            current.copy(moods = next)
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

    /** I9：选择候选池（待补池 / 全部作品）。 */
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
                val allWorks = workRepository.observeWorks().first()
                val worksById: Map<String, Work> = allWorks.associateBy { it.id }

                // O1：候选作品集——两个池统一走「题材/心情感知」打分（用作品社区标签，而非待补条目的空标签）。
                val candidates: List<Work> = when (current.candidatePool) {
                    CandidatePool.ALL_WORKS ->
                        dedupePreferBangumi(allWorks.filter { it.mediaType == MediaType.ANIME })
                    CandidatePool.BACKLOG -> {
                        val backlogIds = backlogRepository
                            .observeBacklog(BacklogFilter.NONE, BacklogSort.ADDED_DESC)
                            .first()
                            .map { it.workId }
                            .toSet()
                        // N14：待补池候选也跨源去重、优先 Bangumi 代表（避免同番多源/偏 Jikan）。
                        dedupePreferBangumi(allWorks.filter { it.id in backlogIds })
                    }
                }
                _result.value = recommendFromWorks(current, candidates)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _result.value = UiState.Error(AppError.Server())
            }
        }
    }

    // endregion

    /**
     * O1：题材/心情感知推荐（两个候选池共用）。综合分 = 题材匹配（主导）+ 口味画像 + 社区评分 + 随机扰动。
     *
     * 题材匹配：把所选心情（如 日常/轻松）展开为关键词，与作品**社区标签**重合得正分；当只选了放松向心情时，
     * 对「热血/战斗/血腥/致郁/战争/巨人」等强冲突标签重罚——这样选「日常·轻松」不会再推进击的巨人。
     * 排除已看过/抛弃；按综合分降序取前 3（不准纠结取 1），分别标稳妥/赌一把/神经病。
     */
    private suspend fun recommendFromWorks(
        input: RecommenderInput,
        candidates: List<Work>,
    ): UiState<List<RecommendationUiModel>> {
        val taste: TasteProfile? = tasteProfileRepository.observeTasteProfile().first()
        val highTags = taste?.tagStats
            ?.filter { it.bucket == TagBucket.HIGH_SCORE }
            ?.map { it.tagName.lowercase() }?.toSet().orEmpty()
        val lowTags = taste?.tagStats
            ?.filter { it.bucket == TagBucket.LOW_SCORE }
            ?.map { it.tagName.lowercase() }?.toSet().orEmpty()
        val excludeIds = runCatching {
            userCollectionDao.getAll()
                .filter { it.status == "看过" || it.status == "抛弃" }
                .map { it.localWorkId }
                .toSet()
        }.getOrDefault(emptySet())

        // 所选心情 → 关键词集；是否「仅放松向」决定是否对强冲突标签重罚。
        val selectedMoods = input.moods
        val moodKeywords = selectedMoods.flatMap { MOOD_KEYWORDS[it].orEmpty() }.toSet()
        val onlyRelaxing = selectedMoods.isNotEmpty() && selectedMoods.all { it in RELAXING_MOODS }
        val wantsAcclaimed = MoodOption.MASTERPIECE in selectedMoods || MoodOption.SHOCKED in selectedMoods
        val wantsShort = MoodOption.SHORT in selectedMoods

        // P0-4：接受程度 → 容忍/硬排除关键词。「可X」把对应题材移出冲突集（即便选了放松向也容忍）；
        // 「不要X」作为硬排除（不要太累→烧脑/上头；不要未完结→未完结作品）。
        val toleratedKeywords = input.acceptances.flatMap { ACCEPTANCE_TOLERATE[it].orEmpty() }.toSet()
        val hardExcludeKeywords = input.acceptances.flatMap { ACCEPTANCE_EXCLUDE[it].orEmpty() }.toSet()
        val excludeUnfinished = AcceptanceOption.NO_UNFINISHED in input.acceptances
        val moodSelected = moodKeywords.isNotEmpty()
        // P0-4 冲突集：仅放松向时排除强烈/沉重题材，扣除用户明确容忍的；任何时候叠加「不要X」硬排除。
        val conflictKeywords: Set<String> =
            ((if (onlyRelaxing) INTENSE_KEYWORDS.toSet() else emptySet()) - toleratedKeywords) + hardExcludeKeywords

        data class Scored(
            val work: Work,
            val genre: Float,
            val matched: List<String>,
            val mean10: Float?,
            val combined: Float,
        )

        val scoredList = candidates.asSequence()
            .filter { w ->
                w.mediaType == MediaType.ANIME && w.id !in excludeIds &&
                    // Q3：期末保护模式——排除长篇/未完结/高耗能（致郁·上头·烧脑）。
                    (!input.finalsProtectionMode || passesFinalsProtection(w))
            }
            .mapNotNull { w ->
                val tagNames = w.tags.map { it.name }
                val tagLower = tagNames.map { it.lowercase() }
                // P0-4 硬排除：未完结（不要未完结）/ 含「不要X」关键词的作品直接淘汰。
                if (excludeUnfinished && w.status != com.acgcompass.domain.model.ReleaseStatus.FINISHED) {
                    return@mapNotNull null
                }
                if (hardExcludeKeywords.isNotEmpty() &&
                    tagNames.any { t -> hardExcludeKeywords.any { t.contains(it) } }
                ) {
                    return@mapNotNull null
                }
                // 题材正分：作品标签命中所选心情关键词。
                val matched = tagNames.filter { t -> moodKeywords.any { k -> t.contains(k) } }
                // 冲突标签数：与所选心情相斥的强烈/沉重题材（已扣除容忍项）。
                val conflicts = tagNames.count { t -> conflictKeywords.any { k -> t.contains(k) } }
                // P0-4 心情硬筛：选了心情 → 必须命中至少一个心情关键词，且不含冲突标签；否则淘汰，
                // 不给「想看轻松」的人推「胃疼/致郁」作品（不足时上层返回空态提示放宽）。
                if (moodSelected && (matched.isEmpty() || conflicts > 0)) return@mapNotNull null
                val positive = matched.size.toFloat()
                var genre = positive * 3f - conflicts * 4f
                // 短篇偏好：集数 ≤ 13 或今晚可看完加分。
                if (wantsShort) {
                    val eps = w.units.episodes
                    if ((eps != null && eps in 1..13) ||
                        w.completionCost == com.acgcompass.domain.model.CompletionCost.TONIGHT
                    ) {
                        genre += 2f
                    }
                }
                val highHits = tagLower.count { it in highTags }
                val lowHits = tagLower.count { it in lowTags }
                val tasteScore = highHits - lowHits * 1.5f
                Scored(w, genre, matched.take(3), null, tasteScore)
            }
            .toList()

        // P0-4：心情/接受程度为硬性筛选——筛完无候选则返回空态，提示放宽条件（不硬塞不相干作品）。
        if (scoredList.isEmpty()) return UiState.Empty(NO_CANDIDATE_CTA)

        // 硬筛已在上方完成（心情命中 + 冲突/未完结/不要X 排除），此处直接进入评分融合。
        val genreFiltered = scoredList

        // 融入社区评分（仅读本地缓存，不联网）。Q2：用**评分人数加权的贝叶斯均分**——人数少的高分被拉回
        // 先验（6.5），避免推荐「高分但没几个人评的奇怪番」；题材/口味权重 > 绝对分（推适合自己而非最高分）。
        val ranked = genreFiltered.map { s ->
            val agg = workRepository.aggregateRatingsCached(s.work.id)
            val mean = agg.mean10()
            val votes = agg.totalVotes()
            val prior = 6.5f
            val m = 300f
            val bayes = if (mean != null && votes > 0f) {
                (votes / (votes + m)) * mean + (m / (votes + m)) * prior
            } else {
                mean ?: prior
            }
            // 评分项 0~3（次于题材/口味）；神作偏好仅对「高分且足够多人评」加成。
            val ratingPart = (bayes / 10f) * 3f
            val acclaimedBoost = if (wantsAcclaimed && (mean ?: 0f) >= 8f && votes >= 500f) 2f else 0f
            // Q1：更大随机扰动（0~1.2），让多次提交不再高度重复。
            val combined = s.genre * 2.2f + s.combined * 0.8f + ratingPart + acclaimedBoost +
                (Math.random().toFloat() * 1.2f)
            s.copy(mean10 = mean, combined = combined)
        }
        // 选了心情时明显低分（<6）的不推；都低则不强过滤。
        val filtered = ranked
            .filter { it.mean10 == null || it.mean10 >= MIN_RECOMMEND_SCORE }
            .ifEmpty { ranked }
            .sortedByDescending { it.combined }

        // Q1：从综合分 top 段随机抽取，制造多样性（不再每次都给同样的最高分几部）；
        // 抽中后再按综合分排序，保证「稳妥档=其中最贴合的一部」。
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
            // O3：理由聚焦「为什么这部贴合」，展示命中的作品题材标签，不再重复回显所选心情。
            val reason = when {
                s.matched.isNotEmpty() -> "贴合你想看的题材：${s.matched.joinToString("、")}$ratingPart"
                wantsAcclaimed -> "高口碑之选$ratingPart"
                s.genre < 0f -> "可选项有限，这部题材略有出入，但口碑尚可$ratingPart"
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

        /** K2：推荐过滤的社区均分下限（避免推荐明显低分作品）。 */
        const val MIN_RECOMMEND_SCORE = 6.0f

        /**
         * O1：所选心情 → 匹配关键词（与作品社区标签 `contains` 比对）。覆盖常见题材/情绪同义词。
         * MASTERPIECE/SHORT 无题材关键词（分别走高口碑加权 / 短篇偏好）。
         */
        val MOOD_KEYWORDS: Map<MoodOption, List<String>> = mapOf(
            MoodOption.RELAXED to listOf("轻松", "日常", "治愈", "搞笑", "温馨", "校园", "萌", "美食", "悠闲", "气氛轻松"),
            MoodOption.HEALING to listOf("治愈", "温馨", "日常", "感人", "暖"),
            MoodOption.FUNNY to listOf("搞笑", "喜剧", "欢乐", "吐槽", "沙雕", "无厘头", "轻松"),
            MoodOption.TEARJERKER to listOf("催泪", "感动", "致郁", "感人", "虐", "泪"),
            MoodOption.STOMACH_ACHE to listOf("致郁", "虐", "刀", "黑暗", "悲剧", "胃疼"),
            MoodOption.HOT_BLOODED to listOf("热血", "战斗", "燃", "运动", "格斗", "少年", "战争"),
            MoodOption.ROMANCE to listOf("恋爱", "爱情", "纯爱", "百合", "后宫", "校园", "青春"),
            MoodOption.DAILY to listOf("日常", "校园", "治愈", "轻松", "萌", "美食", "温馨"),
            MoodOption.SUSPENSE to listOf("悬疑", "推理", "烧脑", "惊悚", "犯罪", "心理"),
            MoodOption.SCIFI to listOf("科幻", "未来", "机战", "机甲", "赛博", "太空"),
            MoodOption.FANTASY to listOf("奇幻", "魔法", "异世界", "冒险", "幻想"),
            MoodOption.MUSIC to listOf("音乐", "乐队", "偶像", "歌"),
            MoodOption.SPORTS to listOf("运动", "竞技", "体育", "球"),
            MoodOption.WAVELENGTH to listOf("电波", "意识流", "实验", "先锋", "脑洞"),
            MoodOption.SHOCKED to listOf("震撼", "史诗", "宏大", "神作", "战争"),
        )

        /** O1：放松向心情——当只选了这些时，对强冲突标签重罚（避免推进击的巨人这类）。 */
        val RELAXING_MOODS: Set<MoodOption> = setOf(
            MoodOption.RELAXED, MoodOption.HEALING, MoodOption.FUNNY,
            MoodOption.DAILY, MoodOption.ROMANCE, MoodOption.MUSIC, MoodOption.SHORT,
        )

        /** O1：与放松向冲突的强烈/沉重标签关键词。 */
        val INTENSE_KEYWORDS: List<String> = listOf(
            "热血", "战斗", "打斗", "血腥", "猎奇", "致郁", "黑暗", "残酷", "战争",
            "军事", "巨人", "恐怖", "惊悚", "暗黑", "复仇", "政治", "悲剧", "杀戮", "末日",
        )

        /** P0-4：接受程度「可X」→ 容忍的题材关键词（从冲突集中移除，即便选了放松向也不排除）。 */
        val ACCEPTANCE_TOLERATE: Map<AcceptanceOption, List<String>> = mapOf(
            AcceptanceOption.DEPRESSING to listOf("致郁", "虐", "悲剧", "黑暗", "刀"),
            AcceptanceOption.SLOW_BURN to listOf("慢热"),
            AcceptanceOption.SHIPPING_WAR to listOf("党争", "后宫"),
        )

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
