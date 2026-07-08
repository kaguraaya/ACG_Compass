package com.acgcompass.domain.taste

import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * 画像构建单样本输入：用户对某作品的**评分 / 短评 / 评定时间** × 该作品的 [WorkFeature]。
 * [rating] 为 null（仅想看 / 在看未评分）时 `pref=0`，不贡献正负向量、不参与组合。
 */
data class TasteSample(
    val subjectId: String,
    val rating: Int?,
    val comment: String?,
    val updatedAtMillis: Long?,
    val feature: WorkFeature,
)

/**
 * 由「用户收藏 × 作品特征」构建 [AdvancedTasteProfile]（最终版算法「画像层」全流程），纯函数可单测：
 *
 * 1. **样本选择**：最多 50；强制纳入「最高分(≥9) / 最低分(≤2) / 有短评」的全部作品，其余按时间近因补齐。
 * 2. **样本加权**：`w_i = w_time·w_extreme·w_comment`；偏好 `pref(r_i)`（[TasteScoringParams.pref]）。
 * 3. **逐标签累加**：`U_c(k) += w_i·pref_i·q_ik`（社区标签 q_xk 对数压缩；结构化 staff/CV/角色 q=1）。
 *    短评经轻量关键词抽取并入 [TasteCategory.COMMENT]。
 * 4. **正负拆分**：`U_c(k)>0 → U_c^+`，`<0 → U_c^-`。
 * 5. **组合挖掘**：正向作品的题材+装置标签生成 2/3 元组合，过支持度 / 正向作品数门槛后计 `strength`。
 * 6. **校准**：对训练样本求 raw `z` 分布，得温度化 logistic 的 `μ=median(z)`、`τ=max(0.18, std(z))`。
 */
class BuildTasteProfileUseCase @Inject constructor() {

    operator fun invoke(
        samples: List<TasteSample>,
        now: Long,
        calibrationPool: List<WorkFeature> = emptyList(),
        tagOverrides: Map<String, TasteCategory> = emptyMap(),
    ): AdvancedTasteProfile {
        val rated = samples.filter { it.rating != null }
        if (rated.isEmpty()) {
            return AdvancedTasteProfile(generatedAt = now)
        }

        val selected = selectSamples(rated)
        val referenceTime = selected.maxOfOrNull { it.updatedAtMillis ?: 0L }?.takeIf { it > 0L } ?: now
        // RC.17：评分偏好以用户均分为中性点（取全体已评分样本均分），修「看得多≠偏爱」导致的画像主导偏差。
        val userMean = rated.mapNotNull { it.rating }.average()

        // 各大类 tag/name → 累加偏好值（正负混合，后续拆分）。
        val acc = HashMap<TasteCategory, HashMap<String, Double>>()
        fun accumulate(cat: TasteCategory, key: String, delta: Double) {
            if (key.isBlank() || delta == 0.0) return
            val m = acc.getOrPut(cat) { HashMap() }
            m[key] = (m[key] ?: 0.0) + delta
        }

        // #11：每个(维度, 标签)出现在**多少部不同已评分作品**中的支持度计数——用于过滤低支持度的偶然负向标签。
        val tagSupport = HashMap<TasteCategory, HashMap<String, Int>>()
        fun countSupport(cat: TasteCategory, key: String) {
            if (key.isBlank()) return
            val m = tagSupport.getOrPut(cat) { HashMap() }
            m[key] = (m[key] ?: 0) + 1
        }

        // 组合挖掘累加器。
        val comboSupport = HashMap<Set<String>, Double>()
        val comboSigned = HashMap<Set<String>, Double>()
        val comboPosWorks = HashMap<Set<String>, Int>()

        var tagCovered = 0
        var staffCovered = 0
        var commentCovered = 0
        var timeCovered = 0

        // 校准去偏（RC.15）：留存每样本的「特征化 + 有符号贡献」，供 calibrate 做**留一式** raw z，
        // 消除「训练样本自身贡献画像 U_c^+ → 其 z 被抬高 → μ 偏高 → 未评分作品普遍塌到十几分」的样本内偏置。
        val perSample = ArrayList<CalibSample>(selected.size)

        for (s in selected) {
            val rating = s.rating ?: continue
            val pref = TasteScoringParams.prefCentered(rating, userMean)
            val ageDays = ageDaysOf(referenceTime, s.updatedAtMillis)
            val w = TasteScoringParams.sampleWeight(ageDays, rating, s.comment)
            val contribution = w * pref

            val f = TasteRawScorer.featurize(s.feature, tagOverrides)
            perSample += CalibSample(s.feature, f, contribution)
            if (f.byCategory.isNotEmpty()) tagCovered++
            if (s.feature.staff.isNotEmpty()) staffCovered++
            if (!s.comment.isNullOrBlank()) commentCovered++
            if ((s.updatedAtMillis ?: 0L) > 0L) timeCovered++

            for ((cat, tagMap) in f.byCategory) {
                for ((k, q) in tagMap) {
                    accumulate(cat, k, contribution * q)
                    // 该标签在本部作品出现 → 支持度 +1（每部至多计一次，tagMap 的 key 已在作品内去重）。
                    countSupport(cat, k)
                }
            }
            // 短评关键词 → COMMENT 维度（q=1）。
            for (kw in CommentKeywords.extract(s.comment).toSet()) {
                accumulate(TasteCategory.COMMENT, kw, contribution)
                countSupport(TasteCategory.COMMENT, kw)
            }

            // 组合挖掘只看正向作品（pref>0）。
            if (pref > 0.0 && f.topicDeviceTags.size >= 2) {
                for (combo in enumerateCombos(f.topicDeviceTags)) {
                    comboSupport[combo] = (comboSupport[combo] ?: 0.0) + w
                    comboSigned[combo] = (comboSigned[combo] ?: 0.0) + contribution
                    comboPosWorks[combo] = (comboPosWorks[combo] ?: 0) + 1
                }
            }
        }

        val categories = acc.mapValues { (cat, m) -> toCategoryPreference(m, tagSupport[cat]) }
            .filterValues { !it.isEmpty }

        val combos = comboSupport.entries.mapNotNull { (tags, support) ->
            val signed = comboSigned[tags] ?: 0.0
            val posWorks = comboPosWorks[tags] ?: 0
            if (support < TasteScoringParams.COMBO_MIN_SUPPORT) return@mapNotNull null
            if (signed <= 0.0) return@mapNotNull null
            if (posWorks < TasteScoringParams.COMBO_MIN_POSITIVE_WORKS) return@mapNotNull null
            TopicCombo(tags, TasteScoringParams.comboStrength(signed, tags.size))
        }.sortedByDescending { it.strength }

        val coreProfile = AdvancedTasteProfile(categories = categories, combos = combos)
        val calibration = calibrate(perSample, acc, tagSupport, categories, coreProfile, calibrationPool, tagOverrides)

        val n = selected.size
        val coverage = TasteCoverage(
            tag = tagCovered.toDouble() / n,
            staff = staffCovered.toDouble() / n,
            comment = commentCovered.toDouble() / n,
            time = timeCovered.toDouble() / n,
        )

        return AdvancedTasteProfile(
            categories = categories,
            combos = combos,
            ratedSubjectScores = rated.mapNotNull { s -> s.rating?.let { s.subjectId to it } }.toMap(),
            calibration = calibration,
            coverage = coverage,
            sampleCount = n,
            userAvgRating = rated.mapNotNull { it.rating }.average(),
            generatedAt = now,
        )
    }

    /** 样本选择：强制保留高分/低分/有短评，其余按近因补齐到 [TasteScoringParams.MAX_PROFILE_SAMPLES]。 */
    private fun selectSamples(rated: List<TasteSample>): List<TasteSample> {
        val forced = rated.filter { s ->
            val r = s.rating ?: return@filter false
            r >= 9 || r <= 2 || !s.comment.isNullOrBlank()
        }
        val forcedIds = forced.mapTo(HashSet()) { it.subjectId }
        val rest = rated.filterNot { it.subjectId in forcedIds }
            .sortedByDescending { it.updatedAtMillis ?: 0L }
        return (forced + rest).distinctBy { it.subjectId }.take(TasteScoringParams.MAX_PROFILE_SAMPLES)
    }

    /**
     * 把有符号累加的偏好 [merged] 拆成正 / 负向量。
     *
     * #11：负向量需通过**最小支持度**过滤——某标签只有在 ≥ [TasteScoringParams.MIN_NEGATIVE_SUPPORT] 部
     * 已评分作品中出现，才允许进入负向量。这样滤除「只看过 1 部、评分略低于均分」的稀疏题材（如 百合 /
     * 美食）被均分中心化偶然打成负、反而拉低匹配的问题；正向量不设门槛（正向偏爱不需要多部佐证也可信）。
     * [support] 为 null（如无支持度信息）时不过滤，保持向后兼容。
     */
    private fun toCategoryPreference(
        merged: Map<String, Double>,
        support: Map<String, Int>? = null,
    ): CategoryPreference {
        val positive = HashMap<String, Double>()
        val negative = HashMap<String, Double>()
        for ((k, v) in merged) {
            when {
                v > 0.0 -> positive[k] = v
                v < 0.0 -> {
                    val supp = support?.get(k) ?: Int.MAX_VALUE
                    if (supp >= TasteScoringParams.MIN_NEGATIVE_SUPPORT) negative[k] = -v
                }
            }
        }
        return CategoryPreference(positive, negative)
    }

    /** 枚举 2 元与 3 元组合（题材+装置标签，已去重排序保证集合键稳定）。 */
    private fun enumerateCombos(tags: Set<String>): List<Set<String>> {
        val list = tags.toList()
        if (list.size < 2) return emptyList()
        val out = ArrayList<Set<String>>()
        for (i in list.indices) {
            for (j in i + 1 until list.size) {
                out += setOf(list[i], list[j])
                if (TasteScoringParams.COMBO_MAX_ORDER >= 3) {
                    for (k in j + 1 until list.size) out += setOf(list[i], list[j], list[k])
                }
            }
        }
        return out
    }

    /** 单样本校准输入：特征化结果 + 该样本对画像的**有符号贡献** `w·pref`（留一去偏用）。 */
    private data class CalibSample(
        val feature: WorkFeature,
        val featurized: TasteRawScorer.Featurized,
        val contribution: Double,
    )

    /**
     * 温度化 logistic 校准（RC.16 **候选池校准**版）：`μ=median(z_pool)`、`τ=max(0.05, std(z_pool))`。
     *
     * 根因（RC.16）：`simCombo` 归一化后已消除「combo 项爆炸主导 rawZ」，但若仍用训练样本 z 定中心，
     * 训练样本因自身贡献画像（自我重合）rawZ 系统性高于外部作品，μ 偏高 → 未评分作品仍普遍偏低。
     * 改用**未评分候选池**（用户会去搜索 / 浏览的外部作品全体，取自 `work_features` 缓存）的 rawZ 分布
     * 定 μ/τ：池中位即「典型未评分作品」的中心，据此校准后未评分作品自然落在 50 分附近、按契合度拉开
     * （完美命中→90+、部分契合→中段、中性→50 下、反口味→低分）。已评分作品另走显式评分锚定，不受影响。
     * 池不足 [CALIB_MIN_POOL] 时回退训练样本留一 z（小数据兜底，无更好参照）。
     */
    private fun calibrate(
        perSample: List<CalibSample>,
        acc: Map<TasteCategory, Map<String, Double>>,
        tagSupport: Map<TasteCategory, Map<String, Int>>,
        baseCategories: Map<TasteCategory, CategoryPreference>,
        core: AdvancedTasteProfile,
        pool: List<WorkFeature>,
        tagOverrides: Map<String, TasteCategory> = emptyMap(),
    ): TasteCalibration {
        val poolZs = if (pool.size >= CALIB_MIN_POOL) {
            pool.mapNotNull { f -> runCatching { TasteRawScorer.rawScore(f, core, tagOverrides) }.getOrNull() }
        } else {
            emptyList()
        }
        val usePool = poolZs.size >= CALIB_MIN_POOL
        val zs = when {
            usePool -> poolZs
            perSample.isNotEmpty() -> perSample.map { cs ->
                TasteRawScorer.rawScore(cs.featurized, cs.feature, leaveOneOutProfile(cs, acc, tagSupport, baseCategories, core))
            }
            else -> return TasteCalibration()
        }
        // 池校准（RC.16）以未评分候选池中位为 μ；池不足回退训练样本 LOO z 时改用低分位数：已评分作品是
        // 用户「选择看过并评分」的幸存者，其 rawZ 分布系统性高于随机未评分作品，用 median 会令 μ 偏高
        // → 未评分作品普遍塌到十几分（用户实测症状）。取低分位近似未评分中心，缓解池不足时的整体压低。
        val mu = quantile(zs, if (usePool) 0.5 else CALIB_FALLBACK_MU_QUANTILE)
        val mean = zs.average()
        val variance = if (zs.size > 1) zs.sumOf { (it - mean) * (it - mean) } / (zs.size - 1) else 0.0
        val tau = maxOf(TasteScoringParams.CALIBRATION_TAU_FLOOR, sqrt(variance))
        return TasteCalibration(mu = mu, tau = tau, isotonicPoints = null)
    }

    /**
     * 构造「扣除样本 [cs] 自身贡献」的留一画像：仅重算 [cs] 命中的单标签维度（其余维度沿用全局）。
     * 题材组合因需 ≥2 正向作品支撑、单样本影响小，近似沿用全局 combos；社区口碑项不依赖画像，无需去偏。
     */
    private fun leaveOneOutProfile(
        cs: CalibSample,
        acc: Map<TasteCategory, Map<String, Double>>,
        tagSupport: Map<TasteCategory, Map<String, Int>>,
        baseCategories: Map<TasteCategory, CategoryPreference>,
        core: AdvancedTasteProfile,
    ): AdvancedTasteProfile {
        if (cs.contribution == 0.0) return core
        val overridden = HashMap(baseCategories)
        for ((cat, tagMap) in cs.featurized.byCategory) {
            val accCat = acc[cat] ?: continue
            val looAccCat = HashMap(accCat)
            for ((k, q) in tagMap) {
                val newVal = (looAccCat[k] ?: 0.0) - cs.contribution * q
                if (abs(newVal) < 1e-12) looAccCat.remove(k) else looAccCat[k] = newVal
            }
            val pref = toCategoryPreference(looAccCat, tagSupport[cat])
            if (pref.isEmpty) overridden.remove(cat) else overridden[cat] = pref
        }
        return core.copy(categories = overridden)
    }

    private fun median(values: List<Double>): Double = quantile(values, 0.5)

    /** 线性插值分位数（q∈[0,1]）；空表返回 0，单元素返回该值。 */
    private fun quantile(values: List<Double>, q: Double): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        if (sorted.size == 1) return sorted[0]
        val pos = q.coerceIn(0.0, 1.0) * (sorted.size - 1)
        val lo = floor(pos).toInt()
        val hi = ceil(pos).toInt()
        if (lo == hi) return sorted[lo]
        val frac = pos - lo
        return sorted[lo] * (1 - frac) + sorted[hi] * frac
    }

    private fun ageDaysOf(referenceTime: Long, updatedAt: Long?): Double {
        if (updatedAt == null || updatedAt <= 0L) return 0.0
        val diffMs = (referenceTime - updatedAt).coerceAtLeast(0L)
        return diffMs.toDouble() / MILLIS_PER_DAY
    }

    private companion object {
        const val MILLIS_PER_DAY: Double = 24.0 * 3600 * 1000

        /** 候选池校准最小样本数：池 ≥ 此值才用其 rawZ 分布定 μ/τ，否则回退训练样本留一 z。 */
        const val CALIB_MIN_POOL: Int = 20

        /** 池不足回退时 μ 取已评分 LOO z 的低分位数（补偿幸存者偏差：未评分作品 rawZ 中心低于已评分作品）。 */
        const val CALIB_FALLBACK_MU_QUANTILE: Double = 0.4
    }
}
