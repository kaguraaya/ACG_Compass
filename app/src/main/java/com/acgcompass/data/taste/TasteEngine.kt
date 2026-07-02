package com.acgcompass.data.taste

import com.acgcompass.core.common.DispatcherProvider
import com.acgcompass.data.local.dao.UserCollectionDao
import com.acgcompass.data.local.entity.UserCollectionEntity
import com.acgcompass.domain.taste.AdvancedTasteProfile
import com.acgcompass.domain.taste.BuildTasteProfileUseCase
import com.acgcompass.domain.taste.ComputeTasteMatchUseCase
import com.acgcompass.domain.taste.TasteMatchResult
import com.acgcompass.domain.taste.TasteSample
import com.acgcompass.domain.taste.WorkFeature
import com.acgcompass.domain.taste.WorkFeatureRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.OffsetDateTime
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 口味引擎（最终版算法的应用层编排器，RC.10/RC.11）：把领域纯用例
 * （[BuildTasteProfileUseCase] / [ComputeTasteMatchUseCase]）与数据
 * （`user_collections` × [WorkFeatureRepository] 的 work_features 缓存）粘合，
 * **详情页口味匹配度**与**今晚看什么**共用同一画像与同一打分口径。
 *
 * 画像缓存于内存 [StateFlow]：
 * - [rebuildFromCache]：仅用已缓存特征重建（不联网），供冷启动 / 评分后自动刷新（快、零流量）。
 * - [refreshFull]：联网补齐缺失特征后重建（导入 / 同步后调用，一次性建好 work_features）。
 * - [score]：对单部候选评分（缺画像先按缓存重建；候选特征 best-effort 联网取）。
 *
 * 韧性：任一步骤失败都不抛、不伪造——画像不可用时 [score] 退化为低置信中性结果（RC.01 3.7）。
 */
@Singleton
class TasteEngine @Inject constructor(
    private val userCollectionDao: UserCollectionDao,
    private val workFeatureRepository: WorkFeatureRepository,
    private val buildProfile: BuildTasteProfileUseCase,
    private val computeMatch: ComputeTasteMatchUseCase,
    private val dispatchers: DispatcherProvider,
) {

    private val _profile = MutableStateFlow<AdvancedTasteProfile?>(null)

    /** 当前口味画像流（`null` = 尚未构建）。详情页 / 推荐页可观察以驱动刷新。 */
    fun observeProfile(): StateFlow<AdvancedTasteProfile?> = _profile.asStateFlow()

    /** 最近一次构建好的画像（同步访问）。 */
    val currentProfile: AdvancedTasteProfile? get() = _profile.value

    private val rebuildMutex = Mutex()

    /**
     * 本进程是否已尝试过「联网补齐 work_features」（[ensureReady] 用）：避免对暂无可用画像的用户
     * 每次进详情页 / 推荐页都重复联网拉取。导入页显式调用 [refreshFull] 同样会置位。
     */
    @Volatile
    private var networkFillAttempted = false

    /** 仅用本地缓存特征重建画像（不联网）。 */
    suspend fun rebuildFromCache(): AdvancedTasteProfile? = rebuild(networkBudget = 0)

    /** 联网补齐缺失 work_features 后重建画像（导入 / 同步后调用）。 */
    suspend fun refreshFull(): AdvancedTasteProfile? {
        networkFillAttempted = true
        return rebuild(networkBudget = MAX_NETWORK_FILL)
    }

    /**
     * 保障画像「就绪」（详情页 / 推荐页进入时调用）：
     * 1) 内存已有可用画像 → 直接返回；
     * 2) 否则先用已缓存特征**不联网**重建；
     * 3) 仍不可用、且本进程尚未联网补过、且用户**确有已评分作品** → best-effort 联网补齐 work_features 再建。
     *
     * 解决「存量用户从未点过导入 → work_features 恒空 → 画像不可用 → 详情页匹配度恒回退『暂无可匹配标签』、
     * 推荐器口味阈值因 tasteAvailable=false 被全放行而不生效」：让画像在首次进入相关页面时自动建好
     * （每进程至多一次联网，失败不抛、不伪造，RC.01 3.7 / RC.10.03）。
     */
    suspend fun ensureReady(): AdvancedTasteProfile? {
        _profile.value?.let { if (it.isUsable) return it }
        rebuildFromCache()?.let { if (it.isUsable) return it }
        if (networkFillAttempted) return _profile.value
        val hasRated = withContext(dispatchers.io) {
            userCollectionDao.getAll().any { it.rating != null && it.sourceItemId.isNotBlank() }
        }
        if (!hasRated) return _profile.value
        return refreshFull()
    }

    private suspend fun rebuild(networkBudget: Int): AdvancedTasteProfile? = rebuildMutex.withLock {
        withContext(dispatchers.io) {
            val now = System.currentTimeMillis()
            val rated = userCollectionDao.getAll().filter { it.rating != null && it.sourceItemId.isNotBlank() }
            if (rated.isEmpty()) {
                val empty = AdvancedTasteProfile(generatedAt = now)
                _profile.value = empty
                return@withContext empty
            }
            val ids = rated.map { it.sourceItemId }.distinct()
            val features: Map<String, WorkFeature> = runCatching {
                workFeatureRepository.getFeatures(ids, networkBudget)
            }.getOrDefault(emptyMap())

            val samples = rated.mapNotNull { c ->
                val feature = features[c.sourceItemId] ?: return@mapNotNull null
                TasteSample(
                    subjectId = c.sourceItemId,
                    rating = c.rating,
                    comment = c.comment,
                    updatedAtMillis = timestampOf(c),
                    feature = feature,
                )
            }
            val profile = buildProfile(samples, now)
            // N2：显式评分锚定必须覆盖「全部已评分作品」——含特征未取到、未进 50 样本者，否则这些作品
            // 在详情页拿不到显式评分锚（rating 查不到）→ 仍会塌到个位数。用完整评分表覆盖 ratedSubjectScores。
            val allRatedScores = rated.mapNotNull { c -> c.rating?.let { c.sourceItemId to it } }.toMap()
            val finalProfile = if (allRatedScores.isEmpty()) profile else profile.copy(ratedSubjectScores = allRatedScores)
            _profile.value = finalProfile
            finalProfile
        }
    }

    /**
     * 对候选作品计算口味匹配度。画像为空时先按缓存重建；候选特征缓存缺失时按 [allowNetwork] 决定是否联网。
     * 无任何可用数据返回 `null`（调用方据此显示「暂无数据」，不伪造）。
     */
    suspend fun score(
        subjectId: String,
        userRating: Int? = null,
        allowNetwork: Boolean = true,
    ): TasteMatchResult? = withContext(dispatchers.io) {
        if (subjectId.isBlank()) return@withContext null
        val profile = _profile.value ?: rebuildFromCache()
        if (profile == null || !profile.isUsable) return@withContext null
        val feature = runCatching { workFeatureRepository.getFeature(subjectId, allowNetwork) }
            .getOrNull() ?: return@withContext null
        computeMatch(feature, profile, userRating)
    }

    /**
     * 批量评分（今晚看什么召回后精排用）：对 [subjectIds] 在 [networkBudget] 限额内补齐特征后逐一打分。
     * 画像不可用时返回空表（调用方回退旧打分）。仅返回成功取到特征者，键为 subjectId。
     */
    suspend fun scoreBatch(
        subjectIds: List<String>,
        networkBudget: Int = 0,
    ): Map<String, TasteMatchResult> = withContext(dispatchers.io) {
        if (subjectIds.isEmpty()) return@withContext emptyMap()
        val profile = _profile.value ?: rebuildFromCache()
        if (profile == null || !profile.isUsable) return@withContext emptyMap()
        val features = runCatching {
            workFeatureRepository.getFeatures(subjectIds.distinct(), networkBudget)
        }.getOrDefault(emptyMap())
        features.mapValues { (_, f) -> computeMatch(f, profile, null) }
    }

    /** 解析单条收藏的「评定时间」毫秒：优先 Bangumi `sourceUpdatedAt`，回退本地 `updatedAt`。 */
    private fun timestampOf(c: UserCollectionEntity): Long =
        parseIsoMillis(c.sourceUpdatedAt) ?: c.updatedAt

    private fun parseIsoMillis(iso: String?): Long? {
        val s = iso?.trim().orEmpty()
        if (s.isEmpty()) return null
        return runCatching { OffsetDateTime.parse(s).toInstant().toEpochMilli() }.getOrNull()
            ?: runCatching { Instant.parse(s).toEpochMilli() }.getOrNull()
    }

    private companion object {
        /** 单次 refreshFull 联网补齐 work_features 的上限（覆盖典型 50 样本画像，避免无限拉取）。 */
        const val MAX_NETWORK_FILL: Int = 60
    }
}
