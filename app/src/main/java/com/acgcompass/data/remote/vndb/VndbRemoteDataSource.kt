package com.acgcompass.data.remote.vndb

import com.acgcompass.core.common.AppError
import com.acgcompass.core.common.AppResult
import com.acgcompass.core.common.asException
import com.acgcompass.core.common.runCatchingApp
import com.acgcompass.core.network.HttpErrorMapper
import com.acgcompass.core.network.interceptor.SourceAuth
import com.acgcompass.core.network.interceptor.SourceAuths
import com.acgcompass.data.datastore.SettingsDataStore
import com.acgcompass.domain.model.RatingEntry
import com.acgcompass.domain.model.Work
import com.acgcompass.domain.model.WorkMatch
import kotlinx.coroutines.flow.first
import retrofit2.Response
import javax.inject.Inject
import javax.inject.Singleton

/**
 * VNDB 远程数据源（P2 末位降级源，RC.01 3.6 / RC.02 4.9）。
 *
 * 职责：构造 [VndbQueryRequestDto]（`filters` + `fields`）→ 调用 [VndbApi] → 检视 HTTP 状态 →
 * 经 [HttpErrorMapper] 映射错误 → DTO 转领域模型 → **按成人内容开关过滤** → 统一返回 [AppResult]
 * （异常兜底，绝不崩溃，RC.03.04 / RC.17.4）。
 *
 * 鉴权：VNDB 公共查询免鉴权；所有请求打上 [SourceAuths.vndb]（`TokenHeaderAuth(VNDB,"Token")`）标签，
 * 由拦截器在用户本机配置了可选 token 时注入 `Authorization: Token <token>`，否则透传匿名查询。
 *
 * 成人内容分级（RC.02 4.9/4.10）：各查询方法接受可选 [showAdultContent] 布尔参数；为 `null` 时
 * 从 [SettingsDataStore.showAdultContent] 读取当前开关。`false` 时过滤/隐藏被标记为成人内容
 * （`image.sexual >= [VNDB_ADULT_SEXUAL_THRESHOLD]`）的条目（[VndbVnDto.isAdult]）。
 */
@Singleton
class VndbRemoteDataSource @Inject constructor(
    private val api: VndbApi,
    private val settingsDataStore: SettingsDataStore,
) {

    private val auth: SourceAuth = SourceAuths.vndb

    /**
     * 按关键字搜索 VN → 候选 [WorkMatch] 列表（精确置信度由上层匹配器另行计算）。
     * 成人内容按 [showAdultContent]（缺省读取设置）过滤。
     */
    suspend fun searchVn(
        keyword: String,
        showAdultContent: Boolean? = null,
        results: Int? = null,
        page: Int? = null,
    ): AppResult<List<WorkMatch>> = runCatchingApp {
        val allowAdult = resolveAdultFlag(showAdultContent)
        val request = VndbQueryRequestDto(
            filters = VndbFilters.bySearch(keyword),
            fields = VndbFieldSets.VN_DETAIL,
            sort = VndbSort.SEARCH_RANK,
            results = results,
            page = page,
        )
        api.queryVn(request, auth).bodyOrThrow().results
            .filter { allowAdult || !it.isAdult() }
            .map { it.toWorkMatch() }
    }

    /**
     * 按 vndbid 取 VN 详情 → 规范化 [Work]；`results` 为空视为缺失（`Failure(FieldMissing)`）。
     * 当条目为成人内容且 [showAdultContent] 为 `false` 时，按「隐藏」处理（`Failure(FieldMissing)`）。
     */
    suspend fun getVn(
        id: String,
        showAdultContent: Boolean? = null,
    ): AppResult<Work> = runCatchingApp {
        val allowAdult = resolveAdultFlag(showAdultContent)
        val vn = fetchVnById(id)
        if (!allowAdult && vn.isAdult()) throw AppError.FieldMissing().asException()
        vn.toWork()
    }

    /**
     * 按 vndbid 取 VN 评分 → 单源 [RatingEntry]（保留 10–100 源标度）；评分缺失返回
     * `Failure(FieldMissing)`（UI 显示「暂无数据」，不以 0 伪造评分）。
     * 成人内容门控同 [getVn]。
     */
    suspend fun getVnRating(
        id: String,
        showAdultContent: Boolean? = null,
    ): AppResult<RatingEntry> = runCatchingApp {
        val allowAdult = resolveAdultFlag(showAdultContent)
        val vn = fetchVnById(id)
        if (!allowAdult && vn.isAdult()) throw AppError.FieldMissing().asException()
        vn.toRatingEntry() ?: throw AppError.FieldMissing().asException()
    }

    /** 按 id 取单个 VN DTO；`results` 为空抛出 [AppError.FieldMissing]。 */
    private suspend fun fetchVnById(id: String): VndbVnDto {
        val request = VndbQueryRequestDto(
            filters = VndbFilters.byId(id),
            fields = VndbFieldSets.VN_DETAIL,
            results = 1,
        )
        return api.queryVn(request, auth).bodyOrThrow().results.firstOrNull()
            ?: throw AppError.FieldMissing().asException()
    }

    /** 解析成人内容开关：显式参数优先，否则读取 [SettingsDataStore] 当前值。 */
    private suspend fun resolveAdultFlag(explicit: Boolean?): Boolean =
        explicit ?: settingsDataStore.showAdultContent.first()

    /**
     * 解析 [Response]：非 2xx 经 [HttpErrorMapper.mapStatusCode] 抛出对应 [AppError]
     * （VNDB：`401` 无效 token、`404` 路径错误、`429` 限流、`5xx` 服务端错误）；2xx 但响应体为空
     * 抛出 [AppError.FieldMissing]。抛出的领域错误会被外层 [runCatchingApp] 还原为
     * `AppResult.Failure`（RC.01 3.9）。
     */
    private fun <T> Response<T>.bodyOrThrow(): T {
        if (!isSuccessful) throw HttpErrorMapper.mapStatusCode(code()).asException()
        return body() ?: throw AppError.FieldMissing().asException()
    }
}
