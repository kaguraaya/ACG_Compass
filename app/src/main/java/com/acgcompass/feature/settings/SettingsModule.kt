package com.acgcompass.feature.settings

import com.acgcompass.core.common.AppError
import com.acgcompass.core.common.AppResult
import com.acgcompass.core.common.asException
import com.acgcompass.core.common.runCatchingApp
import com.acgcompass.data.credential.CredentialStore
import com.acgcompass.data.credential.SourceId
import com.acgcompass.data.datastore.SettingsDataStore
import com.acgcompass.data.remote.ai.AiProviderRegistry
import com.acgcompass.data.remote.ai.AiProviderSelector
import com.acgcompass.data.remote.ai.AiRequest
import com.acgcompass.data.remote.anilist.AniListRemoteDataSource
import com.acgcompass.data.remote.bangumi.BangumiRemoteDataSource
import com.acgcompass.data.remote.jikan.JikanRemoteDataSource
import com.acgcompass.data.remote.mal.MalRemoteDataSource
import com.acgcompass.data.remote.vndb.VndbRemoteDataSource
import com.acgcompass.domain.model.WorkMatch
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Singleton

/**
 * 设置页相关 Hilt 绑定。
 *
 * 提供 [ConnectionTester] 的真实实现 [RealConnectionTester]（R18/R28 / RC.02 4.5）：对各源发起一次
 * 最小化公共搜索探针（10s 超时）；AI 服务发起一次最小补全探针；区分「公共搜索可用 / 个人同步需
 * token / 未配置 / 连接失败 / 超时」等结果，不伪造成功（RC.00）。
 */
@Module
@InstallIn(SingletonComponent::class)
object SettingsModule {

    @Provides
    @Singleton
    fun provideConnectionTester(
        bangumi: BangumiRemoteDataSource,
        anilist: AniListRemoteDataSource,
        jikan: JikanRemoteDataSource,
        mal: MalRemoteDataSource,
        vndb: VndbRemoteDataSource,
        credentialStore: CredentialStore,
        aiRegistry: AiProviderRegistry,
        aiSelector: AiProviderSelector,
        settingsDataStore: SettingsDataStore,
    ): ConnectionTester =
        RealConnectionTester(bangumi, anilist, jikan, mal, vndb, credentialStore, aiRegistry, aiSelector, settingsDataStore)
}

/**
 * 真实连接测试（R18/R28）。
 * - 公共可搜的源（Bangumi/AniList/Jikan/VNDB）：探针成功即「公共搜索可用」，并据凭据状态附注个人同步是否就绪。
 * - 需凭据的源（MAL）：未配置时直接提示「需 Client ID」，不发请求。
 * - AI 服务：已配置时发起一次最小补全（model 留空回落默认），验证 Base URL / Key / 模型可用。
 * - 失败 / 超时：给出可读原因（连接失败 / 10 秒超时），并附官方文档入口。
 */
internal class RealConnectionTester(
    private val bangumi: BangumiRemoteDataSource,
    private val anilist: AniListRemoteDataSource,
    private val jikan: JikanRemoteDataSource,
    private val mal: MalRemoteDataSource,
    private val vndb: VndbRemoteDataSource,
    private val credentialStore: CredentialStore,
    private val aiRegistry: AiProviderRegistry,
    private val aiSelector: AiProviderSelector,
    private val settingsDataStore: SettingsDataStore,
) : ConnectionTester {

    override suspend fun test(source: SourceId): ConnectionTestResult {
        val docUrl = SettingsDocLinks.forSource(source)
        val configured = runCatching {
            credentialStore.observeStatus().first()[source]?.configured == true
        }.getOrDefault(false)

        // Bangumi（R57）：分能力细化测试（地址类型 / 地址可访问+公共搜索 / Token / 用户信息 / 收藏）。
        if (source == SourceId.BANGUMI) {
            return testBangumi(configured, docUrl)
        }

        // MAL 必须配置 Client ID 才能搜索 / 同步：未配置时直接说明，不发请求。
        if (source == SourceId.MAL && !mal.isEnabled()) {
            return ConnectionTestResult(
                success = false,
                message = "未配置：需先填写并保存 Client ID 才能搜索 / 同步，详见官方文档",
                docUrl = docUrl,
            )
        }

        // AI 服务（R28）：未配置直接说明；已配置发起一次最小补全探针，验证 Base URL/Key/模型。
        if (source == SourceId.AI_PROVIDER) {
            if (!configured) {
                return ConnectionTestResult(false, "未配置：请先填写 API Key / Base URL / 模型名并保存", docUrl)
            }
            val probe = withTimeoutOrNull(TIMEOUT_MS) { aiProbe() }
                ?: return ConnectionTestResult(
                    false,
                    "连接超时（超过 ${TIMEOUT_MS / 1000} 秒），请检查 Base URL / 网络",
                    docUrl,
                )
            return when (probe) {
                is AppResult.Success -> ConnectionTestResult(true, "连接成功，模型可用", docUrl)
                is AppResult.Failure -> ConnectionTestResult(false, "连接失败：${probe.error.cause}", docUrl)
            }
        }

        val probe: AppResult<List<WorkMatch>>? = withTimeoutOrNull(TIMEOUT_MS) {
            runCatching {
                when (source) {
                    SourceId.BANGUMI -> bangumi.searchSubjects(keyword = PROBE_KEYWORD, limit = 1)
                    SourceId.ANILIST -> anilist.searchMedia(keyword = PROBE_KEYWORD)
                    SourceId.JIKAN -> jikan.searchAnime(keyword = PROBE_KEYWORD, limit = 1)
                    SourceId.MAL -> mal.searchAnime(keyword = PROBE_KEYWORD, limit = 1)
                    SourceId.VNDB -> vndb.searchVn(keyword = PROBE_KEYWORD)
                    SourceId.AI_PROVIDER -> AppResult.Success(emptyList())
                }
            }.getOrElse { AppResult.Failure(AppError.Network()) }
        }

        return when (probe) {
            null -> ConnectionTestResult(
                success = false,
                message = "连接超时（超过 ${TIMEOUT_MS / 1000} 秒），请检查网络后重试",
                docUrl = docUrl,
            )
            is AppResult.Success -> ConnectionTestResult(
                success = true,
                message = successMessage(source, configured),
                docUrl = docUrl,
            )
            is AppResult.Failure -> ConnectionTestResult(
                success = false,
                message = "连接失败：${probe.error.cause}",
                docUrl = docUrl,
            )
        }
    }

    /**
     * Bangumi 分能力连接测试（R57）：当前地址类型 → 公共搜索（地址可访问）→（已配置）Token/用户信息 →
     * 收藏可读。每步给出明确结论；失败带可读原因；官方失败提示切换自定义/检查代理。
     */
    private suspend fun testBangumi(configured: Boolean, docUrl: String?): ConnectionTestResult {
        val settings = runCatching { settingsDataStore.settings.first() }.getOrNull()
        val official = settings?.isBangumiOfficialApi ?: true
        val addrType = when {
            official -> "官方 API"
            com.acgcompass.feature.settings.BANGUMI_API_CANDIDATES.any {
                it.second.trimEnd('/') == settings?.bangumiApiBaseUrl?.trimEnd('/')
            } -> "社区反代"
            else -> "自定义"
        }
        val lines = mutableListOf("当前地址：$addrType（${settings?.bangumiApiBaseUrl ?: "?"}）")

        // 1) 公共搜索（验证地址可访问）。
        val search = withTimeoutOrNull(TIMEOUT_MS) {
            runCatching { bangumi.searchSubjects(keyword = PROBE_KEYWORD, limit = 1) }
                .getOrElse { AppResult.Failure(AppError.Network()) }
        }
        var overallOk = false
        when (search) {
            null -> {
                lines += "✗ 地址不可访问 / 公共搜索：超时（>${TIMEOUT_MS / 1000}s）"
                if (official) lines += "提示：当前网络可能无法访问官方 API，可检查网络/代理，或在下方切换到社区反代 / 自定义地址。"
                return ConnectionTestResult(false, lines.joinToString("\n"), docUrl)
            }
            is AppResult.Failure -> {
                lines += "✗ 公共搜索失败：${search.error.cause}"
                if (official) lines += "提示：当前网络可能无法访问官方 API，可检查网络/代理，或切换到社区反代 / 自定义地址。"
                return ConnectionTestResult(false, lines.joinToString("\n"), docUrl)
            }
            is AppResult.Success -> {
                lines += "✓ 地址可访问，公共搜索可用"
                overallOk = true
            }
        }

        // 2) 个人能力（需 Token）。
        if (!configured) {
            lines += "· 个人同步未配置（未填 Token）"
            return ConnectionTestResult(overallOk, lines.joinToString("\n"), docUrl)
        }
        if (!official && settings?.bangumiNonOfficialTokenConsent != true) {
            lines += "· 非官方地址未确认 Token 风险：个人 Token 不会发送（仅公共搜索）。如需个人同步请在下方确认。"
            return ConnectionTestResult(overallOk, lines.joinToString("\n"), docUrl)
        }
        when (val me = withTimeoutOrNull(TIMEOUT_MS) { bangumi.getMe() }) {
            null -> lines += "✗ 用户信息：超时"
            is AppResult.Failure -> lines += "✗ Token / 用户信息：${me.error.cause}"
            is AppResult.Success -> {
                val uname = me.data.username.ifBlank { me.data.nickname }
                lines += "✓ Token 有效，用户信息可读（@$uname）"
                when (val col = withTimeoutOrNull(TIMEOUT_MS) {
                    bangumi.getUserCollections(username = uname, limit = 1)
                }) {
                    null -> lines += "✗ 收藏读取：超时"
                    is AppResult.Failure -> lines += "✗ 收藏读取：${col.error.cause}"
                    is AppResult.Success -> lines += "✓ 收藏可同步（云端约 ${col.data.total} 条）"
                }
            }
        }
        return ConnectionTestResult(overallOk, lines.joinToString("\n"), docUrl)
    }

    /** 发起一次最小 AI 补全（model 留空回落用户配置默认），用于验证连通性（R28）。 */
    private suspend fun aiProbe(): AppResult<Unit> = runCatchingApp {        val provider = aiRegistry.get(aiSelector.selected())
            ?: throw AppError.Server(cause = "未注册可用的 AI 服务").asException()
        provider.complete(
            AiRequest(
                systemPrompt = "Connectivity check. Reply with: ok",
                userContent = "ping",
                model = "",
                // R-new1：探针给足少量输出 token。maxTokens=1 会让许多模型（尤其推理型如 Kimi）
                // 仅产出 0 个可见 token → 误判连接失败；这里放宽到 32 以容纳一句简短回复。
                maxTokens = 32,
            ),
        )
        Unit
    }

    /** 公共搜索成功时的文案：区分无需 key / 个人同步是否就绪。 */
    private fun successMessage(source: SourceId, configured: Boolean): String = when (source) {
        SourceId.JIKAN -> "公共搜索可用，无需 API Key"
        SourceId.BANGUMI, SourceId.ANILIST, SourceId.VNDB ->
            if (configured) {
                "公共搜索可用，个人同步已就绪"
            } else {
                "公共搜索可用；个人同步未配置（可选填 Token 后启用）"
            }
        else -> if (configured) "连接正常" else "公共搜索可用"
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
        const val PROBE_KEYWORD = "fate"
    }
}
