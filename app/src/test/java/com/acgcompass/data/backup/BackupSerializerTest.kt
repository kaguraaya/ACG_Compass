package com.acgcompass.data.backup

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/**
 * 备份序列化器与合并器单元测试（task 30.1 / RC.16.01/03 / RC.00 1.5）。
 *
 * 覆盖：默认零凭据（Property 1 示例侧）、序列化 round-trip（Property 17 示例侧，属性侧见 task 30.2）、
 * 合并不覆盖（RC.16.03 / 18.3）。纯函数，无 Android / DAO 依赖。
 */
class BackupSerializerTest : StringSpec({

    fun sampleEnvelope(): BackupEnvelope =
        BackupEnvelope(
            schemaVersion = BackupEnvelope.CURRENT_SCHEMA_VERSION,
            exportedAt = 1_730_000_000_000L,
            appVersion = "0.1.0",
            works = listOf(
                BackupWork(
                    id = "w1",
                    canonicalTitle = "作品一",
                    titleJa = "さくひん",
                    aliases = listOf("别名A", "别名B"),
                    mediaType = "ANIME",
                    year = 2021,
                    status = "FINISHED",
                    primarySource = "BANGUMI",
                    createdAt = 1000,
                    updatedAt = 2000,
                ),
            ),
            backlog = listOf(
                BackupBacklogItem(
                    workId = "w1",
                    priority = "HIGH",
                    moodTags = listOf("治愈"),
                    riskTags = emptyList(),
                    note = "记得看",
                    addedAt = 1500,
                    dustDays = 3,
                    inDustMuseum = false,
                ),
            ),
            ratings = listOf(
                BackupRating("r1", "w1", "BANGUMI", 8.5f, 1200, 42, 1800, missing = false),
            ),
            tags = listOf(BackupTag("t1", "MOOD", "治愈")),
            workTags = listOf(BackupWorkTag("w1", "t1")),
        )

    "default export excludes credentials (Property 1 / RC.16.01)" {
        val json = BackupSerializer.serialize(sampleEnvelope())

        json shouldContain "\"includesCredentials\": false"
        json shouldContain "\"credentials\": null"
    }

    "serialize/deserialize round-trips business data (Property 17 / 18.8)" {
        val original = sampleEnvelope()

        val restored = BackupSerializer.deserialize(BackupSerializer.serialize(original))

        restored shouldBe original
    }

    "merge adds rows missing locally (RC.16.03)" {
        val local = sampleEnvelope().copy(
            works = emptyList(),
            backlog = emptyList(),
            ratings = emptyList(),
            tags = emptyList(),
            workTags = emptyList(),
        )
        val incoming = sampleEnvelope()

        val result = BackupMerger.merge(local = local, incoming = incoming)

        result.addedCount shouldBe (
            incoming.works.size + incoming.backlog.size +
                incoming.ratings.size + incoming.tags.size + incoming.workTags.size
            )
        result.merged.works.map { it.id } shouldBe listOf("w1")
        result.updatedCount shouldBe 0
    }

    "merge keeps newer local work and does not overwrite with older incoming (RC.16.03 / 18.3)" {
        val local = sampleEnvelope() // w1.updatedAt = 2000
        val olderIncoming = sampleEnvelope().copy(
            works = listOf(
                sampleEnvelope().works.first().copy(canonicalTitle = "旧标题", updatedAt = 1000),
            ),
        )

        val result = BackupMerger.merge(local = local, incoming = olderIncoming)

        // 本地较新，导入较旧 → 保留本地，不覆盖。
        result.merged.works.first().canonicalTitle shouldBe "作品一"
        result.updatedCount shouldBe 0
        result.conflicts.any {
            it.table == "works" && it.key == "w1" &&
                it.resolution == BackupMerger.Resolution.KEPT_LOCAL
        } shouldBe true
    }

    "merge adopts strictly newer incoming work (default keep newer updatedAt)" {
        val local = sampleEnvelope() // w1.updatedAt = 2000
        val newerIncoming = sampleEnvelope().copy(
            works = listOf(
                sampleEnvelope().works.first().copy(canonicalTitle = "新标题", updatedAt = 3000),
            ),
        )

        val result = BackupMerger.merge(local = local, incoming = newerIncoming)

        result.merged.works.first().canonicalTitle shouldBe "新标题"
        result.updatedCount shouldBe 1
        result.conflicts.any {
            it.table == "works" && it.resolution == BackupMerger.Resolution.USED_INCOMING
        } shouldBe true
    }

    "merged envelope never carries credentials (RC.00 1.2)" {
        val local = sampleEnvelope()
        val incoming = sampleEnvelope()

        val result = BackupMerger.merge(local = local, incoming = incoming)

        result.merged.credentials shouldBe null
        result.merged.includesCredentials shouldBe false
        BackupSerializer.serialize(result.merged) shouldNotContain "\"credentials\": {"
    }
})
