package com.acgcompass.data.backup

import com.acgcompass.data.datastore.SettingsState
import com.acgcompass.data.datastore.ThemeMode
import com.acgcompass.data.local.entity.BacklogItemEntity
import com.acgcompass.data.local.entity.ChangeLogEntity
import com.acgcompass.data.local.entity.ImportBatchEntity
import com.acgcompass.data.local.entity.ImportItemEntity
import com.acgcompass.data.local.entity.RatingEntity
import com.acgcompass.data.local.entity.RecommendationCountEntity
import com.acgcompass.data.local.entity.SnapshotEntity
import com.acgcompass.data.local.entity.SourceLinkEntity
import com.acgcompass.data.local.entity.TagEntity
import com.acgcompass.data.local.entity.TasteProfileEntity
import com.acgcompass.data.local.entity.TasteTagStatEntity
import com.acgcompass.data.local.entity.WorkEntity
import com.acgcompass.data.local.entity.WorkTagEntity

/**
 * Room 实体 <-> 备份模型的**纯**双向映射（无 Android / IO 依赖）。
 *
 * 每个 `toBackup()` / `toEntity()` 对均为字段一一对应的无损映射，保证经由备份 round-trip
 * （导出再导入）后实体内容业务等价（Property 17 / RC.16 18.8）。设置以 [ThemeMode] 的稳定字符串
 * 表示持久化，未知值回退为 `SYSTEM`（RC.00 1.8 / RC.17.4）。
 */

// --- Work ------------------------------------------------------------------

fun WorkEntity.toBackup(): BackupWork = BackupWork(
    id = id,
    canonicalTitle = canonicalTitle,
    titleJa = titleJa,
    titleRomaji = titleRomaji,
    titleEn = titleEn,
    aliases = aliases,
    mediaType = mediaType,
    year = year,
    status = status,
    episodes = episodes,
    episodeMinutes = episodeMinutes,
    volumes = volumes,
    estPlayMinutes = estPlayMinutes,
    coverUrl = coverUrl,
    primarySource = primarySource,
    completionCostBucket = completionCostBucket,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

fun BackupWork.toEntity(): WorkEntity = WorkEntity(
    id = id,
    canonicalTitle = canonicalTitle,
    titleJa = titleJa,
    titleRomaji = titleRomaji,
    titleEn = titleEn,
    aliases = aliases,
    mediaType = mediaType,
    year = year,
    status = status,
    episodes = episodes,
    episodeMinutes = episodeMinutes,
    volumes = volumes,
    estPlayMinutes = estPlayMinutes,
    coverUrl = coverUrl,
    primarySource = primarySource,
    completionCostBucket = completionCostBucket,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

// --- SourceLink ------------------------------------------------------------

fun SourceLinkEntity.toBackup(): BackupSourceLink = BackupSourceLink(
    id = id,
    workId = workId,
    sourceId = sourceId,
    sourceItemId = sourceItemId,
    matchConfidence = matchConfidence,
    userOverridden = userOverridden,
    linkedAt = linkedAt,
)

fun BackupSourceLink.toEntity(): SourceLinkEntity = SourceLinkEntity(
    id = id,
    workId = workId,
    sourceId = sourceId,
    sourceItemId = sourceItemId,
    matchConfidence = matchConfidence,
    userOverridden = userOverridden,
    linkedAt = linkedAt,
)

// --- RecommendationCount ---------------------------------------------------

fun RecommendationCountEntity.toBackup(): BackupRecommendationCount = BackupRecommendationCount(
    workId = workId,
    recommendedCount = recommendedCount,
    lastRecommendedAt = lastRecommendedAt,
)

fun BackupRecommendationCount.toEntity(): RecommendationCountEntity = RecommendationCountEntity(
    workId = workId,
    recommendedCount = recommendedCount,
    lastRecommendedAt = lastRecommendedAt,
)

// --- BacklogItem -----------------------------------------------------------

fun BacklogItemEntity.toBackup(): BackupBacklogItem = BackupBacklogItem(
    workId = workId,
    priority = priority,
    moodTags = moodTags,
    riskTags = riskTags,
    note = note,
    addedAt = addedAt,
    dustDays = dustDays,
    inDustMuseum = inDustMuseum,
)

fun BackupBacklogItem.toEntity(): BacklogItemEntity = BacklogItemEntity(
    workId = workId,
    priority = priority,
    moodTags = moodTags,
    riskTags = riskTags,
    note = note,
    addedAt = addedAt,
    dustDays = dustDays,
    inDustMuseum = inDustMuseum,
)

// --- Rating ----------------------------------------------------------------

fun RatingEntity.toBackup(): BackupRating = BackupRating(
    id = id,
    workId = workId,
    sourceId = sourceId,
    score = score,
    voteCount = voteCount,
    rank = rank,
    fetchedAt = fetchedAt,
    missing = missing,
)

fun BackupRating.toEntity(): RatingEntity = RatingEntity(
    id = id,
    workId = workId,
    sourceId = sourceId,
    score = score,
    voteCount = voteCount,
    rank = rank,
    fetchedAt = fetchedAt,
    missing = missing,
)

// --- Tag / WorkTag ---------------------------------------------------------

fun TagEntity.toBackup(): BackupTag = BackupTag(id = id, category = category, name = name)

fun BackupTag.toEntity(): TagEntity = TagEntity(id = id, category = category, name = name)

fun WorkTagEntity.toBackup(): BackupWorkTag = BackupWorkTag(workId = workId, tagId = tagId)

fun BackupWorkTag.toEntity(): WorkTagEntity = WorkTagEntity(workId = workId, tagId = tagId)

// --- Import ----------------------------------------------------------------

fun ImportBatchEntity.toBackup(): BackupImportBatch = BackupImportBatch(
    id = id,
    name = name,
    createdAt = createdAt,
    source = source,
    recognizedCount = recognizedCount,
    successCount = successCount,
    failureCount = failureCount,
)

fun BackupImportBatch.toEntity(): ImportBatchEntity = ImportBatchEntity(
    id = id,
    name = name,
    createdAt = createdAt,
    source = source,
    recognizedCount = recognizedCount,
    successCount = successCount,
    failureCount = failureCount,
)

fun ImportItemEntity.toBackup(): BackupImportItem = BackupImportItem(
    id = id,
    batchId = batchId,
    rawText = rawText,
    parsedTitle = parsedTitle,
    workId = workId,
    matchConfidence = matchConfidence,
    status = status,
)

fun BackupImportItem.toEntity(): ImportItemEntity = ImportItemEntity(
    id = id,
    batchId = batchId,
    rawText = rawText,
    parsedTitle = parsedTitle,
    workId = workId,
    matchConfidence = matchConfidence,
    status = status,
)

// --- Snapshot / ChangeLog --------------------------------------------------

fun SnapshotEntity.toBackup(): BackupSnapshot = BackupSnapshot(
    id = id,
    takenAt = takenAt,
    kind = kind,
    payloadHash = payloadHash,
)

fun BackupSnapshot.toEntity(): SnapshotEntity = SnapshotEntity(
    id = id,
    takenAt = takenAt,
    kind = kind,
    payloadHash = payloadHash,
)

fun ChangeLogEntity.toBackup(): BackupChangeLog = BackupChangeLog(
    id = id,
    snapshotId = snapshotId,
    workId = workId,
    changeType = changeType,
    field = field,
    oldValue = oldValue,
    newValue = newValue,
    changedAt = changedAt,
)

fun BackupChangeLog.toEntity(): ChangeLogEntity = ChangeLogEntity(
    id = id,
    snapshotId = snapshotId,
    workId = workId,
    changeType = changeType,
    field = field,
    oldValue = oldValue,
    newValue = newValue,
    changedAt = changedAt,
)

// --- TasteProfile / TasteTagStat -------------------------------------------

fun TasteProfileEntity.toBackup(): BackupTasteProfile = BackupTasteProfile(
    id = id,
    strictness = strictness,
    avgScore = avgScore,
    highScoreRarity = highScoreRarity,
    commonScoreBand = commonScoreBand,
    titles = titles,
    confidence = confidence,
    generatedAt = generatedAt,
)

fun BackupTasteProfile.toEntity(): TasteProfileEntity = TasteProfileEntity(
    id = id,
    strictness = strictness,
    avgScore = avgScore,
    highScoreRarity = highScoreRarity,
    commonScoreBand = commonScoreBand,
    titles = titles,
    confidence = confidence,
    generatedAt = generatedAt,
)

fun TasteTagStatEntity.toBackup(): BackupTasteTagStat = BackupTasteTagStat(
    id = id,
    profileId = profileId,
    tagName = tagName,
    bucket = bucket,
    count = count,
)

fun BackupTasteTagStat.toEntity(): TasteTagStatEntity = TasteTagStatEntity(
    id = id,
    profileId = profileId,
    tagName = tagName,
    bucket = bucket,
    count = count,
)

// --- Settings --------------------------------------------------------------

fun SettingsState.toBackup(): BackupSettings = BackupSettings(
    allowAiAnalyzeReviews = allowAiAnalyzeReviews,
    recordTimeMachineSnapshots = recordTimeMachineSnapshots,
    bangumiEnabled = bangumiEnabled,
    anilistEnabled = anilistEnabled,
    jikanEnabled = jikanEnabled,
    malEnabled = malEnabled,
    vndbEnabled = vndbEnabled,
    showAdultContent = showAdultContent,
    onboardingShown = onboardingShown,
    themeMode = themeMode.toStorage(),
    dynamicColor = dynamicColor,
)

fun BackupSettings.toSettingsState(): SettingsState = SettingsState(
    allowAiAnalyzeReviews = allowAiAnalyzeReviews,
    recordTimeMachineSnapshots = recordTimeMachineSnapshots,
    bangumiEnabled = bangumiEnabled,
    anilistEnabled = anilistEnabled,
    jikanEnabled = jikanEnabled,
    malEnabled = malEnabled,
    vndbEnabled = vndbEnabled,
    showAdultContent = showAdultContent,
    onboardingShown = onboardingShown,
    themeMode = ThemeMode.fromStorage(themeMode),
    dynamicColor = dynamicColor,
)
