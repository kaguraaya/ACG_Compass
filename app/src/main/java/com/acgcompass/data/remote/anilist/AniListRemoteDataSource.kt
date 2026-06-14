package com.acgcompass.data.remote.anilist

import com.acgcompass.core.common.AppError
import com.acgcompass.core.common.AppResult
import com.acgcompass.core.common.asException
import com.acgcompass.core.common.runCatchingApp
import com.acgcompass.domain.model.RatingEntry
import com.acgcompass.domain.model.Work
import com.acgcompass.domain.model.WorkMatch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AniList 远程数据源（P1 辅助源，RC.01 3.3/3.8/3.11）。
 *
 * 职责：调用 [AniListApi]（GraphQL）→ DTO 经 [AniListMappers] 转领域模型，统一返回 [AppResult]
 * （异常兜底，绝不崩溃，RC.03.04 / RC.17.4）。
 *
 * 鉴权：[AniListApi] 复用的 [com.acgcompass.core.network.graphql.GraphQlClient] 已打上
 * `SourceAuths.anilist` 标签；有凭据时由拦截器注入 `Authorization: Bearer <token>`，无凭据时
 * 透传匿名读取公共数据（**AniList 公共查询免鉴权**，RC.01 3.3）。
 *
 * 降级中文兜底（RC.01 3.11）：AniList 无中文标题，[Work.titles.canonical] 取英文标题，
 * 作为 Bangumi（P0，提供中文名）不可用时的兜底展示。
 */
@Singleton
class AniListRemoteDataSource @Inject constructor(
    private val api: AniListApi,
) {

    /** 按 AniList Media ID 取详情 → 规范化 [Work]。 */
    suspend fun getMedia(mediaId: Int): AppResult<Work> = runCatchingApp {
        api.getMediaById(mediaId).toWork()
    }

    /** 按 Media ID 取评分 → 单源 [RatingEntry]；评分缺失返回 `Failure(FieldMissing)`（UI「暂无数据」）。 */
    suspend fun getMediaRating(mediaId: Int): AppResult<RatingEntry> = runCatchingApp {
        api.getMediaById(mediaId).toRatingEntry() ?: throw AppError.FieldMissing().asException()
    }

    /**
     * 按标题搜索 → 候选 [WorkMatch] 列表（精确置信度由上层匹配器另行计算）。
     *
     * @param type 可选媒介过滤（`ANIME` / `MANGA`，见 [AniListMediaType]）；为 `null` 时不限类型。
     */
    suspend fun searchMedia(
        keyword: String,
        type: String? = null,
        perPage: Int = AniListQueries.DEFAULT_SEARCH_PER_PAGE,
    ): AppResult<List<WorkMatch>> = runCatchingApp {
        api.searchMedia(keyword, type, perPage).map { it.toWorkMatch() }
    }

    /**
     * F10：取当前趋势榜单（公共数据，免鉴权）。返回每条 Media 的 [Work] 与可空 [RatingEntry]
     * （评分缺失为 `null` → 不伪造，由上层按需写入）。供发现页 / 首页公共发现池使用。
     */
    suspend fun getTrending(
        perPage: Int = AniListQueries.DEFAULT_TRENDING_PER_PAGE,
    ): AppResult<List<Pair<WorkMatch, RatingEntry?>>> = runCatchingApp {
        api.getTrending(perPage).map { dto -> dto.toWorkMatch() to dto.toRatingEntry() }
    }
}
