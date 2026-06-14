package com.acgcompass.core.network.interceptor

import com.acgcompass.data.credential.CredentialStore
import com.acgcompass.data.credential.SecretBundle
import com.acgcompass.data.credential.SourceId
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Response

/**
 * 鉴权注入拦截器（RC.01 3.2 / RC.02）。
 *
 * 流程：从请求上标注的 [SourceAuth]（见 [networkSource]）识别目标源 → 从 [CredentialStore]
 * 读取该源的明文凭据 → 由 [SourceAuth] 策略注入对应鉴权头/参数。
 *
 * 行为约束：
 * - **无标注**：请求未标注 [SourceAuth] 时原样透传（公共请求 / 未接入源）。
 * - **无凭据**：该源未配置或读取失败时原样透传（RC.01 3.2「公共查询免鉴权」）。
 * - **安全（RC.00 1.2 / 1.7）**：凭据仅在内存中用于注入，**绝不**记录日志或缓存于本类。
 *
 * 实现说明：OkHttp 拦截器是同步 API，而 [CredentialStore.get] 是挂起函数；拦截器运行在
 * OkHttp 的后台分发线程，这里用 [runBlocking] 在该线程上等待读取凭据是可接受的，不阻塞主线程。
 */
class AuthInterceptor(
    private val credentialStore: CredentialStore,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val auth = request.sourceAuth() ?: return chain.proceed(request)

        val secret: SecretBundle? = readSecret(auth.sourceId)
        val authenticated = auth.authenticate(request, secret)
        return chain.proceed(authenticated)
    }

    /** 读取指定源凭据；任何异常都降级为「无凭据」以保证不崩溃（RC.17.4）。 */
    private fun readSecret(sourceId: SourceId): SecretBundle? =
        runCatching { runBlocking { credentialStore.get(sourceId) } }.getOrNull()
}
