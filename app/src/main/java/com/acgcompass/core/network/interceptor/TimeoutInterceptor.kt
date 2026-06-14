package com.acgcompass.core.network.interceptor

import okhttp3.Interceptor
import okhttp3.Response
import java.util.concurrent.TimeUnit

/**
 * 超时强化拦截器（RC.01 3.9）。
 *
 * 端到端 10s **调用超时**由 [com.acgcompass.core.network.HttpClientFactory]（`callTimeout`）统一强制；
 * 本拦截器作为补充，在单次请求维度再覆盖 connect / read / write 三段超时，避免个别请求因默认档位
 * 偏大而长时间挂起，同时为「按请求覆盖超时」预留扩展点。
 *
 * 注意：OkHttp 的 `Interceptor.Chain` 仅支持覆盖 connect/read/write 超时，整体 callTimeout 仍由
 * 客户端配置决定；二者共同确保「10 秒内未返回即视为失败」的语义（RC.01 3.9）。
 */
class TimeoutInterceptor(
    private val connectTimeoutMillis: Int = DEFAULT_TIMEOUT_MILLIS,
    private val readTimeoutMillis: Int = DEFAULT_TIMEOUT_MILLIS,
    private val writeTimeoutMillis: Int = DEFAULT_TIMEOUT_MILLIS,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response =
        chain
            .withConnectTimeout(connectTimeoutMillis, TimeUnit.MILLISECONDS)
            .withReadTimeout(readTimeoutMillis, TimeUnit.MILLISECONDS)
            .withWriteTimeout(writeTimeoutMillis, TimeUnit.MILLISECONDS)
            .proceed(chain.request())

    companion object {
        /** REST 默认每段超时：10s（RC.01 3.9）。 */
        const val DEFAULT_TIMEOUT_MILLIS: Int = 10_000
    }
}
