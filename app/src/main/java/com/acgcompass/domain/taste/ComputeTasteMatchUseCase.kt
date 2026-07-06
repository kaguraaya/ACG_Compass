package com.acgcompass.domain.taste

import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.roundToInt

/** 一条推荐理由：某大类（及其代表标签）对总分的有符号贡献。 */
data class TasteReason(
    val category: TasteCategory,
    val label: String,
    val contribution: Double,
)

/**
 * 口味匹配结果。[score] 为最终 5–95 分；[confidence] 独立输出（不混入主分）；[reasons] 为可解释贡献项。
 */
data class TasteMatchResult(
    val score: Int,
    val confidence: Double,
    val rawZ: Double,
    val probability: Double,
    val isRated: Boolean,
    val reasons: List<TasteReason> = emptyList(),
) {
    companion object {
        /** 画像不可用时的占位中性结果（冷启动）。 */
        val NEUTRAL = TasteMatchResult(
            score = 50,
            confidence = 0.25,
            rawZ = 0.0,
            probability = 0.5,
            isRated = false,
        )
    }
}

/**
 * 候选作品 → 口味匹配度（最终版算法「候选层 + 校准层」），纯函数可单测：
 *
 * 1. 用 [TasteRawScorer] 求十二维融合 raw `z(x)`；
 * 2. 叠加「已评分同作偏置」`0.12·pref(r_x)`（评分过的作品分数贴合本人评分）；
 * 3. 温度化 logistic 校准 `p=σ((z-μ)/τ)`；
 * 4. 分数拉开 `50 + sign·min(45, 1.18·|100p-50|)` → [5,95] 整数（解决「分数挤在 55–65」）；
 * 5. 置信度由样本量 + 各维覆盖率独立计算。
 */
class ComputeTasteMatchUseCase @Inject constructor() {

    /**
     * @param userRating 若用户已给该作品评分则传入（触发已评分偏置）；否则回退到画像内记录的评分。
     * @param tagOverrides N3 AI 分维分类缓存（清洗后标签 → 维度），仅作用于本地兜底为题材的未知标签；缺省空表回退本地。
     */
    operator fun invoke(
        feature: WorkFeature,
        profile: AdvancedTasteProfile,
        userRating: Int? = null,
        tagOverrides: Map<String, TasteCategory> = emptyMap(),
    ): TasteMatchResult {
        val rating = userRating ?: profile.ratedSubjectScores[feature.subjectId]
        val isRated = rating != null

        if (!profile.isUsable) {
            // 冷启动：仅用社区口碑做极弱先验，低置信度。
            val z = TasteCategory.COMMUNITY.defaultWeight *
                TasteScoringParams.communityGuidance(feature.bangumiScore?.toDouble(), feature.bangumiVotes)
            val p = TasteScoringParams.logistic(z, 0.0, TasteScoringParams.LOGISTIC_TAU_FLOOR)
            return TasteMatchResult.NEUTRAL.copy(
                score = finalScore(p),
                rawZ = z,
                probability = p,
                isRated = isRated,
            )
        }

        val f = TasteRawScorer.featurize(feature, tagOverrides)
        val rawZ = TasteRawScorer.rawScore(f, feature, profile)
        val pProfile = TasteScoringParams.logistic(rawZ, profile.calibration.mu, profile.calibration.tau)
        // N2 修复：已评分作品以「显式评分锚」为主、长期画像做修正。此前只加过弱的 `0.12·pref` 偏置，
        // 使《游戏人生》《命运石之门》等「与画像整体重合少的小众挚爱」rawZ≪μ 时塌到个位数，与文档
        // 「你明确打 10 分→稳定 90+、命运石之门/寒蝉→80+」完全相反。改用锚定融合（锚函数此前已定义但未接线）。
        // 未评分作品仍走纯画像校准分。
        val p = if (isRated) {
            TasteScoringParams.RATED_ANCHOR_WEIGHT * TasteScoringParams.ratedAnchorProbability(rating) +
                (1.0 - TasteScoringParams.RATED_ANCHOR_WEIGHT) * pProfile
        } else {
            pProfile
        }
        val score = finalScore(p)

        val confidence = TasteScoringParams.confidence(
            realSampleCount = profile.sampleCount,
            tagCoverage = profile.coverage.tag,
            staffCoverage = profile.coverage.staff,
            commentCoverage = profile.coverage.comment,
            timeCoverage = profile.coverage.time,
        )

        return TasteMatchResult(
            score = score,
            confidence = confidence,
            rawZ = rawZ,
            probability = p,
            isRated = isRated,
            reasons = buildReasons(f, feature, profile),
        )
    }

    private fun finalScore(probability: Double): Int =
        TasteScoringParams.spread(probability).roundToInt().coerceIn(5, 95)

    /** 取贡献最大的若干维度作为理由（正向优先，附代表标签）。 */
    private fun buildReasons(
        f: TasteRawScorer.Featurized,
        feature: WorkFeature,
        profile: AdvancedTasteProfile,
    ): List<TasteReason> {
        val reasons = ArrayList<TasteReason>()
        val lambda = TasteScoringParams.LAMBDA_NEGATIVE

        for (cat in TasteCategory.SINGLE_TAG_CATEGORIES) {
            if (cat == TasteCategory.NOISE) continue
            val catTags = f.byCategory[cat] ?: continue
            val pref = profile.category(cat)
            val sim = TasteRawScorer.simCategory(catTags, pref, lambda)
            val contribution = cat.defaultWeight * sim
            if (abs(contribution) < REASON_MIN) continue
            val topTag = catTags.entries
                .maxByOrNull { (k, q) -> q * (pref.positive[k] ?: 0.0) }
                ?.key
            val text = if (topTag != null) "${cat.label}·$topTag" else cat.label
            reasons += TasteReason(cat, text, contribution)
        }

        val comboSim = TasteRawScorer.simCombo(f.topicDeviceTags, profile.combos)
        if (comboSim > 0.0) {
            val best = profile.combos
                .filter { f.topicDeviceTags.containsAll(it.tags) }
                .maxByOrNull { it.strength }
            val text = best?.tags?.joinToString("+")?.let { "${TasteCategory.COMBO.label}·$it" }
                ?: TasteCategory.COMBO.label
            reasons += TasteReason(TasteCategory.COMBO, text, TasteCategory.COMBO.defaultWeight * comboSim)
        }

        return reasons.sortedByDescending { it.contribution }.take(MAX_REASONS)
    }

    private companion object {
        const val REASON_MIN: Double = 1e-3
        const val MAX_REASONS: Int = 4
    }
}
