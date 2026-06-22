package com.acgcompass.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.acgcompass.data.local.converter.Converters
import com.acgcompass.data.local.dao.AiResultDao
import com.acgcompass.data.local.dao.BacklogDao
import com.acgcompass.data.local.dao.CredentialMetaDao
import com.acgcompass.data.local.dao.ImportDao
import com.acgcompass.data.local.dao.RankingCacheDao
import com.acgcompass.data.local.dao.RatingDao
import com.acgcompass.data.local.dao.RouteNodeDao
import com.acgcompass.data.local.dao.SnapshotDao
import com.acgcompass.data.local.dao.SourceLinkDao
import com.acgcompass.data.local.dao.TagDao
import com.acgcompass.data.local.dao.TasteDao
import com.acgcompass.data.local.dao.UserCollectionDao
import com.acgcompass.data.local.dao.WorkDao
import com.acgcompass.data.local.entity.AiResultEntity
import com.acgcompass.data.local.entity.BacklogItemEntity
import com.acgcompass.data.local.entity.ChangeLogEntity
import com.acgcompass.data.local.entity.CredentialMetaEntity
import com.acgcompass.data.local.entity.ImportBatchEntity
import com.acgcompass.data.local.entity.ImportItemEntity
import com.acgcompass.data.local.entity.RankingCacheEntity
import com.acgcompass.data.local.entity.RatingEntity
import com.acgcompass.data.local.entity.RecommendationCountEntity
import com.acgcompass.data.local.entity.RouteNodeEntity
import com.acgcompass.data.local.entity.SnapshotEntity
import com.acgcompass.data.local.entity.SourceLinkEntity
import com.acgcompass.data.local.entity.TagEntity
import com.acgcompass.data.local.entity.TasteProfileEntity
import com.acgcompass.data.local.entity.TasteTagStatEntity
import com.acgcompass.data.local.entity.UserCollectionEntity
import com.acgcompass.data.local.entity.WorkEntity
import com.acgcompass.data.local.entity.WorkTagEntity

/**
 * The single Room database for ACG Compass — the local-first source of truth for
 * all business data (RC.00 1.1).
 *
 * Schema is exported (`exportSchema = true`, location configured in build.gradle:
 * `$projectDir/schemas`) so every version's schema json is committed and used by
 * migration tests (RC.16.03 / design 迁移策略).
 *
 * Migration policy (task 5.3): explicit [androidx.room.migration.Migration]
 * objects only — `fallbackToDestructiveMigration` is intentionally NOT used so an
 * app upgrade never drops user data (RC.00 1.8). Migrations are registered on the
 * builder in the Hilt module, not here.
 *
 * RC.00 1.2: no credential plaintext is ever stored here — only the non-sensitive
 * [CredentialMetaEntity] metadata.
 */
@Database(
    entities = [
        WorkEntity::class,
        SourceLinkEntity::class,
        RatingEntity::class,
        BacklogItemEntity::class,
        TagEntity::class,
        WorkTagEntity::class,
        RecommendationCountEntity::class,
        ImportBatchEntity::class,
        ImportItemEntity::class,
        SnapshotEntity::class,
        ChangeLogEntity::class,
        TasteProfileEntity::class,
        TasteTagStatEntity::class,
        AiResultEntity::class,
        RouteNodeEntity::class,
        CredentialMetaEntity::class,
        UserCollectionEntity::class,
        RankingCacheEntity::class,
    ],
    version = 5,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AcgCompassDatabase : RoomDatabase() {

    abstract fun workDao(): WorkDao

    abstract fun sourceLinkDao(): SourceLinkDao

    abstract fun ratingDao(): RatingDao

    abstract fun backlogDao(): BacklogDao

    abstract fun tagDao(): TagDao

    abstract fun importDao(): ImportDao

    abstract fun snapshotDao(): SnapshotDao

    abstract fun tasteDao(): TasteDao

    abstract fun aiResultDao(): AiResultDao

    abstract fun routeNodeDao(): RouteNodeDao

    abstract fun credentialMetaDao(): CredentialMetaDao

    abstract fun userCollectionDao(): UserCollectionDao

    abstract fun rankingCacheDao(): RankingCacheDao

    companion object {
        /** Physical database file name. */
        const val DATABASE_NAME = "acg_compass.db"
    }
}
