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

    /**
     * 把一部作品的社区标签（带计数）+ 结构化 staff/CV/角色 特征化为各大类的 tag→q。
     *
     * N3：[overrides]（清洗后标签 → 维度）为 AI 分维分类缓存，仅作用于本地规则兜底为题材的未知标签（见
     * [TagClassifier.classify]）。缺省空表 → 行为与之前完全一致，AI 未配置 / 未分类时全回退本地。
     */
    fun featurize(feature: WorkFeature, overrides: Map<String, TasteCategory> = emptyMap()): Featurized {
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
            val cat = TagClassifier.classify(tc.name, ctx, overrides)
            if (cat == TasteCategory.NOISE) continue
            val q = TasteScoringParams.tagStrength(tc.count, maxCount)
            // #9：时间标签落 5 年桶（2014年4月/2013年10月 → 2010-2014），使同代不同季度聚合成年代偏好。
            val key = if (cat == TasteCategory.TIME) TagClassifier.timeBucket(cleaned) else cleaned
            add(cat, key, q)
            if (cat == TasteCategory.TOPIC || cat == TasteCategory.DEVICE) topicDevice += key
        }
        // 结构化真实名按「出现即满强度」并入对应维度（无标注人数，q=1.0）。
        for (s in feature.staff) add(TasteCategory.STAFF, TagClassifier.clean(s), 1.0)
        for (c in feature.cv) add(TasteCategory.CV, TagClassifier.clean(c), 1.0)
        for (ch in feature.characters) add(TasteCategory.CHARACTER, TagClassifier.clean(ch), 1.0)

        // #10：实体维度（STAFF/CV/CHARACTER）为身份权威——若同一名字也被社区标签误分进内容维度
        // （TOPIC/DEVICE/XP/MEME/SOURCE/TIME），从内容维度剔除，消除「同名既在题材又在制作阵容」的双重
        // 归类与重复计分。以结构化名为准（它们来自作品真实 staff/角色/CV 字段，比社区标签可信）。
        dedupeEntityNamesFromContent(byCat, topicDevice)

        return Featurized(byCat.mapValues { it.value.toMap() }, topicDevice)
    }

    /** #10：参与去重的内容维度（人名一旦落在这些维度且同名于结构化实体，即视为误分类，剔除）。 */
    private val CONTENT_CATEGORIES: List<TasteCategory> = listOf(
        TasteCategory.TOPIC, TasteCategory.DEVICE, TasteCategory.XP,
        TasteCategory.MEME, TasteCategory.SOURCE, TasteCategory.TIME,
    )

    /**
     * #10：以结构化实体维度（STAFF/CV/CHARACTER）为权威，从内容维度剔除同名键，消除双重归类。
     * 同步把被剔除的键移出 [topicDevice]，避免人名混入题材组合命中。就地修改 [byCat] / [topicDevice]。
     */
    private fun dedupeEntityNamesFromContent(
        byCat: HashMap<TasteCategory, HashMap<String, Double>>,
        topicDevice: HashSet<String>,
    ) {
        val entityNames = buildSet {
            addAll(byCat[TasteCategory.STAFF]?.keys.orEmpty())
            addAll(byCat[TasteCategory.CV]?.keys.orEmpty())
            addAll(byCat[TasteCategory.CHARACTER]?.keys.orEmpty())
        }
        if (entityNames.isEmpty()) return
        for (cat in CONTENT_CATEGORIES) {
            val m = byCat[cat] ?: continue
            for (name in entityNames) {
                if (m.remove(name) != null && (cat == TasteCategory.TOPIC || cat == TasteCategory.DEVICE)) {
                    topicDevice -= name
                }
            }
            if (m.isEmpty()) byCat.remove(cat)
        }
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

    /**
     * 题材组合命中分（RC.16 归一化）：`sim_combo = Σ_{命中} strength / (Σ_{全部} strength + ε) ∈ [0,1]`。
     *
     * 修复前用「命中组合 strength 之和」的**未归一化绝对量**：`strength=signed_support/|C|^0.3` 随样本数
     * 线性膨胀，大样本画像下 combo 项可达 7+，彻底压倒被 `||U^+||` 归一到 ~0.05 的单标签项 → rawZ 几乎
     * 只由「是否命中画像既有热门组合」决定：续作 / 同题材扎堆番爆高，只命中单个题材的番与反口味番同塌到
     * 十几分（与实测「随便搜感兴趣的番都十几二十分、只有续作 / 高重合才高」完全吻合）。归一化为「命中占
     * 画像组合偏好的比例」后与单标签 posPart 同尺度，恢复十二维线性融合的平衡。
     */
    fun simCombo(topicDeviceTags: Set<String>, combos: List<TopicCombo>): Double {
        if (topicDeviceTags.isEmpty() || combos.isEmpty()) return 0.0
        var hit = 0.0
        var total = 0.0
        for (combo in combos) {
            if (combo.tags.isEmpty()) continue
            total += combo.strength
            if (topicDeviceTags.containsAll(combo.tags)) hit += combo.strength
        }
        return if (total > 0.0) hit / (total + TasteScoringParams.EPSILON) else 0.0
    }

    /**
     * 十二维线性融合 raw `z(x)`（候选作品对画像的综合相似度，未校准）。
     * 候选无用户短评，故 [TasteCategory.COMMENT] 项恒 0（评论语义只在画像侧建模）。
     */
    fun rawScore(
        feature: WorkFeature,
        profile: AdvancedTasteProfile,
        overrides: Map<String, TasteCategory> = emptyMap(),
    ): Double = rawScore(featurize(feature, overrides), feature, profile)

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
