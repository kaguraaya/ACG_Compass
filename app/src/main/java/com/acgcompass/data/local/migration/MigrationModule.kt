package com.acgcompass.data.local.migration

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt bindings for the database migration safety-net (task 5.3, RC.00 1.8 / RC.16.03).
 *
 * - [MigrationVersionStore] → [DataStoreMigrationVersionStore] (DataStore-backed version bookkeeping).
 * - [BackupGuard] → [NoOpBackupGuard] **stub** until the full backup serializer lands in task 30.1;
 *   swap this single binding to the real implementation then (see [NoOpBackupGuard] docs).
 *
 * [DatabasePreMigrationBackup] is `@Inject`-constructed and consumes these bindings.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class MigrationModule {

    @Binds
    @Singleton
    abstract fun bindMigrationVersionStore(impl: DataStoreMigrationVersionStore): MigrationVersionStore

    @Binds
    @Singleton
    abstract fun bindBackupGuard(impl: NoOpBackupGuard): BackupGuard
}
