package com.acgcompass.data.repository

import com.acgcompass.core.common.AppResult
import com.acgcompass.core.common.AppError
import com.acgcompass.core.common.DispatcherProvider
import com.acgcompass.core.common.asException
import com.acgcompass.core.common.runCatchingApp
import com.acgcompass.core.common.toAppError
import com.acgcompass.core.network.DataSourceOrchestrator
import com.acgcompass.core.network.SourceData
import com.acgcompass.core.network.SourceFetcher
import com.acgcompass.core.network.SourceOutcome
import com.acgcompass.core.network.SourceRequest
import com.acgcompass.core.ui.Cta
import com.acgcompass.core.ui.UiState
import com.acgcompass.data.datastore.SettingsDataStore
import com.acgcompass.data.local.dao.RankingCacheDao
import com.acgcompass.data.local.dao.RatingDao
import com.acgcompass.data.local.dao.SourceLinkDao
import com.acgcompass.data.local.dao.TagDao
import com.acgcompass.data.local.dao.WorkDao
import com.acgcompass.data.local.dao.WorkTagWithCategory
import com.acgcompass.data.local.entity.RankingCacheEntity
import com.acgcompass.data.local.entity.WorkEntity
import com.acgcompass.data.local.mapper.toDomain
import com.acgcompass.data.local.mapper.toEntity
import com.acgcompass.data.local.mapper.toEntryOrNull
import com.acgcompass.data.local.mapper.toRatingAggregate
import com.acgcompass.data.local.mapper.toSourceRef
import com.acgcompass.data.remote.anilist.AniListRemoteDataSource
import com.acgcompass.data.remote.bangumi.BangumiRemoteDataSource
import com.acgcompass.data.remote.jikan.JikanRemoteDataSource
import com.acgcompass.data.remote.jikan.toRatingEntry as jikanToRatingEntry
import com.acgcompass.data.remote.jikan.toWork as jikanToWork
import com.acgcompass.data.remote.jikan.toWorkMatch as jikanToWorkMatch
import com.acgcompass.data.remote.mal.MalRemoteDataSource
import com.acgcompass.data.remote.vndb.VndbRemoteDataSource
import com.acgcompass.domain.matching.ResolvedLink
import com.acgcompass.domain.matching.decideMatch
import com.acgcompass.domain.matching.matchConfidence
import com.acgcompass.domain.matching.resolveLink
import com.acgcompass.domain.model.RatingAggregate
import com.acgcompass.domain.model.SourceId
import com.acgcompass.domain.model.SourceRef
import com.acgcompass.domain.model.Tag
import com.acgcompass.domain.model.TagCategory
import com.acgcompass.domain.model.Work
import com.acgcompass.domain.model.WorkMatch
import com.acgcompass.domain.repository.RankingPage
import com.acgcompass.domain.repository.WorkRepository
import com.acgcompass.domain.usecase.AggregateRatingsUseCase
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

/**
 * [WorkRepository] 实现（task 13.1 / RC.00 1.1 / RC.05.02/03 / RC.07 9.x）。
 *
 * **单一可信源 = Room**（设计「关键架构决策」1）：
 * - [observeWork] 始终从 [WorkDao] 的 Flow 读取并映射为七态 [UiState]，离线可用、状态一致。
 * - [search] 经 [DataSourceOrchestrator] + [BangumiRemoteDataSource] 取数，用匹配器
 *   （[matchConfidence] / [decideMatch]）重算置信度并排序，再把命中作品 upsert 进 Room，
 *   使后续 UI 读取保持一致。
 * - [aggregateRatings] 以 Room 中每源 [com.acgcompass.domain.model.RatingEntry] 为输入，
 *   交 [AggregateRatingsUseCase] 聚合；缺失源标记为 `missing`，绝不跨源回填（Property 5）。
 * - [overrideMatch] 以 [ResolvedLink] / [resolveLink] 语义写入 `userOverridden=true`，
 *   后续同步不再自动改写该链接（Property 8）。
 *
 * **韧性**（RC.03.04 / RC.17.4）：所有远程调用都包裹在 [AppResult] 中，异常兜底为领域错误，
 * 绝不崩溃；字段缺失走「暂无数据」语义（[UiState.PartialMissing] / `missing`）。
 */
@Singleton
class WorkRepositoryImpl @Inject constructor(
    private val workDao: WorkDao,
    private val sourceLinkDao: SourceLinkDao,
    private val ratingDao: RatingDao,
    private val tagDao: TagDao,
    private val rankingCacheDao: RankingCacheDao,
    private val bangumi: BangumiRemoteDataSource,
    private val anilist: AniListRemoteDataSource,
    private val jikan: JikanRemoteDataSource,
    private val mal: MalRemoteDataSource,
    private val vndb: VndbRemoteDataSource,
    private val orchestrator: DataSourceOrchestrator,
    private val aggregateRatingsUseCase: AggregateRatingsUseCase,
    private val dispatchers: DispatcherProvider,
    private val settingsDataStore: SettingsDataStore,
    private val workTagWriter: WorkTagWriter,
) : WorkRepository {

    // --- observeWork -------------------------------------------------------

    override fun observeWork(workId: String): Flow<UiState<Work>> =
        workDao.observeById(workId)
            .map<WorkEntity?, UiState<Work>> { entity ->
                if (entity == null) {
                    UiState.Empty(EMPTY_WORK_CTA)
                } else {
                    val work = entity.toDomain()
                    // 字段级缺失（封面 / 年份 / 状态未知）→ PartialMissing，UI 显示「暂无数据」
                    // 而非隐藏整块（RC.01 3.7 / RC.07 9.3）。
                    if (work.hasMissingDisplayFields()) {
                        UiState.PartialMissing(work)
                    } else {
                        UiState.Success(work)
                    }
                }
            }
            .onStart { emit(UiState.Loading) }
            .catch { throwable ->
                if (throwable is CancellationException) throw throwable
                emit(UiState.Error(throwable.toAppError()))
            }
            .flowOn(dispatchers.io)

    override fun observeWorks(): Flow<List<Work>> =
        workDao.observeAll()
            .map { entities ->
                // P2-5/P2-6：回填作品社区标签——候选池/题材筛选/今晚看什么依赖真实 tag，
                // 而 WorkEntity 不含 tags（存于连接表），故此处一次性 join 回填，避免列表恒空 tag。
                val tagsByWork = tagsForWorks(entities.map { it.id })
                entities.map { it.toDomain(tags = tagsByWork[it.id].orEmpty()) }
            }
            .flowOn(dispatchers.io)

    /** P2-5/P2-6：批量读取作品社区标签并按 workId 分组（分批避开 SQLite 变量上限）。 */
    private suspend fun tagsForWorks(workIds: List<String>): Map<String, List<Tag>> {
        if (workIds.isEmpty()) return emptyMap()
        val rows: List<WorkTagWithCategory> = workIds.chunked(900).flatMap { tagDao.getTagsForWorks(it) }
        return rows.groupBy({ it.workId }) { row ->
            Tag(category = TagCategory.fromStorage(row.category) ?: TagCategory.CONTENT, name = row.name)
        }
    }

    // --- search ------------------------------------------------------------

    override suspend fun search(query: String): AppResult<List<WorkMatch>> =
        withContext(dispatchers.io) {
            runCatchingApp {
                // 搜索采用「多源并行 + 合并」（而非降级取一源）：Bangumi / AniList / Jikan / MAL(配置后) / VNDB
                // 并行查询，各源失败互不影响（容错，RC.03.04 / RC.17.4）；汇总后按标题相似度重算置信度并排序。
                val perSource: List<List<WorkMatch>> = coroutineScope {
                    val tasks = listOf(
                        async { safeSearch { bangumi.searchSubjects(keyword = query) } },
                        async { safeSearch { anilist.searchMedia(keyword = query) } },
                        async { safeSearch { jikan.searchAnime(keyword = query) } },
                        async {
                            if (mal.isEnabled()) safeSearch { mal.searchAnime(keyword = query) } else emptyList()
                        },
                        async { safeSearch { vndb.searchVn(keyword = query) } },
                    )
                    tasks.map { it.await() }
                }

                val merged = perSource.flatten()
                // K4：跨源补齐 Bangumi——对「不含 Bangumi 命中」的簇，用其日文/罗马音/规范标题反查 Bangumi
                // 并并入结果。这样不同关键词搜同一部番都会稳定带出 Bangumi 源、且详情与列表来源一致
                //（修复「9nine 不同搜索词来源数不同」类问题，通用、非特例）。
                val backfilled = merged + backfillBangumi(merged)
                // 重算置信度后排序：先按匹配置信度降序，再按来源可信度（Bangumi>AniList>MAL>Jikan>VNDB）
                // 作为同分裁决（R2）。上层据此分组展示来源标签与 Match_Confidence。
                backfilled
                    .map { it.withRecomputedConfidence(query) }
                    .sortedWith(
                        // P0-1：用综合得分（相似度 + 评分人数对数加成，见 representativeScore）排序，
                        // 让正片在相似度仅略低于同名小条目时仍凭评分人数靠前；再按人数、来源可信度裁决。
                        compareByDescending<WorkMatch> {
                            com.acgcompass.domain.matching.representativeScore(it.matchConfidence.toDouble(), it.popularity)
                        }
                            .thenByDescending { it.popularity }
                            .thenBy { sourceCredibilityRank(it.sourceTag) },
                    )
                    .also { persistMatches(it) }
            }
        }

    /**
     * K4：对搜索结果里「缺少 Bangumi 来源」的作品簇，用其日文/罗马音/规范标题反查 Bangumi 补齐。
     * 仅动画、限额 [BANGUMI_BACKFILL_BUDGET] 次请求（控开销）；命中需达 [CROSS_MATCH_THRESHOLD]。
     * best-effort，失败忽略。使搜索结果跨关键词稳定、且统一可由 Bangumi 代表（中文/聚合评分）。
     */
    private suspend fun backfillBangumi(matches: List<WorkMatch>): List<WorkMatch> {
        if (matches.isEmpty()) return emptyList()
        val clusters = com.acgcompass.domain.matching.clusterMatches(matches)
        val result = mutableListOf<WorkMatch>()
        var budget = BANGUMI_BACKFILL_BUDGET
        for (cluster in clusters) {
            if (budget <= 0) break
            if (cluster.any { it.sourceTag == SourceId.BANGUMI }) continue
            val rep = com.acgcompass.domain.matching.representativeOf(cluster)
            if (rep.work.mediaType != com.acgcompass.domain.model.MediaType.ANIME) continue
            val queries = listOfNotNull(
                rep.work.titles.ja,
                rep.work.titles.romaji,
                rep.work.titles.canonical,
            ).filter { it.isNotBlank() }.distinct()
            for (q in queries) {
                if (budget <= 0) break
                budget--
                val hits = (bangumi.searchSubjects(keyword = q) as? AppResult.Success)?.data.orEmpty()
                val best = hits
                    // P0-1：仅接受动画条目，排除同名的小说/漫画/游戏（如「負けヒロインが多すぎる！」
                    // 小说 343241、或「ぬきたし」原作 VN），避免把非动画条目当作动画的 Bangumi 代表，
                    // 导致评分人数 / 简介错配（rep 已确认是动画，见上方 mediaType 判定）。
                    .filter { it.work.mediaType == com.acgcompass.domain.model.MediaType.ANIME }
                    .map { m -> m to candidateTitles(m).maxOf { matchConfidence(candidate = it, query = q) } }
                    // P0-1：用综合得分（相似度 + 评分人数对数加成，见 representativeScore）择优，
                    // 让正片在相似度仅略低于续作/小条目时仍凭评分人数胜出（如正片 27330 人 vs 第二季 11 人）。
                    .maxWithOrNull(
                        compareBy<Pair<WorkMatch, Double>>(
                            { com.acgcompass.domain.matching.representativeScore(it.second, it.first.popularity) },
                            { it.first.popularity },
                        ),
                    )
                if (best != null && best.second >= CROSS_MATCH_THRESHOLD) {
                    result += best.first.copy(matchConfidence = best.second.toFloat())
                    break
                }
            }
        }
        return result
    }

    /** 单源搜索容错包装：成功返回结果，失败 / 异常返回空列表（一个源失败不影响其它源，RC.03.04）。 */
    private suspend fun safeSearch(
        block: suspend () -> AppResult<List<WorkMatch>>,
    ): List<WorkMatch> =
        try {
            when (val result = block()) {
                is AppResult.Success -> result.data
                is AppResult.Failure -> emptyList()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }


    /** 构造 Bangumi 搜索取数器；远程失败原样透传 [AppResult.Failure]，由编排器降级处理。 */
    private fun bangumiSearchFetcher(query: String): SourceFetcher<List<WorkMatch>> =
        SourceFetcher {
            when (val result = bangumi.searchSubjects(keyword = query)) {
                is AppResult.Success -> AppResult.Success(SourceData(result.data))
                is AppResult.Failure -> AppResult.Failure(result.error)
            }
        }

    /**
     * 构造 AniList 搜索取数器（P1 辅助源）；远程失败原样透传 [AppResult.Failure]，由编排器降级处理。
     *
     * 中文兜底语义（RC.01 3.11）：AniList 命中作品以英文标题作为 `canonical`（无中文名），
     * 当 Bangumi（P0，提供中文名）不可用时即由此兜底展示。
     */
    private fun anilistSearchFetcher(query: String): SourceFetcher<List<WorkMatch>> =
        SourceFetcher {
            when (val result = anilist.searchMedia(keyword = query)) {
                is AppResult.Success -> AppResult.Success(SourceData(result.data))
                is AppResult.Failure -> AppResult.Failure(result.error)
            }
        }

    /** 构造 Jikan 搜索取数器；远程失败原样透传 [AppResult.Failure]，由编排器降级处理（RC.01 3.4/3.8）。 */
    private fun jikanSearchFetcher(query: String): SourceFetcher<List<WorkMatch>> =
        SourceFetcher {
            when (val result = jikan.searchAnime(keyword = query)) {
                is AppResult.Success -> AppResult.Success(SourceData(result.data))
                is AppResult.Failure -> AppResult.Failure(result.error)
            }
        }

    /**
     * 构造 MAL 官方搜索取数器（P2 用户源，RC.01 3.5 / RC.02 4.8）。
     *
     * 启用门控：仅当用户显式配置 Client ID（[MalRemoteDataSource.isEnabled]）才参与降级；
     * 未配置时取数器返回 `null`，编排器据此**跳过**本源、不发请求（RC.01 3.5）。
     * 已启用时远程失败原样透传 [AppResult.Failure]，由编排器降级到 VNDB。
     */
    private fun malSearchFetcher(query: String): SourceFetcher<List<WorkMatch>> =
        SourceFetcher {
            if (!mal.isEnabled()) return@SourceFetcher null
            when (val result = mal.searchAnime(keyword = query)) {
                is AppResult.Success -> AppResult.Success(SourceData(result.data))
                is AppResult.Failure -> AppResult.Failure(result.error)
            }
        }

    /** 来源可信度排序（数值越小越靠前）：动画综合资料以 Bangumi/AniList 为先（R2 同分裁决）。 */
    private fun sourceCredibilityRank(source: SourceId): Int = when (source) {
        SourceId.BANGUMI -> 0
        SourceId.ANILIST -> 1
        SourceId.MAL -> 2
        SourceId.JIKAN -> 3
        SourceId.VNDB -> 4
    }

    /** 以标题相似度（覆盖所有已知标题 / 别名取最大值）重算匹配置信度（RC.05.02 / Property 8）。 */
    private fun WorkMatch.withRecomputedConfidence(query: String): WorkMatch {
        val titles = buildList {
            add(work.titles.canonical)
            work.titles.ja?.let(::add)
            work.titles.romaji?.let(::add)
            work.titles.en?.let(::add)
            addAll(work.titles.aliases)
        }
        val best = titles.maxOfOrNull { matchConfidence(candidate = it, query = query) } ?: 0.0
        return copy(matchConfidence = best.toFloat())
    }

    /**
     * 把命中作品与其源链接写入 Room（upsert）。
     *
     * 源链接经 [resolveLink] 解析：若本地已存在且被用户手动纠正（`userOverridden=true`），
     * 则保留用户选择、不被本次远程结果覆盖（Property 8）；作品的 `createdAt` 在重复 upsert 时保留。
     */
    private suspend fun persistMatches(matches: List<WorkMatch>) {
        if (matches.isEmpty()) return
        val now = System.currentTimeMillis()

        val entities = matches.map { match ->
            val createdAt = workDao.getById(match.work.id)?.createdAt ?: now
            match.work.toEntity(createdAt = createdAt, updatedAt = now)
        }
        workDao.upsertAll(entities)
        // P2-5/P2-6：同步持久化作品社区标签（tags+work_tags 连接表，WorkEntity 不含 tags）。经共享
        // WorkTagWriter 写入，与个人收藏同步路径用一致的标签主键规则，修复候选池/题材筛选/今晚看什么标签恒空。
        workTagWriter.persist(matches.map { it.work })

        // R42：跨源合并——把同一簇内每个来源的链接都指向「代表作品 id」，使详情页 aggregateRatings
        // 能汇总多平台评分；通用聚类规则见 domain/matching/CrossSourceMerge（无样例硬编码）。
        com.acgcompass.domain.matching.clusterMatches(matches).forEach { cluster ->
            val repId = com.acgcompass.domain.matching.representativeOf(cluster).work.id
            cluster.forEach { match ->
                val recomputed = ResolvedLink(
                    sourceRef = SourceRef(
                        sourceId = match.sourceTag,
                        sourceItemId = match.work.id,
                        matchConfidence = match.matchConfidence,
                        userOverridden = false,
                    ),
                    userOverridden = false,
                )
                val existing = sourceLinkDao.getBySourceItem(match.sourceTag.name, match.work.id)
                val resolved = existing?.toSourceRef()
                    ?.let { current ->
                        resolveLink(
                            current = ResolvedLink(current, existing.userOverridden),
                            recomputed = recomputed,
                        )
                    }
                    ?: recomputed
                sourceLinkDao.upsert(
                    resolved.sourceRef.toEntity(
                        id = existing?.id ?: linkId(repId, match.sourceTag, match.work.id),
                        workId = repId,
                        linkedAt = now,
                    ),
                )
            }
        }
    }

    // --- aggregateRatings --------------------------------------------------
    override suspend fun loadPublicDiscovery(): AppResult<Int> = withContext(dispatchers.io) {
        runCatchingApp {
            var written = 0
            // 收集全部公共源命中，最后**一次性** persistMatches → 跨源按标题聚类合并，
            // 同一作品的多平台链接指向同一代表作品，使评分差异/多平台评分能聚合（G4/G16）。
            val allMatches = mutableListOf<com.acgcompass.domain.model.WorkMatch>()
            // 评分写入缓冲：(workId, source, entry)，persistMatches 后统一写。
            val ratingRows = mutableListOf<Triple<String, SourceId, com.acgcompass.domain.model.RatingEntry>>()

            // G4/G16：本季 / 近期热门**默认 Bangumi**（每日放送）。
            val calendar = (bangumi.getCalendar() as? AppResult.Success)?.data.orEmpty()
            calendar.forEach { (match, entry) ->
                allMatches += match
                if (entry != null) ratingRows += Triple(match.work.id, SourceId.BANGUMI, entry)
            }
            written += calendar.size

            // L2b / N6：真实排行——Bangumi `sort=rank` 的 Top 动画分页拉取（扩充筛选/差异样本）+ 本季榜，
            // 带真实评分，使「榜单/筛选/差异」有量且准确。失败降级（不阻塞）。
            val rankedOverall = buildList {
                for (page in 0 until 3) {
                    val pageData = (
                        bangumi.searchRankedSubjects(limit = 50, offset = page * 50) as? AppResult.Success
                        )?.data?.items.orEmpty()
                    if (pageData.isEmpty()) break
                    addAll(pageData)
                }
            }
            val seasonRange = currentSeasonAirDateRange()
            val rankedSeason =
                (bangumi.searchRankedSubjects(airDate = seasonRange, limit = 30) as? AppResult.Success)
                    ?.data?.items.orEmpty()
            (rankedOverall + rankedSeason).forEach { (match, entry) ->
                allMatches += match
                if (entry != null) ratingRows += Triple(match.work.id, SourceId.BANGUMI, entry)
            }
            written += rankedOverall.size + rankedSeason.size

            // 补充源（Jikan top/season）。
            val season = (jikan.getSeasonNow() as? AppResult.Success)?.data.orEmpty()
            val top = (jikan.getTopAnime() as? AppResult.Success)?.data.orEmpty()
            val jikanAll = (season + top).distinctBy { it.malId }.filter { it.malId > 0 }
            jikanAll.forEach { dto ->
                allMatches += dto.jikanToWorkMatch()
                dto.jikanToRatingEntry()?.let { ratingRows += Triple(dto.jikanToWork().id, SourceId.JIKAN, it) }
            }
            written += jikanAll.size

            // 补充源（AniList trending）。
            val trending = (anilist.getTrending() as? AppResult.Success)?.data.orEmpty()
            trending.forEach { (match, entry) ->
                allMatches += match
                if (entry != null) ratingRows += Triple(match.work.id, SourceId.ANILIST, entry)
            }
            written += trending.size

            if (allMatches.isEmpty()) {
                throw AppError.Network(cause = "未能获取公共榜单，请检查网络后重试").asException()
            }
            // 一次性持久化 + 跨源聚类（source links 指向代表作品）。
            persistMatches(allMatches)
            // 写入各源评分行（按各源 work id；详情/差异聚合时 aggregateRatings 会按 source links 汇总）。
            ratingRows.forEach { (workId, source, entry) ->
                ratingDao.upsert(
                    entry.toEntity(id = ratingId(workId, source), workId = workId, sourceId = source),
                )
            }
            written
        }
    }

    /** L2b：当前季度的开播日期范围过滤（用于本季榜单 `sort=rank` 浏览）。 */
    private fun currentSeasonAirDateRange(): List<String> {
        val now = java.time.LocalDate.now()
        val startMonth = ((now.monthValue - 1) / 3) * 3 + 1
        val start = java.time.LocalDate.of(now.year, startMonth, 1)
        val end = start.plusMonths(3)
        val fmt = java.time.format.DateTimeFormatter.ISO_LOCAL_DATE
        return listOf(">=${start.format(fmt)}", "<${end.format(fmt)}")
    }

    override suspend fun loadBangumiRankingPage(
        airDate: List<String>?,
        offset: Int,
        limit: Int,
    ): AppResult<RankingPage> = withContext(dispatchers.io) {
        runCatchingApp {
            // P2-2：按 offset/limit 拉取真实排行的一页（Bangumi 已按 rank 排序，跨页顺序由 offset 维持）。
            val page = when (
                val r = bangumi.searchRankedSubjects(airDate = airDate, limit = limit, offset = offset)
            ) {
                is AppResult.Success -> r.data
                is AppResult.Failure -> throw r.error.asException()
            }
            val pageData = page.items
            // 写入 Room（详情可跳转 + 冷启动重建卡片）+ 写 Bangumi 评分行；跨源聚类由 persistMatches 处理。
            // 空页（分页到底）属正常：persistMatches 对空列表直接返回。
            persistMatches(pageData.map { it.first })
            pageData.forEach { (match, entry) ->
                if (entry != null) {
                    ratingDao.upsert(
                        entry.toEntity(
                            id = ratingId(match.work.id, SourceId.BANGUMI),
                            workId = match.work.id,
                            sourceId = SourceId.BANGUMI,
                        ),
                    )
                }
            }
            // P2-2：返回该页作品 + 过滤后总数，供上层用 total 判定是否还有更多页（不依赖返回数 >= limit）。
            RankingPage(
                items = pageData.map { (match, entry) -> match.work to entry },
                total = page.total,
            )
        }
    }

    override suspend fun getCachedRanking(
        scopeKey: String,
    ): List<Pair<Work, com.acgcompass.domain.model.RatingEntry?>> = withContext(dispatchers.io) {
        // P2-3 / B-4：按 Room `ranking_cache` 表缓存的有序作品 id 重建榜单卡片所需的作品 + Bangumi 评分；
        // 作品已被清理的行安全跳过（容错缓存）。
        rankingCacheDao.getByScope(scopeKey).mapNotNull { row ->
            val work = workDao.getById(row.workId)?.toDomain() ?: return@mapNotNull null
            val entry = ratingDao.getByWorkAndSource(row.workId, SourceId.BANGUMI.name)?.toEntryOrNull()
            work to entry
        }
    }

    override suspend fun saveRankingCache(scopeKey: String, orderedWorkIds: List<String>) {
        // P2-3 / B-4：整范围覆盖写入 Room（单事务）。position 即真实排名次序，cachedAt 供后续新鲜度判断。
        val now = System.currentTimeMillis()
        val rows = orderedWorkIds.mapIndexed { index, id ->
            RankingCacheEntity(scopeKey = scopeKey, position = index, workId = id, cachedAt = now)
        }
        rankingCacheDao.replaceScope(scopeKey, rows)
    }

    override suspend fun aggregateRatings(workId: String): AppResult<RatingAggregate> =
        withContext(dispatchers.io) {
            runCatchingApp {
                // 韧性刷新：best-effort 按作品的源链接拉取各源评分并刷新作品资料写回 Room；失败则沿用本地数据（R3）。
                refreshRatingsAndWork(workId)
                // 单一可信源：从 Room 读取每源评分行，聚合（缺失源保留 null，绝不回填，Property 5）。
                val perSource = ratingDao.getByWork(workId).toRatingAggregate().perSource
                aggregateRatingsUseCase(perSource)
            }
        }

    /** K9：仅读本地缓存评分聚合，不触发网络刷新（发现页批量场景用，避免逐作品联网卡顿）。 */
    override suspend fun aggregateRatingsCached(workId: String): RatingAggregate =
        withContext(dispatchers.io) {
            val perSource = ratingDao.getByWork(workId).toRatingAggregate().perSource
            aggregateRatingsUseCase(perSource)
        }

    /**
     * best-effort 详情刷新（R3 / RC.07）：按作品的全部源链接拉取各源评分写回 Room，并用主源刷新作品资料字段。
     * 任何失败都被吞掉以保持韧性（绝不崩溃，缺失即「暂无数据」，RC.01 3.7 / RC.17.4）。
     */
    private suspend fun refreshRatingsAndWork(workId: String) {
        // H11：路线跳转等情况本地无该作品 → 若 id 为数字（Bangumi subjectId），先拉条目落库，
        // 否则详情页整页空白。失败被吞掉（韧性）。
        if (workDao.getById(workId) == null) {
            workId.toIntOrNull()?.let { sid ->
                val fetched = bangumi.getSubject(sid)
                if (fetched is AppResult.Success) {
                    val now = System.currentTimeMillis()
                    workDao.upsert(fetched.data.toEntity(createdAt = now, updatedAt = now))
                }
            }
        }
        val links = sourceLinkDao.getByWork(workId)
        // 无显式链接时，按「Bangumi 来源作品本地 id 即条目 id」兜底尝试主源评分。
        val pairs: List<Pair<SourceId, String>> = if (links.isEmpty()) {
            val primary = workDao.getById(workId)?.let { sourceIdFromName(it.primarySource) }
            if (primary != null) listOf(primary to workId) else emptyList()
        } else {
            links.mapNotNull { l -> sourceIdFromName(l.sourceId)?.let { it to l.sourceItemId } }
        }

        for ((sid, itemId) in pairs) {
            val rating = fetchSourceRating(sid, itemId)
            if (rating is AppResult.Success) {
                ratingDao.upsert(
                    rating.data.toEntity(id = ratingId(workId, sid), workId = workId, sourceId = sid),
                )
            }
        }

        // H16：多源评分交叉验证（mzzbscore 思路）——对尚无评分的公共源，按作品标题跨平台搜索匹配，
        // 命中（高置信）则把对方平台评分并入本作品，使详情页 / 评分差异能做多源对照。失败被吞掉（韧性）。
        crossValidateRatings(workId)

        // 主源刷新作品资料（补齐封面 / 集数 / 简介等字段），保留 createdAt（不覆盖创建时间）。
        val entity = workDao.getById(workId) ?: return
        val primary = sourceIdFromName(entity.primarySource) ?: return
        val primaryItemId = pairs.firstOrNull { it.first == primary }?.second ?: workId
        val refreshed: AppResult<Work>? = when (primary) {
            SourceId.BANGUMI -> primaryItemId.toIntOrNull()?.let { bangumi.getSubject(it) }
            SourceId.ANILIST -> primaryItemId.toIntOrNull()?.let { anilist.getMedia(it) }
            SourceId.JIKAN -> primaryItemId.toIntOrNull()?.let { jikan.getAnime(it) }
            SourceId.MAL -> if (mal.isEnabled()) primaryItemId.toIntOrNull()?.let { mal.getAnime(it) } else null
            SourceId.VNDB -> vndb.getVn(primaryItemId)
        }
        if (refreshed is AppResult.Success) {
            workDao.upsert(refreshed.data.toEntity(createdAt = entity.createdAt, updatedAt = System.currentTimeMillis()))
        }

        // N15 / P1-1：非 Bangumi 主源但已匹配到 Bangumi 链接时，用 Bangumi 中文内容覆盖展示
        //（标题 + 简介中文化）。修复「打开是 Jikan 源，标题/简介全是外语」。
        // 安全前提：交叉验证落的 Bangumi 链接为高置信（≥CROSS_MATCH_THRESHOLD，仅动画、评分人数多），
        // 故用其中文标题相对安全；原标题保留进 aliases 以免丢失搜索/匹配能力。仅覆盖标题/简介，
        // 不动封面/主源/媒介，避免错配污染身份（tag 展示在详情页另行处理）。
        if (primary != SourceId.BANGUMI) {
            val bgmLink = sourceLinkDao.getByWork(workId).firstOrNull { it.sourceId == SourceId.BANGUMI.name }
            val bgmSubjectId = bgmLink?.sourceItemId?.toIntOrNull()
            if (bgmSubjectId != null) {
                val bgmWork = (bangumi.getSubject(bgmSubjectId) as? AppResult.Success)?.data
                val cur = if (bgmWork != null) workDao.getById(workId) else null
                if (bgmWork != null && cur != null) {
                    val bgmSummary = bgmWork.summary?.takeIf { it.isNotBlank() }
                    // Bangumi `toWork` 的 canonical = 中文名 ?: 原名、ja = 原名；故 `canonical != ja`
                    // 精确表示「该条目存在独立中文名」。仅此前提且与当前标题不同时覆盖标题，原标题并入 aliases。
                    val bgmTitle = bgmWork.titles.canonical.takeIf {
                        it.isNotBlank() && it != bgmWork.titles.ja && it != cur.canonicalTitle
                    }
                    if (bgmSummary != null || bgmTitle != null) {
                        workDao.upsert(
                            cur.copy(
                                canonicalTitle = bgmTitle ?: cur.canonicalTitle,
                                titleJa = cur.titleJa ?: bgmWork.titles.ja,
                                aliases = if (bgmTitle != null) {
                                    (cur.aliases + cur.canonicalTitle).filter { it.isNotBlank() }.distinct()
                                } else {
                                    cur.aliases
                                },
                                summary = bgmSummary ?: cur.summary,
                                updatedAt = System.currentTimeMillis(),
                            ),
                        )
                    }
                }
            }
        }
    }

    /** 从持久化来源名解析 [SourceId]；未知返回 `null`（不臆造，RC.17.4）。 */
    private fun sourceIdFromName(raw: String?): SourceId? =
        SourceId.entries.firstOrNull { it.name == raw }

    /** 按来源拉取单源评分；不支持 / 未启用 / id 非法时返回 `null`。 */
    private suspend fun fetchSourceRating(
        sid: SourceId,
        itemId: String,
    ): AppResult<com.acgcompass.domain.model.RatingEntry>? = when (sid) {
        SourceId.BANGUMI -> itemId.toIntOrNull()?.let { bangumi.getSubjectRating(it) }
        SourceId.ANILIST -> itemId.toIntOrNull()?.let { anilist.getMediaRating(it) }
        SourceId.JIKAN -> itemId.toIntOrNull()?.let { jikan.getAnimeRating(it) }
        SourceId.MAL -> if (mal.isEnabled()) itemId.toIntOrNull()?.let { mal.getAnimeRating(it) } else null
        SourceId.VNDB -> vndb.getVnRating(itemId)
    }

    /**
     * H16：多源评分交叉验证。对当前作品尚无评分行的公共动画源（Bangumi / AniList / Jikan），
     * 用作品标题跨平台搜索，按标题相似度选最佳匹配；相似度达阈值 [CROSS_MATCH_THRESHOLD] 时，
     * 拉取该平台评分并以当前 `workId` 写入 `ratings`，同时落一条源链接便于后续聚合。
     *
     * 仅对动画（[com.acgcompass.domain.model.MediaType.ANIME]）执行，避免 VN / 漫画跨类误配；
     * 全程 best-effort，任何失败都被吞掉（绝不伪造、绝不崩溃，RC.01 3.7 / RC.17.4）。
     */
    private suspend fun crossValidateRatings(workId: String) {
        val entity = workDao.getById(workId) ?: return
        if (com.acgcompass.domain.model.MediaType.fromStorage(entity.mediaType) !=
            com.acgcompass.domain.model.MediaType.ANIME
        ) return
        // N17：用作品的全部标题变体（原名 ja 优先 + 罗马音 + 规范名 + 英文 + 别名）跨源查询。
        // 关键修复：拔作岛（中文规范名）在 AniList/Jikan 搜不到，但其原名「ぬきたし」能命中其他源评分。
        val titles = entity.toDomain().titles
        val titleVariants = (
            listOfNotNull(titles.ja, titles.romaji, titles.canonical, titles.en) + titles.aliases
            ).map { it.trim() }.filter { it.isNotBlank() }.distinct().take(4)
        if (titleVariants.isEmpty()) return

        val existingSources = ratingDao.getByWork(workId).map { it.sourceId }.toSet()
        val now = System.currentTimeMillis()

        // 公共可搜索源（无需 key）。已有评分的源跳过。
        val searchers: List<Pair<SourceId, suspend (String) -> AppResult<List<WorkMatch>>>> = listOf(
            SourceId.BANGUMI to { q -> bangumi.searchSubjects(keyword = q) },
            SourceId.ANILIST to { q -> anilist.searchMedia(keyword = q) },
            SourceId.JIKAN to { q -> jikan.searchAnime(keyword = q) },
        )

        for ((sid, search) in searchers) {
            if (sid.name in existingSources) continue
            // 逐个标题变体搜索该源，取「候选别名 vs 本作各变体」的最大相似度命中。
            var best: Pair<WorkMatch, Double>? = null
            for (q in titleVariants) {
                val matches = (search(q) as? AppResult.Success)?.data.orEmpty()
                if (matches.isEmpty()) continue
                val localBest = matches
                    // P0-1：仅接受动画条目（本作已确认是动画），排除同名小说/漫画/游戏，避免取错
                    // 条目的评分（如「負けヒロインが多すぎる！」小说 343241=2353人 vs 动画 464376=27330人；
                    // 「ぬきたし」原作 VN vs 动画 477825）——这是免卸载重装即可纠正评分错配的关键。
                    .filter { it.work.mediaType == com.acgcompass.domain.model.MediaType.ANIME }
                    .map { m ->
                        val conf = candidateTitles(m).maxOf { ct ->
                            titleVariants.maxOf { tv -> matchConfidence(candidate = ct, query = tv) }
                        }
                        m to conf
                    }
                    // P0-1：用综合得分（相似度 + 评分人数对数加成，见 representativeScore）择优，
                    // 让正片在相似度仅略低于续作/小条目时仍凭评分人数胜出（如正片 27330 人 vs 第二季 11 人）。
                    .maxWithOrNull(
                        compareBy<Pair<WorkMatch, Double>>(
                            { com.acgcompass.domain.matching.representativeScore(it.second, it.first.popularity) },
                            { it.first.popularity },
                        ),
                    )
                // P0-1：跨标题变体也按综合得分比较，避免某变体命中续作（相似度略高、人数极少）顶替正片。
                if (localBest != null && (
                        best == null ||
                            com.acgcompass.domain.matching.representativeScore(localBest.second, localBest.first.popularity) >
                            com.acgcompass.domain.matching.representativeScore(best!!.second, best!!.first.popularity)
                        )
                ) {
                    best = localBest
                }
                if (best != null && best!!.second >= 0.97) break // 已极高，提前结束省请求
            }
            if (best == null || best!!.second < CROSS_MATCH_THRESHOLD) continue

            val matchedId = best!!.first.work.id
            val matchedConf = best!!.second
            val rating = fetchSourceRating(sid, matchedId)
            if (rating is AppResult.Success) {
                ratingDao.upsert(
                    rating.data.toEntity(id = ratingId(workId, sid), workId = workId, sourceId = sid),
                )
                // 落源链接（指向当前作品），后续刷新直接命中，无需再次搜索。
                sourceLinkDao.upsert(
                    SourceRef(
                        sourceId = sid,
                        sourceItemId = matchedId,
                        matchConfidence = matchedConf.toFloat(),
                        userOverridden = false,
                    ).toEntity(
                        id = linkId(workId, sid, matchedId),
                        workId = workId,
                        linkedAt = now,
                    ),
                )
            }
        }
    }
    private fun candidateTitles(match: WorkMatch): List<String> = buildList {
        add(match.work.titles.canonical)
        match.work.titles.ja?.let(::add)
        match.work.titles.romaji?.let(::add)
        match.work.titles.en?.let(::add)
        addAll(match.work.titles.aliases)
    }.filter { it.isNotBlank() }

    // --- overrideMatch -----------------------------------------------------

    override suspend fun overrideMatch(localId: String, chosen: SourceRef): AppResult<Unit> =
        withContext(dispatchers.io) {
            runCatchingApp {
                // 手动纠正：标记 userOverridden=true，后续同步不再自动改写该链接（Property 8）。
                val overridden = ResolvedLink(
                    sourceRef = chosen.copy(userOverridden = true),
                    userOverridden = true,
                )
                val existing = sourceLinkDao.getBySourceItem(
                    chosen.sourceId.name,
                    chosen.sourceItemId,
                )
                sourceLinkDao.upsert(
                    overridden.sourceRef.toEntity(
                        id = existing?.id ?: linkId(localId, chosen.sourceId, chosen.sourceItemId),
                        workId = localId,
                    ),
                )
            }
        }

    private companion object {
        /** 空态「下一步」引导：去搜索添加作品（RC.03.03）。 */
        val EMPTY_WORK_CTA = Cta(label = "搜索并添加作品", action = "search")

        /** 稳定的源链接主键：`workId:sourceId:sourceItemId`（同一三元组重复 upsert 即覆盖）。 */
        fun linkId(workId: String, sourceId: SourceId, sourceItemId: String): String =
            "$workId:${sourceId.name}:$sourceItemId"

        /** 稳定的评分行主键：`workId:sourceId`（每源单行，刷新即覆盖）。 */
        fun ratingId(workId: String, sourceId: SourceId): String = "$workId:${sourceId.name}"

        /** K4：跨源补齐 Bangumi 的请求预算（单次搜索最多反查次数，控开销）。 */
        const val BANGUMI_BACKFILL_BUDGET = 8

        /** H16：跨平台交叉验证的标题相似度阈值（高阈值避免误配，宁缺毋滥）。 */
        const val CROSS_MATCH_THRESHOLD = 0.86
    }
}

/** 判断作品是否存在字段级缺失（封面 / 年份 / 发行状态未知）→ 触发「暂无数据」展示。 */
private fun Work.hasMissingDisplayFields(): Boolean =
    coverUrl == null ||
        year == null ||
        status == com.acgcompass.domain.model.ReleaseStatus.UNKNOWN
