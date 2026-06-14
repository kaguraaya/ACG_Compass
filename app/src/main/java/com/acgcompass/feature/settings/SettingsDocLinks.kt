package com.acgcompass.feature.settings

import com.acgcompass.data.credential.SourceId

/**
 * 各数据源**官方公开文档**链接（RC.02 4.5/4.13）。
 *
 * 这些仅为公开 API 文档入口，**绝不**包含任何 key / token / secret / OAuth client 私有信息
 * （RC.00 1.2）。用于设置卡片的「查看文档」入口与连接测试失败时的引导。
 *
 * > 实现接入前需按 RC.01 联网核验各源最新文档并记入 DEVELOPMENT.md；此处链接为官方文档主页。
 */
object SettingsDocLinks {
    /** Bangumi 官方 API 文档。 */
    const val BANGUMI = "https://bangumi.github.io/api/"

    /** AniList 官方 API 文档（GraphQL）。 */
    const val ANILIST = "https://docs.anilist.co/"

    /** Jikan 官方 API 文档（非官方 MAL，免 key）。 */
    const val JIKAN = "https://docs.api.jikan.moe/"

    /** MyAnimeList 官方 API 文档（OAuth2）。 */
    const val MAL = "https://myanimelist.net/apiconfig/references/api/v2"

    /** VNDB 官方 API 文档（HTTP API / Kana）。 */
    const val VNDB = "https://api.vndb.org/kana"

    /** 按 [SourceId] 取文档链接。 */
    fun forSource(source: SourceId): String = when (source) {
        SourceId.BANGUMI -> BANGUMI
        SourceId.ANILIST -> ANILIST
        SourceId.JIKAN -> JIKAN
        SourceId.MAL -> MAL
        SourceId.VNDB -> VNDB
        // AI_PROVIDER 文档在 task 14.2 的 AI 卡片中处理；此处给出占位（不应被本任务的卡片使用）。
        SourceId.AI_PROVIDER -> ""
    }
}
