package com.acgcompass.data.repository

import com.acgcompass.core.common.AppResult
import com.acgcompass.core.common.DispatcherProvider
import com.acgcompass.data.local.dao.WorkFeatureDao
import com.acgcompass.data.local.entity.WorkFeatureEntity
import com.acgcompass.data.remote.bangumi.BangumiRemoteDataSource
import com.acgcompass.data.remote.bangumi.BangumiSubjectDto
import com.acgcompass.data.remote.bangumi.mapBangumiMediaType
import com.acgcompass.domain.model.MediaType
import com.acgcompass.domain.taste.TagCount
import com.acgcompass.domain.taste.WorkFeature
import com.acgcompass.domain.taste.WorkFeatureRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [WorkFeatureRepository] 实现：Room `work_features` 为单一可信源，缓存缺失 / 过期（> [TTL_MILLIS]）
 * 时 best-effort 联网（Bangumi 条目 DTO + 关联人物 + 关联角色）补齐并落库。
 *
 * 韧性：任一网络步骤失败都被吞掉（staff / 角色 / CV 退化为空集合，仍可用社区标签建画像），绝不崩溃。
 * 标签计数以 JSON 持久化（[tagCountsJson]）；staff/角色/CV 经 `Converters` 以 `List<String>` 持久化。
 */
@Singleton
class WorkFeatureRepositoryImpl @Inject constructor(
    private val workFeatureDao: WorkFeatureDao,
    private val bangumi: BangumiRemoteDataSource,
    private val dispatchers: DispatcherProvider,
) : WorkFeatureRepository {

    override suspend fun getFeature(subjectId: String, allowNetwork: Boolean): WorkFeature? =
        withContext(dispatchers.io) {
            val cached = workFeatureDao.getById(subjectId)
            if (cached != null && isFresh(cached)) return@withContext cached.toDomain()
            val sid = subjectId.toIntOrNull()
            if (!allowNetwork || sid == null) return@withContext cached?.toDomain()
            fetchAndCache(sid, subjectId) ?: cached?.toDomain()
        }

    override suspend fun getFeatures(
        subjectIds: List<String>,
        networkBudget: Int,
        onProgress: ((done: Int, total: Int) -> Unit)?,
    ): Map<String, WorkFeature> = withContext(dispatchers.io) {
        if (subjectIds.isEmpty()) return@withContext emptyMap()
        val cached = subjectIds.chunked(900).flatMap { workFeatureDao.getByIds(it) }.associateBy { it.subjectId }
        val total = subjectIds.size
        val progress = AtomicInteger(0)
        fun tick() { onProgress?.invoke(progress.incrementAndGet(), total) }

        // 先解析缓存命中；再挑出需联网者（限额内、数字 id，保序取前 networkBudget 个），其余用陈旧缓存兜底。
        val resolved = ConcurrentHashMap<String, WorkFeature>()
        val toFetch = ArrayList<Pair<Int, String>>()
        var budget = networkBudget
        for (id in subjectIds) {
            val hit = cached[id]
            if (hit != null && isFresh(hit)) {
                resolved[id] = hit.toDomain()
                tick()
                continue
            }
            val sid = id.toIntOrNull()
            if (budget > 0 && sid != null) {
                budget--
                toFetch += sid to id
            } else {
                if (hit != null) resolved[id] = hit.toDomain()
                tick()
            }
        }

        // F 提速：需联网的作品并发补齐（[FETCH_CONCURRENCY] 限流，配合 fetchAndCache 内 3 请求并发，
        // 把原「逐部 × 逐请求」串行拍平成两级并发），失败回退陈旧缓存；抓取数据与串行完全一致（零质量损失）。
        if (toFetch.isNotEmpty()) {
            val gate = Semaphore(FETCH_CONCURRENCY)
            coroutineScope {
                toFetch.map { (sid, id) ->
                    async {
                        gate.withPermit {
                            val value = fetchAndCache(sid, id) ?: cached[id]?.toDomain()
                            if (value != null) resolved[id] = value
                            tick()
                        }
                    }
                }.awaitAll()
            }
        }

        // 保序返回（键顺序 = 入参顺序）。
        val result = LinkedHashMap<String, WorkFeature>(subjectIds.size)
        for (id in subjectIds) resolved[id]?.let { result[id] = it }
        result
    }

    override suspend fun getCached(subjectId: String): WorkFeature? = withContext(dispatchers.io) {
        workFeatureDao.getById(subjectId)?.toDomain()
    }

    override suspend fun getCachedPool(limit: Int): List<WorkFeature> = withContext(dispatchers.io) {
        workFeatureDao.getAll(limit).map { it.toDomain() }
    }

    override suspend fun refresh(subjectId: String): Boolean = withContext(dispatchers.io) {
        val sid = subjectId.toIntOrNull() ?: return@withContext false
        fetchAndCache(sid, subjectId) != null
    }

    /**
     * best-effort 拉取条目 + 人物 + 角色并落库。条目本身拉取失败则返回 null（不落库）。
     * F 提速：三个请求相互独立，并发发起（`async`）而非串行等待，单部特征联网耗时约降至原 1/3；
     * 抓取的数据与串行完全一致（零质量损失）。人物 / 角色失败仍静默退化为空集。
     */
    private suspend fun fetchAndCache(sid: Int, key: String): WorkFeature? = coroutineScope {
        val dtoDeferred = async { (bangumi.getSubjectDto(sid) as? AppResult.Success)?.data }
        val personsDeferred = async { safeNames { (bangumi.getSubjectPersons(sid) as? AppResult.Success)?.data?.map { it.name } } }
        val charactersDeferred = async { (bangumi.getSubjectCharacters(sid) as? AppResult.Success)?.data.orEmpty() }

        val dto = dtoDeferred.await() ?: run {
            // 条目本身失败：取消尚在进行的人物 / 角色请求，返回 null（不落库）。
            personsDeferred.cancel()
            charactersDeferred.cancel()
            return@coroutineScope null
        }
        val staff = personsDeferred.await()
        val characterDtos = charactersDeferred.await()
        val characters = characterDtos.map { it.name }.filter { it.isNotBlank() }.distinct()
        val cv = characterDtos.flatMap { c -> c.actors.map { it.name } }.filter { it.isNotBlank() }.distinct()

        val entity = dto.toFeatureEntity(key, staff, characters, cv)
        workFeatureDao.upsert(entity)
        entity.toDomain()
    }

    private inline fun safeNames(block: () -> List<String>?): List<String> =
        try {
            block().orEmpty().filter { it.isNotBlank() }.distinct()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }

    private fun isFresh(entity: WorkFeatureEntity): Boolean =
        System.currentTimeMillis() - entity.updatedAt < TTL_MILLIS

    // --- mapping -----------------------------------------------------------

    private fun BangumiSubjectDto.toFeatureEntity(
        key: String,
        staff: List<String>,
        characters: List<String>,
        cv: List<String>,
    ): WorkFeatureEntity {
        val tagDtos = tags.filter { it.name.isNotBlank() }.map { TagCountDto(it.name, it.count.coerceAtLeast(0)) }
        return WorkFeatureEntity(
            subjectId = key,
            tagCountsJson = JSON.encodeToString(TAG_LIST_SERIALIZER, tagDtos),
            staff = staff,
            characters = characters,
            cv = cv,
            bangumiScore = rating?.score?.takeIf { it > 0f } ?: 0f,
            bangumiVotes = rating?.total?.coerceAtLeast(0) ?: 0,
            eps = (eps ?: totalEpisodes ?: 0).coerceAtLeast(0),
            durationMin = 0,
            platform = platform?.takeIf { it.isNotBlank() },
            mediaType = mediaTypeOf(type).name,
            titles = listOfNotNull(
                name.takeIf { it.isNotBlank() },
                nameCn.takeIf { it.isNotBlank() },
            ).distinct(),
            updatedAt = System.currentTimeMillis(),
        )
    }

    private fun WorkFeatureEntity.toDomain(): WorkFeature {
        val tags = runCatching { JSON.decodeFromString(TAG_LIST_SERIALIZER, tagCountsJson) }
            .getOrDefault(emptyList())
            .map { TagCount(it.name, it.count) }
        return WorkFeature(
            subjectId = subjectId,
            tagCounts = tags,
            staff = staff,
            characters = characters,
            cv = cv,
            bangumiScore = bangumiScore.takeIf { it > 0f },
            bangumiVotes = bangumiVotes,
            eps = eps,
            durationMin = durationMin,
            platform = platform,
            mediaType = mediaType?.let { MediaType.fromStorage(it) },
            titleVariants = titles,
            updatedAt = updatedAt,
        )
    }

    /**
     * #5：Bangumi type → [MediaType]，委托共享的 [mapBangumiMediaType]（音乐/真人/未知 → [MediaType.OTHER]）。
     * 此前本地私有实现对音乐(3)/真人(6)返回 null，与主映射（→OTHER）口径分叉，是 OST 泄漏进
     * ANIME 池的残留路径；委托后两处口径统一、永不再分叉。
     */
    private fun mediaTypeOf(type: Int?): MediaType = mapBangumiMediaType(type)

    /** 持久化用标签计数 DTO（避免给领域 [TagCount] 加序列化注解）。 */
    @Serializable
    private data class TagCountDto(val name: String, val count: Int)

    private companion object {
        /** 特征缓存有效期：14 天（社区标签/评分变化缓慢，过期再联网刷新）。 */
        const val TTL_MILLIS: Long = 14L * 24 * 3600 * 1000

        /**
         * F 提速：批量联网补齐特征的并发作品数上限。每部内部再并发 3 个请求（条目/人物/角色），
         * 故峰值并发约 [FETCH_CONCURRENCY] × 3；取 4 在「显著提速」与「不过度冲击 Bangumi 反代」间折中。
         */
        const val FETCH_CONCURRENCY: Int = 4

        val JSON = Json { ignoreUnknownKeys = true }
        val TAG_LIST_SERIALIZER = ListSerializer(TagCountDto.serializer())
    }
}
