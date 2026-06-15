package com.acgcompass.feature.detail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.acgcompass.core.common.AppResult
import com.acgcompass.core.ui.UiState
import com.acgcompass.data.credential.CredentialStore
import com.acgcompass.data.credential.SourceId as CredentialSourceId
import com.acgcompass.data.local.dao.UserCollectionDao
import com.acgcompass.data.local.dao.WorkDao
import com.acgcompass.data.local.entity.UserCollectionEntity
import com.acgcompass.data.local.mapper.toEntity
import com.acgcompass.domain.ai.AiEngine
import com.acgcompass.domain.ai.AiRunOptions
import com.acgcompass.domain.ai.AiRunResult
import com.acgcompass.domain.ai.AiTask
import com.acgcompass.domain.ai.SpoilerRadarResult
import com.acgcompass.domain.model.CollectionState
import com.acgcompass.domain.model.RatingAggregate
import com.acgcompass.domain.model.TagBucket
import com.acgcompass.domain.model.TasteProfile
import com.acgcompass.domain.model.Work
import com.acgcompass.domain.repository.BacklogRepository
import com.acgcompass.domain.repository.BulkOp
import com.acgcompass.domain.repository.TasteProfileRepository
import com.acgcompass.domain.repository.WorkRepository
import com.acgcompass.domain.usecase.GenerateSpoilerRadarUseCase
import com.acgcompass.domain.usecase.RadarOutcome
import com.acgcompass.domain.usecase.RadarRequest
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 作品详情页 ViewModel（RC.07.01–07.07 / Requirements 9.1–9.8）。MVVM + Hilt + StateFlow。
 *
 * 数据来源（单一可信源 = Room）：
 * - [WorkRepository.observeWork]：观察规范化作品，提供顶部信息区数据并贡献七态 [UiState]
 *   （加载 / 空 / 错误 / 字段缺失 / 成功 …）。
 * - [WorkRepository.aggregateRatings]：聚合多平台评分（内部经 [com.acgcompass.domain.usecase.AggregateRatingsUseCase]
 *   计算，缺失源标记 `missing`、样本不足 `consensus=null`），提供评分区与社区共识卡数据。
 * - [BacklogRepository.observeBacklog]：判定该作品是否已在待补池（驱动个人区「加入 / 移出待补池」按钮，RC.07.04）。
 * - [TasteProfileRepository.observeTasteProfile]：口味画像；未生成时决策区口味匹配度显示「暂无数据」（RC.07.05 / RC.10.03）。
 *
 * 这些流合并：作品流决定页面整体状态；评分 / 待补 / 口味为「尽力而为」的附加数据——尚未就绪或拉取失败时，
 * 不阻塞作品展示（评分区按平台显示「暂无数据」、共识低置信，RC.07.03）。
 *
 * `workId` 由导航参数经 [SavedStateHandle] 注入（[DETAIL_ARG_WORK_ID]）。
 */
@HiltViewModel
class DetailViewModel @Inject constructor(
    private val workRepository: WorkRepository,
    private val backlogRepository: BacklogRepository,
    tasteProfileRepository: TasteProfileRepository,
    private val generateSpoilerRadar: GenerateSpoilerRadarUseCase,
    private val bangumiDataSource: com.acgcompass.data.remote.bangumi.BangumiRemoteDataSource,
    private val aiEngine: AiEngine,
    private val credentialStore: CredentialStore,
    private val userCollectionDao: UserCollectionDao,
    private val workDao: WorkDao,
    private val sourceLinkDao: com.acgcompass.data.local.dao.SourceLinkDao,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val workId: String = savedStateHandle.get<String>(DETAIL_ARG_WORK_ID).orEmpty()

    /**
     * N1：解析作品的**真实 Bangumi subjectId**（绝不把 Jikan/AniList 的裸数字 work.id 当成 subjectId）。
     * - 作品主源即 Bangumi → work.id 即 subjectId。
     * - 否则查 Bangumi 源链接（交叉验证后落库）取 sourceItemId。
     * - 都没有 → 返回 null（该作品在 Bangumi 无对应词条，不回写，避免污染无关条目）。
     */
    private suspend fun resolveBangumiSubjectId(work: Work): Int? {
        if (work.primarySource == com.acgcompass.domain.model.SourceId.BANGUMI) {
            return work.id.toIntOrNull()
        }
        val link = sourceLinkDao.getByWork(work.id).firstOrNull { it.sourceId == "BANGUMI" }
        return link?.sourceItemId?.toIntOrNull()
    }

    /** G8/G9/G13：编辑「我的记录」并回写 Bangumi 的结果提示（Snackbar 文案）；消费后置 null。 */
    private val _recordMessage = MutableStateFlow<String?>(null)
    val recordMessage: StateFlow<String?> = _recordMessage

    /** 消费一次性提示。 */
    fun consumeRecordMessage() { _recordMessage.value = null }

    /** E：「AI 分析匹配度」按钮状态（RC.10.03 / RC.14）。初始为 [AiMatchUi.Idle]。 */
    private val _aiMatch = MutableStateFlow<AiMatchUi>(AiMatchUi.Idle)
    val aiMatch: StateFlow<AiMatchUi> = _aiMatch

    /** F7：详情页角色·Staff / 关联作品 / 观看路线（来自各源端点拉取后折叠的可读正文）。 */
    private val detailExtras = MutableStateFlow(DetailExtras())

    /** H：当前系列（含主线+可选）的 Bangumi subject id，供一键加入待补池。 */
    @Volatile
    private var seriesSubjectIds: List<Int> = emptyList()

    /** R44：我的个人记录（来自同步入库的用户收藏）；`null` 表示未记录 → 个人区显示「暂无数据」。 */
    private val myCollection: StateFlow<CollectionState?> =
        userCollectionDao.observeByWork(workId)
            .map { entity ->
                entity?.let {
                    CollectionState(
                        workId = it.localWorkId,
                        status = it.status,
                        rating = it.rating,
                        shortReview = it.comment,
                        progress = it.progress,
                    )
                }
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
                initialValue = null,
            )

    /** 评分聚合结果（附加数据流）；`null` 表示尚未加载 / 拉取失败 → 评分区显示「暂无数据」。 */
    private val ratings = MutableStateFlow<RatingAggregate?>(null)

    /**
     * 无剧透评价雷达（RC.09.04/05/06）；`null` 表示尚未生成 / 需成本确认 → 决策区显示「暂无数据」，
     * 绝不伪造。未配置 AI 时由 [GenerateSpoilerRadarUseCase] 自动回退到本地规则版（generator=RULE）。
     */
    private val radar = MutableStateFlow<SpoilerRadarResult?>(null)

    /**
     * 该作品在待补池/吃灰区的归属（来自待补池流），驱动按钮形态与详情页归属徽章（F4），
     * 并供 [onToggleBacklog] 决定增删。
     */
    private val backlogMembership: StateFlow<BacklogMembership> =
        backlogRepository.observeBacklog()
            .map { items ->
                val item = items.firstOrNull { it.workId == workId }
                BacklogMembership(
                    inBacklog = item != null,
                    inDustMuseum = item?.inDustMuseum == true,
                )
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
                initialValue = BacklogMembership(),
            )

    /** 口味画像流；`null` 时决策区口味匹配度为「暂无数据」（不伪造，RC.10.03）。 */
    private val tasteProfile: StateFlow<TasteProfile?> =
        tasteProfileRepository.observeTasteProfile()
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
                initialValue = null,
            )

    /** 最近一次就绪的作品，供 [onToggleBacklog] 加入待补池时使用（addAll 需要 [Work]）。 */
    @Volatile
    private var currentWork: Work? = null

    /**
     * I12：是否已尝试「确保作品已加载」。初始为 false——在首次确保（必要时联网拉取并入库）完成前，
     * 把 [UiState.Empty] 视为加载中，避免观看路线等跳转时短暂闪现「暂无内容」。
     */
    private val workEnsured = MutableStateFlow(false)

    val uiState: StateFlow<UiState<DetailUiState>> =
        combine(
            workRepository.observeWork(workId),
            ratings,
            combine(backlogMembership, myCollection) { membership, collection -> membership to collection },
            tasteProfile,
            combine(radar, detailExtras, workEnsured) { r, e, ensured -> Triple(r, e, ensured) },
        ) { workState, ratingAggregate, personal, profile, radarExtrasEnsured ->
            workState.toDetailState(
                ratingAggregate,
                personal.first,
                personal.second,
                profile,
                radarExtrasEnsured.first,
                radarExtrasEnsured.second,
                radarExtrasEnsured.third,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = UiState.Loading,
        )

    init {
        ensureWorkLoaded()
        loadRatings()
    }

    /**
     * I12：尽早确保作品存在于本地（数字 id = Bangumi subjectId 时联网补全），完成后置 [workEnsured]。
     * 在此之前 UI 维持加载态，避免跳转瞬间闪现「暂无内容」。
     */
    private fun ensureWorkLoaded() {
        viewModelScope.launch {
            runCatching {
                if (workDao.getById(workId) == null) {
                    workId.toIntOrNull()?.let { sid ->
                        val fetched = bangumiDataSource.getSubject(sid)
                        if (fetched is AppResult.Success) {
                            val now = System.currentTimeMillis()
                            workDao.upsert(fetched.data.toEntity(createdAt = now, updatedAt = now))
                        }
                    }
                }
            }
            workEnsured.value = true
        }
    }

    /** 重试入口（错误态「重试」/ 用户下拉）：重新拉取评分与雷达；作品流由 Room 自动刷新。 */
    fun retry() {
        loadRatings()
    }

    /**
     * 加入 / 移出待补池（RC.07.04）。当前已在池中则按作品 id 移除（[BulkOp.DELETE]）；
     * 否则把当前作品批量加入（[BacklogRepository.addAll] 去重幂等）。作品尚未就绪时不操作。
     */
    fun onToggleBacklog() {
        val work = currentWork ?: return
        viewModelScope.launch {
            if (backlogMembership.value.inBacklog) {
                backlogRepository.bulk(BulkOp.DELETE, listOf(work.id))
            } else {
                backlogRepository.addAll(listOf(work))
                // H4：加入待补池默认置「想看」，写本地 user_collections 并回写 Bangumi（已配置时）。
                markWantToWatch(work)
            }
        }
    }

    /** H4：把作品标记为「想看」——本地 user_collections 先写，再回写 Bangumi（best-effort，不阻塞）。 */
    private suspend fun markWantToWatch(work: Work) {
        val now = System.currentTimeMillis()
        val existing = userCollectionDao.getByWork(work.id)
        // 已有更进一步的状态（在看/看过）时不回退为想看。
        if (existing?.status in listOf("在看", "看过", "搁置", "抛弃")) return
        userCollectionDao.upsert(
            UserCollectionEntity(
                id = existing?.id ?: "BANGUMI:${work.id}",
                source = "BANGUMI",
                sourceItemId = existing?.sourceItemId ?: work.id,
                localWorkId = work.id,
                status = "想看",
                rating = existing?.rating,
                progress = existing?.progress,
                comment = existing?.comment,
                tags = existing?.tags.orEmpty(),
                updatedAt = now,
                syncedAt = existing?.syncedAt ?: now,
                sourceUpdatedAt = existing?.sourceUpdatedAt,
            ),
        )
        val subjectId = resolveBangumiSubjectId(work) ?: return
        val configured = credentialStore.observeStatus().first()[CredentialSourceId.BANGUMI]?.configured == true
        if (!configured) return
        runCatching { bangumiDataSource.updateUserCollection(subjectId = subjectId, type = 1) }
    }

    private fun loadRatings() {
        if (workId.isBlank()) return
        viewModelScope.launch {
            // 评分拉取失败不影响作品展示：失败时退回空聚合（四平台均「暂无数据」、共识低置信）。
            ratings.value = when (val result = workRepository.aggregateRatings(workId)) {
                is AppResult.Success -> result.data
                is AppResult.Failure -> RatingAggregate()
            }
            // N4：评分聚合内部会做跨源交叉验证并落 Bangumi 源链接；待其完成后再加载雷达与角色/评论，
            // 这样非 Bangumi 主源但能匹配到 Bangumi 的作品也能取到真实短评与角色资料。
            loadRadar()
            loadExtras()
        }
    }

    /**
     * 生成无剧透评价雷达（RC.09.04/05/06）。等待作品就绪后以其标题 / 标签 / 个人短评为输入调用
     * [GenerateSpoilerRadarUseCase]：未配置 AI 时自动回退本地规则版（generator=RULE），保证雷达可展示；
     * 当 AI 已配置但需成本确认（[RadarOutcome.NeedsCostConfirmation]）时不擅自发起付费请求，
     * 决策区维持「暂无数据」直至用户在 AI 流程中确认（RC.14.05 / RC.00）。
     */
    private fun loadRadar() {
        if (workId.isBlank()) return
        viewModelScope.launch {
            val work = workRepository.observeWork(workId)
                .mapNotNull { state ->
                    when (state) {
                        is UiState.Success -> state.data
                        is UiState.PartialMissing -> state.data
                        else -> null
                    }
                }
                .first()
            // L10：社区评分信号（仅读本地缓存，不联网），让 AI 校准口碑判断。
            val communitySignal = runCatching {
                val agg = workRepository.aggregateRatingsCached(work.id)
                val scored = agg.perSource.entries.mapNotNull { (src, entry) ->
                    val s = entry?.score ?: return@mapNotNull null
                    if (s <= 0f) return@mapNotNull null
                    val ten = when (src) {
                        com.acgcompass.domain.model.SourceId.ANILIST,
                        com.acgcompass.domain.model.SourceId.VNDB -> s / 10f
                        else -> s
                    }.coerceIn(0f, 10f)
                    ten to (entry.voteCount.coerceAtLeast(0))
                }
                if (scored.isEmpty()) null
                else scored.map { it.first }.average() to scored.sumOf { it.second }
            }.getOrNull()
            // L5/M1：真实他人短评作为雷达 publicReviews 输入（AI/规则都更有料）。
            // N4：用解析出的真实 Bangumi subjectId（主源或链接），避免对非 Bangumi 作品取错条目短评。
            val publicComments = (
                resolveBangumiSubjectId(work)
                    ?.let { bangumiDataSource.getSubjectComments(it, limit = 20) as? AppResult.Success }
                    ?.data.orEmpty()
                )
                .map { it.text }
                .filter { it.isNotBlank() }
                .take(15)
            val request = RadarRequest(
                workId = work.id,
                title = work.titles.canonical,
                summary = work.summary.orEmpty(),
                mediaType = work.mediaType.name,
                tags = work.tags.map { it.name },
                userComments = listOfNotNull(myCollection.value?.shortReview?.takeIf { it.isNotBlank() }),
                publicReviews = publicComments,
                communityScore = communitySignal?.first,
                communityVotes = communitySignal?.second,
            )
            // K3：雷达走 AI 驱动——已配置 AI 时直接以「成本已确认」运行，产出真实无剧透分析；
            // 未配置 AI 时回退本地规则（占位 + 引导配置 AI）。决策助手/评论摘要均消费该结果。
            val aiConfigured =
                credentialStore.observeStatus().first()[CredentialSourceId.AI_PROVIDER]?.configured == true
            radar.value = when (
                val outcome = generateSpoilerRadar(request, AiRunOptions(confirmed = aiConfigured))
            ) {
                is RadarOutcome.Ready -> {
                    // L10：已配置 AI 却回退到规则时，把原因暴露给用户（便于诊断 AI 为何没跑成）。
                    if (aiConfigured && outcome.aiFallbackReason != null) {
                        _recordMessage.value =
                            "AI 雷达本次回退本地规则：" + outcome.aiFallbackReason.cause +
                                "（" + outcome.aiFallbackReason.nextStep + "）"
                    }
                    outcome.result
                }
                is RadarOutcome.NeedsCostConfirmation -> generateSpoilerRadar.local(request)
            }
        }
    }

    /**
     * 把作品的七态 [UiState] 映射为详情页七态。仅在作品**已就绪**（成功 / 字段缺失）时合入评分 / 待补 / 口味；
     * 其余状态（加载 / 空 / 错误 / 未授权 / 限流 / 无网络）原样透传，由 `StateScaffold` 统一渲染。
     */
    private fun UiState<Work>.toDetailState(
        ratingAggregate: RatingAggregate?,
        membership: BacklogMembership,
        collectionState: CollectionState?,
        profile: TasteProfile?,
        radarResult: SpoilerRadarResult?,
        extras: DetailExtras,
        workEnsured: Boolean,
    ): UiState<DetailUiState> = when (this) {
        is UiState.Success -> {
            currentWork = data
            UiState.Success(buildDetailUiState(data, ratingAggregate, membership.inBacklog, collectionState, tasteProfile = profile, radar = radarResult, inDustMuseum = membership.inDustMuseum, extras = extras))
        }
        // 作品字段缺失：仍渲染详情，缺失字段在 UI 内显示「暂无数据」（RC.07.03）。
        is UiState.PartialMissing -> {
            currentWork = data
            UiState.PartialMissing(buildDetailUiState(data, ratingAggregate, membership.inBacklog, collectionState, tasteProfile = profile, radar = radarResult, inDustMuseum = membership.inDustMuseum, extras = extras))
        }
        is UiState.Loading -> UiState.Loading
        // I12：作品尚未确保加载完成（可能正在联网补全）时，把空态视为加载中，避免跳转闪现「暂无内容」。
        is UiState.Empty -> if (!workEnsured) UiState.Loading else this
        is UiState.Error -> this
        is UiState.Unauthorized -> UiState.Unauthorized
        is UiState.RateLimited -> UiState.RateLimited
        is UiState.NoNetwork -> UiState.NoNetwork
    }

    /**
     * F7：拉取角色·Staff / 关联作品 / 观看路线。仅对 Bangumi 来源作品（work id == subjectId，数字）发起；
     * 任一失败被吞掉（韧性，对应 Tab 显示「暂无数据」，绝不伪造，RC.17.4）。
     */
    private fun loadExtras() {
        if (workId.isBlank()) return
        viewModelScope.launch {
            val work = workRepository.observeWork(workId)
                .mapNotNull { state ->
                    when (state) {
                        is UiState.Success -> state.data
                        is UiState.PartialMissing -> state.data
                        else -> null
                    }
                }
                .first()
            // N4：解析真实 Bangumi subjectId（主源或交叉验证链接）——让有 Bangumi 词条的非主源作品
            // 也能拉角色/Staff/关联与真实短评；无 Bangumi 词条则跳过（绝不乱用裸数字 id）。
            val subjectId = resolveBangumiSubjectId(work) ?: return@launch

            val characters = (bangumiDataSource.getSubjectCharacters(subjectId) as? AppResult.Success)?.data.orEmpty()
            val persons = (bangumiDataSource.getSubjectPersons(subjectId) as? AppResult.Success)?.data.orEmpty()
            val relations = (bangumiDataSource.getSubjectRelations(subjectId) as? AppResult.Success)?.data.orEmpty()

            val charLines = characters.take(12).mapNotNull { c ->
                val name = c.name.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val role = c.relation?.takeIf { it.isNotBlank() }
                val cv = c.actors.firstOrNull()?.name?.takeIf { it.isNotBlank() }
                buildString {
                    append("· ")
                    append(name)
                    if (role != null) append("（$role）")
                    if (cv != null) append(" · CV：$cv")
                }
            }
            val staffLines = persons.take(12).mapNotNull { p ->
                val name = p.name.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val job = p.relation?.takeIf { it.isNotBlank() }
                    ?: p.career.firstOrNull()?.takeIf { it.isNotBlank() }
                "· $name" + (if (job != null) "（$job）" else "")
            }
            val charactersStaffBody = buildString {
                if (charLines.isNotEmpty()) {
                    append("角色\n").append(charLines.joinToString("\n"))
                }
                if (staffLines.isNotEmpty()) {
                    if (isNotEmpty()) append("\n\n")
                    append("Staff\n").append(staffLines.joinToString("\n"))
                }
            }.ifBlank { "暂无数据" }

            val relLines = relations.take(20).mapNotNull { r ->
                val title = r.nameCn.takeIf { it.isNotBlank() } ?: r.name.takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                val rel = r.relation?.takeIf { it.isNotBlank() }
                "· " + (if (rel != null) "[$rel] " else "") + title
            }
            val relationsBody = relLines.joinToString("\n").ifBlank { "暂无数据" }

            // 观看路线：基于关联作品的关系推断；不确定不编造，显示「路线待确认」。
            val watchRouteBody = if (relations.isEmpty()) {
                "暂无数据"
            } else {
                val prequels = relations.filter { it.relation?.contains("前") == true }
                val sequels = relations.filter { it.relation?.contains("续") == true }
                if (prequels.isEmpty() && sequels.isEmpty()) {
                    "路线待确认（已有关联作品，但前传/续作关系不明确，建议参考「关联作品」自行判断）"
                } else {
                    buildString {
                        append("根据关联作品推断的大致顺序（仅供参考）：\n")
                        prequels.forEach { append("· 前作：").append(it.nameCn.ifBlank { it.name }).append("\n") }
                        append("· 本作\n")
                        sequels.forEach { append("· 续作：").append(it.nameCn.ifBlank { it.name }).append("\n") }
                    }.trim()
                }
            }

            // H：结构化观看路线——本地按 relation 文本分主线/可选/可跳过，不编造作品。
            val routeEntries = buildRouteEntries(work, relations)
            seriesSubjectIds = routeEntries.mapNotNull { it.workId.toIntOrNull() }

            // M1（L5）：真实他人短评（next.bgm.tv/p1 公共接口）→ 评论摘要展示行。
            val commentLines = (bangumiDataSource.getSubjectComments(subjectId, limit = 20) as? AppResult.Success)
                ?.data.orEmpty()
                .take(8)
                .map { c ->
                    val rate = if (c.rate > 0) " · ${c.rate}分" else ""
                    "${c.nickname}$rate：${c.text.replace("\n", " ").take(80)}"
                }

            // P2-4：Bangumi 社区标签——主源即 Bangumi 时直接用已入库的 work.tags（社区标签，H13）；
            // 非 Bangumi 主源（如 Jikan，work.tags 为空）则用解析到的 subjectId 拉取 Bangumi 标签补充。
            val bangumiTags = work.tags.map { it.name }.filter { it.isNotBlank() }.ifEmpty {
                (bangumiDataSource.getSubject(subjectId) as? AppResult.Success)?.data
                    ?.tags?.map { it.name }?.filter { it.isNotBlank() }.orEmpty()
            }.take(15)

            detailExtras.value = DetailExtras(
                charactersStaffBody = charactersStaffBody,
                relationsBody = relationsBody,
                watchRouteBody = watchRouteBody,
                routeEntries = routeEntries,
                comments = commentLines,
                tags = bangumiTags,
            )
        }
    }

    /** H：把「主线必看」（MAIN）加入待补池。best-effort 拉取各关联条目为 Work 后加入。 */
    fun onAddMainlineToBacklog() = addRouteToBacklog(mainOnly = true)

    /** H：把「全系列」（主线+可选+可跳过）加入待补池。 */
    fun onAddAllSeriesToBacklog() = addRouteToBacklog(mainOnly = false)

    private fun addRouteToBacklog(mainOnly: Boolean) {
        viewModelScope.launch {
            val entries = detailExtras.value.routeEntries
            val targetIds = entries
                .filter { if (mainOnly) it.section == RouteSection.MAIN else true }
                .mapNotNull { it.workId.toIntOrNull() }
                .distinct()
            val works = mutableListOf<Work>()
            currentWork?.let { works += it }
            for (id in targetIds) {
                val r = bangumiDataSource.getSubject(id)
                if (r is AppResult.Success) works += r.data
            }
            if (works.isNotEmpty()) {
                runCatching { backlogRepository.addAll(works.distinctBy { it.id }) }
            }
        }
    }

    /**
     * E：AI 分析匹配度（RC.10.03 / RC.14）。点击「AI 分析匹配度」按钮触发：
     * 1. 未配置 AI → [AiMatchUi.NotConfigured]（提示可用本地模型估计），不发请求。
     * 2. 已配置 → [AiMatchUi.Loading] → 组装作品信息 + 口味画像高低分标签为输入，
     *    经 [AiEngine] 运行 [AiTask.TasteMatch]（成本已确认）。
     * 3. 成功 → [AiMatchUi.Result]（结构化 matchScore / likedReasons / riskReasons / confidence，
     *    AiEngine 自动写入 AI_RESULT 缓存）。
     * 4. 低置信 / 失败 / 未配置 → [AiMatchUi.Error]，提示已回退本地模型，页面不无响应（RC.17.4）。
     */
    fun onAnalyzeMatchWithAi() {
        val work = currentWork ?: return
        viewModelScope.launch {
            // AI 配置检查（不读取明文，仅看元数据 configured，RC.00）。
            val configured = credentialStore.observeStatus().first()[CredentialSourceId.AI_PROVIDER]?.configured == true
            if (!configured) {
                _aiMatch.value = AiMatchUi.NotConfigured("未配置 AI，可使用本地模型估计（见上方「口味匹配度」）")
                return@launch
            }
            _aiMatch.value = AiMatchUi.Loading

            val profile = tasteProfile.value
            val highTags = profile?.tagStats
                ?.filter { it.bucket == TagBucket.HIGH_SCORE }
                ?.map { it.tagName }
                ?.take(20)
                .orEmpty()
            val lowTags = profile?.tagStats
                ?.filter { it.bucket == TagBucket.LOW_SCORE }
                ?.map { it.tagName }
                ?.take(20)
                .orEmpty()
            val community = ratings.value
            // G10 / I10：纳入近期评分作品 + 历史最高/最低分锚点，比仅用标签更具参考价值。
            val allRated = runCatching {
                userCollectionDao.getAll().filter { it.rating != null }
            }.getOrDefault(emptyList())
            val recentRated = allRated.sortedByDescending { it.updatedAt }.take(20)
            // I10：口味锚点——历史最高分（最喜欢）与最低分（最不喜欢），各取前若干。
            val topRated = allRated.sortedByDescending { it.rating }.take(5)
            val lowRated = allRated.filter { (it.rating ?: 10) <= 6 }.sortedBy { it.rating }.take(5)
            val lineOf: suspend (com.acgcompass.data.local.entity.UserCollectionEntity) -> String? = { c ->
                workDao.getById(c.localWorkId)?.canonicalTitle?.takeIf { it.isNotBlank() }?.let { title ->
                    buildString {
                        append(title).append(" · ").append(c.rating).append("分")
                        c.comment?.takeIf { it.isNotBlank() }?.let { append("（短评：").append(it.take(40)).append("）") }
                    }
                }
            }
            val ratedLines = recentRated.mapNotNull { lineOf(it) }
            val topLines = topRated.mapNotNull { lineOf(it) }
            val lowLines = lowRated.mapNotNull { lineOf(it) }
            val content = buildString {
                append("作品：").append(work.titles.canonical).append('\n')
                work.titles.ja?.let { append("原名：").append(it).append('\n') }
                append("类型：").append(work.mediaType.name).append('\n')
                work.year?.let { append("年份：").append(it).append('\n') }
                val workTags = work.tags.map { it.name }
                if (workTags.isNotEmpty()) append("作品标签：").append(workTags.joinToString("、")).append('\n')
                work.summary?.takeIf { it.isNotBlank() }?.let {
                    append("简介：").append(it.take(400)).append('\n')
                }
                if (topLines.isNotEmpty()) {
                    append("我历史评分最高（最喜欢）的作品：\n").append(topLines.joinToString("\n")).append('\n')
                }
                if (lowLines.isNotEmpty()) {
                    append("我历史评分最低（最不喜欢）的作品：\n").append(lowLines.joinToString("\n")).append('\n')
                }
                if (ratedLines.isNotEmpty()) {
                    append("我近期评分过的作品（标题·评分·短评，请据此判断我的口味）：\n")
                    append(ratedLines.joinToString("\n")).append('\n')
                }
                if (highTags.isNotEmpty()) append("我的高分作品常见标签：").append(highTags.joinToString("、")).append('\n')
                if (lowTags.isNotEmpty()) append("我的低分作品常见标签：").append(lowTags.joinToString("、")).append('\n')
                profile?.avgScore?.takeIf { it > 0f }?.let { append("我的平均分：").append("%.1f".format(it)).append('\n') }
            }
            val dataSources = buildList {
                add("作品资料")
                if (recentRated.isNotEmpty()) add("近期评分作品")
                if (profile != null) add("口味画像")
                if (community != null) add("社区评分")
            }

            val result = runCatching {
                aiEngine.run(
                    AiTask.TasteMatch(workId = work.id, content = content, dataSources = dataSources),
                    AiRunOptions(confirmed = true),
                )
            }.getOrElse { AiRunResult.Failure(com.acgcompass.core.common.AppError.Server()) }

            _aiMatch.value = when (result) {
                is AiRunResult.Success -> {
                    val out = result.payload
                    val fraction = (out.matchScore.coerceIn(0, 100)) / 100f
                    AiMatchUi.Result(
                        matchPercentText = "${out.matchScore.coerceIn(0, 100)}%",
                        fraction = fraction,
                        likedReasons = out.likedReasons,
                        riskReasons = out.riskReasons,
                        confidenceText = "置信度 ${(out.confidence.coerceIn(0f, 1f) * 100).toInt()}%",
                        dataSources = result.result.dataSources.ifEmpty { dataSources },
                    )
                }
                is AiRunResult.LowConfidence ->
                    AiMatchUi.Error("AI 这次未能给出可靠结构化结果，已为你保留上方的本地口味匹配度（基于你的长期口味标签）")
                is AiRunResult.NotConfigured ->
                    AiMatchUi.NotConfigured("未配置 AI，可使用本地模型估计（见上方「口味匹配度」）")
                is AiRunResult.NeedsConfirmation ->
                    AiMatchUi.Error("AI 分析需确认成本，已回退本地模型估计")
                is AiRunResult.Failure ->
                    AiMatchUi.Error("AI 分析失败（${result.error.nextStep ?: "请稍后重试"}），已回退本地模型估计")
            }
        }
    }

    /**
     * G8/G9/G13：编辑「我的记录」并回写 Bangumi（状态 / 评分 / 进度 / 短评）。
     * 流程：本地先写 user_collections（即时反映 UI）→ 调 Bangumi `POST /v0/users/-/collections/{id}`
     * 回传；未配置 Bangumi 或非 Bangumi 条目则只本地保存并提示。失败不回滚本地，提示同步失败原因。
     */
    fun onUpdateMyRecord(
        statusLabel: String?,
        rating: Int?,
        progress: Int?,
        comment: String?,
        tags: List<String>? = null,
    ) {
        // H4：进度不得超过总集数；并据进度自动推导状态（用户未显式选状态时）。
        val totalEps = currentWork?.units?.episodes?.takeIf { it > 0 }
        val clampedProgress = progress?.let { p ->
            if (totalEps != null) p.coerceIn(0, totalEps) else p.coerceAtLeast(0)
        }
        val effectiveStatus = statusLabel ?: when {
            clampedProgress == null || clampedProgress <= 0 -> null
            totalEps != null && clampedProgress >= totalEps -> "看过"
            else -> "在看"
        }
        viewModelScope.launch {
            // I6：本地先写——采用「编辑对话框即完整意图」的覆盖语义：未选状态/留空即清空该字段
            //（不再回退既有值），从而支持清空状态/评分/进度/短评。
            val now = System.currentTimeMillis()
            val existing = userCollectionDao.getByWork(workId)
            // N1：解析真实 Bangumi subjectId（非 Bangumi 作品的裸数字 id 绝不当 subjectId）。
            val subjectId = currentWork?.let { resolveBangumiSubjectId(it) }
            val configured = credentialStore.observeStatus().first()[CredentialSourceId.BANGUMI]?.configured == true
            // K6：全字段为空（无状态/评分/进度/短评/标签）→ 删除该收藏行，避免残留一条「暂无数据」空记录
            //（此前会写入空行，污染时光机基线 / 我的收藏）。
            val fullyCleared = effectiveStatus == null && rating == null && clampedProgress == null &&
                comment.isNullOrBlank() && tags.isNullOrEmpty()
            if (fullyCleared) {
                if (existing != null) userCollectionDao.deleteByWork(workId)
                // N2：清空时同步清除 Bangumi 端评分 / 短评 / 标签（rate=0、空），避免下次同步又拉回。
                // 注：Bangumi v0 无「删除收藏状态」端点，收藏状态本身需在 Bangumi 网页移除。
                if (subjectId != null && configured) {
                    val r = runCatching {
                        bangumiDataSource.updateUserCollection(
                            subjectId = subjectId,
                            rate = 0,
                            comment = "",
                            tags = emptyList(),
                        )
                    }
                    _recordMessage.value = if (r.isSuccess) {
                        "已清空本地，并清除 Bangumi 评分/短评（收藏状态如需移除请在 Bangumi 网页操作）"
                    } else {
                        "已清空本地；清除 Bangumi 端失败，请稍后重试"
                    }
                } else {
                    _recordMessage.value = "已清空本地记录"
                }
                return@launch
            }
            userCollectionDao.upsert(
                UserCollectionEntity(
                    id = existing?.id ?: "BANGUMI:$workId",
                    source = "BANGUMI",
                    sourceItemId = existing?.sourceItemId ?: workId,
                    localWorkId = workId,
                    status = effectiveStatus,
                    rating = rating,
                    progress = clampedProgress,
                    comment = comment,
                    tags = tags ?: emptyList(),
                    updatedAt = now,
                    syncedAt = existing?.syncedAt ?: now,
                    sourceUpdatedAt = existing?.sourceUpdatedAt,
                ),
            )

            if (subjectId == null) {
                _recordMessage.value = "已本地保存（该作品在 Bangumi 无对应词条，仅本地，不同步）"
                return@launch
            }
            if (!configured) {
                _recordMessage.value = "已本地保存；未配置 Bangumi Token，未同步到云端"
                return@launch
            }
            // M6：写回前确认条目在 Bangumi 存在，避免对无词条作品反复 PATCH/POST 报错。
            if (!bangumiDataSource.subjectExists(subjectId)) {
                _recordMessage.value = "已本地保存；该作品在 Bangumi 无词条，无法同步到云端"
                return@launch
            }
            val result = bangumiDataSource.updateUserCollection(
                subjectId = subjectId,
                type = effectiveStatus?.let { bangumiTypeOf(it) },
                // I6：覆盖语义——清空时显式提交 0 / "" 以在 Bangumi 端清除评分 / 短评。
                rate = rating ?: 0,
                comment = comment ?: "",
                tags = tags ?: emptyList(),
            )
            // M5：动画进度通过专用章节端点上传（书籍 / 漫画不上传进度，仅本地）。
            val isAnime = currentWork?.mediaType == com.acgcompass.domain.model.MediaType.ANIME
            val progressResult: AppResult<Unit>? =
                if (isAnime && clampedProgress != null && clampedProgress > 0) {
                    bangumiDataSource.markEpisodesWatched(subjectId, clampedProgress)
                } else {
                    null
                }
            _recordMessage.value = when (result) {
                is AppResult.Success -> {
                    userCollectionDao.upsert(
                        (userCollectionDao.getByWork(workId) ?: existing)?.copy(syncedAt = System.currentTimeMillis())
                            ?: return@launch,
                    )
                    val progressNote = when (progressResult) {
                        is AppResult.Failure -> "（进度同步失败：${progressResult.error.cause}）"
                        else -> ""
                    }
                    "已保存并同步到 Bangumi$progressNote"
                }
                is AppResult.Failure -> "本地已保存，同步 Bangumi 失败：${result.error.cause}（${result.error.nextStep}）"
            }
        }
    }

    /** 状态标签 → Bangumi 收藏 type（想看1/看过2/在看3/搁置4/抛弃5）。未知返回 null。 */
    private fun bangumiTypeOf(label: String): Int? = when (label) {
        "想看" -> 1
        "看过" -> 2
        "在看" -> 3
        "搁置" -> 4
        "抛弃" -> 5
        else -> null
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}

/**
 * 该作品在待补池中的归属信号（F4）。
 *
 * @property inBacklog 是否已在待补池。
 * @property inDustMuseum 是否落入吃灰区（待补池中长期未动的分区）。
 */
data class BacklogMembership(
    val inBacklog: Boolean = false,
    val inDustMuseum: Boolean = false,
)

/**
 * H：本地规则把 Bangumi 关联作品分为 主线/可选/可跳过（不调用 AI、不编造作品）。
 * - 主线 MAIN：前传 / 续集 / 正篇（含「本作」）。
 * - 可跳过 SKIPPABLE：总集篇 / PV / 角色歌 / 预告。
 * - 可选 OPTIONAL：其余（番外 / 外传 / 剧场版 / OVA / 其他）。
 * 排序：主线（前传→本作→续集）→ 可选 → 可跳过。
 */
private fun buildRouteEntries(
    current: Work,
    relations: List<com.acgcompass.data.remote.bangumi.BangumiSubjectRelationDto>,
): List<RouteEntryUi> {
    if (relations.isEmpty()) return emptyList()
    fun sectionOf(rel: String?): RouteSection {
        val r = rel ?: ""
        return when {
            r.contains("总集") || r.contains("PV") || r.contains("预告") || r.contains("角色歌") -> RouteSection.SKIPPABLE
            r.contains("前") || r.contains("续") || r.contains("正篇") || r.contains("主线") -> RouteSection.MAIN
            else -> RouteSection.OPTIONAL
        }
    }
    fun mainOrder(rel: String?): Int = when {
        rel?.contains("前") == true -> 0
        rel?.contains("续") == true -> 2
        else -> 1
    }
    val others = relations.mapNotNull { rel ->
        val title = rel.nameCn.ifBlank { rel.name }.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        // G14：非动画（游戏=4 / 书籍画集=1 / 音乐=3 / 三次元=6 等）归为衍生作/原作，不进可选/可跳过。
        val section = if (rel.type != null && rel.type != 2) RouteSection.DERIVED else sectionOf(rel.relation)
        RouteEntryUi(
            workId = rel.id.toString(),
            title = title,
            relationLabel = rel.relation?.takeIf { it.isNotBlank() } ?: "关联",
            section = section,
        )
    }
    // 当前作品作为主线「本作」。
    val self = RouteEntryUi(workId = current.id, title = current.titles.canonical, relationLabel = "本作", section = RouteSection.MAIN)
    val main = (others.filter { it.section == RouteSection.MAIN } + self)
        .sortedBy { if (it.relationLabel == "本作") 1 else mainOrder(it.relationLabel) }
    val optional = others.filter { it.section == RouteSection.OPTIONAL }
    val skippable = others.filter { it.section == RouteSection.SKIPPABLE }
    val derived = others.filter { it.section == RouteSection.DERIVED }
    return main + optional + skippable + derived
}
