package com.acgcompass.data.repository

import com.acgcompass.core.common.AppResult
import com.acgcompass.core.common.DispatcherProvider
import com.acgcompass.data.local.dao.WorkFeatureDao
import com.acgcompass.data.local.entity.WorkFeatureEntity
import com.acgcompass.data.remote.bangumi.BangumiRemoteDataSource
import com.acgcompass.data.remote.bangumi.BangumiSubjectDto
import com.acgcompass.data.remote.bangumi.BangumiSubjectType
import com.acgcompass.domain.model.MediaType
import com.acgcompass.domain.taste.TagCount
import com.acgcompass.domain.taste.WorkFeature
import com.acgcompass.domain.taste.WorkFeatureRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
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
    ): Map<String, WorkFeature> = withContext(dispatchers.io) {
        if (subjectIds.isEmpty()) return@withContext emptyMap()
        val result = LinkedHashMap<String, WorkFeature>()
        val cached = subjectIds.chunked(900).flatMap { workFeatureDao.getByIds(it) }.associateBy { it.subjectId }
        var budget = networkBudget
        for (id in subjectIds) {
            val hit = cached[id]
            if (hit != null && isFresh(hit)) {
                result[id] = hit.toDomain()
                continue
            }
            val sid = id.toIntOrNull()
            if (budget > 0 && sid != null) {
                budget--
                val fetched = fetchAndCache(sid, id)
                when {
                    fetched != null -> result[id] = fetched
                    hit != null -> result[id] = hit.toDomain()
                }
            } else if (hit != null) {
                result[id] = hit.toDomain()
            }
        }
        result
    }

    override suspend fun getCached(subjectId: String): WorkFeature? = withContext(dispatchers.io) {
        workFeatureDao.getById(subjectId)?.toDomain()
    }

    override suspend fun refresh(subjectId: String): Boolean = withContext(dispatchers.io) {
        val sid = subjectId.toIntOrNull() ?: return@withContext false
        fetchAndCache(sid, subjectId) != null
    }

    /** best-effort 拉取条目 + 人物 + 角色并落库。条目本身拉取失败则返回 null（不落库）。 */
    private suspend fun fetchAndCache(sid: Int, key: String): WorkFeature? {
        val dto = (bangumi.getSubjectDto(sid) as? AppResult.Success)?.data ?: return null
        val staff = safeNames { (bangumi.getSubjectPersons(sid) as? AppResult.Success)?.data?.map { it.name } }
        val characterDtos = (bangumi.getSubjectCharacters(sid) as? AppResult.Success)?.data.orEmpty()
        val characters = characterDtos.map { it.name }.filter { it.isNotBlank() }.distinct()
        val cv = characterDtos.flatMap { c -> c.actors.map { it.name } }.filter { it.isNotBlank() }.distinct()

        val entity = dto.toFeatureEntity(key, staff, characters, cv)
        workFeatureDao.upsert(entity)
        return entity.toDomain()
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
            mediaType = mediaTypeOf(type)?.name,
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

    private fun mediaTypeOf(type: Int?): MediaType? = when (type) {
        BangumiSubjectType.ANIME -> MediaType.ANIME
        BangumiSubjectType.BOOK -> MediaType.MANGA
        BangumiSubjectType.GAME -> MediaType.GAME
        else -> null
    }

    /** 持久化用标签计数 DTO（避免给领域 [TagCount] 加序列化注解）。 */
    @Serializable
    private data class TagCountDto(val name: String, val count: Int)

    private companion object {
        /** 特征缓存有效期：14 天（社区标签/评分变化缓慢，过期再联网刷新）。 */
        const val TTL_MILLIS: Long = 14L * 24 * 3600 * 1000

        val JSON = Json { ignoreUnknownKeys = true }
        val TAG_LIST_SERIALIZER = ListSerializer(TagCountDto.serializer())
    }
}
