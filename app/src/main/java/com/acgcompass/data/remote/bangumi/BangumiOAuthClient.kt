package com.acgcompass.data.remote.bangumi

import com.acgcompass.core.common.AppError
import com.acgcompass.core.common.AppResult
import com.acgcompass.core.common.asException
import com.acgcompass.core.common.runCatchingApp
import com.acgcompass.core.network.HttpErrorMapper
import com.acgcompass.core.network.NetworkConstants
import com.acgcompass.data.credential.CredentialStore
import com.acgcompass.data.credential.SourceId
import okhttp3.HttpUrl.Companion.toHttpUrl
import retrofit2.Response
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * Bangumi OAuth2 **授权码流程**客户端（RC.02 4.6）。复刻已有 [com.acgcompass.data.remote.mal.MalOAuthClient]
 * 风格，但 Bangumi 不支持 PKCE，必须 `client_id` + `client_secret`。凭据**优先用内置应用**（[defaultClientId]/
 * [defaultClientSecret]，来自 BuildConfig，所有用户共用、仅需登录），缺省时回退到用户在 `bgm.tv/dev/app` 自助注册并在设置页填写的应用。
 *
 * 职责：
 * 1. [buildAuthorizeUrl]：读取用户在 [CredentialStore] 配置的 App ID（`clientId`，**不得**硬编码，RC.00），
 *    拼接授权 URL（在应用内 WebView 打开，回调地址固定 [NetworkConstants.BANGUMI_OAUTH_REDIRECT_URI]）。
 * 2. [exchangeCode] / [refreshToken]：调用 [BangumiOAuthApi] 令牌端点换取 / 续期 token。
 *
 * **启用门控（RC.02 4.8）**：未配置 App ID / Secret 时直接以 [AppError.Unauthorized] 短路，**不发请求**。
 *
 * **安全约束（RC.00）**：本类不持久化任何凭据；token 交换结果由调用方写回加密 [CredentialStore]，
 * 绝不记录日志 / 写入 Room / 默认备份。`state` 校验、token 落盘由更高层（授权流程）负责。
 */
@Singleton
class BangumiOAuthClient @Inject constructor(
    private val oauthApi: BangumiOAuthApi,
    private val credentialStore: CredentialStore,
    @Named("bangumiDefaultClientId") private val defaultClientId: String,
    @Named("bangumiDefaultClientSecret") private val defaultClientSecret: String,
) {

    /** 一次授权请求的非敏感产物：授权 URL + 防 CSRF `state`（调用方在回调时校验）。 */
    data class AuthorizationRequest(
        val authorizeUrl: String,
        val state: String,
    )

    /**
     * 构造授权 URL（`GET {oauthBase}authorize?...`），由调用方在 WebView 打开。
     *
     * @param state 防 CSRF 随机串（调用方生成并在回调时校验）。
     * @return [AppResult.Success] 携带 [AuthorizationRequest]；未配置 App ID 时
     *   [AppResult.Failure] 携带 [AppError.Unauthorized]（引导用户到设置页填写，RC.02 4.8）。
     */
    suspend fun buildAuthorizeUrl(state: String): AppResult<AuthorizationRequest> = runCatchingApp {
        val clientId = requireClientId()
        val url = NetworkConstants.BANGUMI_OAUTH_BASE_URL.toHttpUrl().newBuilder()
            .addPathSegment("authorize")
            .addQueryParameter("client_id", clientId)
            .addQueryParameter("response_type", "code")
            .addQueryParameter("redirect_uri", NetworkConstants.BANGUMI_OAUTH_REDIRECT_URI)
            .addQueryParameter("state", state)
            .build()
            .toString()
        AuthorizationRequest(authorizeUrl = url, state = state)
    }

    /** 用授权码换取 token（`grant_type=authorization_code`）。 */
    suspend fun exchangeCode(code: String): AppResult<BangumiTokenResponseDto> = runCatchingApp {
        oauthApi.exchangeCode(
            clientId = requireClientId(),
            clientSecret = requireClientSecret(),
            code = code,
            redirectUri = NetworkConstants.BANGUMI_OAUTH_REDIRECT_URI,
        ).tokenOrThrow()
    }

    /** 用 Refresh Token 续期 Access Token（`grant_type=refresh_token`）。 */
    suspend fun refreshToken(refreshToken: String): AppResult<BangumiTokenResponseDto> = runCatchingApp {
        oauthApi.refreshToken(
            clientId = requireClientId(),
            clientSecret = requireClientSecret(),
            refreshToken = refreshToken,
            redirectUri = NetworkConstants.BANGUMI_OAUTH_REDIRECT_URI,
        ).tokenOrThrow()
    }

    /**
     * 生效 App ID：优先用户自填（[CredentialStore]），否则回退内置默认（[defaultClientId]，来自 BuildConfig）。
     * 两者皆空时短路、不发请求（RC.02 4.8）。
     */
    private suspend fun requireClientId(): String =
        (credentialStore.get(SourceId.BANGUMI)?.clientId?.takeIf { it.isNotBlank() }
            ?: defaultClientId.takeIf { it.isNotBlank() })
            ?: throw AppError.Unauthorized(cause = "未配置 Bangumi App ID（OAuth），请在设置页填写或在构建中内置").asException()

    /** 生效 App Secret：优先用户自填，否则回退内置默认；Bangumi 强制要求，皆空时短路。 */
    private suspend fun requireClientSecret(): String =
        (credentialStore.get(SourceId.BANGUMI)?.clientSecret?.takeIf { it.isNotBlank() }
            ?: defaultClientSecret.takeIf { it.isNotBlank() })
            ?: throw AppError.Unauthorized(cause = "未配置 Bangumi App Secret（OAuth），请在设置页填写或在构建中内置").asException()

    /**
     * 解析令牌响应：非 2xx 经 [HttpErrorMapper.mapStatusCode] 抛出领域错误（如 `400` 参数错误 /
     * `401` 凭据无效）；2xx 但缺 `access_token` 抛出 [AppError.FieldMissing]。
     */
    private fun Response<BangumiTokenResponseDto>.tokenOrThrow(): BangumiTokenResponseDto {
        if (!isSuccessful) throw HttpErrorMapper.mapStatusCode(code()).asException()
        val body = body() ?: throw AppError.FieldMissing().asException()
        if (body.accessToken.isNullOrBlank()) throw AppError.FieldMissing().asException()
        return body
    }
}
