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
     * v5 -> v6（C 轮）：backlog_items 新增可空 `prevStatus`（归档进吃灰馆前的 Bangumi 收藏状态）。
     * 吃灰归档会把状态置「搁置」，此列记住原状态以便「移出吃灰馆」时还原（修复「移出后仍是搁置」）。
     * 仅新增可空列，保留全部既有行，**不丢任何用户数据**；与 [com.acgcompass.data.local.entity.BacklogItemEntity]
     * 生成的期望 schema 一致（TEXT、可空），以通过 Room 运行时校验与迁移测试。
     */
    private val MIGRATION_5_6 = Migration(5, 6) { db ->
        db.execSQL("ALTER TABLE `backlog_items` ADD COLUMN `prevStatus` TEXT")
    }

    /**
     * v6 -> v7（最终版算法）：新增 `work_features`（作品结构化特征缓存：社区标签计数 + staff/角色/CV +
     * 社区评分/票数 + 集数/时长/平台）与 `recommendation_exposure`（推荐曝光记录，支撑重复推荐冷却）。
     * 仅 CREATE TABLE，不触碰既有表，**不丢任何用户数据**；DDL 与 [com.acgcompass.data.local.entity.WorkFeatureEntity]
     * / [com.acgcompass.data.local.entity.RecommendationExposureEntity] 生成的期望 schema 完全一致
     * （列名/类型/可空/主键），以通过 Room 运行时校验与迁移测试。
     */
    private val MIGRATION_6_7 = Migration(6, 7) { db ->
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `work_features` (" +
                "`subjectId` TEXT NOT NULL, " +
                "`tagCountsJson` TEXT NOT NULL, " +
                "`staff` TEXT NOT NULL, " +
                "`characters` TEXT NOT NULL, " +
                "`cv` TEXT NOT NULL, " +
                "`bangumiScore` REAL NOT NULL, " +
                "`bangumiVotes` INTEGER NOT NULL, " +
                "`eps` INTEGER NOT NULL, " +
                "`durationMin` INTEGER NOT NULL, " +
                "`platform` TEXT, " +
                "`mediaType` TEXT, " +
                "`titles` TEXT NOT NULL, " +
                "`updatedAt` INTEGER NOT NULL, " +
                "PRIMARY KEY(`subjectId`))",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `recommendation_exposure` (" +
                "`id` TEXT NOT NULL, " +
                "`subjectId` TEXT NOT NULL, " +
                "`context` TEXT NOT NULL, " +
                "`exposedAt` INTEGER NOT NULL, " +
                "`clickedAt` INTEGER, " +
                "`dismissedAt` INTEGER, " +
                "PRIMARY KEY(`id`))",
        )
    }

    /**
     * v7 -> v8：works 表新增可空 `titleCn`（中文标题，Bangumi name_cn，D2 卡片中文优先展示）。
     * 仅 `ALTER TABLE ADD COLUMN`，既有行新列默认 NULL，**不丢任何用户数据**；与 [com.acgcompass.data.local.entity.WorkEntity]
     * 生成的期望 schema 一致（TEXT、可空），以通过 Room 运行时校验与迁移测试。
     */
    private val MIGRATION_7_8 = Migration(7, 8) { db ->
        db.execSQL("ALTER TABLE `works` ADD COLUMN `titleCn` TEXT")
    }

    /**
     * v8 -> v9（M）：user_collections 表新增非空 `isPrivate`（是否「仅自己可见 / 私密」，Bangumi `private`）。
     * 仅 `ALTER TABLE ADD COLUMN` 且带 `NOT NULL DEFAULT 0`，既有行默认置公开（0），**不丢任何用户数据**；
     * 与 [com.acgcompass.data.local.entity.UserCollectionEntity] 生成的期望 schema 一致（INTEGER NOT NULL、默认 0），
     * 以通过 Room 运行时校验与迁移测试。
     */
    private val MIGRATION_8_9 = Migration(8, 9) { db ->
        db.execSQL("ALTER TABLE `user_collections` ADD COLUMN `isPrivate` INTEGER NOT NULL DEFAULT 0")
    }

    /**
     * v9 -> v10（N3）：新增 `tag_dimensions` 缓存表（社区标签 → 口味维度的 AI 分维分类结果）。
     * 仅 `CREATE TABLE`，纯新增、不触碰既有表，**不丢任何用户数据**；列与顺序须与
     * [com.acgcompass.data.local.entity.TagDimensionEntity] 生成的期望 schema 完全一致
     * （tag TEXT PK、dimension/source TEXT NOT NULL、confidence REAL NOT NULL、updatedAt INTEGER NOT NULL），
     * 以通过 Room 运行时校验与迁移测试。
     */
    private val MIGRATION_9_10 = Migration(9, 10) { db ->
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `tag_dimensions` (" +
                "`tag` TEXT NOT NULL, " +
                "`dimension` TEXT NOT NULL, " +
                "`source` TEXT NOT NULL, " +
                "`confidence` REAL NOT NULL, " +
                "`updatedAt` INTEGER NOT NULL, " +
                "PRIMARY KEY(`tag`))",
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
        MIGRATION_5_6,
        MIGRATION_6_7,
        MIGRATION_7_8,
        MIGRATION_8_9,
        MIGRATION_9_10,
    )
}
