package com.acgcompass.data.taste

import com.acgcompass.core.common.DispatcherProvider
import com.acgcompass.data.local.dao.TagDimensionDao
import com.acgcompass.data.local.entity.TagDimensionEntity
import com.acgcompass.domain.ai.AiEngine
import com.acgcompass.domain.ai.AiRunOptions
import com.acgcompass.domain.ai.AiRunResult
import com.acgcompass.domain.ai.AiTask
import com.acgcompass.domain.ai.TagClassifyOutput
import com.acgcompass.domain.taste.TagClassifier
import com.acgcompass.domain.taste.TasteCategory
import com.acgcompass.domain.taste.WorkFeatureRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * N3 后台标签分维分类器：把本地规则「其余视为题材」兜底的**未知社区标签**分批交 AI 归入更精确的口味维度，
 * 结果落库到 `tag_dimensions`（[TagDimensionDao]），供 [TasteEngine] 在画像构建 / 评分时读为覆盖表。
 *
 * 设计要点（RC.14.01/03 / RC.01 3.7）：
 * - **只分类真正未知的标签**：候选取自 [WorkFeatureRepository.getCachedPool] 全体社区标签，经
 *   [TagClassifier.isUnknownTopicFallback] 过滤（已被词典 / 交叉验证命中的标签本地已能精确分类，不送 AI），
 *   按出现频次降序优先分类高频标签（更影响画像 / 评分），并排除已缓存标签避免重复调用。
 * - **回退优先**：AI 未配置 → 立即停止并回退本地（画像照常工作）；单批低置信 / 失败 → 跳过该批继续，
 *   绝不写入伪造维度。非法维度 key 经 [TasteCategory.fromKey] 过滤丢弃。
 * - **手动触发、单例串行**：由设置页显式触发（成本可控）；[mutex] 保证同一时刻只跑一轮。
 */
@Singleton
class AiTagClassifier @Inject constructor(
    private val aiEngine: AiEngine,
    private val workFeatureRepository: WorkFeatureRepository,
    private val tagDimensionDao: TagDimensionDao,
    private val dispatchers: DispatcherProvider,
) {

    private val _progress = MutableStateFlow<TagClassifyProgress?>(null)

    /** 分类进度流（`null` = 无分类进行中）。设置页可观察以展示进度条。 */
    fun observeProgress(): StateFlow<TagClassifyProgress?> = _progress.asStateFlow()

    private val mutex = Mutex()

    /**
     * 分类一批待分维的未知标签并写入缓存。串行执行；[maxTags] 限定单轮送 AI 的标签上限（成本可控，
     * 可多次触发逐步补全）。返回 [TagClassifyOutcome] 供 UI 展示结果。
     */
    suspend fun classifyPending(maxTags: Int = DEFAULT_MAX_TAGS): TagClassifyOutcome = mutex.withLock {
        withContext(dispatchers.io) {
            val cached = runCatching { tagDimensionDao.getCachedTags() }
                .getOrDefault(emptyList())
                .toHashSet()
            val pool = runCatching { workFeatureRepository.getCachedPool() }.getOrDefault(emptyList())

            // 清洗后标签 → 出现频次 / 首个原始写法（送 AI 用原始写法，缓存键用清洗后）。
            val freq = HashMap<String, Int>()
            val raw = HashMap<String, String>()
            for (f in pool) {
                for (tc in f.tagCounts) {
                    val cleaned = TagClassifier.clean(tc.name)
                    if (cleaned.isEmpty() || cleaned in cached) continue
                    if (!TagClassifier.isUnknownTopicFallback(tc.name)) continue
                    freq[cleaned] = (freq[cleaned] ?: 0) + 1
                    raw.putIfAbsent(cleaned, tc.name)
                }
            }
            if (freq.isEmpty()) {
                _progress.value = null
                return@withContext TagClassifyOutcome.NothingToDo
            }

            val candidates = freq.entries
                .sortedByDescending { it.value }
                .take(maxTags.coerceAtLeast(1))
                .mapNotNull { raw[it.key] }
            val batches = candidates.chunked(BATCH_SIZE)

            var classified = 0
            var notConfigured = false
            _progress.value = TagClassifyProgress(0, candidates.size)
            loop@ for ((i, batch) in batches.withIndex()) {
                val task = AiTask.TagClassify(
                    content = buildContent(batch),
                    tags = batch,
                    dataSources = listOf("work_features 社区标签"),
                )
                when (val r = aiEngine.run(task, AiRunOptions(confirmed = true))) {
                    is AiRunResult.Success -> {
                        val entities = mapToEntities(r.payload, batch)
                        if (entities.isNotEmpty()) {
                            runCatching { tagDimensionDao.upsertAll(entities) }
                            classified += entities.size
                        }
                    }
                    // 未配置 AI：立即停止，回退本地（RC.14.01）。
                    is AiRunResult.NotConfigured -> {
                        notConfigured = true
                        break@loop
                    }
                    // 低置信 / 失败 / 待确认：跳过本批继续，绝不写伪造维度（RC.14.03）。
                    else -> Unit
                }
                _progress.value = TagClassifyProgress(
                    done = minOf((i + 1) * BATCH_SIZE, candidates.size),
                    total = candidates.size,
                )
            }
            _progress.value = null
            when {
                notConfigured && classified == 0 -> TagClassifyOutcome.NotConfigured
                else -> TagClassifyOutcome.Done(classified = classified, requested = candidates.size)
            }
        }
    }

    /** AI 已缓存的标签数（UI 展示 / 判断是否需要分类）。读失败返回 0。 */
    suspend fun cachedCount(): Int = withContext(dispatchers.io) {
        runCatching { tagDimensionDao.count() }.getOrDefault(0)
    }

    /** 把 AI 输出映射为缓存实体：仅保留维度合法（[TasteCategory.fromKey]）且属于本批的标签，去重。 */
    private fun mapToEntities(output: TagClassifyOutput, batch: List<String>): List<TagDimensionEntity> {
        if (output.items.isEmpty()) return emptyList()
        val batchKeys = batch.mapTo(HashSet()) { TagClassifier.clean(it) }
        val now = System.currentTimeMillis()
        val seen = HashSet<String>()
        val confidence = output.confidence.coerceIn(0f, 1f)
        return output.items.mapNotNull { item ->
            val key = TagClassifier.clean(item.tag)
            if (key.isEmpty() || key !in batchKeys || !seen.add(key)) return@mapNotNull null
            val category = TasteCategory.fromKey(item.dimension) ?: return@mapNotNull null
            TagDimensionEntity(
                tag = key,
                dimension = category.key,
                source = "AI",
                confidence = confidence,
                updatedAt = now,
            )
        }
    }

    private fun buildContent(tags: List<String>): String = buildString {
        appendLine("请对下列 ${tags.size} 个社区标签逐一分维，为每个标签各输出一条 {tag, dimension}（tag 原样回填）：")
        tags.forEachIndexed { i, t -> appendLine("${i + 1}. $t") }
    }

    private companion object {
        /** 单批送 AI 的标签数（一批约需千级输出 token，适配 2000 上限，避免 JSON 截断）。 */
        const val BATCH_SIZE: Int = 40

        /** 单轮默认最多分类的标签数（约 5 批 / 5 次 AI 调用，成本可控；可多次触发逐步补全）。 */
        const val DEFAULT_MAX_TAGS: Int = 200
    }
}

/** 标签分维分类进度（[AiTagClassifier.observeProgress]）。 */
data class TagClassifyProgress(val done: Int, val total: Int)

/** 一轮分类的结果（供设置页展示反馈）。 */
sealed interface TagClassifyOutcome {

    /** 完成：本轮成功分类并落库 [classified] 个标签（共请求 [requested] 个）。 */
    data class Done(val classified: Int, val requested: Int) : TagClassifyOutcome

    /** 无待分类标签（缓存池里的未知标签都已分过，或池为空）。 */
    data object NothingToDo : TagClassifyOutcome

    /** AI 未配置：已回退本地规则，未做任何分类。 */
    data object NotConfigured : TagClassifyOutcome
}
