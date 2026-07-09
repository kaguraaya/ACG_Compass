package com.acgcompass.data.remote.bangumi

import com.acgcompass.core.common.AppResult
import com.acgcompass.data.credential.CredentialStore
import com.acgcompass.data.credential.SourceId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bangumi OAuth token **启动期自动续期器**（RC.02 4.6）。
 *
 * 在应用启动时调用 [refreshIfNeeded]：当本地存有 OAuth `refresh_token`，且 access token 已过期
 * 或将在 [REFRESH_THRESHOLD_MS] 内过期时，用 `refresh_token` 静默换取新 token 并写回加密存储。
 *
 * 纯客户端方案（无后端中转）：依赖用户已通过 [com.acgcompass.feature.auth.BangumiOAuthViewModel]
 * 完成一次 OAuth 登录、拿到 `refresh_token`。手动粘贴的 PAT（无 `refresh_token`）不受影响、原样保留。
 *
 * **安全（RC.00）**：token 仅在内存流转并写入加密存储，绝不记录日志。失败静默——续期失败不影响
 * 既有（可能仍有效的）token，用户仍可手动重新登录。
 */
@Singleton
class BangumiTokenRefresher @Inject constructor(
    private val oauthClient: BangumiOAuthClient,
    private val credentialStore: CredentialStore,
) {

    /** 按需续期：无 refresh_token / 仍很新则直接返回；best-effort，任何异常静默吞掉。 */
    suspend fun refreshIfNeeded() {
        val secret = runCatching { credentialStore.get(SourceId.BANGUMI) }.getOrNull() ?: return
        val refreshToken = secret.refreshToken?.takeIf { it.isNotBlank() } ?: return
        val expiresAt = secret.tokenExpiresAt
        val now = System.currentTimeMillis()
        // RC.40：纯客户端「打开即续期」模型下，Bangumi OAuth access token 固定 7 天、无法申请更长时长，
        // 只能靠 refresh_token 在用户打开应用时续期。为把有效期窗口拉到最长——只要 token 已超过约 1 天
        // （剩余 < REFRESH_THRESHOLD_MS）就在本次打开续期，使每次打开近乎必续、每天最多续一次（当天再开时
        // token 已刷新回 7 天、剩余 > 阈值即跳过）。如此只要用户在任意 7 天窗口内打开过一次，token 就不过期。
        // 未知过期时刻一律尝试续期。
        if (expiresAt != null && expiresAt - now > REFRESH_THRESHOLD_MS) return

        val result = oauthClient.refreshToken(refreshToken)
        if (result is AppResult.Success) {
            val dto = result.data
            credentialStore.put(
                SourceId.BANGUMI,
                secret.copy(
                    token = dto.accessToken ?: secret.token,
                    refreshToken = dto.refreshToken ?: secret.refreshToken,
                    tokenExpiresAt = dto.expiresIn?.let { now + it * 1000L } ?: secret.tokenExpiresAt,
                ),
            )
        }
    }

    private companion object {
        /**
         * 剩余寿命少于 6 天即续期（Bangumi access token 默认 7 天）。取 6 天而非贴近过期，是为了「每次打开
         * 近乎必续」：token 老过约 1 天后的当天首次打开就续回 7 天，最大化「靠打开续期」模型的有效期窗口，
         * 同时当天多次打开只续一次（续后剩余 7 天 > 阈值即跳过），避免频繁请求。
         */
        const val REFRESH_THRESHOLD_MS: Long = 6L * 24 * 60 * 60 * 1000
    }
}
