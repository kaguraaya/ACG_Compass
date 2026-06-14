package com.acgcompass.core.network.interceptor

import com.acgcompass.data.credential.SourceId
import com.acgcompass.data.datastore.SettingsDataStore
import com.acgcompass.data.datastore.SettingsState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Bangumi API 地址动态改写拦截器（R55/R58）+ Token 隐私保护（R56）。
 *
 * Retrofit 的 baseUrl 在构建期固定为官方地址；本拦截器在请求期对**标注为 Bangumi 源**的请求，
 * 按用户在设置中选择的 API Base URL（[SettingsDataStore.bangumiApiBaseUrl]：官方 / 社区反代 / 自定义）
 * 改写 scheme/host/port，从而让**所有** Bangumi 功能（搜索 / 详情 / getMe / 收藏同步 / 连接测试 …）
 * 统一走同一个配置地址（RC.01 / R58）。
 *
 * 隐私（R56）：当目标为**非官方**地址且用户**未确认**风险时，移除该请求的 `Authorization` 头，
 * 确保个人 Token 绝不在未同意的情况下发往第三方（公共搜索仍可匿名工作）。因此本拦截器需排在
 * [AuthInterceptor] **之后**（先注入再按需移除）。
 *
 * 读取配置用 `runBlocking`（运行在 OkHttp 后台分发线程，不阻塞主线程，与 [AuthInterceptor] 同模式）。
 */
class BangumiBaseUrlInterceptor(
    private val settingsDataStore: SettingsDataStore,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        // 仅作用于 Bangumi 源请求。
        if (request.sourceAuth()?.sourceId != SourceId.BANGUMI) {
            return chain.proceed(request)
        }
        val settings = runCatching {
            runBlocking { settingsDataStore.settings.first() }
        }.getOrNull() ?: return chain.proceed(request)

        val configured = settings.bangumiApiBaseUrl.takeIf { it.isNotBlank() }
            ?: return chain.proceed(request)
        val base = configured.toHttpUrlOrNull() ?: return chain.proceed(request)

        var builder = request.newBuilder()
        val original = request.url
        val needsRewrite = original.host != base.host ||
            original.scheme != base.scheme ||
            original.port != base.port
        if (needsRewrite) {
            builder = builder.url(
                original.newBuilder()
                    .scheme(base.scheme)
                    .host(base.host)
                    .port(base.port)
                    .build(),
            )
        }
        // R56：非官方地址且未确认风险 → 移除 Authorization（不向第三方发送个人 Token）。
        val official = configured.trimEnd('/') == SettingsState.DEFAULT_BANGUMI_API_BASE_URL.trimEnd('/')
        if (!official && !settings.bangumiNonOfficialTokenConsent) {
            builder = builder.removeHeader("Authorization")
        }
        return chain.proceed(builder.build())
    }
}
