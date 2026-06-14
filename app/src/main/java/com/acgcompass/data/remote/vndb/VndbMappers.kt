package com.acgcompass.data.remote.vndb

import com.acgcompass.domain.model.MediaType
import com.acgcompass.domain.model.RatingEntry
import com.acgcompass.domain.model.ReleaseStatus
import com.acgcompass.domain.model.SourceId
import com.acgcompass.domain.model.Titles
import com.acgcompass.domain.model.Units
import com.acgcompass.domain.model.Work
import com.acgcompass.domain.model.WorkMatch

/**
 * VNDB DTO → 领域模型映射（RC.01 3.6/3.7/3.8 / RC.02 4.9）。
 *
 * 原则（不伪造，缺失即缺失，与 `JikanMappers` / `BangumiMappers` 一致）：
 * - 缺失字段以 `null` / 空集合透传，由 UI 渲染「暂无数据」（RC.01 3.7）。
 * - 评分缺失（`rating==null` 或 `votecount<=0`）时 [VndbVnDto.toRatingEntry] 返回 `null`，
 *   **保留 10–100 源标度**（聚合时由 `AggregateRatingsUseCase.normalizeToTen` 归一），
 *   绝不以 0 伪造评分，也绝不跨源回填（Property 5 / RC.07 9.2）。
 * - **成人内容分级（RC.02 4.9/4.10）**：依据 `image.sexual`（0–2 均值），[VndbVnDto.isAdult]
 *   判定是否成人内容；过滤/隐藏逻辑在 [VndbRemoteDataSource] 按 `showAdultContent` 开关执行。
 */

/** `image.sexual`（0–2 均值）达到该阈值即视为成人内容（1=暗示 / 2=露骨）。 */
const val VNDB_ADULT_SEXUAL_THRESHOLD: Float = 1.0f

/** 是否为成人内容：当 `image.sexual >= [VNDB_ADULT_SEXUAL_THRESHOLD]` 时为 `true`（无图/无评分视为非成人）。 */
fun VndbVnDto.isAdult(): Boolean {
    val sexual = image?.sexual ?: return false
    return sexual >= VNDB_ADULT_SEXUAL_THRESHOLD
}

/** 从 VNDB 发行日期串（`"YYYY-MM-DD"`/`"YYYY-MM"`/`"YYYY"`/`"TBA"`）解析年份；无法解析返回 `null`（不臆造）。 */
internal fun parseVndbYear(released: String?): Int? {
    val head = released?.take(4) ?: return null
    return head.toIntOrNull()?.takeIf { it in 1900..2999 }
}

/** 选取原文（日文等）标题：优先与 `olang` 同语言的标题，其次主标题；全无返回 `null`。 */
internal fun VndbVnDto.originalScriptTitle(): String? {
    val byOlang = olang?.let { lang -> titles.firstOrNull { it.lang == lang } }
    val main = titles.firstOrNull { it.main }
    return (byOlang ?: main)?.title?.takeIf { it.isNotBlank() } ?: altTitle?.takeIf { it.isNotBlank() }
}

/** 选取罗马音标题：优先 `olang`/主标题的 `latin`；全无返回 `null`。 */
internal fun VndbVnDto.romajiTitle(): String? {
    val byOlang = olang?.let { lang -> titles.firstOrNull { it.lang == lang } }
    val main = titles.firstOrNull { it.main }
    return (byOlang ?: main)?.latin?.takeIf { it.isNotBlank() }
}

/**
 * VN DTO → [RatingEntry]（VNDB 贝叶斯评分，**保留 10–100 源标度**）；当无有效样本
 * （`rating==null` 或 `votecount<=0`）时返回 `null` 表示「暂无数据」，不以 0 伪造评分
 * （RC.07 9.2 / Property 5）。VNDB 不提供单独排名，[RatingEntry.rank] 恒为 `null`。
 */
fun VndbVnDto.toRatingEntry(): RatingEntry? {
    val r = rating ?: return null
    val votes = voteCount ?: 0
    if (r <= 0f || votes <= 0) return null
    return RatingEntry(
        score = r,
        voteCount = votes,
        rank = null,
    )
}

/**
 * VN DTO → 规范化 [Work]（[MediaType.VN]）。
 *
 * 标题：[Titles.canonical] 取 `title`（VNDB 主标题，通常罗马音），缺失回退原文标题 / id；
 * [Titles.ja] 取原文（`olang`）标题，[Titles.romaji] 取其罗马音，别名取 `aliases`（过滤空白）。
 * 体量：VN 用 [Units.estPlayMinutes]（取 `length_minutes` 实测均值，缺失 / 非正为 `null`）。
 * VNDB 不提供清晰的发行状态字段，[Work.status] 取 [ReleaseStatus.UNKNOWN]（不臆造）。
 */
fun VndbVnDto.toWork(): Work {
    val main = title?.takeIf { it.isNotBlank() }
    val original = originalScriptTitle()
    val canonical = main ?: original ?: id
    return Work(
        id = id,
        titles = Titles(
            canonical = canonical,
            ja = original,
            romaji = romajiTitle(),
            en = null,
            aliases = aliases.filter { it.isNotBlank() },
        ),
        mediaType = MediaType.VN,
        year = parseVndbYear(released),
        status = ReleaseStatus.UNKNOWN,
        units = Units(
            estPlayMinutes = lengthMinutes?.takeIf { it > 0 },
        ),
        coverUrl = image?.url,
        primarySource = SourceId.VNDB,
        tags = emptyList(),
        summary = description?.takeIf { it.isNotBlank() },
    )
}

/**
 * VN DTO → [WorkMatch]（搜索 / 匹配结果）。
 *
 * @param matchConfidence 由匹配器给出的置信度 ∈ [0,1]；直接 `getVn` 视为精确命中（默认 `1f`）。
 */
fun VndbVnDto.toWorkMatch(matchConfidence: Float = 1f): WorkMatch =
    WorkMatch(
        work = toWork(),
        matchConfidence = matchConfidence,
        sourceTag = SourceId.VNDB,
    )
