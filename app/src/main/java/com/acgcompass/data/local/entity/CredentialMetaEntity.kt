package com.acgcompass.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Non-sensitive credential metadata for UI status display only (RC.02 / RC.15.01).
 *
 * CRITICAL (RC.00 1.2): credential plaintext (key/token/secret) is NEVER stored in
 * Room. This entity holds only whether a source is [configured], its [status]
 * label and the [lastTestedAt] timestamp. Actual secrets live exclusively in the
 * encrypted Credential_Store (EncryptedSharedPreferences / Keystore).
 */
@Entity(tableName = "credential_meta")
data class CredentialMetaEntity(
    @PrimaryKey
    val sourceId: String,
    val configured: Boolean,
    val status: String,
    val lastTestedAt: Long?,
)
