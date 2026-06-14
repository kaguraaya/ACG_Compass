package com.acgcompass.domain.repository

import com.acgcompass.core.common.AppResult
import com.acgcompass.domain.model.BacklogItem
import com.acgcompass.domain.model.Priority
import com.acgcompass.domain.model.Work
import kotlinx.coroutines.flow.Flow

/**
 * 待补池仓库契约（领域层，纯 Kotlin，RC.08）。负责待补条目的观察、批量加入（去重）、
 * 优先级 / 批量操作与一键抽番。
 *
 * _Requirements: 7.2, 8.7, 8.6_
 */
interface BacklogRepository {

    /**
     * 按过滤与排序观察待补池（RC.08.01/02）。条件变化时应重新发射。
     *
     * @param filter 过滤条件；默认 [BacklogFilter.NONE] 不过滤。
     * @param sort 排序方式；默认按加入时间降序。
     */
    fun observeBacklog(
        filter: BacklogFilter = BacklogFilter.NONE,
        sort: BacklogSort = BacklogSort.ADDED_DESC,
    ): Flow<List<BacklogItem>>

    /**
     * 批量加入待补池并去重（RC.06.07 / Property 10）。重复作品不新增条目，其被安利次数自增
     * （RC.06.06 / Property 11）。该操作幂等：对同一集合重复执行不增大池规模。
     *
     * @param items 待加入的作品集合（可含重复）。
     * @return [AddResult]：新增与去重的作品 id 明细。
     */
    suspend fun addAll(items: List<Work>): AddResult

    /**
     * 设置某待补条目的优先级（RC.08.04）。
     *
     * @param id 作品 id（待补条目主键）。
     * @param p 目标优先级。
     */
    suspend fun setPriority(id: String, p: Priority)

    /**
     * 设置 / 清除某待补条目的自定义备注（RC.08.04）。
     *
     * @param id 作品 id（待补条目主键）。
     * @param note 备注内容；`null` 或空串表示清除备注。
     */
    suspend fun setNote(id: String, note: String?)

    /**
     * 对选中条目执行批量操作（RC.08.05）。
     *
     * @param op 批量操作类型。
     * @param ids 选中的作品 id 列表。
     * @return 成功时为 [AppResult.Success]（Unit）；失败时为 [AppResult.Failure]。
     */
    suspend fun bulk(op: BulkOp, ids: List<String>): AppResult<Unit>

    /**
     * 一键抽番，返回满足硬过滤约束的条目及可解释理由（RC.08.06 / RC.11 / Property 14）。
     * 无满足约束的候选时 [DrawResult.pick] 为 `null`。
     *
     * @param criteria 抽番硬过滤约束（时间 / 心情 / 风险 / 期末保护 / 深夜等）。
     * @return 抽番结果（含理由）。
     */
    suspend fun draw(criteria: DrawCriteria): DrawResult
}
