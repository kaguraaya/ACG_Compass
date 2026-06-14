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
        // F9：口味画像标签来源——合并「用户自定义标签」与「作品社区标签（Bangumi subject tags，按标注人数取前若干）」，
        // 并清洗（下划线/连字符转空格、折叠空白、去过短）。使高/低分倾向标签来自作品标签而非仅自定义标签。
        val userTags = dto.tags
        val subjectTags = dto.subject?.tags.orEmpty()
            .sortedByDescending { it.count }
            .take(12)
            .map { it.name }
        val mergedTags = (userTags + subjectTags)
            .map { cleanTag(it) }
            .filter { it.length >= 2 }
            .distinct()
        return UserCollectionEntity(
            id = "BANGUMI:$subjectId",
            source = CredentialSourceId.BANGUMI.name,
            sourceItemId = workId,
            localWorkId = workId,
            status = mapStatus(dto.type),
            rating = dto.rate.takeIf { it in 1..10 },
            progress = dto.epStatus.takeIf { it > 0 },
            comment = dto.comment?.takeIf { it.isNotBlank() },
            tags = mergedTags,
            updatedAt = now,
            syncedAt = now,
            sourceUpdatedAt = dto.updatedAt,
        )
    }

    /** 标签清洗（F9）：下划线/连字符转空格、折叠多余空白、trim。 */
    private fun cleanTag(raw: String): String =
        raw.replace('_', ' ').replace('-', ' ').trim().replace(Regex("\\s+"), " ")

    private companion object {
        const val PAGE_SIZE = 50
        const val MAX_PAGES = 40

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
