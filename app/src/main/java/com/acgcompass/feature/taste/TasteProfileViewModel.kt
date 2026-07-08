package com.acgcompass.feature.taste

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.acgcompass.core.common.AppResult
import com.acgcompass.core.ui.Cta
import com.acgcompass.core.ui.UiState
import com.acgcompass.data.local.dao.UserCollectionDao
import com.acgcompass.data.sync.BangumiSyncManager
import com.acgcompass.data.taste.AiTagClassifier
import com.acgcompass.data.taste.TagClassifyOutcome
import com.acgcompass.data.taste.TagClassifyProgress
import com.acgcompass.data.taste.TagDimensionSummary
import com.acgcompass.data.taste.TasteEngine
import com.acgcompass.data.taste.TasteRefreshProgress
import com.acgcompass.domain.model.TasteProfile
import com.acgcompass.domain.repository.TasteProfileRepository
import com.acgcompass.domain.taste.AdvancedTasteProfile
import com.acgcompass.domain.usecase.TasteInputRecord
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException

/**
 * 口味画像 ViewModel（RC.10 / R47）。观察画像流；提供从 Bangumi 同步并计算画像的真实入口。
 *
 * R47：[onImportFromBangumi] 先用 [BangumiSyncManager] 同步收藏入库，再把入库的用户收藏映射为
 * [TasteInputRecord] 交 [TasteProfileRepository.importAndCompute] 计算并持久化，画像流随之刷新。
 */
@HiltViewModel
class TasteProfileViewModel @Inject constructor(
    private val repository: TasteProfileRepository,
    private val bangumiSyncManager: BangumiSyncManager,
    private val userCollectionDao: UserCollectionDao,
    private val tasteEngine: TasteEngine,
    private val aiTagClassifier: AiTagClassifier,
) : ViewModel() {

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _importing = MutableStateFlow(false)
    val importing: StateFlow<Boolean> = _importing.asStateFlow()

    /** B：联网分析进度（进度条），直接透传引擎进度流；null = 无分析进行中。 */
    val refreshProgress: StateFlow<TasteRefreshProgress?> = tasteEngine.observeRefreshProgress()

    /** N3：AI 标签分维分类进度（进度条），透传分类器进度流；null = 无分类进行中。 */
    val tagClassifyProgress: StateFlow<TagClassifyProgress?> = aiTagClassifier.observeProgress()

    /** A5：12 维引擎画像（各维度偏好 / 题材组合 / 样本量 / 覆盖率 / 均分），供画像页「更丰富」展示；null=尚未构建。 */
    val advancedProfile: StateFlow<AdvancedTasteProfile?> = tasteEngine.observeProfile()

    private val _classifying = MutableStateFlow(false)
    val classifying: StateFlow<Boolean> = _classifying.asStateFlow()

    /**
     * RC.20.2e：AI「升维」效果摘要（各精确维度已细化多少个原本兜底为「题材」的标签）。进入画像页与
     * 每次分维分类完成后刷新；为空（未配置 / 未分类）时页面隐藏该区。
     */
    private val _dimensionSummary = MutableStateFlow<TagDimensionSummary?>(null)
    val dimensionSummary: StateFlow<TagDimensionSummary?> = _dimensionSummary.asStateFlow()

    /** RC.20.2e：刷新升维效果摘要（后台读缓存计数；失败静默保持原值）。 */
    private fun refreshDimensionSummary() {
        viewModelScope.launch {
            runCatching { aiTagClassifier.dimensionSummary() }
                .onSuccess { _dimensionSummary.value = it }
        }
    }

    val uiState: StateFlow<UiState<TasteProfile>> =
        repository.observeTasteProfile()
            .map<TasteProfile?, UiState<TasteProfile>> { profile ->
                if (profile == null) UiState.Empty(EMPTY_CTA) else UiState.Success(profile)
            }
            .catch { e ->
                if (e is CancellationException) throw e
                emit(UiState.Empty(EMPTY_CTA))
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000L),
                initialValue = UiState.Loading,
            )

    /** 是否已配置 Bangumi（决定 CTA 是导入还是跳设置）。 */
    suspend fun isBangumiConfigured(): Boolean = bangumiSyncManager.isConfigured()

    /**
     * R47：从 Bangumi 同步并计算口味画像。
     * @return true 表示已发起导入（已配置）；false 表示未配置，调用方应跳转设置。
     */
    fun onImportFromBangumi(onNotConfigured: () -> Unit) {
        if (_importing.value) return
        _importing.value = true
        viewModelScope.launch {
            try {
                if (!bangumiSyncManager.isConfigured()) {
                    onNotConfigured()
                    return@launch
                }
                when (val sync = bangumiSyncManager.syncCollections()) {
                    is AppResult.Failure -> _message.value = "导入失败：${sync.error.cause}"
                    is AppResult.Success -> {
                        val records = userCollectionDao.getAll().map { c ->
                            TasteInputRecord(
                                rating = c.rating,
                                tags = c.tags,
                                reviewText = c.comment,
                                status = c.status,
                                updatedAt = c.updatedAt,
                            )
                        }
                        if (records.isEmpty()) {
                            _message.value = "Bangumi 暂无可用于画像的收藏 / 评分数据"
                        } else {
                            when (repository.importAndCompute(records)) {
                                is AppResult.Success -> {
                                    _message.value = "已导入 ${records.size} 条记录并刷新口味画像"
                                    // 最终版 12 维引擎：联网补齐 work_features（社区标签计数 + staff/角色/CV）并构建高级画像。
                                    runCatching { tasteEngine.refreshFull() }
                                }
                                is AppResult.Failure -> _message.value = "画像计算失败，请稍后再试"
                            }
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                _message.value = "导入失败，请稍后再试"
            } finally {
                _importing.value = false
            }
        }
    }

    /**
     * A4：手动「重新分析」入口——用当前本地收藏重算统计画像 + 联网补全 12 维引擎特征（无需重新同步 Bangumi）。
     * 供画像页按钮触发；后台执行，进度经 [refreshProgress] 展示。不受节流限制。
     */
    fun onRefreshAnalysis() = runRefresh(isAuto = false)

    /**
     * B：进入画像页时的**自动联网分析**——距上次 12 维引擎画像构建超过 [AUTO_REFRESH_INTERVAL_MS] 且确有已评分
     * 收藏时，后台静默重算（联网补齐 work_features）。节流避免每次进入都联网；失败静默不打扰用户。
     */
    fun onScreenOpened() {
        // RC.20.2e：进入页面即刷新升维摘要（不受下方联网重算节流影响），让已有 AI 分维缓存的效果立即展示。
        refreshDimensionSummary()
        if (_importing.value) return
        val lastBuiltAt = tasteEngine.currentProfile?.generatedAt ?: 0L
        if (System.currentTimeMillis() - lastBuiltAt <= AUTO_REFRESH_INTERVAL_MS) return
        viewModelScope.launch {
            val hasRated = userCollectionDao.getAll().any { it.rating != null && it.sourceItemId.isNotBlank() }
            if (hasRated) runRefresh(isAuto = true)
        }
    }

    /** 重算统计画像 + 联网补齐 12 维特征。[isAuto] 自动触发时不弹 toast（仅靠进度条提示）。 */
    private fun runRefresh(isAuto: Boolean) {
        if (_importing.value) return
        _importing.value = true
        viewModelScope.launch {
            try {
                when (repository.recomputeFromLocal()) {
                    is AppResult.Success -> {
                        runCatching { tasteEngine.refreshFull() }
                        if (!isAuto) _message.value = "已重新分析口味画像"
                    }
                    is AppResult.Failure -> if (!isAuto) _message.value = "重新分析失败，请稍后再试"
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                if (!isAuto) _message.value = "重新分析失败，请稍后再试"
            } finally {
                _importing.value = false
            }
        }
    }

    /**
     * N3：手动触发「AI 标签分维分类」——把本地兜底为题材的未知社区标签分批交 AI 归入更精确维度并缓存，
     * 供画像 / 评分复用。后台执行，进度经 [tagClassifyProgress] 展示；成功后仅用缓存快速重建画像使新维度生效。
     * AI 未配置 / 失败时提示并回退本地规则（不阻塞、不伪造，RC.14.01/03）。
     */
    fun onClassifyTags() {
        if (_classifying.value) return
        _classifying.value = true
        viewModelScope.launch {
            try {
                _message.value = when (val outcome = aiTagClassifier.classifyPending()) {
                    is TagClassifyOutcome.Done ->
                        if (outcome.classified > 0) {
                            runCatching { tasteEngine.rebuildFromCache() }
                            refreshDimensionSummary()
                            "已用 AI 分维分类 ${outcome.classified} 个标签"
                        } else {
                            // A1：AI 有返回但落库 0——多为模型未按要求输出维度 key；给出可操作提示而非笼统「未返回」。
                            "本轮未新增分类：AI 返回的维度不合规，建议更换支持 JSON 结构化输出的模型后重试"
                        }
                    TagClassifyOutcome.NothingToDo -> "没有待分类的新标签"
                    TagClassifyOutcome.NotConfigured -> "未配置 AI，已回退本地规则（可在设置中配置 AI）"
                    TagClassifyOutcome.AiUnresponsive -> "AI 多次未返回合法结果，请检查所选模型 / 网络后重试"
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                _message.value = "标签分维分类失败，请稍后再试"
            } finally {
                _classifying.value = false
            }
        }
    }

    fun clearMessage() {
        _message.value = null
    }

    private companion object {
        val EMPTY_CTA = Cta(label = "从 Bangumi 导入口味数据（或先在设置登录）", action = "import")

        /** B：自动联网分析节流间隔——距上次引擎画像构建超过此值才在进入画像页时后台重算（6 小时）。 */
        const val AUTO_REFRESH_INTERVAL_MS: Long = 6L * 60 * 60 * 1000
    }
}
