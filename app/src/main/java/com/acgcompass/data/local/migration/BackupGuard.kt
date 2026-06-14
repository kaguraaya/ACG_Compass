package com.acgcompass.data.local.migration

/**
 * Safety-net contract that produces an internal JSON backup of business data **before** a
 * destructive-risk database upgrade/migration runs (RC.00 1.8 / RC.16.03, requirements 18.3/18.5).
 *
 * This is the seam between the migration framework (this package, task 5.3) and the full backup
 * serializer (task 30.1). Task 5.3 only defines the mechanism and a stub
 * ([NoOpBackupGuard]); once the real serializer exists it implements this interface and is bound
 * in [MigrationModule] in place of the stub — no other call site changes.
 *
 * Implementations MUST:
 * - Exclude credentials/keys/tokens from the produced backup (RC.00 1.2/1.5).
 * - Write the backup to **app-internal** storage only (never world-readable, never network).
 * - Be safe to call when there is nothing to back up (return [PreMigrationBackup.empty]).
 */
interface BackupGuard {

    /**
     * Produce a pre-migration backup of all business data as a recovery safety net.
     *
     * @param fromVersion the schema version currently on disk (before migration).
     * @param toVersion the target schema version the app is upgrading to.
     * @return a descriptor of the backup that was written. Implementations that cannot or need not
     *   write a backup should return [PreMigrationBackup.empty]; failures should be surfaced by
     *   throwing so the caller ([DatabasePreMigrationBackup]) can record a recoverable error.
     */
    suspend fun createPreMigrationBackup(fromVersion: Int, toVersion: Int): PreMigrationBackup
}

/**
 * Descriptor of a backup written by a [BackupGuard]. The actual payload lives on internal storage
 * at [filePath]; this object is the lightweight handle the migration flow tracks for recovery.
 */
data class PreMigrationBackup(
    /** Absolute path to the internal backup file, or `null` when no payload was written. */
    val filePath: String?,
    /** Wall-clock time the backup was created (epoch millis). */
    val createdAtEpochMillis: Long,
    /** Schema version on disk before the migration. */
    val fromVersion: Int,
    /** Target schema version of the upgrade. */
    val toVersion: Int,
    /** Size of the written payload in bytes (0 when none). */
    val byteSize: Long,
) {
    /** True when an actual backup payload was written to disk. */
    val hasPayload: Boolean get() = filePath != null && byteSize > 0L

    companion object {
        /** A backup descriptor representing "nothing was written" (no payload). */
        fun empty(fromVersion: Int, toVersion: Int, atEpochMillis: Long): PreMigrationBackup =
            PreMigrationBackup(
                filePath = null,
                createdAtEpochMillis = atEpochMillis,
                fromVersion = fromVersion,
                toVersion = toVersion,
                byteSize = 0L,
            )
    }
}
