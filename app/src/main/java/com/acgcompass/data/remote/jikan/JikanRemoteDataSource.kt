package com.acgcompass.data.remote.jikan

import com.acgcompass.core.common.AppError
import com.acgcompass.core.common.AppResult
import com.acgcompass.core.common.asException
import com.acgcompass.core.common.runCatchingApp
import com.acgcompass.core.network.HttpErrorMapper
import com.acgcompass.core.network.interceptor.SourceAuth
import com.acgcompass.core.network.interceptor.SourceAuths
import com.acgcompass.domain.model.RatingEntry
import com.acgcompass.domain.model.Work
import com.acgcompass.domain.model.WorkMatch
import retrofit2.Response
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Jikan 远程数据源（P1/P2 补充源，RC.01 3.4/3.8/3.10）。
 *
 * 职责：调用 [JikanApi] → 检视 HTTP 状态 → 经 [HttpErrorMapper] 映射错误 → DTO 转领域模型，
 * 统一返回 [AppResult]（异常兜底，绝不崩溃，RC.03.04 / RC.17.4）。
 *
 * 鉴权：Jikan 无需 key，所有请求都打上 [SourceAuths.jikan]（`NoAuth`）标签；该标签的主要作用是
 * 让 [com.acgcompass.core.network.interceptor.RateLimitInterceptor] 据 [SourceAuth.sourceId] 命中
 * Jikan 的**双桶限流器**（3 req/s 且 60 req/min，见 `RateLimiterRegistry.default`），在接近上限
 * 80% 时主动错峰（RC.01 3.4 / 3.10）。
 */
@Singleton
class JikanRemoteDataSource @Inject constructor(
    private val api: JikanApi,
) {

    private val auth: SourceAuth = SourceAuths.jikan

    /** 搜索动画 → 候选 [WorkMatch] 列表（精确置信度由上层匹配器另行计算）。 */
    suspend fun searchAnime(
        keyword: String,
        limit: Int? = null,
        page: Int? = null,
        sfw: Boolean? = null,
    ): AppResult<List<WorkMatch>> = runCatchingApp {
        api.searchAnime(keyword, limit, page, sfw, auth).bodyOrThrow().data.map { it.toWorkMatch() }
    }

    /** 动画详情 → 规范化 [Work]；`data` 为空视为缺失（`Failure(FieldMissing)`）。 */
    suspend fun getAnime(id: Int): AppResult<Work> = runCatchingApp {
        val anime = api.getAnime(id, auth).bodyOrThrow().data
            ?: throw AppError.FieldMissing().asException()
        anime.toWork()
    }

    /** 动画 MAL 评分 → 单源 [RatingEntry]；评分缺失返回 `Failure(FieldMissing)`（UI 显示「暂无数据」）。 */
    suspend fun getAnimeRating(id: Int): AppResult<RatingEntry> = runCatchingApp {
        val anime = api.getAnime(id, auth).bodyOrThrow().data
            ?: throw AppError.FieldMissing().asException()
        anime.toRatingEntry() ?: throw AppError.FieldMissing().asException()
    }

    /** 动画用户评论（原样透传 DTO；评论文本 / 标签由上层处理）。 */
    suspend fun getAnimeReviews(id: Int, page: Int? = null): AppResult<List<JikanReviewDto>> =
        runCatchingApp { api.getAnimeReviews(id, page, auth).bodyOrThrow().data }

    /** 动画推荐（原样透传 DTO；推荐目标的展开由上层处理）。 */
    suspend fun getAnimeRecommendations(id: Int): AppResult<List<JikanRecommendationDto>> =
        runCatchingApp { api.getAnimeRecommendations(id, auth).bodyOrThrow().data }

    /** F10：Top 动画榜（公共，无需 token）。返回原始 DTO 供上层映射 Work + 评分。 */
    suspend fun getTopAnime(limit: Int? = 25): AppResult<List<JikanAnimeDto>> =
        runCatchingApp { api.getTopAnime(page = 1, limit = limit, auth = auth).bodyOrThrow().data }

    /** F10：本季动画（公共，无需 token）。 */
    suspend fun getSeasonNow(limit: Int? = 25): AppResult<List<JikanAnimeDto>> =
        runCatchingApp { api.getSeasonNow(page = 1, limit = limit, auth = auth).bodyOrThrow().data }

    /**
     * 解析 [Response]：非 2xx 经 [HttpErrorMapper.mapStatusCode] 抛出对应 [AppError]；
     * 2xx 但响应体为空抛出 [AppError.FieldMissing]。抛出的领域错误会被外层 [runCatchingApp]
     * 捕获并原样还原为 `AppResult.Failure`（RC.01 3.9）。
     */
    private fun <T> Response<T>.bodyOrThrow(): T {
        if (!isSuccessful) throw HttpErrorMapper.mapStatusCode(code()).asException()
        return body() ?: throw AppError.FieldMissing().asException()
    }
}
