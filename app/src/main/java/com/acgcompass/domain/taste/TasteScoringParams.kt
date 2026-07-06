package com.acgcompass.domain.taste

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign

/**
 * 口味匹配度 / 今晚看什么的**全部默认参数与核心公式**（最终版算法开发文档）。
 *
 * 纯 Kotlin、无 Android/IO 依赖，便于单元 / 属性测试与离线回归。所有数值均直接取自文档
 * 「默认参数、复现实验」与各层公式，集中于此以避免散落、便于统一调参与 AB 对比。算法版本号
 * [ALGORITHM_VERSION] 随重大公式/参数变动递增，用于画像缓存失效与回归判定。
 */
object TasteScoringParams {

    /** 算法版本号（画像缓存键 / 回归判定用；公式或默认参数重大变动时 +1）。 */
    const val ALGORITHM_VERSION: Int = 2

    // region 样本层（文档「口味匹配度最终算法 · 第一步」）

    /** 画像真实样本上限：50 部信息量最高作品（含全部最高分 / 最低分作品）。 */
    const val MAX_PROFILE_SAMPLES: Int = 50

    /** 时间半衰期（天）：约 18 个月，最近两年评分更重要但老作品不被抹掉。 */
    const val TIME_HALF_LIFE_DAYS: Double = 540.0

    /** 时间权重地板：再旧的评分也保留此最低话语权。 */
    const val TIME_WEIGHT_FLOOR: Double = 0.30

    /** 高分例外地板：10 分作品时间权重不低于此（防「人生锚点」被近因冲淡）。 */
    const val TIME_FLOOR_RATING_10: Double = 0.85

    /** 高分例外地板：9 分作品时间权重不低于此。 */
    const val TIME_FLOOR_RATING_9: Double = 0.70

    /** 极端分加权：评分 ∈ {1,2,9,10} 时样本权重乘此系数（其余 1.0）。 */
    const val EXTREME_WEIGHT: Double = 1.15

    /** 评论长度归一上限（字符）：超过即拿满评论长度加成。 */
    const val COMMENT_LEN_FULL: Int = 80

    /**
     * 显式评分 → 偏好值 `pref(r)`（文档「故意偏激」分段映射，服务「敢给敢拉开」）：
     * 10→+1.25, 9→+1.00, 8→+0.70, 7→+0.35, 6→+0.10, 5→-0.10, 4→-0.35, 3→-0.65, 2→-0.95, 1→-1.20。
     * 越界 / 缺失返回 0（中性，不参与正负向量）。
     */
    fun pref(rating: Int?): Double = when (rating) {
        10 -> 1.25
        9 -> 1.00
        8 -> 0.70
        7 -> 0.35
        6 -> 0.10
        5 -> -0.10
        4 -> -0.35
        3 -> -0.65
        2 -> -0.95
        1 -> -1.20
        else -> 0.0
    }

    /** 评分中心化斜率（每偏离用户均分 1 分的偏好增量）。 */
    const val PREF_SLOPE: Double = 0.35

    /** 评分中心化偏好绝对值上限（对齐绝对 [pref] 的 10 分强度）。 */
    const val PREF_MAX_ABS: Double = 1.25

    /**
     * 评分 → 偏好值（**用户均分中心化**版，RC.17）：以用户自己的平均分为中性点，
     * `pref = clip((rating − userMean)·[PREF_SLOPE], ±[PREF_MAX_ABS])`。
     *
     * 根因（RC.17，真实数据 LOO 盲测 Spearman≈0）：绝对 [pref] 以 5.5 为中性点，对「均分偏高（如 7.0）」
     * 的用户，6–7 分（其实是平庸片）仍记正偏好 → 用户**看得多但只给中等分**的题材（如某长番系列）
     * 累积成强正向、主导画像，而**看得少却给高分**的挚爱题材被淹没 → 预测分与真实评分排序几乎不相关。
     * 中心化后 6–7 分≈0 贡献，只有高于自己均分的作品才推正偏好、低于的推负偏好，画像回归「相对偏爱」。
     */
    fun prefCentered(rating: Int?, userMean: Double): Double {
        if (rating == null) return 0.0
        return ((rating - userMean) * PREF_SLOPE).coerceIn(-PREF_MAX_ABS, PREF_MAX_ABS)
    }

    /**
     * 时间权重 `w_time = 0.3 + 0.7·2^(-Δdays/540)`，再对 10/9 分施加高分例外地板。
     * @param ageDays 该评分距「最近一条评分」的天数（< 0 视为 0）。
     */
    fun timeWeight(ageDays: Double, rating: Int?): Double {
        val base = TIME_WEIGHT_FLOOR + 0.7 * Math.pow(2.0, -max(0.0, ageDays) / TIME_HALF_LIFE_DAYS)
        val floored = when (rating) {
            10 -> max(base, TIME_FLOOR_RATING_10)
            9 -> max(base, TIME_FLOOR_RATING_9)
            else -> base
        }
        return floored.coerceIn(0.0, 1.0)
    }

    /** 极端分加权：r ∈ {1,2,9,10} → [EXTREME_WEIGHT]，否则 1.0。 */
    fun extremeWeight(rating: Int?): Double =
        if (rating != null && rating in intArrayOf(1, 2, 9, 10)) EXTREME_WEIGHT else 1.0

    /** 评论置信权重：`1 + 0.1·I(comment非空) + 0.1·min(len,80)/80`（无评论自然退化为 1.0）。 */
    fun commentWeight(comment: String?): Double {
        val len = comment?.trim()?.length ?: 0
        if (len == 0) return 1.0
        return 1.0 + 0.1 + 0.1 * min(len, COMMENT_LEN_FULL).toDouble() / COMMENT_LEN_FULL
    }

    /** 单样本最终权重 `w_i = w_time · w_extreme · w_comment`。 */
    fun sampleWeight(ageDays: Double, rating: Int?, comment: String?): Double =
        timeWeight(ageDays, rating) * extremeWeight(rating) * commentWeight(comment)

    // endregion

    // region 画像层（文档「第二步 / 第三步」）

    /**
     * subject 内标签强度归一 + 对数压缩：`q_xk = log(1+count_xk) / log(1+maxCount_x)`。
     * `maxCount` ≤ 0 时返回 0（该作品无有效标签计数）。
     */
    fun tagStrength(count: Int, maxCountInSubject: Int): Double {
        if (maxCountInSubject <= 0) return 0.0
        return ln(1.0 + count.coerceAtLeast(0)) / ln(1.0 + maxCountInSubject)
    }

    /** 题材组合挖掘：最小支持度（低于此视为偶然命中，丢弃）。 */
    const val COMBO_MIN_SUPPORT: Double = 1.8

    /** 题材组合挖掘：最大阶数（只做二元 / 三元，不做四元以上）。 */
    const val COMBO_MAX_ORDER: Int = 3

    /** 题材组合挖掘：组合至少出现在多少部正向作品中才保留。 */
    const val COMBO_MIN_POSITIVE_WORKS: Int = 2

    /** 组合强度 `strength(C) = signed_support(C) / |C|^0.3` 的阶数惩罚指数。 */
    const val COMBO_ORDER_PENALTY_EXP: Double = 0.3

    /** 组合命中强度：`strength = signed_support / size^0.3`（size 为组合元素个数）。 */
    fun comboStrength(signedSupport: Double, comboSize: Int): Double =
        signedSupport / Math.pow(comboSize.coerceAtLeast(1).toDouble(), COMBO_ORDER_PENALTY_EXP)

    // endregion

    // region 候选层（文档「第四步」）

    /** 各类相似度负向惩罚系数 λ_c（正向减负向时的负向权重）。 */
    const val LAMBDA_NEGATIVE: Double = 0.75

    /** 防 0 除小量。 */
    const val EPSILON: Double = 1e-6

    /**
     * 社区评分弱引导：`clip((score-6.5)/2, -1, 1) · clip(log(1+votes)/log(20000), 0.3, 1)`。
     * 仅以 [TasteCategory.COMMUNITY] 的权重（0.03）进入总分，绝不主导个性化。
     */
    fun communityGuidance(bangumiScore10: Double?, votes: Int?): Double {
        if (bangumiScore10 == null || bangumiScore10 <= 0.0) return 0.0
        val scorePart = ((bangumiScore10 - 6.5) / 2.0).coerceIn(-1.0, 1.0)
        val v = votes?.coerceAtLeast(0) ?: 0
        val votePart = (ln(1.0 + v) / ln(20000.0)).coerceIn(0.3, 1.0)
        return scorePart * votePart
    }

    /** 十二维线性融合权重（= 各 [TasteCategory.defaultWeight]，集中此处便于调参）。 */
    val FUSION_WEIGHTS: Map<TasteCategory, Double> =
        TasteCategory.entries
            .filter { it != TasteCategory.NOISE }
            .associateWith { it.defaultWeight.toDouble() }

    // endregion

    // region 校准层（文档「第五步」）

    /** 拟合 isotonic 校准所需的最小显式评分样本数；不足时退化为温度化 logistic。 */
    const val ISOTONIC_MIN_SAMPLES: Int = 25

    /** 温度化 logistic 的温度地板 `τ_u = max(0.18, std(z_train))`（冷启动 / logistic 兜底用）。 */
    const val LOGISTIC_TAU_FLOOR: Double = 0.18

    /**
     * 校准温度地板（RC.16）：候选池校准下 combo 已归一化，rawZ 落在 ~[-0.3, 1] 的小尺度，
     * [LOGISTIC_TAU_FLOOR]=0.18 会过大而压平区分度；此地板仅防 `std→0` 退化，实际温度以候选池 std 为准。
     */
    const val CALIBRATION_TAU_FLOOR: Double = 0.05

    /** 分数拉开：基准分。 */
    const val SPREAD_BASE: Double = 50.0

    /** 分数拉开：最大偏移（最终分落在 [5,95]）。 */
    const val SPREAD_MAX_OFFSET: Double = 45.0

    /** 分数拉开：放大系数（>1，敢拉开）。 */
    const val SPREAD_GAIN: Double = 1.18

    /** 已评分同作偏置：`bias = 0.12·pref(r_x)`，加在校准前 raw 分上。 */
    const val RATED_BIAS_COEFF: Double = 0.12

    /** 温度化 logistic：`p = σ((z-μ)/τ)`。 */
    fun logistic(z: Double, mu: Double, tau: Double): Double {
        val t = if (tau <= 0.0) LOGISTIC_TAU_FLOOR else tau
        return 1.0 / (1.0 + exp(-(z - mu) / t))
    }

    /** 分数拉开：`50 + sign(100p-50)·min(45, 1.18·|100p-50|)`，结果四舍五入到 [5,95] 整数前的浮点。 */
    fun spread(probability: Double): Double {
        val centered = 100.0 * probability - SPREAD_BASE
        return SPREAD_BASE + sign(centered) * min(SPREAD_MAX_OFFSET, SPREAD_GAIN * abs(centered))
    }

    /** 已评分同作偏置项 `0.12·pref(r)`（保留兼容；显示锚定改用 [ratedAnchorProbability] 融合）。 */
    fun ratedBias(rating: Int?): Double = RATED_BIAS_COEFF * pref(rating)

    /**
     * 已评分作品**显示锚定权重**（C 方案）：显式评分为主锚、长期画像做修正。
     * 文档承诺「你明确打 10 分的作品稳定拉到 90+、命运石之门/寒蝉稳在 80+」，而 `0.12·pref` 偏置对
     * 「与画像整体重合少的小众挚爱」（rawZ≪μ）太弱、拉不动，实测反而塌到个位数——故对已评分作品
     * 以本权重把显示概率主要锚到用户自己的评分上（仍保留画像修正空间，不生硬顶到 100%）。
     */
    const val RATED_ANCHOR_WEIGHT: Double = 0.85

    /**
     * 显式评分 → 锚定概率 `p_anchor`（经 [spread] 后约：10→95 / 9→90 / 8→80 / 7→64 / 6→54 /
     * 5→44 / 4→32 / 3→20 / 2→10 / 1→5），达成文档「10 分→90+、低分→个位」的目标。
     * 越界 / 缺失返回 0.5（中性，不锚定）。
     */
    fun ratedAnchorProbability(rating: Int?): Double = when (rating) {
        10 -> 0.95
        9 -> 0.88
        8 -> 0.76
        7 -> 0.62
        6 -> 0.53
        5 -> 0.45
        4 -> 0.35
        3 -> 0.25
        2 -> 0.14
        1 -> 0.07
        else -> 0.5
    }

    /**
     * 置信度（独立输出，不混进主分）：
     * `clip(0.25 + 0.20·log(1+n)/log(51) + 0.20·cov_tag + 0.15·cov_staff + 0.10·cov_comment + 0.10·cov_time, 0, 1)`。
     */
    fun confidence(
        realSampleCount: Int,
        tagCoverage: Double,
        staffCoverage: Double,
        commentCoverage: Double,
        timeCoverage: Double,
    ): Double {
        val n = realSampleCount.coerceAtLeast(0)
        val raw = 0.25 +
            0.20 * (ln(1.0 + n) / ln(51.0)) +
            0.20 * tagCoverage.coerceIn(0.0, 1.0) +
            0.15 * staffCoverage.coerceIn(0.0, 1.0) +
            0.10 * commentCoverage.coerceIn(0.0, 1.0) +
            0.10 * timeCoverage.coerceIn(0.0, 1.0)
        return raw.coerceIn(0.0, 1.0)
    }

    // endregion

    // region 今晚看什么（文档「今晚看什么最终算法」）

    /** 今晚推荐默认最低 Bangumi 分（硬过滤）。 */
    const val TONIGHT_MIN_BANGUMI_SCORE: Double = 6.0

    /** 今晚推荐默认最低口味匹配度（硬过滤，0–100）。 */
    const val TONIGHT_MIN_TASTE_SCORE: Int = 55

    /** 时间拟合度硬过滤下限。 */
    const val TONIGHT_MIN_TIME_FIT: Double = 0.55

    /** 重复推荐冷却天数。 */
    const val TONIGHT_REPEAT_COOLDOWN_DAYS: Int = 14

    /** 单系列默认最多保留进入末轮的部数。 */
    const val TONIGHT_MAX_PER_FRANCHISE: Int = 1

    /** runtime 估计兜底（分钟 / 集 或 单部）：TV 每集、SP、OVA、剧场版。 */
    const val RUNTIME_TV_PER_EP: Int = 24
    const val RUNTIME_SP: Int = 45
    const val RUNTIME_OVA: Int = 30
    const val RUNTIME_MOVIE: Int = 90

    /** 时间拟合度 `exp(-|need-user| / max(25, 0.35·user))`。 */
    fun timeFit(needMinutes: Int, userMinutes: Int): Double {
        if (userMinutes <= 0) return 0.0
        val denom = max(25.0, 0.35 * userMinutes)
        return exp(-abs(needMinutes - userMinutes).toDouble() / denom)
    }

    /** 多样性重排 MMR 系数 λ（越大越偏相关度、越小越偏多样性）。 */
    const val MMR_LAMBDA: Double = 0.78

    /** 今晚分各项权重（`tonightRaw` 线性融合）。 */
    const val TONIGHT_W_TASTE: Double = 0.42
    const val TONIGHT_W_INTENT_TAG: Double = 0.18
    const val TONIGHT_W_TIME_FIT: Double = 0.10
    const val TONIGHT_W_COMMUNITY: Double = 0.08
    const val TONIGHT_W_NOVELTY: Double = 0.08
    const val TONIGHT_W_COMPLETION_EASE: Double = 0.07
    const val TONIGHT_W_FRESHNESS: Double = 0.07
    const val TONIGHT_W_REPEAT_PENALTY: Double = 0.20
    const val TONIGHT_W_FRANCHISE_PENALTY: Double = 0.12
    const val TONIGHT_W_EXAM_RISK: Double = 0.08

    // endregion
}
