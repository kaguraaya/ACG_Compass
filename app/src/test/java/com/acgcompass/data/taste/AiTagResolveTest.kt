package com.acgcompass.data.taste

import com.acgcompass.domain.ai.TagClassifyOutput
import com.acgcompass.domain.ai.TagDimensionAssignment
import com.acgcompass.domain.taste.TasteCategory
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * A1 回归：[resolveTagDimensionAssignments]（AI 分维输出 → (标签,维度) 解析）的命中 / 兜底 / 丢弃规则。
 *
 * 覆盖根因修复：模型在不支持 json_schema 的中转站上常改写 tag 或用中文 / 近义 dimension，导致旧逻辑
 * 「key !in batchKeys 或 fromKey==null」整批丢弃 → classified=0 → 误报「AI 未返回可用结果」。
 * 新逻辑用「位置对齐兜底 + 维度别名容错」保住可用响应，同时对真正对不上 / 非法维度仍丢弃（不编造）。
 */
class AiTagResolveTest : StringSpec({

    fun output(vararg items: Pair<String, String>): TagClassifyOutput =
        TagClassifyOutput(
            items = items.map { TagDimensionAssignment(tag = it.first, dimension = it.second) },
            confidence = 0.8f,
        )

    "精确命中：AI 回填 tag 落在本批 + 合法维度 → 采用" {
        val out = output("异世界" to "topic", "后宫" to "xp")
        resolveTagDimensionAssignments(out, listOf("异世界", "后宫")) shouldBe listOf(
            "异世界" to TasteCategory.TOPIC,
            "后宫" to TasteCategory.XP,
        )
    }

    "位置对齐兜底：AI 改写了 tag，但 items 数与本批一致 → 按位置回填输入原 tag" {
        // 旧逻辑会因 clean(\"Isekai\")=\"isekai\" 不在本批而整批丢弃；新逻辑按位置回填。
        val out = output("Isekai" to "topic", "Harem" to "xp")
        resolveTagDimensionAssignments(out, listOf("异世界", "后宫")) shouldBe listOf(
            "异世界" to TasteCategory.TOPIC,
            "后宫" to TasteCategory.XP,
        )
    }

    "数量不一致且 tag 对不上 → 丢弃（不猜位置，避免错配）" {
        val out = output("乱写标签" to "topic")
        resolveTagDimensionAssignments(out, listOf("异世界", "后宫", "治愈")) shouldBe emptyList()
    }

    "维度中文近义别名（题材）→ 经 fromKey 归一为 TOPIC" {
        val out = output("异世界" to "题材")
        resolveTagDimensionAssignments(out, listOf("异世界")) shouldBe listOf("异世界" to TasteCategory.TOPIC)
    }

    "非法维度 → 丢弃（不编造）" {
        val out = output("异世界" to "bogus")
        resolveTagDimensionAssignments(out, listOf("异世界")) shouldBe emptyList()
    }

    "同一标签重复 → 去重只保留首个" {
        val out = output("异世界" to "topic", "异世界" to "device")
        resolveTagDimensionAssignments(out, listOf("异世界", "异世界")) shouldBe listOf("异世界" to TasteCategory.TOPIC)
    }

    "空 items → 空结果" {
        resolveTagDimensionAssignments(
            TagClassifyOutput(items = emptyList(), confidence = 0f),
            listOf("异世界"),
        ) shouldBe emptyList()
    }
})
