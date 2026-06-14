package com.acgcompass.core.network.interceptor

import com.acgcompass.core.network.RateLimiter
import com.acgcompass.data.credential.SourceId
import okhttp3.Interceptor
import okhttp3.Response

/**
 * 每源限流注册表（RC.01 3.4 / 3.10）。
 *
 * 维护「[SourceId] → [RateLimiter]」映射；每个源使用**独立**的限流器实例，支持双桶/多桶规则
 * （如 Jikan 同时满足 3 req/s 与 60 req/min）。未注册的源返回 `null`，表示不限流。
 *
 * 由 DI（`InterceptorModule`）以单例构建，确保同源所有请求共享同一令牌账本。
 */
class RateLimiterRegistry(
    private val limiters: Map<SourceId, RateLimiter>,
) {
    /** 取指定源的限流器；未配置返回 `null`（不限流）。 */
    fun limiterFor(sourceId: SourceId): RateLimiter? = limiters[sourceId]

    companion object {
        /**
         * 构建默认注册表。
         *
         * 当前仅 Jikan 有明确公共速率限制（约 3 req/s 且 60 req/min，以最新文档为准，RC.01 3.4）；
         * 其余源未配置默认限流（接入时按核验文档补充）。
         */
        fun default(clock: () -> Long = { System.nanoTime() / 1_000_000L }): RateLimiterRegistry =
            RateLimiterRegistry(
                mapOf(
                    SourceId.JIKAN to RateLimiter(
                        rules = listOf(
                            RateLimiter.Rule.perSecond(3),
                            RateLimiter.Rule.perMinute(60),
                        ),
                        clock = clock,
                    ),
                ),
            )
    }
}

/**
 * 限流拦截器（RC.01 3.4 / 3.10）。
 *
 * 从请求标注的 [SourceAuth] 识别目标源，向对应 [RateLimiter] 申请放行名额；达到上限的 80%
 * 即主动错峰，并保证任意窗口内放行数不超过该源配置上限。无标注或无对应限流器时透传。
 *
 * 阻塞说明：在 OkHttp 后台分发线程上通过 [RateLimiter.acquireBlocking]（`Thread.sleep`）等待，
 * 不阻塞主线程；同时受 [com.acgcompass.core.network.HttpClientFactory] 的 10s 调用超时约束（RC.01 3.9）。
 */
class RateLimitInterceptor(
    private val registry: RateLimiterRegistry,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val sourceId = request.sourceAuth()?.sourceId
        val limiter = sourceId?.let(registry::limiterFor)
        limiter?.acquireBlocking()
        return chain.proceed(request)
    }
}
