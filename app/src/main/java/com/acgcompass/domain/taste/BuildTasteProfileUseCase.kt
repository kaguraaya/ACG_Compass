package com.acgcompass.domain.taste

import javax.inject.Inject
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

    operator fun invoke(samples: List<TasteSample>, now: Long): AdvancedTasteProfile {
        val rated = samples.filter { it.rating != null }
        if (rated.isEmpty()) {
            return AdvancedTasteProfile(generatedAt = now)
        }

        val selected = selectSamples(rated)
        val referenceTime = selected.maxOfOrNull { it.updatedAtMillis ?: 0L }?.takeIf { it > 0L } ?: now

        // 各大类 tag/name → 累加偏好值（正负混合，后续拆分）。
        val acc = HashMap<TasteCategory, HashMap<String, Double>>()
        fun accumulate(cat: TasteCategory, key: String, delta: Double) {
            if (key.isBlank() || delta == 0.0) return
            val m = acc.getOrPut(cat) { HashMap() }
            m[key] = (m[key] ?: 0.0) + delta
        }

        // 组合挖掘累加器。
        val comboSupport = HashMap<Set<String>, Double>()
        val comboSigned = HashMap<Set<String>, Double>()
        val comboPosWorks = HashMap<Set<String>, Int>()

        var tagCovered = 0
        var staffCovered = 0
        var commentCovered = 0
        var timeCovered = 0

        for (s in selected) {
            val rating = s.rating ?: continue
            val pref = TasteScoringParams.pref(rating)
            val ageDays = ageDaysOf(referenceTime, s.updatedAtMillis)
            val w = TasteScoringParams.sampleWeight(ageDays, rating, s.comment)
            val contribution = w * pref

            val f = TasteRawScorer.featurize(s.feature)
            if (f.byCategory.isNotEmpty()) tagCovered++
            if (s.feature.staff.isNotEmpty()) staffCovered++
            if (!s.comment.isNullOrBlank()) commentCovered++
            if ((s.updatedAtMillis ?: 0L) > 0L) timeCovered++

            for ((cat, tagMap) in f.byCategory) {
                for ((k, q) in tagMap) accumulate(cat, k, contribution * q)
            }
            // 短评关键词 → COMMENT 维度（q=1）。
            for (kw in CommentKeywords.extract(s.comment)) {
                accumulate(TasteCategory.COMMENT, kw, contribution)
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

        val categories = acc.mapValues { (_, m) -> toCategoryPreference(m) }
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
        val calibration = calibrate(selected, coreProfile)

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

    private fun toCategoryPreference(merged: Map<String, Double>): CategoryPreference {
        val positive = HashMap<String, Double>()
        val negative = HashMap<String, Double>()
        for ((k, v) in merged) {
            when {
                v > 0.0 -> positive[k] = v
                v < 0.0 -> negative[k] = -v
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

    /** 温度化 logistic 校准：`μ=median(z_train)`、`τ=max(0.18, std(z_train))`。 */
    private fun calibrate(samples: List<TasteSample>, core: AdvancedTasteProfile): TasteCalibration {
        val zs = samples.map { TasteRawScorer.rawScore(it.feature, core) }
        if (zs.isEmpty()) return TasteCalibration()
        val mu = median(zs)
        val mean = zs.average()
        val variance = if (zs.size > 1) zs.sumOf { (it - mean) * (it - mean) } / (zs.size - 1) else 0.0
        val tau = maxOf(TasteScoringParams.LOGISTIC_TAU_FLOOR, sqrt(variance))
        return TasteCalibration(mu = mu, tau = tau, isotonicPoints = null)
    }

    private fun median(values: List<Double>): Double {
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2.0
    }

    private fun ageDaysOf(referenceTime: Long, updatedAt: Long?): Double {
        if (updatedAt == null || updatedAt <= 0L) return 0.0
        val diffMs = (referenceTime - updatedAt).coerceAtLeast(0L)
        return diffMs.toDouble() / MILLIS_PER_DAY
    }

    private companion object {
        const val MILLIS_PER_DAY: Double = 24.0 * 3600 * 1000
    }
}
