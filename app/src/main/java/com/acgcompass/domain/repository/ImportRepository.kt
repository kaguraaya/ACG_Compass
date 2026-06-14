package com.acgcompass.domain.repository

import com.acgcompass.core.common.AppResult
import com.acgcompass.domain.model.ImportBatch
import com.acgcompass.domain.model.ImportItem
import com.acgcompass.domain.model.ImportSource
import com.acgcompass.domain.model.Work
import com.acgcompass.domain.usecase.ParsedCandidate
import kotlinx.coroutines.flow.Flow

/**
 * 批量导入仓库契约（领域层，纯 Kotlin，RC.06）。负责把解析出的候选生成 Import_Batch、
 * 逐条多源匹配、记录被安利次数，并支持用户确认低置信条目与一键加入待补池（去重）。
 *
 * _Requirements: 8.5, 8.6, 8.7, 8.8_
 */
interface ImportRepository {

    /** 观察全部导入批次（按创建时间降序，RC.06.05）。 */
    fun observeBatches(): Flow<List<ImportBatch>>

    /** 观察某批次下的全部导入明细（RC.06.05）。 */
    fun observeItems(batchId: String): Flow<List<ImportItem>>

    /**
     * 由解析候选生成一个导入批次（RC.06.05 / 需求 8.5）。
     *
     * 逐条对候选标题做多源搜索与匹配：高置信（≥ 阈值）自动匹配并将作品写入本地单一可信源；
     * 低置信标记为待用户确认（RC.06.08 / 需求 8.8）；无任何候选则记为失败。批次记录批次名、
     * 时间、来源、识别数量、成功数量与失败数量。
     *
     * @param name 批次名（用户可读）。
     * @param source 导入来源（粘贴 / 剪贴板 / 文件 / OCR）。
     * @param candidates 解析得到的候选作品（保持原始顺序，可含重复）。
     * @return 成功时为新建的 [ImportBatch]；失败时为 [AppResult.Failure]（绝不崩溃，RC.17.4）。
     */
    suspend fun createBatch(
        name: String,
        source: ImportSource,
        candidates: List<ParsedCandidate>,
    ): AppResult<ImportBatch>

    /**
     * 用户为某低置信 / 未匹配的导入明细确认正确作品（RC.06.08 / 需求 8.8）。确认后该明细状态
     * 置为已匹配，作品写入本地单一可信源，批次成功 / 失败计数随之刷新。条目不存在时为安全空操作。
     *
     * @param itemId 导入明细 id。
     * @param chosen 用户选定的规范化作品。
     */
    suspend fun confirmItem(itemId: String, chosen: Work): AppResult<Unit>

    /**
     * 一键将批次中所有已匹配（含已确认）的作品加入待补池并去重（RC.06.07 / 需求 8.7）。
     * 命中同一作品自增被安利次数（RC.06.06 / 需求 8.6）。操作幂等：重复调用不重复加入。
     *
     * @param batchId 批次 id。
     * @return 成功时为 [AddResult]（新增 / 去重明细）；失败时为 [AppResult.Failure]。
     */
    suspend fun addBatchToBacklog(batchId: String): AppResult<AddResult>
}
