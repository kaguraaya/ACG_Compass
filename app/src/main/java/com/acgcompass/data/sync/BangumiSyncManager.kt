package com.acgcompass.data.sync

import com.acgcompass.core.common.AppError
import com.acgcompass.core.common.AppResult
import com.acgcompass.core.common.DispatcherProvider
import com.acgcompass.data.credential.CredentialStore
import com.acgcompass.data.credential.SourceId as CredentialSourceId
import com.acgcompass.data.datastore.SettingsDataStore
import com.acgcompass.data.local.dao.UserCollectionDao
import com.acgcompass.data.local.dao.WorkDao
import com.acgcompass.data.local.entity.UserCollectionEntity
import com.acgcompass.data.local.mapper.toEntity
import com.acgcompass.data.remote.bangumi.BangumiRemoteDataSource
import com.acgcompass.data.remote.bangumi.BangumiUserSubjectCollectionDto
import com.acgcompass.data.remote.bangumi.toWork
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** 一次 Bangumi 同步的结果统计（R45）。 */
data class SyncReport(
    val added: Int = 0,
    val updated: Int = 0,
    val skipped: Int = 0,
    val failed: Int = 0,
    val total: Int = 0,
) {
    fun summary(username: String): String =
        "已从 Bangumi @$username 同步：新增 $added · 更新 $updated · 跳过 $skipped · 失败 $failed（共 $total）"
}

/**
 * Bangumi 用户数据同步（R45）。分页拉取当前用户收藏，映射并写入本地 Room（[WorkDao] +
 * [UserCollectionDao]），供我的页统计 / 详情页我的记录 / 口味画像 / 时光机消费。
 *
 * 凭据门控：未配置 Token 时返回 [AppResult.Failure]（Unauthorized），不发请求。
 * 错误处理：getMe / 分页失败均映射为带可读原因的 [AppError]（401/403/网络/超时/解析）。
 * 写入为 best-effort 幂等：以 `BANGUMI:<subjectId>` 为主键 upsert，重复同步只更新不重复。
 */
@Singleton
class BangumiSyncManager @Inject constructor(
    private val bangumi: BangumiRemoteDataSource,
    private val credentialStore: CredentialStore,
    private val userCollectionDao: UserCollectionDao,
    private val workDao: WorkDao,
    private val backlogRepository: com.acgcompass.domain.repository.BacklogRepository,
    private val settingsDataStore: SettingsDataStore,
    private val syncStatusRepository: SyncStatusRepository,
    private val dispatchers: DispatcherProvider,
) {

    /** 是否已配置 Bangumi 凭据（个人同步前置条件）。 */
    suspend fun isConfigured(): Boolean =
        credentialStore.observeStatus().first()[CredentialSourceId.BANGUMI]?.configured == true

    /**
     * 同步当前 Bangumi 用户的全部收藏到本地。
     * @return 成功时返回 [SyncReport]；失败时返回带可读原因的 [AppResult.Failure]。
     *
     * R100：无论成功或失败，结果都会写入 [SyncStatusRepository]，使各页（首页/我的/时光机/口味）
     * 读取到同一份同步状态，且重启后仍可见。
     */
    suspend fun syncCollections(): AppResult<SyncReport> = withContext(dispatchers.io) {
        val result = runSyncInternal()
        when (result) {
            is AppResult.Success -> syncStatusRepository.recordSuccess(result.data)
            is AppResult.Failure -> syncStatusRepository.recordFailure(result.error.cause)
        }
        result
    }

    private suspend fun runSyncInternal(): AppResult<SyncReport> {
        if (!isConfigured()) {
            return AppResult.Failure(
                AppError.Unauthorized(cause = "未配置 Bangumi 账号，无法同步个人数据"),
            )
        }
        // R56：个人同步会携带 Token；非官方 API 且未确认风险时拒绝，提示去设置确认。
        val settings = settingsDataStore.settings.first()
        if (!settings.isBangumiOfficialApi && !settings.bangumiNonOfficialTokenConsent) {
            return AppResult.Failure(
                AppError.Unauthorized(
                    cause = "当前为非官方 Bangumi API；个人同步需先在「设置 → Bangumi」确认 Token 隐私风险",
                ),
            )
        }
        val username = when (val me = bangumi.getMe()) {
            is AppResult.Failure -> return AppResult.Failure(me.error)
            is AppResult.Success -> me.data.username.ifBlank { me.data.nickname }
        }
        if (username.isBlank()) {
            return AppResult.Failure(AppError.Unauthorized(cause = "无法获取 Bangumi 用户名"))
        }

        val now = System.currentTimeMillis()
        var added = 0
        var updated = 0
        var skipped = 0
        var failed = 0
        var total = 0
        var offset = 0
        var pages = 0
        val batch = mutableListOf<UserCollectionEntity>()
        // H4 反向同步：Bangumi「想看/在看」收藏 → 待补池（看过/搁置/抛弃不进）。
        val backlogWorks = mutableListOf<com.acgcompass.domain.model.Work>()

        while (pages < MAX_PAGES) {
            when (val page = bangumi.getUserCollections(username = username, limit = PAGE_SIZE, offset = offset)) {
                is AppResult.Failure -> {
                    // 首页就失败 → 整体失败；后续页失败 → 计入 failed 并停止。
                    if (offset == 0) return AppResult.Failure(page.error)
                    failed += 1
                    break
                }
                is AppResult.Success -> {
                    total = page.data.total
                    val data = page.data.data
                    if (data.isEmpty()) break
                    for (dto in data) {
                        val entity = mapCollection(dto, now)
                        if (entity == null) {
                            skipped += 1
                            continue
                        }
                        // 写入对应 Work（详情页/统计可读）；保留既有 createdAt。
                        dto.subject?.toWork()?.let { work ->
                            val existingWork = workDao.getById(work.id)
                            workDao.upsert(work.toEntity(createdAt = existingWork?.createdAt ?: now, updatedAt = now))
                            if (entity.status == "想看" || entity.status == "在看") backlogWorks += work
                        }
                        val existing = userCollectionDao.getByWork(entity.localWorkId)
                        if (existing == null) added += 1 else updated += 1
                        batch += entity
                    }
                    offset += data.size
                    pages += 1
                    if (offset >= total) break
                }
            }
        }

        if (batch.isNotEmpty()) userCollectionDao.upsertAll(batch)
        // H4：把想看/在看作品并入待补池（addAll 去重幂等）；失败不影响同步结果。
        if (backlogWorks.isNotEmpty()) {
            runCatching { backlogRepository.addAll(backlogWorks.distinctBy { it.id }) }
        }
        return AppResult.Success(SyncReport(added = added, updated = updated, skipped = skipped, failed = failed, total = total))
    }

    /** Bangumi 收藏 DTO → 本地用户收藏实体；无法映射（无 subject）时返回 null。 */
    private fun mapCollection(dto: BangumiUserSubjectCollectionDto, now: Long): UserCollectionEntity? {
        val subjectId = dto.subjectId.takeIf { it > 0 } ?: return null
        val workId = subjectId.toString()
        // P0-2：口味画像只用「作品自身的 Bangumi 社区标签」（按标注人数降序），不再混入用户自定义
        // 标签（dto.tags）；并过滤年份/季度/媒介格式/改编来源等非内容噪声（如「2024年10月」「TV」「漫画改」），
        // 使高/低分倾向标签是真正的题材/情绪信号，而非每部都有的元数据（用户诉求：用作品本身 tag）。
        val contentTags = dto.subject?.tags.orEmpty()
            .sortedByDescending { it.count }
            .map { cleanTag(it.name) }
            .filter { it.length >= 2 && !isNoiseTag(it) }
            .distinct()
            .take(12)
        return UserCollectionEntity(
            id = "BANGUMI:$subjectId",
            source = CredentialSourceId.BANGUMI.name,
            sourceItemId = workId,
            localWorkId = workId,
            status = mapStatus(dto.type),
            rating = dto.rate.takeIf { it in 1..10 },
            progress = dto.epStatus.takeIf { it > 0 },
            comment = dto.comment?.takeIf { it.isNotBlank() },
            tags = contentTags,
            updatedAt = now,
            syncedAt = now,
            sourceUpdatedAt = dto.updatedAt,
        )
    }

    /** 标签清洗（F9）：下划线/连字符转空格、折叠多余空白、trim。 */
    private fun cleanTag(raw: String): String =
        raw.replace('_', ' ').replace('-', ' ').trim().replace(Regex("\\s+"), " ")

    /**
     * P0-2：是否为「非内容」噪声标签——年份/季度（如 2024、2024年10月）、媒介格式（TV/OVA/剧场版）、
     * 改编来源（漫画改/轻小说改/原创）、地区（日本）等。这些是元数据而非题材/情绪信号，
     * 计入口味画像会让「每部都有的标签」霸榜，污染高/低分倾向，故剔除。
     */
    private fun isNoiseTag(tag: String): Boolean {
        val t = tag.trim()
        if (t.isEmpty()) return true
        if (NOISE_DATE_REGEX.matches(t)) return true
        return t.lowercase() in NOISE_TAGS
    }

    private companion object {
        const val PAGE_SIZE = 50
        const val MAX_PAGES = 40

        /** P0-2：年份/季度型噪声标签正则（如 2024、2024年、2024年10月、2024-10、10月）。 */
        val NOISE_DATE_REGEX = Regex(
            "^(\\d{4}|\\d{4}\u5e74|\\d{4}\u5e74\\d{1,2}\u6708|\\d{4}[-./]\\d{1,2}|\\d{1,2}\u6708)$",
        )

        /** P0-2：媒介格式/改编来源/地区等非内容噪声标签（小写比较）。 */
        val NOISE_TAGS: Set<String> = setOf(
            "tv", "ova", "oad", "ona", "web", "sp", "pv", "cm", "mv", "op", "ed",
            "\u5267场版", "\u5267场", "\u7535影", "\u77ed片", "\u7279别篇", "\u52a8画", "\u65e5本\u52a8画", "tv\u52a8画", "\u52a8画\u5316",
            "\u6f2b画改", "\u8f7b小说改", "\u5c0f说改", "\u6e38戏改", "gal\u6539", "galgame\u6539", "eroge\u6539", "\u539f创", "\u6f2b改",
            "\u6539编", "18\u7981\u6e38戏\u6539", "\u624b游改", "\u65e5本", "\u56fd产", "\u4e2d国", "\u7f8e国",
        )

        /** Bangumi 收藏 type：1 想看 / 2 看过 / 3 在看 / 4 搁置 / 5 抛弃。 */
        fun mapStatus(type: Int?): String? = when (type) {
            1 -> "想看"
            2 -> "看过"
            3 -> "在看"
            4 -> "搁置"
            5 -> "抛弃"
            else -> null
        }
    }
}
