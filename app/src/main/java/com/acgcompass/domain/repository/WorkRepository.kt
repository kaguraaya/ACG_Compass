package com.acgcompass.domain.repository

import com.acgcompass.core.common.AppResult
import com.acgcompass.core.ui.UiState
import com.acgcompass.domain.model.RatingAggregate
import com.acgcompass.domain.model.SourceRef
import com.acgcompass.domain.model.Work
import com.acgcompass.domain.model.WorkMatch
import kotlinx.coroutines.flow.Flow

/**
 * 作品仓库契约（领域层，纯 Kotlin）。负责规范化作品的观察、多源搜索 / 匹配、评分聚合与手动纠正。
 *
 * 单一可信源为本地缓存（Room）；[observeWork] 始终从本地 Flow 读取，离线可用、状态一致
 * （RC.00 本地优先 / RC.01）。
 *
 * _Requirements: 1.1, 7.2, 8.7_
 */
interface WorkRepository {

    /**
     * 观察某作品的页面状态（RC.03.07）。从本地单一可信源读取，远程刷新后自动发射新值。
     *
     * @param workId 规范化作品 id。
     * @return 七态 [UiState] 流；缺失字段时发射 [UiState.PartialMissing]（RC.07 9.3）。
     */
    fun observeWork(workId: String): Flow<UiState<Work>>

    /**
     * 观察本地全部规范化作品（单一可信源 = Room）。供需要批量作品展示数据的页面使用
     * （如待补池把 [Work] 折叠为统一作品卡片，RC.08.01）。按最近更新降序。
     *
     * @return 本地作品列表流；为空时发射空列表（由调用方决定空态展示）。
     */
    fun observeWorks(): Flow<List<Work>>

    /**
     * 多源搜索并合并，返回带匹配置信度的候选（RC.05.02 / Property 8）。
     *
     * 高于阈值的结果自动合并到同一规范化 [Work]，低于阈值的标记为待用户确认（不自动合并）。
     *
     * @param query 搜索关键词（标题 / 别名等）。
     * @return 成功时为按相关性排序的 [WorkMatch] 列表；失败时为 [AppResult.Failure]。
     */
    suspend fun search(query: String): AppResult<List<WorkMatch>>

    /**
     * 聚合某作品的多源评分（RC.07 9.x / Property 5）。缺失源标记为 `missing`，**绝不**用其它源回填；
     * 有效样本不足时社区共识为 `null` 或低置信。
     *
     * @param workId 规范化作品 id。
     * @return 成功时为 [RatingAggregate]；失败时为 [AppResult.Failure]。
     */
    suspend fun aggregateRatings(workId: String): AppResult<RatingAggregate>

    /**
     * K9：仅读取本地已缓存的多源评分聚合（**不触发网络刷新**）。用于发现页榜单 / 评分差异 / 筛选
     * 等需要遍历大量作品的场景，避免逐作品网络刷新造成的卡顿 / 永不收敛。
     *
     * @return 本地评分聚合；无缓存评分时各源为 `null`（不伪造）。
     */
    suspend fun aggregateRatingsCached(workId: String): RatingAggregate

    /**
     * 手动纠正某本地作品的源匹配（RC.05.03 / Property 8）。写入后置 `userOverridden=true`，
     * 后续同步不再自动改写该链接。
     *
     * @param localId 本地规范化作品 id。
     * @param chosen 用户选择的外部源条目引用。
     * @return 成功时为 [AppResult.Success]（Unit）；失败时为 [AppResult.Failure]。
     */
    suspend fun overrideMatch(localId: String, chosen: SourceRef): AppResult<Unit>

    /**
     * F10：加载公共发现池（本季 / Top 榜单，无需 token）。拉取公共榜单作品并连同其评分写入 Room，
     * 使发现页本季榜单 / 评分差异 / 筛选有内容；网络失败返回 [AppResult.Failure]（UI 显示错误 + 重试）。
     *
     * @return 成功时为写入 / 更新的作品数；失败时为 [AppResult.Failure]。
     */
    suspend fun loadPublicDiscovery(): AppResult<Int>

    /**
     * M2（L2b）/ P2-2：按范围分页加载 Bangumi 真实排行榜的**一页**（`sort=rank`，offset/limit）。
     * 总榜（airDate=null）/ 今年 / 本季由 [airDate] 过滤区间决定（如 `[">=2026-01-01", "<2027-01-01"]`）。
     * 命中作品 + 该源评分写入 Room（详情可跳转、冷启动可重建卡片），返回该页作品 + 评分（缺失为 null，不伪造），
     * 按真实排名顺序。空页（分页到底）属正常，返回空列表；仅网络/解析失败返回 [AppResult.Failure]。
     */
    suspend fun loadBangumiRankingPage(
        airDate: List<String>?,
        offset: Int,
        limit: Int,
    ): AppResult<RankingPage>

    /**
     * P2-3：读取某榜单范围已持久化的缓存（冷启动秒开）。按缓存的有序作品 id 从 Room 重建作品 + Bangumi 评分；
     * 无缓存或作品已被清理时返回空。[scopeKey] 为范围稳定键（如 OVERALL/YEAR/SEASON）。
     */
    suspend fun getCachedRanking(scopeKey: String): List<Pair<Work, com.acgcompass.domain.model.RatingEntry?>>

    /** P2-3：持久化某榜单范围的有序作品 id（覆盖写），供下次冷启动秒开。 */
    suspend fun saveRankingCache(scopeKey: String, orderedWorkIds: List<String>)
}

/**
 * P2-2：榜单分页结果。[items] 为该页作品 + Bangumi 评分（按真实排名顺序，缺失评分为 null）；
 * [total] 为该范围过滤后的总条目数（来自 Bangumi 分页响应），供上层判断是否还有更多页——
 * 据此修复「某页返回数 < limit 即误判到底」（Bangumi 实验性搜索分页可能返回少于 limit 条）。
 */
data class RankingPage(
    val items: List<Pair<Work, com.acgcompass.domain.model.RatingEntry?>>,
    val total: Int,
)
