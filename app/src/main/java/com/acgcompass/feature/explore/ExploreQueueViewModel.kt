package com.acgcompass.feature.explore

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.acgcompass.data.local.dao.RecommendationExposureDao
import com.acgcompass.data.local.dao.UserCollectionDao
import com.acgcompass.data.local.entity.RecommendationExposureEntity
import com.acgcompass.data.taste.TasteEngine
import com.acgcompass.domain.model.MediaType
import com.acgcompass.domain.model.Work
import com.acgcompass.domain.repository.BacklogRepository
import com.acgcompass.domain.repository.WorkRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 探索队列 ViewModel（C 轮新功能）。MVVM + Hilt + StateFlow，本地规则路径（无需 AI key）。
 *
 * 生成一批（[BATCH_SIZE]）探索卡片：
 * - **候选召回**：本地单一可信源（含公共池）的动画作品，排除「已收藏（看过/在看/想看…）+ 已在待补池
 *   + 近 [COOLDOWN_MS] 曝光过」，保证探索的是「还没接触」的新作品；
 * - **口味精排**：12 维引擎 [TasteEngine.scoreBatch] 批量打分，按匹配度降序（加稳定微抖动避免每批雷同）；
 * - **曝光记录**：本批写入 `recommendation_exposure`（context=explore）支撑冷却，复用既有表（无需新表）。
 *
 * 交互：左滑 [onLike] → 加入待补池（[BacklogRepository.addAll] 去重）；右滑 [onSkip] → 记曝光略过。
 * 韧性：生成异常兜底为 [ExploreQueueUiState.Error]，绝不崩溃（RC.03.04 / RC.17.4）。
 */
@HiltViewModel
class ExploreQueueViewModel @Inject constructor(
    private val workRepository: WorkRepository,
    private val backlogRepository: BacklogRepository,
    private val userCollectionDao: UserCollectionDao,
    private val tasteEngine: TasteEngine,
    private val exposureDao: RecommendationExposureDao,
) : ViewModel() {

    private val _state = MutableStateFlow<ExploreQueueUiState>(ExploreQueueUiState.Loading)
    val state: StateFlow<ExploreQueueUiState> = _state.asStateFlow()

    /** 当前批次作品对象（workId → Work），供左滑加入待补池（addAll 需 [Work]）。 */
    private val worksById = mutableMapOf<String, Work>()
    private var likedCount = 0
    private var skippedCount = 0

    init {
        generate()
    }

    /** 生成 / 重新生成一批探索卡片（首屏与「再来一批」共用）。 */
    fun generate() {
        viewModelScope.launch {
            _state.value = ExploreQueueUiState.Loading
            likedCount = 0
            skippedCount = 0
            worksById.clear()
            _state.value = runCatching { buildBatch() }
                .getOrElse { ExploreQueueUiState.Error("生成探索队列失败，请稍后重试") }
        }
    }

    private suspend fun buildBatch(): ExploreQueueUiState {
        val all = workRepository.observeWorks().first()
        val collected = userCollectionDao.getAll().map { it.localWorkId }.toSet()
        val inBacklog = backlogRepository.observeBacklog().first().map { it.workId }.toSet()
        val cooled = exposureDao.exposedSince(System.currentTimeMillis() - COOLDOWN_MS).toSet()
        val excluded = collected + inBacklog + cooled

        val candidates = all.filter { it.mediaType == MediaType.ANIME && it.id !in excluded }
        if (candidates.isEmpty()) {
            val msg = if (all.isEmpty()) {
                "本地候选不足：先到「发现 → 本季 / 榜单」加载作品池，或在「我的」同步 Bangumi 收藏"
            } else {
                "暂时没有新作品可探索：当前候选都浏览过了，去发现页加载更多，或稍后再来"
            }
            return ExploreQueueUiState.Empty(msg)
        }

        // 12 维口味精排；画像不可用时 scoreBatch 返回空表 → 统一按中性分排序（仍可探索）。
        val scores = tasteEngine.scoreBatch(candidates.map { it.id }, networkBudget = NETWORK_BUDGET)
        val ranked = candidates
            .map { it to scores[it.id] }
            .sortedByDescending { (work, match) ->
                (match?.score?.toDouble() ?: 50.0) + work.id.hashCode().mod(7) * 0.4
            }
            .take(BATCH_SIZE)

        val now = System.currentTimeMillis()
        exposureDao.upsertAll(
            ranked.map { (work, _) ->
                RecommendationExposureEntity(
                    id = "$EXPLORE_CONTEXT:${work.id}",
                    subjectId = work.id,
                    context = EXPLORE_CONTEXT,
                    exposedAt = now,
                    clickedAt = null,
                    dismissedAt = null,
                )
            },
        )

        val cards = ranked.map { (work, match) ->
            worksById[work.id] = work
            work.toExploreCard(match)
        }
        return ExploreQueueUiState.Ready(cards = cards, index = 0)
    }

    /** 右滑：加入待补池（去重）并前进。 */
    fun onLike() {
        val st = _state.value as? ExploreQueueUiState.Ready ?: return
        val card = st.cards.getOrNull(st.index) ?: return
        worksById[card.workId]?.let { work ->
            viewModelScope.launch { backlogRepository.addAll(listOf(work)) }
        }
        likedCount++
        advance(st)
    }

    /** 左滑：记曝光略过（冷却内不再推荐）并前进。 */
    fun onSkip() {
        val st = _state.value as? ExploreQueueUiState.Ready ?: return
        val card = st.cards.getOrNull(st.index) ?: return
        viewModelScope.launch {
            exposureDao.markDismissed("$EXPLORE_CONTEXT:${card.workId}", System.currentTimeMillis())
        }
        skippedCount++
        advance(st)
    }

    private fun advance(st: ExploreQueueUiState.Ready) {
        val next = st.index + 1
        _state.value = if (next >= st.cards.size) {
            ExploreQueueUiState.Finished(liked = likedCount, skipped = skippedCount)
        } else {
            st.copy(index = next)
        }
    }

    private companion object {
        const val BATCH_SIZE = 10
        const val NETWORK_BUDGET = 8
        const val EXPLORE_CONTEXT = "explore"
        const val COOLDOWN_MS = 14L * 24 * 60 * 60 * 1000
    }
}
