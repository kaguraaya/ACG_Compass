package com.acgcompass.data.remote.anilist

/**
 * AniList GraphQL 查询语句（RC.01 3.3，已核验官方 Media 查询示例见 `DEVELOPMENT.md` 2026-06-08）。
 *
 * 两条查询请求的 `Media` 字段保持一致（[MEDIA_FIELDS]），便于复用同一套 [AniListMappers]：
 * - [MEDIA_BY_ID]：`Media(id: Int)` 精确取条目（ID 不存在 → HTTP 404）。
 * - [SEARCH_BY_TITLE]：`Page { media(search: String!, type: MediaType, sort: SEARCH_MATCH) }`
 *   按标题搜索，`SEARCH_MATCH` 让最相关结果排在前（官方推荐；精确置信度由上层匹配器另算）。
 *
 * 仅请求本项目实际使用的字段，不臆造未核验字段（RC.01 3.5）。
 */
object AniListQueries {

    /** 复用的 `Media` 选择集（含评分 / 热度 / 趋势 / 标题 / 季度 / 排名 / 评分分布）。 */
    private const val MEDIA_FIELDS = """
        id
        type
        format
        status
        title { romaji english native }
        description(asHtml: false)
        averageScore
        meanScore
        popularity
        favourites
        trending
        genres
        season
        seasonYear
        episodes
        duration
        chapters
        volumes
        startDate { year month day }
        coverImage { extraLarge large medium color }
        rankings { rank type allTime context }
        stats { scoreDistribution { score amount } }
    """

    /** 按 ID 取单个 Media。变量：`id: Int`。 */
    val MEDIA_BY_ID: String = """
        query (${'$'}id: Int) {
          Media(id: ${'$'}id) {
            $MEDIA_FIELDS
          }
        }
    """.trimIndent()

    /** 按标题搜索 Media。变量：`search: String!`、可选 `type: MediaType`、`perPage: Int`。 */
    val SEARCH_BY_TITLE: String = """
        query (${'$'}search: String!, ${'$'}type: MediaType, ${'$'}perPage: Int) {
          Page(perPage: ${'$'}perPage) {
            media(search: ${'$'}search, type: ${'$'}type, sort: SEARCH_MATCH) {
              $MEDIA_FIELDS
            }
          }
        }
    """.trimIndent()

    /** 搜索默认返回条数（避免一次拉取过多，节流友好）。 */
    const val DEFAULT_SEARCH_PER_PAGE: Int = 10

    /**
     * F10：本季 / 趋势榜单（公共数据，免鉴权）。按 `TRENDING_DESC` 取当前热门动画，
     * 复用 [MEDIA_FIELDS] 以便同一套 [AniListMappers] 映射。变量：可选 `perPage: Int`。
     */
    val TRENDING_NOW: String = """
        query (${'$'}perPage: Int) {
          Page(perPage: ${'$'}perPage) {
            media(sort: TRENDING_DESC, type: ANIME) {
              $MEDIA_FIELDS
            }
          }
        }
    """.trimIndent()

    /** 公共榜单默认返回条数。 */
    const val DEFAULT_TRENDING_PER_PAGE: Int = 25
}
