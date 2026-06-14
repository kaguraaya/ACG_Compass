package com.acgcompass.data.local.di

import android.content.Context
import androidx.room.Room
import com.acgcompass.data.local.AcgCompassDatabase
import com.acgcompass.data.local.dao.AiResultDao
import com.acgcompass.data.local.dao.BacklogDao
import com.acgcompass.data.local.dao.CredentialMetaDao
import com.acgcompass.data.local.dao.ImportDao
import com.acgcompass.data.local.dao.RatingDao
import com.acgcompass.data.local.dao.RouteNodeDao
import com.acgcompass.data.local.dao.SnapshotDao
import com.acgcompass.data.local.dao.SourceLinkDao
import com.acgcompass.data.local.dao.TagDao
import com.acgcompass.data.local.dao.TasteDao
import com.acgcompass.data.local.dao.UserCollectionDao
import com.acgcompass.data.local.dao.WorkDao
import com.acgcompass.data.local.migration.AcgMigrations
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module providing the [AcgCompassDatabase] singleton and each DAO (task 5.2).
 *
 * The database is built with [Room.databaseBuilder] at application scope so the
 * whole process shares one connection pool. `fallbackToDestructiveMigration` is
 * intentionally NOT called — explicit migrations from [AcgMigrations.ALL] are
 * registered here as they are authored (task 5.3) so an upgrade never drops user
 * data (RC.00 1.8). The upgrade-time JSON backup safety net lives in
 * `com.acgcompass.data.local.migration.DatabasePreMigrationBackup` (RC.16.03).
 */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(
        @ApplicationContext context: Context,
    ): AcgCompassDatabase =
        Room.databaseBuilder(
            context,
            AcgCompassDatabase::class.java,
            AcgCompassDatabase.DATABASE_NAME,
        )
            // No fallbackToDestructiveMigration: data must survive upgrades (RC.00 1.8).
            // Explicit Migration objects are registered centrally in AcgMigrations (task 5.3);
            // empty at schema v1, so this spread is currently a safe no-op.
            .addMigrations(*AcgMigrations.ALL)
            .build()

    @Provides
    @Singleton
    fun provideWorkDao(db: AcgCompassDatabase): WorkDao = db.workDao()

    @Provides
    @Singleton
    fun provideSourceLinkDao(db: AcgCompassDatabase): SourceLinkDao = db.sourceLinkDao()

    @Provides
    @Singleton
    fun provideRatingDao(db: AcgCompassDatabase): RatingDao = db.ratingDao()

    @Provides
    @Singleton
    fun provideBacklogDao(db: AcgCompassDatabase): BacklogDao = db.backlogDao()

    @Provides
    @Singleton
    fun provideTagDao(db: AcgCompassDatabase): TagDao = db.tagDao()

    @Provides
    @Singleton
    fun provideImportDao(db: AcgCompassDatabase): ImportDao = db.importDao()

    @Provides
    @Singleton
    fun provideSnapshotDao(db: AcgCompassDatabase): SnapshotDao = db.snapshotDao()

    @Provides
    @Singleton
    fun provideTasteDao(db: AcgCompassDatabase): TasteDao = db.tasteDao()

    @Provides
    @Singleton
    fun provideAiResultDao(db: AcgCompassDatabase): AiResultDao = db.aiResultDao()

    @Provides
    @Singleton
    fun provideRouteNodeDao(db: AcgCompassDatabase): RouteNodeDao = db.routeNodeDao()

    @Provides
    @Singleton
    fun provideCredentialMetaDao(db: AcgCompassDatabase): CredentialMetaDao = db.credentialMetaDao()

    @Provides
    @Singleton
    fun provideUserCollectionDao(db: AcgCompassDatabase): UserCollectionDao = db.userCollectionDao()
}
