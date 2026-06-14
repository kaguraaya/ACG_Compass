package com.acgcompass.data.repository

import com.acgcompass.BuildConfig
import com.acgcompass.core.common.AppResult
import com.acgcompass.core.common.DispatcherProvider
import com.acgcompass.core.common.runCatchingApp
import com.acgcompass.data.backup.BackupEnvelope
import com.acgcompass.data.backup.BackupMerger
import com.acgcompass.data.backup.BackupSerializer
import com.acgcompass.data.backup.CsvExporter
import com.acgcompass.data.backup.toBackup
import com.acgcompass.data.backup.toEntity
import com.acgcompass.data.credential.CredentialStore
import com.acgcompass.data.datastore.SettingsDataStore
import com.acgcompass.data.local.dao.BacklogDao
import com.acgcompass.data.local.dao.ImportDao
import com.acgcompass.data.local.dao.RatingDao
import com.acgcompass.data.local.dao.SnapshotDao
import com.acgcompass.data.local.dao.SourceLinkDao
import com.acgcompass.data.local.dao.TagDao
import com.acgcompass.data.local.dao.TasteDao
import com.acgcompass.data.local.dao.WorkDao
import com.acgcompass.domain.repository.BackupRepository
import com.acgcompass.domain.repository.CsvKind
import com.acgcompass.domain.repository.ImportConflict
import com.acgcompass.domain.repository.ImportReport
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [BackupRepository] 实现（task 30.1 / RC.16.01/03 / RC.00 1.5）。
 *
 * **默认零凭据（CRITICAL）**：[exportBackup] 缺省 `includeCredentials=false`，输出信封
 * `includesCredentials=false`、`credentials=null`，绝不含任何 key/token/secret 明文
 * （RC.16.01 / RC.00 1.2）。仅当显式传 `true`（更高层二次确认后）才附带**脱敏**凭据，经
 * [CredentialStore.exportRedacted] 掩码处理（RC.16.02 / RC.00 1.6）。
 *
 * **导入合并不覆盖**：[importBackup] 反序列化后交由纯函数 [BackupMerger.merge] 与当前本地数据按
 * 业务主键合并——本地不存在则新增，冲突默认保留较新 `updatedAt`，**绝不**覆盖更新的本地数据
 * （RC.16.03 / 需求 18.3），合并结果再整表 upsert 落库。设置与凭据不被导入覆盖。
 *
 * 读写均经 DAO（works/backlog/ratings/tags/import/snapshot/taste 等）；凭据明文全程不进入本类的
 * 序列化路径。
 */
@Singleton
class BackupRepositoryImpl @Inject constructor(
    private val workDao: WorkDao,
    private val sourceLinkDao: SourceLinkDao,
    private val ratingDao: RatingDao,
    private val backlogDao: BacklogDao,
    private val tagDao: TagDao,
    private val importDao: ImportDao,
    private val snapshotDao: SnapshotDao,
    private val tasteDao: TasteDao,
    private val settingsDataStore: SettingsDataStore,
    private val credentialStore: CredentialStore,
    private val dispatchers: DispatcherProvider,
) : BackupRepository {

    // --- exportBackup (RC.16.01) -------------------------------------------

    override suspend fun exportBackup(includeCredentials: Boolean): AppResult<String> =
        withContext(dispatchers.io) {
            runCatchingApp {
                val envelope = readLocalEnvelope(includeCredentials)
                BackupSerializer.serialize(envelope)
            }
        }

    /** 从各 DAO + 设置读出当前本地业务数据，组装为备份信封。默认不含凭据。 */
    private suspend fun readLocalEnvelope(includeCredentials: Boolean): BackupEnvelope {
        val settings = settingsDataStore.settings.first()
        // 仅在显式要求时附带脱敏凭据；否则恒为 null（RC.16.01 / RC.00 1.2）。
        val redacted = if (includeCredentials) credentialStore.exportRedacted() else null

        return BackupEnvelope(
            schemaVersion = BackupEnvelope.CURRENT_SCHEMA_VERSION,
            exportedAt = System.currentTimeMillis(),
            appVersion = BuildConfig.VERSION_NAME,
            includesCredentials = includeCredentials,
            works = workDao.getAll().map { it.toBackup() },
            sourceLinks = sourceLinkDao.getAll().map { it.toBackup() },
            recommendationCounts = workDao.getAllRecommendationCounts().map { it.toBackup() },
            backlog = backlogDao.getAll().map { it.toBackup() },
            ratings = ratingDao.getAll().map { it.toBackup() },
            reviews = emptyList(),
            tags = tagDao.getAll().map { it.toBackup() },
            workTags = workDao.getAllWorkTags().map { it.toBackup() },
            importBatches = importDao.getAllBatches().map { it.toBackup() },
            importItems = importDao.getAllItems().map { it.toBackup() },
            snapshots = snapshotDao.getAllSnapshots().map { it.toBackup() },
            changeLogs = snapshotDao.getAllChangeLogs().map { it.toBackup() },
            tasteProfiles = tasteDao.getAllProfiles().map { it.toBackup() },
            tasteTagStats = tasteDao.getAllTagStats().map { it.toBackup() },
            settings = settings.toBackup(),
            credentials = redacted,
        )
    }

    // --- importBackup (RC.16.03 merge-not-overwrite) -----------------------

    override suspend fun importBackup(json: String): AppResult<ImportReport> =
        withContext(dispatchers.io) {
            runCatchingApp {
                val incoming = BackupSerializer.deserialize(json)
                // 读出当前本地数据（不含凭据）作为合并基线。
                val local = readLocalEnvelope(includeCredentials = false)
                val result = BackupMerger.merge(local = local, incoming = incoming)

                persist(result.merged)

                ImportReport(
                    added = result.addedCount,
                    updated = result.updatedCount,
                    conflicts = result.conflicts.map { conflict ->
                        ImportConflict(
                            table = conflict.table,
                            key = conflict.key,
                            keptLocal = conflict.resolution == BackupMerger.Resolution.KEPT_LOCAL,
                        )
                    },
                )
            }
        }

    // --- exportCsv (RC.16.06 / R6) -----------------------------------------

    override suspend fun exportCsv(kind: CsvKind): AppResult<String> =
        withContext(dispatchers.io) {
            runCatchingApp {
                val envelope = readLocalEnvelope(includeCredentials = false)
                when (kind) {
                    CsvKind.BACKLOG -> {
                        val titles = envelope.works.associate { it.id to it.canonicalTitle }
                        CsvExporter.exportBacklog(envelope.backlog, titles)
                    }
                    CsvKind.RATINGS -> CsvExporter.exportRatings(envelope.ratings)
                    CsvKind.TIME_MACHINE -> CsvExporter.exportTimeMachine(envelope.changeLogs)
                }
            }
        }

    /**
     * 将合并后的信封整表 upsert 落库。仅写入业务数据；设置保持本地、凭据绝不写入（合并结果已保证
     * `credentials=null`）。
     */
    private suspend fun persist(envelope: BackupEnvelope) {
        workDao.upsertAll(envelope.works.map { it.toEntity() })
        envelope.recommendationCounts.forEach { workDao.upsertRecommendationCount(it.toEntity()) }
        workDao.upsertWorkTags(envelope.workTags.map { it.toEntity() })
        sourceLinkDao.upsertAll(envelope.sourceLinks.map { it.toEntity() })
        ratingDao.upsertAll(envelope.ratings.map { it.toEntity() })
        backlogDao.upsertAll(envelope.backlog.map { it.toEntity() })
        tagDao.upsertAll(envelope.tags.map { it.toEntity() })
        envelope.importBatches.forEach { importDao.upsertBatch(it.toEntity()) }
        importDao.upsertItems(envelope.importItems.map { it.toEntity() })
        envelope.snapshots.forEach { snapshotDao.upsertSnapshot(it.toEntity()) }
        snapshotDao.upsertChangeLogs(envelope.changeLogs.map { it.toEntity() })
        envelope.tasteProfiles.forEach { tasteDao.upsertProfile(it.toEntity()) }
        tasteDao.upsertTagStats(envelope.tasteTagStats.map { it.toEntity() })
    }
}
