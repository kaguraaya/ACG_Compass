package com.acgcompass.data.taste

import com.acgcompass.core.common.DispatcherProvider
import com.acgcompass.data.local.dao.TagDimensionDao
import com.acgcompass.data.local.dao.UserCollectionDao
import com.acgcompass.data.local.dao.WorkDao
import com.acgcompass.data.local.entity.UserCollectionEntity
import com.acgcompass.domain.model.MediaType
import com.acgcompass.domain.taste.AdvancedTasteProfile
import com.acgcompass.domain.taste.BuildTasteProfileUseCase
import com.acgcompass.domain.taste.ComputeTasteMatchUseCase
import com.acgcompass.domain.taste.TasteCategory
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
    private val workDao: WorkDao,
    private val workFeatureRepository: WorkFeatureRepository,
    private val buildProfile: BuildTasteProfileUseCase,
    private val computeMatch: ComputeTasteMatchUseCase,
    private val tagDimensionDao: TagDimensionDao,
    private val dispatchers: DispatcherProvider,
) {

    /**
     * N3：读 `tag_dimensions` 缓存为「清洗后标签 → 口味维度」覆盖表，供画像构建 / 评分对本地兜底为题材的
     * 未知标签做更精确分维。表为空 / 读失败 / 维度非法 → 返回空表，全回退本地规则（不阻塞、不伪造）。
     */
    private suspend fun loadTagOverrides(): Map<String, TasteCategory> = runCatching {
        tagDimensionDao.getAll()
            .mapNotNull { e -> TasteCategory.fromKey(e.dimension)?.let { e.tag to it } }
            .toMap()
    }.getOrDefault(emptyMap())

    private val _profile = MutableStateFlow<AdvancedTasteProfile?>(null)

    /** 当前口味画像流（`null` = 尚未构建）。详情页 / 推荐页可观察以驱动刷新。 */
    fun observeProfile(): StateFlow<AdvancedTasteProfile?> = _profile.asStateFlow()

    private val _refreshProgress = MutableStateFlow<TasteRefreshProgress?>(null)

    /** 联网分析进度流（B：自动 / 手动后台分析进度条）；`null` = 无分析进行中。 */
    fun observeRefreshProgress(): StateFlow<TasteRefreshProgress?> = _refreshProgress.asStateFlow()

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
            if (networkBudget > 0) {
                _refreshProgress.value = TasteRefreshProgress(TasteRefreshProgress.Phase.FETCHING_RATED, 0, ids.size)
            }
            val features: Map<String, WorkFeature> = runCatching {
                workFeatureRepository.getFeatures(ids, networkBudget) { done, total ->
                    if (networkBudget > 0) {
                        _refreshProgress.value =
                            TasteRefreshProgress(TasteRefreshProgress.Phase.FETCHING_RATED, done, total)
                    }
                }
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
            // RC.16 候选池校准：以本地缓存的**未评分**作品特征全体为校准池，其 rawZ 中位定 μ，使未评分作品
            // 出分落在 50 分附近、按契合度拉开（详见 BuildTasteProfileUseCase.calibrate）。排除已评分作品
            // （它们走显式评分锚定，且会污染「典型未评分作品」中心）。池不足时 build 内部回退训练样本留一 z。
            val ratedIds = rated.mapTo(HashSet()) { it.sourceItemId }
            var calibrationPool = runCatching { workFeatureRepository.getCachedPool() }
                .getOrDefault(emptyList())
                .filter { it.subjectId !in ratedIds }
            // G Step2：冷启动候选池补齐——仅在联网重建（refreshFull）且未评分缓存池不足 CALIB_POOL_TARGET 时，
            // 从本地发现池（works 表）取未评分且未缓存的动画，best-effort 联网补齐其 work_features 落库后重取池。
            // 根因：refreshFull 原本只拉「已评分」作品特征，未评分候选池近乎为空 → calibrate 回退幸存者偏置的
            // 训练样本 LOO（μ 偏高）→ 未评分作品普遍塌到十几分。补足真实未评分作品后 μ=median(池) 方为「典型
            // 未评分中心」，未评分作品出分落回 50 附近并按契合度拉开。补齐失败静默（保持原池，回退 Step1 低分位）。
            if (networkBudget > 0 && calibrationPool.size < CALIB_POOL_TARGET) {
                runCatching {
                    backfillCalibrationPool(ratedIds, calibrationPool.mapTo(HashSet()) { it.subjectId })
                }
                calibrationPool = runCatching { workFeatureRepository.getCachedPool() }
                    .getOrDefault(emptyList())
                    .filter { it.subjectId !in ratedIds }
            }
            if (networkBudget > 0) {
                _refreshProgress.value = TasteRefreshProgress(TasteRefreshProgress.Phase.BUILDING)
            }
            val profile = buildProfile(samples, now, calibrationPool, loadTagOverrides())
            // N2：显式评分锚定必须覆盖「全部已评分作品」——含特征未取到、未进 50 样本者，否则这些作品
            // 在详情页拿不到显式评分锚（rating 查不到）→ 仍会塌到个位数。用完整评分表覆盖 ratedSubjectScores。
            val allRatedScores = rated.mapNotNull { c -> c.rating?.let { c.sourceItemId to it } }.toMap()
            val finalProfile = if (allRatedScores.isEmpty()) profile else profile.copy(ratedSubjectScores = allRatedScores)
            _profile.value = finalProfile
            if (networkBudget > 0) _refreshProgress.value = null
            finalProfile
        }
    }

    /**
     * G Step2 冷启动候选池补齐：从本地发现池（`works` 表）取**未评分且尚未缓存**的动画 id，
     * best-effort 联网补齐其 [WorkFeature]（复用 [WorkFeatureRepository.getFeatures] 的取 + 落库）。
     * 只拉「补足到 [CALIB_POOL_TARGET] 的缺口」，避免无谓联网；无候选或失败即静默返回（回退 LOO 校准）。
     */
    private suspend fun backfillCalibrationPool(ratedIds: Set<String>, cachedNonRatedIds: Set<String>) {
        val deficit = CALIB_POOL_TARGET - cachedNonRatedIds.size
        if (deficit <= 0) return
        val candidates = workDao.getIdsByMediaType(MediaType.ANIME.name, CALIB_POOL_TARGET * 4)
            .asSequence()
            .filter { it.isNotBlank() && it.toIntOrNull() != null }
            .filter { it !in ratedIds && it !in cachedNonRatedIds }
            .distinct()
            .take(deficit)
            .toList()
        if (candidates.isEmpty()) return
        _refreshProgress.value = TasteRefreshProgress(TasteRefreshProgress.Phase.FETCHING_POOL, 0, candidates.size)
        workFeatureRepository.getFeatures(candidates, networkBudget = candidates.size) { done, total ->
            _refreshProgress.value = TasteRefreshProgress(TasteRefreshProgress.Phase.FETCHING_POOL, done, total)
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
        computeMatch(feature, profile, userRating, loadTagOverrides())
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
        val tagOverrides = loadTagOverrides()
        features.mapValues { (_, f) -> computeMatch(f, profile, null, tagOverrides) }
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

        /**
         * G Step2 冷启动候选池目标规模：未评分缓存池 < 此值且联网重建时，从发现池补齐到此值。
         * 取 40（> [BuildTasteProfileUseCase] 的 CALIB_MIN_POOL=20 留余量），使 μ=median(池) 稳定可信。
         */
        const val CALIB_POOL_TARGET: Int = 40
    }
}
