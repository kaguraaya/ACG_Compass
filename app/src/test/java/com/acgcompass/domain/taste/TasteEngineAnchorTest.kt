package com.acgcompass.domain.taste

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * 最终版口味引擎**锚点回归**（[BuildTasteProfileUseCase] + [ComputeTasteMatchUseCase] 纯函数链路）。
 *
 * 复刻算法文档「锚点表」的精神（而非具体条目）：一个明显偏爱「奇幻 + 异世界 + 智斗」、厌恶「恋爱 + 日常」
 * 的用户，其强命中候选应**显著高分**、反口味候选应**低分**，且分数**拉开差距**（治「都挤在 55–65」）；
 * 同时校验题材组合优于单题材、已评分偏置生效、分数恒落在 [5,95]。
 *
 * 断言以**设计上可保证的关系性质 + 宽松绝对界**为主（避免脆弱的精确阈值），本机因路径含中文无法运行单测，
 * 但可在干净路径机器执行；此处亦作为引擎行为的可编译规范。
 */
class TasteEngineAnchorTest : StringSpec({

    val build = BuildTasteProfileUseCase()
    val compute = ComputeTasteMatchUseCase()
    val now = 1_700_000_000_000L
    val day = 24L * 3600 * 1000

    fun feature(
        id: String,
        tags: List<Pair<String, Int>>,
        score: Float? = 8.0f,
        votes: Int = 5000,
    ) = WorkFeature(
        subjectId = id,
        tagCounts = tags.map { TagCount(it.first, it.second) },
        bangumiScore = score,
        bangumiVotes = votes,
        titleVariants = listOf(id),
    )

    fun sample(id: String, rating: Int, ageDays: Long, tags: List<Pair<String, Int>>) =
        TasteSample(
            subjectId = id,
            rating = rating,
            comment = null,
            updatedAtMillis = now - ageDays * day,
            feature = feature(id, tags),
        )

    // 训练集：偏爱 奇幻 + 异世界 + 智斗（高分），厌恶 恋爱 + 日常（低分）。
    val training = listOf(
        sample("h1", 10, 30, listOf("奇幻" to 50, "异世界" to 40, "智斗" to 30, "战斗" to 20)),
        sample("h2", 9, 60, listOf("奇幻" to 45, "异世界" to 35, "魔法" to 25)),
        sample("h3", 9, 90, listOf("奇幻" to 40, "智斗" to 30, "冒险" to 20)),
        sample("h4", 10, 120, listOf("异世界" to 50, "智斗" to 40, "魔法" to 10)),
        sample("h5", 8, 150, listOf("奇幻" to 30, "冒险" to 25)),
        sample("l1", 3, 50, listOf("恋爱" to 50, "日常" to 40)),
        sample("l2", 2, 80, listOf("恋爱" to 45, "校园" to 30)),
        sample("l3", 4, 100, listOf("日常" to 40, "治愈" to 30)),
    )

    val profile = build(training, now)

    "画像可用（有正负向量 / 题材组合）" {
        profile.isUsable shouldBe true
    }

    "强命中候选 > 反口味候选，且分数拉开差距" {
        val high = compute(
            feature("c_high", listOf("奇幻" to 60, "异世界" to 50, "智斗" to 40, "魔法" to 30)),
            profile,
        )
        val low = compute(
            feature("c_low", listOf("恋爱" to 60, "日常" to 50, "校园" to 30)),
            profile,
        )
        (high.score > low.score) shouldBe true
        (high.score - low.score >= 20) shouldBe true
        (high.score >= 65) shouldBe true
        (low.score <= 55) shouldBe true
    }

    "分数恒落在 [5,95]" {
        val r = compute(feature("c", listOf("奇幻" to 10)), profile)
        (r.score in 5..95) shouldBe true
    }

    "题材组合命中 > 仅单题材命中" {
        val combo = compute(feature("cc", listOf("奇幻" to 40, "异世界" to 40, "智斗" to 30)), profile)
        val single = compute(feature("cs", listOf("奇幻" to 40)), profile)
        (combo.score >= single.score) shouldBe true
    }

    "已评分偏置：本人给 10 分 > 给 2 分" {
        val f = feature("rx", listOf("奇幻" to 30, "异世界" to 20))
        val hi = compute(f, profile, userRating = 10)
        val lo = compute(f, profile, userRating = 2)
        (hi.score > lo.score) shouldBe true
    }

    "冷启动（空画像）→ 中性兜底，不崩溃" {
        val empty = build(emptyList(), now)
        val r = compute(feature("x", listOf("奇幻" to 10)), empty)
        (r.score in 5..95) shouldBe true
    }
})
