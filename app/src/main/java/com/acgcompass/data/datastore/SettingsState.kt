package com.acgcompass.data.datastore

/**
 * 主题模式（RC.03.05 现代原生视觉：深色模式适配）。
 *
 * - [SYSTEM]：跟随系统深浅色。
 * - [LIGHT]：强制浅色。
 * - [DARK]：强制深色。
 */
enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK,
    ;

    companion object {
        /** 从持久化字符串解析，未知值回退为 [SYSTEM]，保证升级/损坏数据不崩溃（RC.00 1.8 / RC.17.4）。 */
        fun fromStorage(raw: String?): ThemeMode =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: SYSTEM
    }

    /** 持久化用的稳定字符串表示。 */
    fun toStorage(): String = name
}

/**
 * 非敏感设置/开关的聚合快照（RC.02 4.11/4.12 / RC.15.04 / RC.00 1.1）。
 *
 * 仅承载布尔开关与主题等本地偏好，**绝不包含**任何凭据 / key / token，
 * 凭据由 `CredentialStore`（EncryptedSharedPreferences + Keystore）单独管理（RC.00 1.2）。
 *
 * @property allowAiAnalyzeReviews 是否允许 AI 分析评论（RC.02 4.11）。默认关闭，AI 功能未配置时保持关闭（RC.00 1.3）。
 * @property recordTimeMachineSnapshots 是否记录本地时光机快照（RC.02 4.11 / RC.13）。
 * @property bangumiEnabled 是否启用 Bangumi 数据源（P0 主源，默认启用）。
 * @property anilistEnabled 是否启用 AniList 数据源（P1）。
 * @property jikanEnabled 是否启用 Jikan 数据源（P1/P2，无需 key）。
 * @property malEnabled 是否启用 MAL 官方数据源（P2，需用户显式配置）。
 * @property vndbEnabled 是否启用 VNDB 数据源（P2）。
 * @property showAdultContent 是否显示 VNDB 成人内容（分级过滤，RC.02 4.9/4.10）。默认关闭。
 * @property onboardingShown 首启引导是否已展示（RC.03.01）。
 * @property themeMode 主题模式（system/light/dark，RC.03.05）。
 * @property dynamicColor 是否启用动态取色（Material You，RC.03.05）。
 */
data class SettingsState(
    val allowAiAnalyzeReviews: Boolean = DEFAULT_ALLOW_AI_ANALYZE_REVIEWS,
    val recordTimeMachineSnapshots: Boolean = DEFAULT_RECORD_TIME_MACHINE_SNAPSHOTS,
    val bangumiEnabled: Boolean = DEFAULT_BANGUMI_ENABLED,
    val anilistEnabled: Boolean = DEFAULT_ANILIST_ENABLED,
    val jikanEnabled: Boolean = DEFAULT_JIKAN_ENABLED,
    val malEnabled: Boolean = DEFAULT_MAL_ENABLED,
    val vndbEnabled: Boolean = DEFAULT_VNDB_ENABLED,
    val showAdultContent: Boolean = DEFAULT_SHOW_ADULT_CONTENT,
    val onboardingShown: Boolean = DEFAULT_ONBOARDING_SHOWN,
    val themeMode: ThemeMode = DEFAULT_THEME_MODE,
    val dynamicColor: Boolean = DEFAULT_DYNAMIC_COLOR,
    /** Bangumi API Base URL（R55）。默认官方；可切换社区反代 / 自定义。结尾带斜杠。 */
    val bangumiApiBaseUrl: String = DEFAULT_BANGUMI_API_BASE_URL,
    /** 用户已确认「非官方 API + 个人 Token」隐私风险（R56）。默认未确认。 */
    val bangumiNonOfficialTokenConsent: Boolean = false,
    /** H7：自动同步间隔（分钟）。0 = 关闭自动同步；最小有效间隔由调度层钳制到 WorkManager 下限。 */
    val autoSyncIntervalMinutes: Int = DEFAULT_AUTO_SYNC_INTERVAL_MINUTES,
    /** Phase④：推荐过滤的社区均分下限（0~10）。低于此分的作品不进入「今晚看什么」推荐；0 = 不限。 */
    val recommendMinCommunityScore: Float = DEFAULT_RECOMMEND_MIN_COMMUNITY_SCORE,
    /** Phase④：口味匹配度阈值（0~1）。低于此匹配度的作品不进入推荐（相当于自动重 roll）；0 = 关闭该过滤。 */
    val tasteMatchThreshold: Float = DEFAULT_TASTE_MATCH_THRESHOLD,
) {
    /** 当前 Bangumi API 是否为官方地址。 */
    val isBangumiOfficialApi: Boolean
        get() = bangumiApiBaseUrl.trimEnd('/') == DEFAULT_BANGUMI_API_BASE_URL.trimEnd('/')

    companion object {
        const val DEFAULT_ALLOW_AI_ANALYZE_REVIEWS = false
        const val DEFAULT_RECORD_TIME_MACHINE_SNAPSHOTS = true
        const val DEFAULT_BANGUMI_ENABLED = true
        const val DEFAULT_ANILIST_ENABLED = false
        const val DEFAULT_JIKAN_ENABLED = false
        const val DEFAULT_MAL_ENABLED = false
        const val DEFAULT_VNDB_ENABLED = false
        const val DEFAULT_SHOW_ADULT_CONTENT = false
        const val DEFAULT_ONBOARDING_SHOWN = false
        val DEFAULT_THEME_MODE = ThemeMode.SYSTEM
        const val DEFAULT_DYNAMIC_COLOR = true

        /** Bangumi 官方 API（默认）。 */
        const val DEFAULT_BANGUMI_API_BASE_URL = "https://api.bgm.tv/"

        /** H7：默认关闭自动同步（0 分钟）。 */
        const val DEFAULT_AUTO_SYNC_INTERVAL_MINUTES = 0

        /** Phase④：默认推荐社区均分下限 6.0（沿用此前推荐器的默认下限）。 */
        const val DEFAULT_RECOMMEND_MIN_COMMUNITY_SCORE = 6.0f

        /** Phase④：默认口味匹配度阈值 0（关闭，行为与旧版一致；用户可上调以过滤低匹配作品）。 */
        const val DEFAULT_TASTE_MATCH_THRESHOLD = 0.0f
    }
}
