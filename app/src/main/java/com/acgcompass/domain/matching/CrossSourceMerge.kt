package com.acgcompass.domain.matching

import com.acgcompass.domain.model.SourceId
import com.acgcompass.domain.model.Work
import com.acgcompass.domain.model.WorkMatch

/**
 * 跨源合并（通用算法，R20/R42）。纯函数，无任何针对具体作品（如 9-nine / 2.5）的硬编码特判。
 *
 * 判定两个作品是否「同一作品」综合多字段信号：
 * - **标题归一化**：复用 [normalizeTitle]（NFKC 全半角统一、大小写、去标点/连字符/点号、空白折叠）。
 *   候选标题集合涵盖规范名 / 日文 / 罗马音 / 英文 / 别名——跨语言匹配依赖各源普遍填充的日文原名。
 * - **原文标题一致（强匹配）**：任一归一化标题变体完全相等。
 * - **年份 / 类型一致性**：作为合并的硬约束，冲突则不自动合并（区分系列总称 / 续作 / 外传 / 新章 /
 *   不同媒介），仅原文+年份+类型都不冲突才自动合并；原文一致但年份/类型冲突则不合并（留独立/人工确认）。
 * - **相似度兜底**：无完全相等时按最大跨变体相似度 ≥ [MERGE_THRESHOLD] 且年份/类型不冲突才合并。
 *
 * 热度 / 评分人数仅用于排序，绝不单独决定合并（避免误合并）。
 */

/** 跨源合并阈值（相似度兜底用，略低于自动链接阈值以便聚合展示）。 */
public const val MERGE_THRESHOLD: Double = 0.82

/** 收集作品全部可比较标题变体（规范名 / 日文 / 罗马音 / 英文 / 别名），去空白。 */
public fun Work.allTitleVariants(): List<String> = buildList {
    add(titles.canonical)
    titles.ja?.let(::add)
    titles.romaji?.let(::add)
    titles.en?.let(::add)
    addAll(titles.aliases)
}.filter { it.isNotBlank() }

private fun maxVariantSimilarity(a: List<String>, b: List<String>): Double {
    var best = 0.0
    for (x in a) for (y in b) {
        val s = similarity(x, y)
        if (s > best) best = s
        if (best >= 1.0) return 1.0
    }
    return best
}

/**
 * 两作品是否疑似同一作品（通用规则，R42）。
 *
 * 规则顺序：
 * 1. 同 id → 是。
 * 2. 原文标题任一变体归一化后完全相等：年份与类型都不冲突 → 是（自动合并）；否则 → 否（冲突，保留独立/人工确认）。
 * 3. 年份冲突或类型冲突 → 否（不靠前缀相似强行合并）。
 * 4. 否则最大跨变体相似度 ≥ [MERGE_THRESHOLD] → 是。
 */
public fun sameWork(a: Work, b: Work): Boolean {
    if (a.id == b.id) return true
    val yearConflict = a.year != null && b.year != null && a.year != b.year
    val typeConflict = mediaTypesConflict(a.mediaType, b.mediaType)

    val ta = a.allTitleVariants().map { normalizeTitle(it) }.filter { it.isNotEmpty() }
    val tb = b.allTitleVariants().map { normalizeTitle(it) }.filter { it.isNotEmpty() }

    val exactShared = ta.any { it in tb }
    if (exactShared) return !yearConflict && !typeConflict
    // F5：紧凑（去空格）精确相等——处理 9nine / 9-nine / 9 -Nine- 这类仅空格/连字符差异。
    val ca = a.allTitleVariants().map { normalizeCompact(it) }.filter { it.length >= MIN_COMPACT_LEN }
    val cb = b.allTitleVariants().map { normalizeCompact(it) }.filter { it.length >= MIN_COMPACT_LEN }
    val compactShared = ca.any { it in cb }
    if (compactShared) return !yearConflict && !typeConflict
    if (yearConflict || typeConflict) return false
    // R75：核心标题包含关系——一个原文标题是另一个的核心标题加 Episode/副标题等补充时也应合并。
    // 仅当被包含的核心长度足够（避免「9 nine」这类过短系列前缀把不同分作误并）。
    if (coreContainmentMatch(ta, tb)) return true
    return maxVariantSimilarity(ta, tb) >= MERGE_THRESHOLD
}

/** 任一标题变体对存在「较长核心被另一个完整包含」的关系（R75，防过短前缀误并）。 */
private const val MIN_CORE_LEN: Int = 10

/**
 * G2：媒介类型是否冲突（family-aware）。同一「媒介家族」内不视为冲突，避免同一作品因各源标注不同
 * 媒介而漏合并：
 * - 游戏家族：GAME ↔ VN（视觉小说常被某些源标为游戏，反之亦然，如 9-nine 系列）。
 * - 文字家族：MANGA ↔ NOVEL（漫画 / 小说改编互标）。
 * 动画（ANIME）自成一类，与上述家族冲突（防止把动画版与游戏 / 小说版误并为同一卡）。
 */
private fun mediaTypesConflict(a: com.acgcompass.domain.model.MediaType, b: com.acgcompass.domain.model.MediaType): Boolean {
    if (a == b) return false
    val gameFamily = setOf(com.acgcompass.domain.model.MediaType.GAME, com.acgcompass.domain.model.MediaType.VN)
    val textFamily = setOf(com.acgcompass.domain.model.MediaType.MANGA, com.acgcompass.domain.model.MediaType.NOVEL)
    if (a in gameFamily && b in gameFamily) return false
    if (a in textFamily && b in textFamily) return false
    return true
}

/** 紧凑精确相等的最小长度（F5）：避免「9」「ova」这类过短紧凑串误并。 */
private const val MIN_COMPACT_LEN: Int = 4
private fun coreContainmentMatch(a: List<String>, b: List<String>): Boolean {
    for (x in a) for (y in b) {
        val shorter = if (x.length <= y.length) x else y
        val longer = if (x.length <= y.length) y else x
        if (shorter.length >= MIN_CORE_LEN && longer.contains(shorter)) return true
    }
    return false
}

/**
 * 把候选按「同一作品」贪心聚类（R42）。按置信度降序，逐条并入已存在的疑似同一簇，否则新建簇。
 */
public fun clusterMatches(matches: List<WorkMatch>): List<List<WorkMatch>> {
    if (matches.isEmpty()) return emptyList()
    val ranked = matches.sortedByDescending { it.matchConfidence }
    val clusters = mutableListOf<MutableList<WorkMatch>>()
    for (m in ranked) {
        val target = clusters.firstOrNull { cluster -> cluster.any { sameWork(it.work, m.work) } }
        if (target != null) target.add(m) else clusters.add(mutableListOf(m))
    }
    return clusters
}

/**
 * 选取簇代表：优先 Bangumi（提供中文名），否则置信度最高者。
 *
 * 同置信度时再按**评分人数 / 热度**（[WorkMatch.popularity]）降序——避免代表落到同名小条目
 * （PV / 广播剧 / 同人 / 废弃条目，评分人数极少），否则详情页评分人数会异常偏低（如「9000+ 人」
 * 误显示成「1 人」）。这是通用排序规则，不针对任何具体作品（设计：热度仅用于排序、不决定合并）。
 */
public fun representativeOf(cluster: List<WorkMatch>): WorkMatch {
    val ranked = cluster.sortedWith(
        compareByDescending<WorkMatch> { it.matchConfidence }.thenByDescending { it.popularity },
    )
    return ranked.firstOrNull { it.sourceTag == SourceId.BANGUMI } ?: ranked.first()
}
