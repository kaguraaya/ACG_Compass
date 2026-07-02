package com.acgcompass.feature.auth

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.acgcompass.core.common.AppResult
import com.acgcompass.core.network.NetworkConstants
import com.acgcompass.data.credential.CredentialStore
import com.acgcompass.data.credential.SecretBundle
import com.acgcompass.data.credential.SourceId
import com.acgcompass.data.datastore.SettingsDataStore
import com.acgcompass.data.remote.bangumi.BangumiOAuthClient
import com.acgcompass.data.remote.bangumi.BangumiRemoteDataSource
import com.acgcompass.data.remote.bangumi.BangumiTokenResponseDto
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

/**
 * Bangumi OAuth2 授权流程 UI 状态（RC.02 4.6）。
 */
sealed interface BangumiOAuthUiState {
    /** 正在读取用户配置的 App ID 并拼接授权 URL。 */
    data object Loading : BangumiOAuthUiState

    /** 授权 URL 就绪，WebView 引导用户在 Bangumi 登录授权。 */
    data class Authorizing(val authorizeUrl: String) : BangumiOAuthUiState

    /** 已拿到授权码，正在换取 token。 */
    data object Exchanging : BangumiOAuthUiState

    /** 换取成功并已落盘加密存储。 */
    data object Success : BangumiOAuthUiState

    /** 失败（未配置 App ID/Secret、授权被拒、换取失败等）。[retryable] 决定是否提供「重试」。 */
    data class Error(val message: String, val retryable: Boolean = true) : BangumiOAuthUiState
}

/**
 * Bangumi OAuth2 **授权码流程**的 ViewModel（RC.02 4.6）。
 *
 * 职责：
 * 1. [start]：用 [BangumiOAuthClient.buildAuthorizeUrl] 拼接授权 URL（带随机 `state` 防 CSRF），交 WebView 打开。
 * 2. [onRedirect]：WebView 拦截到自定义 scheme 回调后，解析 / 校验 `state` 与 `code`，
 *    用 [BangumiOAuthClient.exchangeCode] 换取 token，合并写入加密 [CredentialStore]（保留用户已填的 App ID/Secret），
 *    并启用 Bangumi 源。
 *
 * **安全（RC.00）**：token / code / secret 仅在内存流转并写入加密存储，绝不记录日志。
 */
@HiltViewModel
class BangumiOAuthViewModel @Inject constructor(
    private val oauthClient: BangumiOAuthClient,
    private val credentialStore: CredentialStore,
    private val settingsDataStore: SettingsDataStore,
    private val bangumi: BangumiRemoteDataSource,
) : ViewModel() {

    private val _uiState = MutableStateFlow<BangumiOAuthUiState>(BangumiOAuthUiState.Loading)
    val uiState: StateFlow<BangumiOAuthUiState> = _uiState.asStateFlow()

    /** 本次授权请求的 `state`，用于回调校验（防 CSRF，RC.01 3.5）。 */
    private var expectedState: String? = null

    /** 防止 WebView 重复回调导致多次换取。 */
    private var handlingCode = false

    init {
        start()
    }

    /** 拼接授权 URL 并进入 WebView 授权态；未配置 App ID/Secret 时直接进入错误态。 */
    fun start() {
        handlingCode = false
        _uiState.value = BangumiOAuthUiState.Loading
        viewModelScope.launch {
            when (val result = oauthClient.buildAuthorizeUrl(UUID.randomUUID().toString().replace("-", ""))) {
                is AppResult.Success -> {
                    expectedState = result.data.state
                    _uiState.value = BangumiOAuthUiState.Authorizing(result.data.authorizeUrl)
                }

                is AppResult.Failure -> {
                    _uiState.value = BangumiOAuthUiState.Error(
                        message = result.error.cause + "（" + result.error.nextStep + "）",
                        retryable = false,
                    )
                }
            }
        }
    }

    /** WebView 判断某 URL 是否为我们的回调地址（命中则应拦截、不放行加载）。 */
    fun isRedirect(url: String): Boolean =
        url.startsWith(NetworkConstants.BANGUMI_OAUTH_REDIRECT_URI)

    /** WebView 拦截到回调 URL：解析 `code`/`state`/`error`，校验后换取并落盘 token。 */
    fun onRedirect(url: String) {
        if (handlingCode) return
        handlingCode = true

        val uri = runCatching { Uri.parse(url) }.getOrNull()
        val error = uri?.getQueryParameter("error")
        val code = uri?.getQueryParameter("code")
        val returnedState = uri?.getQueryParameter("state")

        if (!error.isNullOrBlank()) {
            _uiState.value = BangumiOAuthUiState.Error("授权被拒绝或已取消（$error）")
            return
        }
        if (code.isNullOrBlank()) {
            _uiState.value = BangumiOAuthUiState.Error("回调未包含授权码，请重试")
            return
        }
        if (expectedState != null && returnedState != expectedState) {
            _uiState.value = BangumiOAuthUiState.Error("state 校验失败（可能的 CSRF 风险），已中止登录")
            return
        }

        _uiState.value = BangumiOAuthUiState.Exchanging
        viewModelScope.launch {
            when (val result = oauthClient.exchangeCode(code)) {
                is AppResult.Success -> {
                    persistToken(result.data)
                    runCatching { settingsDataStore.setBangumiEnabled(true) }
                    // D1：登录成功后拉取并存用户名，使设置页「当前用户名」立即可见。
                    fetchAndStoreUsername()
                    _uiState.value = BangumiOAuthUiState.Success
                }

                is AppResult.Failure -> {
                    _uiState.value = BangumiOAuthUiState.Error(
                        result.error.cause + "（" + result.error.nextStep + "）",
                    )
                }
            }
        }
    }

    /** 合并写入：保留用户已填的 App ID/Secret，更新 access/refresh token 与过期时刻。 */
    private suspend fun persistToken(dto: BangumiTokenResponseDto) {
        val existing = runCatching { credentialStore.get(SourceId.BANGUMI) }.getOrNull()
        val expiresAt = dto.expiresIn?.let { System.currentTimeMillis() + it * 1000L }
        credentialStore.put(
            SourceId.BANGUMI,
            SecretBundle(
                token = dto.accessToken,
                clientId = existing?.clientId,
                clientSecret = existing?.clientSecret,
                baseUrl = existing?.baseUrl,
                model = existing?.model,
                refreshToken = dto.refreshToken ?: existing?.refreshToken,
                tokenExpiresAt = expiresAt,
            ),
        )
    }

    /** D1：换取 token 后拉取 `/v0/me` 并持久化用户名（best-effort，失败不影响登录成功）。 */
    private suspend fun fetchAndStoreUsername() {
        runCatching {
            val me = bangumi.getMe()
            if (me is AppResult.Success) {
                val name = me.data.username.ifBlank { me.data.nickname }
                if (name.isNotBlank()) settingsDataStore.setBangumiUsername(name)
            }
        }
    }
}
