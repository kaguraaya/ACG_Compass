package com.acgcompass.data.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 单例 Preferences DataStore 委托。
 *
 * 使用 top-level [preferencesDataStore] 委托保证整个进程仅创建一个 DataStore 实例，
 * 避免多实例并发写入冲突（Jetpack DataStore 要求一个文件仅有一个实例）。
 */
private const val SETTINGS_DATASTORE_NAME = "acg_compass_settings"

internal val Context.dataStore: DataStore<Preferences> by preferencesDataStore(
    name = SETTINGS_DATASTORE_NAME,
)

/**
 * 非敏感设置 / 开关的本地持久化层（RC.02 4.11/4.12 / RC.15.04 / RC.00 1.1）。
 *
 * 基于 Jetpack DataStore (Preferences) 保存布尔开关与主题等本地偏好，并以 [Flow] 对外暴露，
 * 提供 `suspend` setter 写入。**绝不**保存任何凭据 / key / token —— 凭据由
 * `CredentialStore`（EncryptedSharedPreferences + Keystore，task 6.2）单独加密隔离（RC.00 1.2）。
 *
 * 读取失败（如反序列化异常）时回退为 [SettingsState] 默认值，保证应用不崩溃（RC.17.4）。
 */
@Singleton
class SettingsDataStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val dataStore: DataStore<Preferences> = context.dataStore

    /** 聚合所有非敏感开关的设置快照流。任一字段变更都会发射新的 [SettingsState]。 */
    val settings: Flow<SettingsState> = dataStore.data.map { prefs -> prefs.toSettingsState() }

    /** 是否允许 AI 分析评论（RC.02 4.11）。 */
    val allowAiAnalyzeReviews: Flow<Boolean> =
        dataStore.data.map { it[Keys.ALLOW_AI_ANALYZE_REVIEWS] ?: SettingsState.DEFAULT_ALLOW_AI_ANALYZE_REVIEWS }

    /** 是否记录本地时光机快照（RC.02 4.11 / RC.13）。 */
    val recordTimeMachineSnapshots: Flow<Boolean> =
        dataStore.data.map { it[Keys.RECORD_TIME_MACHINE_SNAPSHOTS] ?: SettingsState.DEFAULT_RECORD_TIME_MACHINE_SNAPSHOTS }

    /** Bangumi 数据源启用开关。 */
    val bangumiEnabled: Flow<Boolean> =
        dataStore.data.map { it[Keys.BANGUMI_ENABLED] ?: SettingsState.DEFAULT_BANGUMI_ENABLED }

    /** AniList 数据源启用开关。 */
    val anilistEnabled: Flow<Boolean> =
        dataStore.data.map { it[Keys.ANILIST_ENABLED] ?: SettingsState.DEFAULT_ANILIST_ENABLED }

    /** Jikan 数据源启用开关。 */
    val jikanEnabled: Flow<Boolean> =
        dataStore.data.map { it[Keys.JIKAN_ENABLED] ?: SettingsState.DEFAULT_JIKAN_ENABLED }

    /** MAL 官方数据源启用开关。 */
    val malEnabled: Flow<Boolean> =
        dataStore.data.map { it[Keys.MAL_ENABLED] ?: SettingsState.DEFAULT_MAL_ENABLED }

    /** VNDB 数据源启用开关。 */
    val vndbEnabled: Flow<Boolean> =
        dataStore.data.map { it[Keys.VNDB_ENABLED] ?: SettingsState.DEFAULT_VNDB_ENABLED }

    /** 是否显示 VNDB 成人内容（分级过滤，RC.02 4.9/4.10）。 */
    val showAdultContent: Flow<Boolean> =
        dataStore.data.map { it[Keys.SHOW_ADULT_CONTENT] ?: SettingsState.DEFAULT_SHOW_ADULT_CONTENT }

    /** 首启引导是否已展示（RC.03.01）。 */
    val onboardingShown: Flow<Boolean> =
        dataStore.data.map { it[Keys.ONBOARDING_SHOWN] ?: SettingsState.DEFAULT_ONBOARDING_SHOWN }

    /** 主题模式（system/light/dark，RC.03.05）。 */
    val themeMode: Flow<ThemeMode> =
        dataStore.data.map { ThemeMode.fromStorage(it[Keys.THEME_MODE]) }

    /** 是否启用动态取色（Material You，RC.03.05）。 */
    val dynamicColor: Flow<Boolean> =
        dataStore.data.map { it[Keys.DYNAMIC_COLOR] ?: SettingsState.DEFAULT_DYNAMIC_COLOR }

    /** Bangumi API Base URL（R55）。 */
    val bangumiApiBaseUrl: Flow<String> =
        dataStore.data.map { it[Keys.BANGUMI_API_BASE_URL] ?: SettingsState.DEFAULT_BANGUMI_API_BASE_URL }

    /**
     * R-new1：用户选择的 AI provider 形态标识（[com.acgcompass.data.remote.ai.ProviderId] 的枚举名）。
     * 仅持久化「选哪个 provider 形态」这一**非敏感**选择，凭据仍由 `CredentialStore` 加密隔离（RC.00 1.2）。
     * 未设置时为空字符串（由选择器回退到按 baseUrl 推断或 OpenAI 形态）。
     */
    val aiProviderId: Flow<String> =
        dataStore.data.map { it[Keys.AI_PROVIDER_ID] ?: "" }

    /** R-new1：持久化用户选择的 AI provider 形态标识。 */
    suspend fun setAiProviderId(providerId: String) {
        dataStore.edit { it[Keys.AI_PROVIDER_ID] = providerId }
    }

    /** F11：首页展示模块偏好（启用的模块 key 集合）。空集合表示「全部默认显示」。 */
    val homeModules: Flow<Set<String>> =
        dataStore.data.map { it[Keys.HOME_MODULES] ?: HomeModulePrefs.DEFAULT_KEYS }

    /** H7：自动同步间隔（分钟）。0 = 关闭。 */
    val autoSyncIntervalMinutes: Flow<Int> =
        dataStore.data.map { it[Keys.AUTO_SYNC_INTERVAL_MINUTES] ?: SettingsState.DEFAULT_AUTO_SYNC_INTERVAL_MINUTES }

    /** H7：设置自动同步间隔（分钟）。负数钳为 0（关闭）。 */
    suspend fun setAutoSyncIntervalMinutes(minutes: Int) {
        dataStore.edit { it[Keys.AUTO_SYNC_INTERVAL_MINUTES] = minutes.coerceAtLeast(0) }
    }

    /** Phase④：推荐过滤的社区均分下限（0~10，0=不限）。 */
    val recommendMinCommunityScore: Flow<Float> =
        dataStore.data.map { it[Keys.RECOMMEND_MIN_COMMUNITY_SCORE] ?: SettingsState.DEFAULT_RECOMMEND_MIN_COMMUNITY_SCORE }

    /** Phase④：设置推荐社区均分下限；钳到 0~10。 */
    suspend fun setRecommendMinCommunityScore(score: Float) {
        dataStore.edit { it[Keys.RECOMMEND_MIN_COMMUNITY_SCORE] = score.coerceIn(0f, 10f) }
    }

    /** Phase④：口味匹配度阈值（0~1，0=关闭该过滤）。 */
    val tasteMatchThreshold: Flow<Float> =
        dataStore.data.map { it[Keys.TASTE_MATCH_THRESHOLD] ?: SettingsState.DEFAULT_TASTE_MATCH_THRESHOLD }

    /** Phase④：设置口味匹配度阈值；钳到 0~1。 */
    suspend fun setTasteMatchThreshold(threshold: Float) {
        dataStore.edit { it[Keys.TASTE_MATCH_THRESHOLD] = threshold.coerceIn(0f, 1f) }
    }

    /** I17：待补池展示形态偏好——true=网格，false=列表。默认列表。 */
    val backlogGridMode: Flow<Boolean> =
        dataStore.data.map { it[Keys.BACKLOG_GRID_MODE] ?: false }

    /** I17：持久化待补池网格/列表展示形态。 */
    suspend fun setBacklogGridMode(grid: Boolean) {
        dataStore.edit { it[Keys.BACKLOG_GRID_MODE] = grid }
    }

    /** F11：设置启用的首页模块集合。 */
    suspend fun setHomeModules(keys: Set<String>) {
        dataStore.edit { it[Keys.HOME_MODULES] = keys }
    }

    /** 设置 Bangumi API Base URL（R55）。空白回退官方；自动补结尾斜杠。 */
    suspend fun setBangumiApiBaseUrl(url: String) {
        val normalized = normalizeBaseUrl(url)
        dataStore.edit { it[Keys.BANGUMI_API_BASE_URL] = normalized }
    }

    /** 设置「非官方 API + 个人 Token」隐私风险已确认（R56）。 */
    suspend fun setBangumiNonOfficialTokenConsent(consented: Boolean) {
        dataStore.edit { it[Keys.BANGUMI_NONOFFICIAL_TOKEN_CONSENT] = consented }
    }

    /** 设置是否允许 AI 分析评论。 */
    suspend fun setAllowAiAnalyzeReviews(enabled: Boolean) {
        dataStore.edit { it[Keys.ALLOW_AI_ANALYZE_REVIEWS] = enabled }
    }

    /** 设置是否记录本地时光机快照。 */
    suspend fun setRecordTimeMachineSnapshots(enabled: Boolean) {
        dataStore.edit { it[Keys.RECORD_TIME_MACHINE_SNAPSHOTS] = enabled }
    }

    /** 设置 Bangumi 数据源启用状态。 */
    suspend fun setBangumiEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.BANGUMI_ENABLED] = enabled }
    }

    /** 设置 AniList 数据源启用状态。 */
    suspend fun setAnilistEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.ANILIST_ENABLED] = enabled }
    }

    /** 设置 Jikan 数据源启用状态。 */
    suspend fun setJikanEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.JIKAN_ENABLED] = enabled }
    }

    /** 设置 MAL 官方数据源启用状态。 */
    suspend fun setMalEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.MAL_ENABLED] = enabled }
    }

    /** 设置 VNDB 数据源启用状态。 */
    suspend fun setVndbEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.VNDB_ENABLED] = enabled }
    }

    /** 设置是否显示 VNDB 成人内容。 */
    suspend fun setShowAdultContent(enabled: Boolean) {
        dataStore.edit { it[Keys.SHOW_ADULT_CONTENT] = enabled }
    }

    /** 设置首启引导是否已展示。 */
    suspend fun setOnboardingShown(shown: Boolean) {
        dataStore.edit { it[Keys.ONBOARDING_SHOWN] = shown }
    }

    /** 设置主题模式。 */
    suspend fun setThemeMode(mode: ThemeMode) {
        dataStore.edit { it[Keys.THEME_MODE] = mode.toStorage() }
    }

    /** 设置是否启用动态取色。 */
    suspend fun setDynamicColor(enabled: Boolean) {
        dataStore.edit { it[Keys.DYNAMIC_COLOR] = enabled }
    }

    // 注：榜单结果缓存（P2-3）已于 B-4 迁移到 Room `ranking_cache` 表（见 RankingCacheDao），
    // 不再使用 Preferences；此处原 getRankingOrder/saveRankingOrder 已移除。

    /** 将原始 [Preferences] 映射为带默认值兜底的 [SettingsState]。 */
    private fun Preferences.toSettingsState(): SettingsState =
        SettingsState(
            allowAiAnalyzeReviews = this[Keys.ALLOW_AI_ANALYZE_REVIEWS] ?: SettingsState.DEFAULT_ALLOW_AI_ANALYZE_REVIEWS,
            recordTimeMachineSnapshots = this[Keys.RECORD_TIME_MACHINE_SNAPSHOTS] ?: SettingsState.DEFAULT_RECORD_TIME_MACHINE_SNAPSHOTS,
            bangumiEnabled = this[Keys.BANGUMI_ENABLED] ?: SettingsState.DEFAULT_BANGUMI_ENABLED,
            anilistEnabled = this[Keys.ANILIST_ENABLED] ?: SettingsState.DEFAULT_ANILIST_ENABLED,
            jikanEnabled = this[Keys.JIKAN_ENABLED] ?: SettingsState.DEFAULT_JIKAN_ENABLED,
            malEnabled = this[Keys.MAL_ENABLED] ?: SettingsState.DEFAULT_MAL_ENABLED,
            vndbEnabled = this[Keys.VNDB_ENABLED] ?: SettingsState.DEFAULT_VNDB_ENABLED,
            showAdultContent = this[Keys.SHOW_ADULT_CONTENT] ?: SettingsState.DEFAULT_SHOW_ADULT_CONTENT,
            onboardingShown = this[Keys.ONBOARDING_SHOWN] ?: SettingsState.DEFAULT_ONBOARDING_SHOWN,
            themeMode = ThemeMode.fromStorage(this[Keys.THEME_MODE]),
            dynamicColor = this[Keys.DYNAMIC_COLOR] ?: SettingsState.DEFAULT_DYNAMIC_COLOR,
            bangumiApiBaseUrl = this[Keys.BANGUMI_API_BASE_URL] ?: SettingsState.DEFAULT_BANGUMI_API_BASE_URL,
            bangumiNonOfficialTokenConsent = this[Keys.BANGUMI_NONOFFICIAL_TOKEN_CONSENT] ?: false,
            autoSyncIntervalMinutes = this[Keys.AUTO_SYNC_INTERVAL_MINUTES] ?: SettingsState.DEFAULT_AUTO_SYNC_INTERVAL_MINUTES,
            recommendMinCommunityScore = this[Keys.RECOMMEND_MIN_COMMUNITY_SCORE] ?: SettingsState.DEFAULT_RECOMMEND_MIN_COMMUNITY_SCORE,
            tasteMatchThreshold = this[Keys.TASTE_MATCH_THRESHOLD] ?: SettingsState.DEFAULT_TASTE_MATCH_THRESHOLD,
        )

    /** 归一化 Base URL（R55）：trim、空白回退官方、补结尾斜杠。不校验可达性（由连接测试负责）。 */
    private fun normalizeBaseUrl(raw: String): String {
        val t = raw.trim()
        if (t.isEmpty()) return SettingsState.DEFAULT_BANGUMI_API_BASE_URL
        return if (t.endsWith("/")) t else "$t/"
    }

    /** Preferences 键集合。仅非敏感开关，键名稳定以兼容升级（RC.00 1.8）。 */
    private object Keys {
        val ALLOW_AI_ANALYZE_REVIEWS = booleanPreferencesKey("allow_ai_analyze_reviews")
        val RECORD_TIME_MACHINE_SNAPSHOTS = booleanPreferencesKey("record_time_machine_snapshots")
        val BANGUMI_ENABLED = booleanPreferencesKey("bangumi_enabled")
        val ANILIST_ENABLED = booleanPreferencesKey("anilist_enabled")
        val JIKAN_ENABLED = booleanPreferencesKey("jikan_enabled")
        val MAL_ENABLED = booleanPreferencesKey("mal_enabled")
        val VNDB_ENABLED = booleanPreferencesKey("vndb_enabled")
        val SHOW_ADULT_CONTENT = booleanPreferencesKey("show_adult_content")
        val ONBOARDING_SHOWN = booleanPreferencesKey("onboarding_shown")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val BANGUMI_API_BASE_URL = stringPreferencesKey("bangumi_api_base_url")
        val AI_PROVIDER_ID = stringPreferencesKey("ai_provider_id")
        val BANGUMI_NONOFFICIAL_TOKEN_CONSENT = booleanPreferencesKey("bangumi_nonofficial_token_consent")
        val HOME_MODULES = stringSetPreferencesKey("home_modules")
        val AUTO_SYNC_INTERVAL_MINUTES = intPreferencesKey("auto_sync_interval_minutes")
        val BACKLOG_GRID_MODE = booleanPreferencesKey("backlog_grid_mode")
        val RECOMMEND_MIN_COMMUNITY_SCORE = floatPreferencesKey("recommend_min_community_score")
        val TASTE_MATCH_THRESHOLD = floatPreferencesKey("taste_match_threshold")
    }
}

/** F11：首页可配置模块定义（key + 展示名 + 默认是否开启）。 */
enum class HomeModulePref(val key: String, val label: String, val defaultOn: Boolean) {
    TODAY_DECISION("today_decision", "今日决策（今晚看什么）", true),
    SEASONAL_HOT("seasonal_hot", "近期热门 / 本季", true),
    BACKLOG_OVERVIEW("backlog_overview", "待补池概览", true),
    CONTINUE("continue", "继续看 / 玩 / 读", true),
    SYNC_REMINDER("sync_reminder", "同步提醒", true),
    DAILY_DRAW("daily_draw", "今日补番签", true),
    ;
}

/** F11：首页模块偏好工具。 */
object HomeModulePrefs {
    val DEFAULT_KEYS: Set<String> = HomeModulePref.entries.filter { it.defaultOn }.map { it.key }.toSet()
}
