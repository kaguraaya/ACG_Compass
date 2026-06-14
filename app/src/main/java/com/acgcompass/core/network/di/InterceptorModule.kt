package com.acgcompass.core.network.di

import com.acgcompass.BuildConfig
import com.acgcompass.core.network.interceptor.AuthInterceptor
import com.acgcompass.core.network.interceptor.BangumiBaseUrlInterceptor
import com.acgcompass.core.network.interceptor.RateLimitInterceptor
import com.acgcompass.core.network.interceptor.RateLimiterRegistry
import com.acgcompass.core.network.interceptor.TimeoutInterceptor
import com.acgcompass.core.network.interceptor.UserAgentInterceptor
import com.acgcompass.data.credential.CredentialStore
import com.acgcompass.data.datastore.SettingsDataStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * 拦截器链 Hilt 模块（RC.01，task 8.2）。
 *
 * 以单例提供四个拦截器与每源限流注册表，供 [NetworkModule] 按
 * `UserAgent → Auth → RateLimit → Timeout` 顺序安装到 REST / AI 的 [okhttp3.OkHttpClient]：
 * - [UserAgentInterceptor]：注入 `ACGCompass/{versionName}` 合规 UA（RC.01 3.2）。
 * - [AuthInterceptor]：按请求标注的源从 [CredentialStore] 注入鉴权，无凭据透传（RC.01 3.2）。
 * - [RateLimitInterceptor]：每源独立令牌桶，达上限 80% 即节流（RC.01 3.4/3.10）。
 * - [TimeoutInterceptor]：强化 10s 超时（RC.01 3.9）。
 *
 * 不在此处硬编码任何 key/token；版本号取自 [BuildConfig.VERSION_NAME]（RC.00 1.2）。
 */
@Module
@InstallIn(SingletonComponent::class)
object InterceptorModule {

    @Provides
    @Singleton
    fun provideUserAgentInterceptor(): UserAgentInterceptor =
        UserAgentInterceptor(
            userAgent = UserAgentInterceptor.defaultUserAgent(BuildConfig.VERSION_NAME),
        )

    @Provides
    @Singleton
    fun provideAuthInterceptor(
        credentialStore: CredentialStore,
    ): AuthInterceptor = AuthInterceptor(credentialStore)

    @Provides
    @Singleton
    fun provideBangumiBaseUrlInterceptor(
        settingsDataStore: SettingsDataStore,
    ): BangumiBaseUrlInterceptor = BangumiBaseUrlInterceptor(settingsDataStore)

    @Provides
    @Singleton
    fun provideRateLimiterRegistry(): RateLimiterRegistry = RateLimiterRegistry.default()

    @Provides
    @Singleton
    fun provideRateLimitInterceptor(
        registry: RateLimiterRegistry,
    ): RateLimitInterceptor = RateLimitInterceptor(registry)

    @Provides
    @Singleton
    fun provideTimeoutInterceptor(): TimeoutInterceptor = TimeoutInterceptor()
}
