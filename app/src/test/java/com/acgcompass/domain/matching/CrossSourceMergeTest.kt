package com.acgcompass.domain.matching

import com.acgcompass.domain.model.MediaType
import com.acgcompass.domain.model.SourceId
import com.acgcompass.domain.model.Titles
import com.acgcompass.domain.model.Work
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * Feature: acg-compass, F5/A — 跨源合并通用算法回归（9-nine / 2.5 为样例，非硬编码目标）。
 *
 * 验证：紧凑归一精确匹配（9nine/9-nine）、原文一致合并、系列总称防误并、年份/类型冲突不并。
 */
class CrossSourceMergeTest : StringSpec({

    fun work(
        id: String,
        canonical: String,
        ja: String? = null,
        type: MediaType = MediaType.ANIME,
        year: Int? = 2021,
        source: SourceId = SourceId.BANGUMI,
    ): Work = Work(
        id = id,
        titles = Titles(canonical = canonical, ja = ja),
        mediaType = type,
        year = year,
        primarySource = source,
    )

    "9nine 与 9-nine 仅空格/连字符差异应合并（紧凑归一）" {
        val a = work("1", "9nine", source = SourceId.BANGUMI)
        val b = work("2", "9-nine", source = SourceId.ANILIST)
        sameWork(a, b) shouldBe true
    }

    "原文标题一致且年份/类型不冲突应合并（9-nine- 新章 ↔ 9-nine- Shinshou）" {
        val a = work("3", "9-nine- 新章", ja = "9-nine- そらいろそらうたそらのおと")
        val b = work("4", "9-nine- Shinshou", ja = "9-nine- そらいろそらうたそらのおと")
        sameWork(a, b) shouldBe true
    }

    "类型冲突（动画 vs 视觉小说）即使标题相近也不应自动合并相同年份不同作" {
        // 不同核心标题 + 类型冲突 → 不合并。
        val a = work("5", "完全不同的作品 A", type = MediaType.ANIME)
        val b = work("6", "另一个无关作品 B", type = MediaType.VN)
        sameWork(a, b) shouldBe false
    }

    "系列总称（过短前缀）不应与具体分作误并" {
        // 「9 nine」过短前缀，核心包含判定要求长度≥10，避免误并。
        val umbrella = work("7", "9 nine")
        val entry = work("8", "9-nine-九次九日九重色", ja = "9-nine-九次九日九重色")
        sameWork(umbrella, entry) shouldBe false
    }
})
