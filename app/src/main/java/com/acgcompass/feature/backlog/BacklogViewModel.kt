package com.acgcompass.feature.backlog

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.acgcompass.core.common.AppError
import com.acgcompass.core.ui.Cta
import com.acgcompass.core.ui.UiState
import com.acgcompass.data.credential.CredentialStore
import com.acgcompass.data.credential.SourceId as CredentialSourceId
import com.acgcompass.data.datastore.SettingsDataStore
import com.acgcompass.data.local.dao.UserCollectionDao
import com.acgcompass.data.local.entity.UserCollectionEntity
import com.acgcompass.data.remote.bangumi.BangumiRemoteDataSource
import com.acgcompass.domain.model.MediaType
import com.acgcompass.domain.model.Priority
import com.acgcompass.domain.repository.BacklogFilter
import com.acgcompass.domain.repository.BacklogRepository
import com.acgcompass.domain.repository.BacklogSort
import com.acgcompass.domain.repository.BulkOp
import com.acgcompass.domain.repository.DrawCriteria
import com.acgcompass.domain.repository.DrawResult
import com.acgcompass.domain.repository.WorkRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException

/**
 * 待补池 ViewModel（RC.08 / Requirements 8.1, 10.1–10.3）。MVVM + Hilt + StateFlow。
 *
 * 职责：
 * - 持有筛选 [BacklogFilter] 与排序 [BacklogSort] 的可变状态；二者变化时通过 [flatMapLatest]
 *   重新订阅 [BacklogRepository.observeBacklog]，并与 [WorkRepository.observeWorks] 联结为
 *   可直接渲染的 [BacklogCardItem] 列表（统一作品卡片 RC.03.09）。
 * - 暴露 `StateFlow<UiState<List<BacklogCardItem>>>`（七态 Page_State，RC.03.07）。
 * - 优先级 / 备注编辑（RC.08.04）：委托仓库挂起方法，结果经 Room Flow 自动回流到 UI。
 *
 * 韧性：列表流的异常兜底为 [UiState.Error]，绝不让页面崩溃（RC.03.04 / RC.17.4）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class BacklogViewModel @Inject constructor(
    private val backlogRepository: BacklogRepository,
    private val workRepository: WorkRepository,
    private val userCollectionDao: UserCollectionDao,
    private val bangumiDataSource: BangumiRemoteDataSource,
    private val credentialStore: CredentialStore,
    private val settingsDataStore: SettingsDataStore,
) : ViewModel() {

    /** I17：待补池展示形态（网格/列表）持久化偏好。 */
    val gridMode: StateFlow<Boolean> =
        settingsDataStore.backlogGridMode
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), false)

    /** I17：切换并持久化网格/列表形态。 */
    fun onToggleGridMode() {
        viewModelScope.launch { settingsDataStore.setBacklogGridMode(!gridMode.value) }
    }

    /** I4：把作品移出吃灰馆（回到待补池非吃灰区）。 */
    fun onRestoreFromDust(workId: String) {
        viewModelScope.launch { backlogRepository.bulk(BulkOp.RESTORE_FROM_DUST_MUSEUM, listOf(workId)) }
    }

    /** I4：吃灰馆专用——仅吃灰区条目，按加入时间倒序的卡片流（独立页面消费）。 */
    val dustMuseumCards: StateFlow<UiState<List<BacklogCardItem>>> =
        combine(
            backlogRepository.observeBacklog(
                filter = BacklogFilter(inDustMuseum = true),
                sort = BacklogSort.DUST_DAYS_DESC,
            ),
            workRepository.observeWorks(),
        ) { items, works ->
            val byId = works.associateBy { it.id }
            val cards = items.map { BacklogCardItem(it, byId[it.workId]) }
            if (cards.isEmpty()) UiState.Empty(DUST_EMPTY_CTA) else UiState.Success(cards)
        }.catch { throwable ->
            if (throwable is CancellationException) throw throwable
            emit(UiState.Error(AppError.Server()))
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = UiState.Loading,
        )

    // P1-4：待补池主列表默认排除吃灰馆条目（inDustMuseum=false），使「归档到吃灰馆」是真正的移动
    // 而非复制——吃灰条目只在独立吃灰馆页（dustMuseumCards）展示，不再同时滞留主列表。用户仍可用
    // 「吃灰状态」筛选切到「全部」(null) 或「仅吃灰」(true) 查看。
    private val _filter = MutableStateFlow(BacklogFilter(inDustMuseum = false))
    val filter: StateFlow<BacklogFilter> = _filter.asStateFlow()

    private val _sort = MutableStateFlow(BacklogSort.ADDED_DESC)
    val sort: StateFlow<BacklogSort> = _sort.asStateFlow()

    /** 多选模式开关（RC.08.05）。 */
    private val _selectionMode = MutableStateFlow(false)
    val selectionMode: StateFlow<Boolean> = _selectionMode.asStateFlow()

    /** 已选中的作品 id 集合（RC.08.05）。 */
    private val _selectedIds = MutableStateFlow<Set<String>>(emptySet())
    val selectedIds: StateFlow<Set<String>> = _selectedIds.asStateFlow()

    /** 一键抽番结果（RC.08.06）；`null` 表示当前无抽番弹窗。 */
    private val _drawResult = MutableStateFlow<DrawResult?>(null)
    val drawResult: StateFlow<DrawResult?> = _drawResult.asStateFlow()

    val uiState: StateFlow<UiState<List<BacklogCardItem>>> =
        combine(_filter, _sort) { f, s -> f to s }
            .flatMapLatest { (f, s) ->
                combine(
                    backlogRepository.observeBacklog(f, s),
                    workRepository.observeWorks(),
                ) { items, works ->
                    val byId = works.associateBy { it.id }
                    val cards = items.map { BacklogCardItem(it, byId[it.workId]) }
                    if (cards.isEmpty()) {
                        UiState.Empty(EMPTY_CTA)
                    } else {
                        UiState.Success(cards)
                    }
                }
            }
            .catch { throwable ->
                if (throwable is CancellationException) throw throwable
                emit(UiState.Error(AppError.Server()))
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
                initialValue = UiState.Loading,
            )

    // region 排序 / 筛选（RC.08.02）

    /** 设置排序方式。 */
    fun onSortSelected(sort: BacklogSort) {
        _sort.value = sort
    }

    /** 切换某优先级过滤（再次点击取消）。 */
    fun onTogglePriorityFilter(priority: Priority) {
        _filter.update { current ->
            val next = if (priority in current.priorities) {
                current.priorities - priority
            } else {
                current.priorities + priority
            }
            current.copy(priorities = next)
        }
    }

    /** 切换某媒介类型过滤（再次点击取消）。 */
    fun onToggleMediaTypeFilter(mediaType: MediaType) {
        _filter.update { current ->
            val next = if (mediaType in current.mediaTypes) {
                current.mediaTypes - mediaType
            } else {
                current.mediaTypes + mediaType
            }
            current.copy(mediaTypes = next)
        }
    }

    /** 在「全部 / 仅吃灰 / 仅非吃灰」之间切换吃灰博物馆过滤。 */
    fun onToggleDustMuseumFilter(value: Boolean?) {
        _filter.update { it.copy(inDustMuseum = value) }
    }

    /** 清除全部过滤条件（保留待补池主列表默认排除吃灰馆条目的基线，P1-4）。 */
    fun onClearFilters() {
        _filter.value = BacklogFilter(inDustMuseum = false)
    }

    // endregion

    // region 编辑（RC.08.04）

    /** 设置某条目优先级。 */
    fun onSetPriority(workId: String, priority: Priority) {
        viewModelScope.launch { backlogRepository.setPriority(workId, priority) }
    }

    /** 设置 / 清除某条目自定义备注。 */
    fun onSetNote(workId: String, note: String?) {
        viewModelScope.launch { backlogRepository.setNote(workId, note) }
    }

    // endregion

    // region 多选与批量操作（RC.08.05 / Requirements 10.4）

    /** 进入 / 退出多选模式；退出时清空已选。 */
    fun onToggleSelectionMode() {
        val next = !_selectionMode.value
        _selectionMode.value = next
        if (!next) _selectedIds.value = emptySet()
    }

    /** 切换某条目的选中状态（再次点击取消）。 */
    fun onToggleSelect(workId: String) {
        _selectedIds.update { current ->
            if (workId in current) current - workId else current + workId
        }
    }

    /** 清空已选条目。 */
    fun onClearSelection() {
        _selectedIds.value = emptySet()
    }

    /**
     * 对当前已选条目执行批量操作（RC.08.05）。操作完成后清空选择并退出多选模式；
     * 结果经 Room Flow 自动回流到 UI。空选择时为无操作。
     */
    fun onBulk(op: BulkOp) {
        val ids = _selectedIds.value.toList()
        if (ids.isEmpty()) return
        viewModelScope.launch {
            backlogRepository.bulk(op, ids)
            // H5：吃灰池 = Bangumi 搁置。归档进吃灰馆时，把这些条目本地置「搁置」并回写 Bangumi（best-effort）。
            if (op == BulkOp.ARCHIVE_TO_DUST_MUSEUM) {
                ids.forEach { archiveToBangumiShelved(it) }
            }
            _selectedIds.value = emptySet()
            _selectionMode.value = false
        }
    }

    /**
     * H5：把作品在本地 user_collections 标记为「搁置」，并在已配置 Bangumi 凭据时回写云端（type=4）。
     * 已「看过」的作品不因吃灰而回退状态；非数字 id（非 Bangumi 来源）仅本地标记。失败忽略。
     */
    private suspend fun archiveToBangumiShelved(workId: String) {
        runCatching {
            val now = System.currentTimeMillis()
            val existing = userCollectionDao.getByWork(workId)
            if (existing?.status == "看过") return@runCatching
            userCollectionDao.upsert(
                UserCollectionEntity(
                    id = existing?.id ?: "BANGUMI:$workId",
                    source = "BANGUMI",
                    sourceItemId = existing?.sourceItemId ?: workId,
                    localWorkId = workId,
                    status = "搁置",
                    rating = existing?.rating,
                    progress = existing?.progress,
                    comment = existing?.comment,
                    tags = existing?.tags.orEmpty(),
                    updatedAt = now,
                    syncedAt = existing?.syncedAt ?: now,
                    sourceUpdatedAt = existing?.sourceUpdatedAt,
                ),
            )
            val subjectId = workId.toIntOrNull() ?: return@runCatching
            val configured =
                credentialStore.observeStatus().first()[CredentialSourceId.BANGUMI]?.configured == true
            if (!configured) return@runCatching
            bangumiDataSource.updateUserCollection(subjectId = subjectId, type = 4)
        }
    }

    // endregion

    // region 一键抽番（RC.08.06 / Requirements 10.6, 8.4）

    /**
     * 一键抽番：从待补池中按硬过滤约束抽取一部作品并给出可解释理由（非纯随机）。
     * 结果写入 [drawResult] 供 UI 弹窗展示。
     */
    fun onDraw() {
        viewModelScope.launch {
            _drawResult.value = backlogRepository.draw(DrawCriteria())
        }
    }

    /** 关闭抽番结果弹窗。 */
    fun onDismissDraw() {
        _drawResult.value = null
    }

    // endregion

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
        val EMPTY_CTA = Cta(label = "去导入 / 搜索添加", action = "import")
        val DUST_EMPTY_CTA = Cta(label = "吃灰馆还是空的", action = "backlog")
    }
}
