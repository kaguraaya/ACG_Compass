package com.acgcompass.data.sync

import com.acgcompass.core.common.DispatcherProvider
import com.acgcompass.data.local.dao.UserCollectionDao
import com.acgcompass.domain.repository.TasteProfileRepository
import com.acgcompass.domain.usecase.TasteInputRecord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import javax.inject.Inject
import javax.inject.Singleton

/**
 * P1-3：口味画像自动更新（RC.10）。观察本地个人收藏 [UserCollectionDao] 的任何变化——
 * Bangumi 同步入库、详情页「我的记录」改状态/评分、加入待补池置「想看」等——防抖后自动
 * 以最新收藏重算并持久化口味画像，无需用户手动点「从 Bangumi 导入」。
 *
 * 设计要点：
 * - **防抖**（[DEBOUNCE_MS]）：把一次批量同步（`upsertAll`）或连续编辑合并为一次重算，
 *   避免逐条触发造成抖动与浪费。
 * - **内容去重**：[distinctUntilChanged] 跳过内容未变的重复发射。
 * - **空样本跳过**：收藏为空时不重算（不以空样本生成无意义画像，RC.10.07）。
 * - **best-effort**：单次重算失败被吞掉（绝不崩溃，下次变更再试，RC.17.4）。
 * - 由 [com.acgcompass.app.AcgCompassApplication] 在启动时以 app 级 scope 调用 [start]，全程随应用存活。
 */
@Singleton
class TasteProfileAutoUpdater @Inject constructor(
    private val userCollectionDao: UserCollectionDao,
    private val tasteProfileRepository: TasteProfileRepository,
    private val dispatchers: DispatcherProvider,
) {

    /**
     * 启动自动更新观察。在个人收藏变化、防抖稳定后，用最新收藏重算口味画像。
     * @param scope 应用级协程作用域（随应用存活，不随单个页面销毁）。
     */
    @OptIn(FlowPreview::class)
    fun start(scope: CoroutineScope) {
        userCollectionDao.observeAll()
            .debounce(DEBOUNCE_MS)
            .distinctUntilChanged()
            .onEach { collections ->
                if (collections.isEmpty()) return@onEach
                val records = collections.map { c ->
                    TasteInputRecord(
                        rating = c.rating,
                        tags = c.tags,
                        reviewText = c.comment,
                        status = c.status,
                    )
                }
                runCatching { tasteProfileRepository.importAndCompute(records) }
            }
            .flowOn(dispatchers.io)
            .launchIn(scope)
    }

    private companion object {
        /** 防抖窗口：合并批量同步 / 连续编辑为一次重算。 */
        const val DEBOUNCE_MS = 1_500L
    }
}
