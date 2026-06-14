package com.acgcompass.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.acgcompass.core.common.AppError
import com.acgcompass.core.common.AppResult
import com.acgcompass.core.ui.UiState
import com.acgcompass.data.credential.CredentialStore
import com.acgcompass.data.sync.BangumiSyncManager
import com.acgcompass.data.sync.SyncStatusRepository
import com.acgcompass.domain.repository.BacklogFilter
import com.acgcompass.domain.repository.BacklogRepository
import com.acgcompass.domain.repository.BacklogSort
import com.acgcompass.domain.repository.WorkRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException

/**
 * 首页——今日决策中心 ViewModel（RC.04 / Requirements 6.1–6.6）。MVVM + Hilt + StateFlow。
 *
 * 把待补条目（[BacklogRepository.observeBacklog]）与本地作品（[WorkRepository.observeWorks]）
 * 联结为 [HomeBacklogEntry]，再经纯函数 [buildBacklogSummary] 折叠为待补池概览，对外暴露
 * `StateFlow<UiState<HomeUiState>>`（七态 Page_State，RC.03.07）。
 *
 * 待补池为空时发射 [UiState.Empty]（携带导入引导 CTA，RC.03.03 / RC.04.07）。
 * 同步提醒（RC.04.05）由 [CredentialStore.observeStatus] 折叠（仅元数据，无明文）；今日补番签
 * （RC.04.06）由纯函数 [buildDailyDraw] 依当日日期 + 待补池规模确定性生成。
 *
 * 韧性：列表流异常兜底为 [UiState.Error]，绝不崩溃（RC.03.04 / RC.17.4）。
 */
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val backlogRepository: BacklogRepository,
    private val workRepository: WorkRepository,
    private val credentialStore: CredentialStore,
    private val bangumiSyncManager: BangumiSyncManager,
    private val syncStatusRepository: SyncStatusRepository,
    private val settingsDataStore: com.acgcompass.data.datastore.SettingsDataStore,
    private val userCollectionDao: com.acgcompass.data.local.dao.UserCollectionDao,
) : ViewModel() {

    init {
        // F11：首页「近期热门 / 本季」模块依赖公共发现池；进入首页时按需加载一次（失败静默，模块回退提示）。
        viewModelScope.launch { runCatching { workRepository.loadPublicDiscovery() } }
    }

    /** 当前选中的今日状态（RC.04.02）；`null` 表示未选择。 */
    private val selectedMood = MutableStateFlow<HomeMood?>(null)

    private val _syncMessage = MutableStateFlow<String?>(null)

    /** 同步结果一次性提示；`需配置` 时提示去设置。消费后调用 [clearSyncMessage]。 */
    val syncMessage: StateFlow<String?> = _syncMessage.asStateFlow()

    private val _needsBangumiConfig = MutableStateFlow(false)

    /** 触发后置 true，提示界面跳转设置页 Bangumi 配置；消费后置回 false。 */
    val needsBangumiConfig: StateFlow<Boolean> = _needsBangumiConfig.asStateFlow()

    val uiState: StateFlow<UiState<HomeUiState>> =
        combine(
            backlogRepository.observeBacklog(BacklogFilter.NONE, BacklogSort.ADDED_DESC),
            workRepository.observeWorks(),
            selectedMood,
            credentialStore.observeStatus(),
            combine(
                syncStatusRepository.status,
                settingsDataStore.homeModules,
                userCollectionDao.observeAll(),
            ) { s, m, collections -> Triple(s, m, collections) },
        ) { items, works, mood, statuses, syncModulesCollections ->
            val syncStatus = syncModulesCollections.first
            val enabledModules = syncModulesCollections.second
            val collections = syncModulesCollections.third
            val worksById = works.associateBy { it.id }
            val entries = items.map { HomeBacklogEntry(it, worksById[it.workId]) }
            if (entries.isEmpty()) {
                UiState.Empty(HOME_EMPTY_CTA)
            } else {
                UiState.Success(
                    HomeUiState(
                        selectedMood = mood,
                        continueItems = buildContinueItems(collections, worksById),
                        backlogSummary = buildBacklogSummary(entries),
                        syncReminder = buildSyncReminder(statuses, System.currentTimeMillis(), syncStatus),
                        dailyDraw = buildDailyDraw(backlogSize = entries.size),
                        enabledModules = enabledModules,
                        seasonalHot = buildSeasonalHot(works),
                    ),
                )
            }
        }.catch { throwable ->
            if (throwable is CancellationException) throw throwable
            emit(UiState.Error(AppError.Server()))
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = UiState.Loading,
        )

    /** 选择 / 取消今日状态（再次点击同一项取消，RC.04.02）。 */
    fun onMoodSelected(mood: HomeMood) {
        selectedMood.update { current -> if (current == mood) null else mood }
    }

    /**
     * 手动同步入口（RC.04.05 / R85）。已配置 Bangumi → 真正调用 [BangumiSyncManager] 拉取入库，
     * 成功后刷新同步提醒（自增触发器）并提示统计；未配置 → 提示去设置；失败 → 显示原因。
     */
    fun onManualSync() {
        viewModelScope.launch {
            try {
                if (!bangumiSyncManager.isConfigured()) {
                    _needsBangumiConfig.value = true
                    _syncMessage.value = "未配置 Bangumi 账号，请到设置登录后再同步"
                    return@launch
                }
                _syncMessage.value = "正在从 Bangumi 同步…"
                when (val r = bangumiSyncManager.syncCollections()) {
                    is AppResult.Success -> {
                        val rep = r.data
                        _syncMessage.value =
                            "同步完成：新增 ${rep.added} · 更新 ${rep.updated} · 跳过 ${rep.skipped} · 失败 ${rep.failed}"
                        // 同步状态由 SyncStatusRepository 写入，uiState 的 combine 会自动刷新提醒（R93/R100）。
                    }
                    is AppResult.Failure ->
                        _syncMessage.value = "同步失败：${r.error.cause}"
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                _syncMessage.value = "同步失败，请稍后再试"
            }
        }
    }

    fun clearSyncMessage() {
        _syncMessage.value = null
    }

    fun clearNeedsBangumiConfig() {
        _needsBangumiConfig.value = false
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
