package com.acgcompass.domain.taste

/**
 * 十二维**原始相似度融合** `z(x)`（最终版算法「候选层综合打分」），纯函数、无状态。
 *
 * 由 [BuildTasteProfileUseCase]（求训练样本 z 分布做校准）与 [ComputeTasteMatchUseCase]（评分候选）
 * 共用，保证「画像构建」与「评分」口径完全一致（避免半路径不一致）。不含校准 / 分数拉开 / 已评分偏置。
 */
object TasteRawScorer {

    /**
     * 作品按大类的特征化结果。
     * - [byCategory]：大类 → (清洗后标签/名 → 强度 q)；同标签取最大 q。
     * - [topicDeviceTags]：题材 + 情节装置的清洗标签集合，用于题材组合命中。
     */
    data class Featurized(
        val byCategory: Map<TasteCategory, Map<String, Double>>,
        val topicDeviceTags: Set<String>,
    )

    /** 把一部作品的社区标签（带计数）+ 结构化 staff/CV/角色 特征化为各大类的 tag→q。 */
    fun featurize(feature: WorkFeature): Featurized {
        val ctx = TagClassifier.Context.of(
            staff = feature.staff,
            characters = feature.characters,
            cv = feature.cv,
            titles = feature.titleVariants,
        )
        val maxCount = feature.maxTagCount
        val byCat = HashMap<TasteCategory, HashMap<String, Double>>()
        val topicDevice = HashSet<String>()

        fun add(cat: TasteCategory, key: String, q: Double) {
            if (key.isBlank() || q <= 0.0) return
            val m = byCat.getOrPut(cat) { HashMap() }
            m[key] = maxOf(m[key] ?: 0.0, q)
        }

        for (tc in feature.tagCounts) {
            val cleaned = TagClassifier.clean(tc.name)
            if (cleaned.isEmpty()) continue
            val cat = TagClassifier.classify(tc.name, ctx)
            if (cat == TasteCategory.NOISE) continue
            val q = TasteScoringParams.tagStrength(tc.count, maxCount)
            add(cat, cleaned, q)
            if (cat == TasteCategory.TOPIC || cat == TasteCategory.DEVICE) topicDevice += cleaned
        }
        // 结构化真实名按「出现即满强度」并入对应维度（无标注人数，q=1.0）。
        for (s in feature.staff) add(TasteCategory.STAFF, TagClassifier.clean(s), 1.0)
        for (c in feature.cv) add(TasteCategory.CV, TagClassifier.clean(c), 1.0)
        for (ch in feature.characters) add(TasteCategory.CHARACTER, TagClassifier.clean(ch), 1.0)

        return Featurized(byCat.mapValues { it.value.toMap() }, topicDevice)
    }

    /** 单大类相似度 `sim_c = Σ q·U^+ /(||U^+||+ε) - λ·Σ q·U^- /(||U^-||+ε)`。 */
    fun simCategory(catTags: Map<String, Double>, pref: CategoryPreference, lambda: Double): Double {
        if (catTags.isEmpty() || pref.isEmpty) return 0.0
        var pos = 0.0
        var neg = 0.0
        for ((k, q) in catTags) {
            pref.positive[k]?.let { pos += q * it }
            pref.negative[k]?.let { neg += q * it }
        }
        val posPart = pos / (pref.positiveL1 + TasteScoringParams.EPSILON)
        val negPart = neg / (pref.negativeL1 + TasteScoringParams.EPSILON)
        return posPart - lambda * negPart
    }

    /** 题材组合命中分 `sim_combo = Σ_{C⊆tags(x)} strength(C)`。 */
    fun simCombo(topicDeviceTags: Set<String>, combos: List<TopicCombo>): Double {
        if (topicDeviceTags.isEmpty() || combos.isEmpty()) return 0.0
        var s = 0.0
        for (combo in combos) {
            if (combo.tags.isNotEmpty() && topicDeviceTags.containsAll(combo.tags)) s += combo.strength
        }
        return s
    }

    /**
     * 十二维线性融合 raw `z(x)`（候选作品对画像的综合相似度，未校准）。
     * 候选无用户短评，故 [TasteCategory.COMMENT] 项恒 0（评论语义只在画像侧建模）。
     */
    fun rawScore(feature: WorkFeature, profile: AdvancedTasteProfile): Double =
        rawScore(featurize(feature), feature, profile)

    /** 复用已 featurize 的结果计算 raw z（批量评分时省一次 featurize）。 */
    fun rawScore(f: Featurized, feature: WorkFeature, profile: AdvancedTasteProfile): Double {
        var z = 0.0
        val lambda = TasteScoringParams.LAMBDA_NEGATIVE
        for (cat in TasteCategory.entries) {
            when (cat) {
                TasteCategory.COMBO ->
                    z += cat.defaultWeight * simCombo(f.topicDeviceTags, profile.combos)
                TasteCategory.COMMUNITY ->
                    z += cat.defaultWeight * TasteScoringParams.communityGuidance(
                        feature.bangumiScore?.toDouble(),
                        feature.bangumiVotes,
                    )
                TasteCategory.COMMENT, TasteCategory.NOISE -> Unit
                else -> {
                    val catTags = f.byCategory[cat] ?: continue
                    z += cat.defaultWeight * simCategory(catTags, profile.category(cat), lambda)
                }
            }
        }
        return z
    }
}
