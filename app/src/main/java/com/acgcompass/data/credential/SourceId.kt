package com.acgcompass.data.credential

/**
 * 凭据归属的数据源 / 服务标识（RC.00 / RC.02）。
 *
 * 每个 [SourceId] 对应一组用户自行提供的凭据（token / apiKey / OAuth client 等），
 * 由 `CredentialStore` 以加密形式（EncryptedSharedPreferences + Keystore）单独隔离存储。
 *
 * - [BANGUMI]：Bangumi（P0 主源，Access Token / OAuth）。
 * - [ANILIST]：AniList（P1，可选 Token，公共查询免鉴权）。
 * - [JIKAN]：Jikan（P1/P2，无需 key —— 通常无凭据，保留以统一状态展示）。
 * - [MAL]：MyAnimeList 官方 API（P2，Client ID/Secret，OAuth2 PKCE）。
 * - [VNDB]：VNDB HTTP API（P2，可选 Token）。
 * - [AI_PROVIDER]：用户配置的 AI 服务（OpenAI / Gemini / DeepSeek / OpenRouter / 自定义兼容端点）。
 *
 * 枚举名作为加密存储键的稳定前缀，**不得**随意重命名以保证升级兼容（RC.00 1.8）。
 */
enum class SourceId {
    BANGUMI,
    ANILIST,
    JIKAN,
    MAL,
    VNDB,
    AI_PROVIDER,
    ;

    companion object {
        /** 从持久化字符串解析，未知值返回 `null`，避免升级 / 损坏数据导致崩溃（RC.17.4）。 */
        fun fromStorage(raw: String?): SourceId? =
            entries.firstOrNull { it.name == raw }
    }
}
