package com.acgcompass.data.local.migration

import androidx.room.migration.Migration
/**
 * Central, explicit registry of every Room schema [Migration] for [com.acgcompass.data.local.AcgCompassDatabase]
 * (task 5.3, RC.00 1.8 / RC.16.03).
 *
 * ## Policy
 * ACG Compass is local-first and an app upgrade must **never** drop user data. Therefore:
 * - `fallbackToDestructiveMigration` is **intentionally never** used on the Room builder.
 * - Every schema version bump ships a hand-written [Migration] object registered in [ALL].
 * - Room's exported schema json (`$projectDir/schemas`, see `build.gradle.kts`) is committed and
 *   used to validate migrations.
 *
 * ## How to add a migration (pattern)
 * When you bump `@Database(version = N)` on `AcgCompassDatabase` from `N-1` to `N`:
 *
 * 1. Write the migration as a private `val` using DDL that preserves all existing rows:
 *
 * ```kotlin
 * // Example: v1 -> v2 adds a nullable column to the works table without data loss.
 * private val MIGRATION_1_2 = Migration(1, 2) { db ->
 *     db.execSQL("ALTER TABLE works ADD COLUMN synopsis TEXT")
 * }
 * ```
 *
 * 2. Append it to [ALL] **in ascending order**:
 *
 * ```kotlin
 * val ALL: Array<Migration> = arrayOf(
 *     MIGRATION_1_2,
 *     // MIGRATION_2_3, ...
 * )
 * ```
 *
 * 3. Commit the new `schemas/<N>.json` produced by the Room compiler and add a migration test.
 *
 * The migrations are wired into the Room builder via `.addMigrations(*AcgMigrations.ALL)` in
 * `com.acgcompass.data.local.di.DatabaseModule`.
 *
 * At schema version 1 there are no migrations yet, so [ALL] is empty — `arrayOf(*ALL)` is a safe
 * no-op on the builder.
 */
object AcgMigrations {

    /**
     * v1 → v2（R45）：新增 `user_collections` 表（用户个人收藏：状态/评分/进度/短评/标签）。
     * 仅 CREATE TABLE + 索引，不触碰既有表，**不丢任何用户数据**。DDL 与 Room 为该 Entity 生成的
     * 期望 schema 完全一致（列名/类型/可空/主键/索引），以通过 Room 运行时校验。
     */
    private val MIGRATION_1_2 = Migration(1, 2) { db ->
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `user_collections` (" +
                "`id` TEXT NOT NULL, " +
                "`source` TEXT NOT NULL, " +
                "`sourceItemId` TEXT NOT NULL, " +
                "`localWorkId` TEXT NOT NULL, " +
                "`status` TEXT, " +
                "`rating` INTEGER, " +
                "`progress` INTEGER, " +
                "`comment` TEXT, " +
                "`tags` TEXT NOT NULL, " +
                "`updatedAt` INTEGER NOT NULL, " +
                "`syncedAt` INTEGER NOT NULL, " +
                "`sourceUpdatedAt` TEXT, " +
                "PRIMARY KEY(`id`))",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_user_collections_localWorkId` " +
                "ON `user_collections` (`localWorkId`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_user_collections_source` " +
                "ON `user_collections` (`source`)",
        )
    }

    /**
     * v2 → v3（F7）：为 `works` 表新增可空 `summary` 列（作品简介 / 梗概，来自主源条目详情）。
     * 仅 `ALTER TABLE ADD COLUMN`，既有行的新列默认 NULL，**不丢任何用户数据**。DDL 与 Room 为
     * 该 Entity 生成的期望 schema 一致（TEXT、可空），以通过 Room 运行时校验。
     */
    private val MIGRATION_2_3 = Migration(2, 3) { db ->
        db.execSQL("ALTER TABLE `works` ADD COLUMN `summary` TEXT")
    }

    /**
     * v3 -> v4：works 表新增可空 `airDate`（精确到天的开播/发行日期，I16）。
     * 仅新增可空列，保留全部既有行；与 [WorkEntity] 生成的期望 schema 一致（TEXT、可空）。
     */
    private val MIGRATION_3_4 = Migration(3, 4) { db ->
        db.execSQL("ALTER TABLE `works` ADD COLUMN `airDate` TEXT")
    }

    /**
     * v4 -> v5（B-4）：新增 `ranking_cache` 表（榜单结果本地缓存：范围键 + 排名次序 + 作品 id + 缓存时间）。
     * 由 DataStore Preferences 迁移而来；仅 CREATE TABLE，不触碰既有表，**不丢任何用户数据**。
     * DDL 与 [com.acgcompass.data.local.entity.RankingCacheEntity] 生成的期望 schema 完全一致
     * （列名/类型/可空/复合主键），以通过 Room 运行时校验与迁移测试。
     */
    private val MIGRATION_4_5 = Migration(4, 5) { db ->
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `ranking_cache` (" +
                "`scopeKey` TEXT NOT NULL, " +
                "`position` INTEGER NOT NULL, " +
                "`workId` TEXT NOT NULL, " +
                "`cachedAt` INTEGER NOT NULL, " +
                "PRIMARY KEY(`scopeKey`, `position`))",
        )
    }

    /**
     * All registered migrations, ordered ascending by `startVersion`.
     *
     * Spread into the Room builder with `.addMigrations(*AcgMigrations.ALL)`.
     */
    val ALL: Array<Migration> = arrayOf(
        MIGRATION_1_2,
        MIGRATION_2_3,
        MIGRATION_3_4,
        MIGRATION_4_5,
    )
}
