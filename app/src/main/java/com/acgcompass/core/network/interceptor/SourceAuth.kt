package com.acgcompass.core.network.interceptor

import com.acgcompass.data.credential.SecretBundle
import com.acgcompass.data.credential.SourceId
import okhttp3.Request

/**
 * 单源鉴权策略（RC.01 3.2 / RC.02）。
 *
 * 每个数据源 / AI 服务的鉴权方式不同（Bearer Token、自定义头、查询参数、Client-Id 头等），
 * [SourceAuth] 将「如何把某源的凭据注入到请求」抽象为一个小策略，由 [AuthInterceptor] 调用。
 *
 * **安全约束（RC.00 1.2）**：策略只读取由 `CredentialStore` 提供的明文凭据并注入到出站请求，
 * **绝不**记录、缓存或回写凭据；当 [secret] 为 `null` 或缺少所需字段时，必须原样返回请求（透传）。
 */
interface SourceAuth {

    /** 该策略对应的凭据归属源，用于从 `CredentialStore` 取值。 */
    val sourceId: SourceId

    /**
     * 将 [secret] 中的凭据注入 [request]。
     *
     * @param secret 该源的明文凭据；为 `null` 表示未配置，**必须**原样返回 [request]（透传）。
     * @return 注入鉴权后的新请求；无凭据时返回原请求。
     */
    fun authenticate(request: Request, secret: SecretBundle?): Request
}

/**
 * 一组内置鉴权策略。各策略均为无状态，可作为单例复用。
 *
 * > 注意（RC.01 3.1）：具体头名 / 格式以各源最新官方文档为准（接入前需核验并记入
 * > `DEVELOPMENT.md`）；此处为基于既有文档的通用实现，缺凭据一律透传。
 */
object SourceAuths {

    private const val HEADER_AUTHORIZATION = "Authorization"

    /** 无鉴权（如 Jikan 公共 API，无需 key）：恒透传。 */
    class NoAuth(override val sourceId: SourceId) : SourceAuth {
        override fun authenticate(request: Request, secret: SecretBundle?): Request = request
    }

    /**
     * `Authorization: <scheme> <token>` 型鉴权（Bearer / Token 等）。
     *
     * 当 [SecretBundle.token] 为空白或 `null` 时透传。
     */
    class TokenHeaderAuth(
        override val sourceId: SourceId,
        private val scheme: String = "Bearer",
    ) : SourceAuth {
        override fun authenticate(request: Request, secret: SecretBundle?): Request {
            val token = secret?.token?.takeIf { it.isNotBlank() } ?: return request
            if (request.header(HEADER_AUTHORIZATION) != null) return request
            return request.newBuilder()
                .header(HEADER_AUTHORIZATION, "$scheme $token")
                .build()
        }
    }

    /**
     * MyAnimeList 官方 API：优先使用 OAuth `Bearer <token>`；仅有 Client ID 时退化为
     * `X-MAL-CLIENT-ID` 头（公共数据访问）。两者均缺失时透传（RC.01 3.5 / RC.02 4.7）。
     */
    class MalAuth(override val sourceId: SourceId = SourceId.MAL) : SourceAuth {
        override fun authenticate(request: Request, secret: SecretBundle?): Request {
            if (secret == null) return request
            val token = secret.token?.takeIf { it.isNotBlank() }
            if (token != null) {
                if (request.header(HEADER_AUTHORIZATION) != null) return request
                return request.newBuilder()
                    .header(HEADER_AUTHORIZATION, "Bearer $token")
                    .build()
            }
            val clientId = secret.clientId?.takeIf { it.isNotBlank() } ?: return request
            if (request.header(HEADER_MAL_CLIENT_ID) != null) return request
            return request.newBuilder()
                .header(HEADER_MAL_CLIENT_ID, clientId)
                .build()
        }

        private companion object {
            const val HEADER_MAL_CLIENT_ID = "X-MAL-CLIENT-ID"
        }
    }

    /** Bangumi：Access Token / OAuth → `Authorization: Bearer <token>`（RC.01 3.2）。 */
    val bangumi: SourceAuth = TokenHeaderAuth(SourceId.BANGUMI, scheme = "Bearer")

    /** AniList：可选 Token（公共查询免鉴权）→ `Authorization: Bearer <token>`（RC.01 3.3）。 */
    val anilist: SourceAuth = TokenHeaderAuth(SourceId.ANILIST, scheme = "Bearer")

    /** Jikan：无需 key，恒透传（RC.01 3.4）。 */
    val jikan: SourceAuth = NoAuth(SourceId.JIKAN)

    /** MAL 官方：OAuth Bearer 或 Client-Id 头（RC.01 3.5）。 */
    val mal: SourceAuth = MalAuth()

    /** VNDB：可选 Token → `Authorization: Token <token>`（RC.01 3.6）。 */
    val vndb: SourceAuth = TokenHeaderAuth(SourceId.VNDB, scheme = "Token")

    /** AI Provider：API Key → `Authorization: Bearer <apiKey>`（RC.14）。 */
    class ApiKeyHeaderAuth(
        override val sourceId: SourceId = SourceId.AI_PROVIDER,
        private val scheme: String = "Bearer",
    ) : SourceAuth {
        override fun authenticate(request: Request, secret: SecretBundle?): Request {
            val key = secret?.apiKey?.takeIf { it.isNotBlank() } ?: return request
            if (request.header(HEADER_AUTHORIZATION) != null) return request
            return request.newBuilder()
                .header(HEADER_AUTHORIZATION, "$scheme $key")
                .build()
        }
    }

    val aiProvider: SourceAuth = ApiKeyHeaderAuth()

    /** 按 [SourceId] 取内置策略（未知或无鉴权返回 [NoAuth]）。 */
    fun forSource(sourceId: SourceId): SourceAuth = when (sourceId) {
        SourceId.BANGUMI -> bangumi
        SourceId.ANILIST -> anilist
        SourceId.JIKAN -> jikan
        SourceId.MAL -> mal
        SourceId.VNDB -> vndb
        SourceId.AI_PROVIDER -> aiProvider
    }
}

/**
 * 将目标源标注到请求上，供 [AuthInterceptor] / [com.acgcompass.core.network.interceptor.RateLimitInterceptor]
 * 在共享 [okhttp3.OkHttpClient] 下识别请求归属的源（RC.01）。
 *
 * 使用 OkHttp 的类型化 tag：以 [SourceAuth] 接口为键写入策略实例，拦截器据此读取。
 * 各源的 Retrofit / GraphQL 调用在构造请求时调用本扩展打标即可。
 */
fun Request.Builder.networkSource(auth: SourceAuth): Request.Builder =
    tag(SourceAuth::class.java, auth)

/** 读取请求上标注的 [SourceAuth]（未标注返回 `null`）。 */
fun Request.sourceAuth(): SourceAuth? = tag(SourceAuth::class.java)
