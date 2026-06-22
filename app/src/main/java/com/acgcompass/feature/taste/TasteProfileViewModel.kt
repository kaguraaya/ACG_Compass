package com.acgcompass.feature.taste

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.acgcompass.core.common.AppResult
import com.acgcompass.core.ui.Cta
import com.acgcompass.core.ui.UiState
import com.acgcompass.data.local.dao.UserCollectionDao
import com.acgcompass.data.sync.BangumiSyncManager
import com.acgcompass.domain.model.TasteProfile
import com.acgcompass.domain.repository.TasteProfileRepository
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
) : ViewModel() {

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _importing = MutableStateFlow(false)
    val importing: StateFlow<Boolean> = _importing.asStateFlow()

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
                                is AppResult.Success -> _message.value = "已导入 ${records.size} 条记录并刷新口味画像"
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

    fun clearMessage() {
        _message.value = null
    }

    private companion object {
        val EMPTY_CTA = Cta(label = "从 Bangumi 导入口味数据（或先在设置登录）", action = "import")
    }
}
