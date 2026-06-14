package com.acgcompass.data.local.mapper

import com.acgcompass.data.local.entity.ImportBatchEntity
import com.acgcompass.data.local.entity.ImportItemEntity
import com.acgcompass.domain.model.ImportBatch
import com.acgcompass.domain.model.ImportItem
import com.acgcompass.domain.model.ImportItemStatus
import com.acgcompass.domain.model.ImportSource

/**
 * Entity ↔ domain-model mappers for the batch-import models (task 17.3 / RC.06.05/06.08).
 *
 * Enum fields persisted as String ([ImportSource] / [ImportItemStatus]) are converted at the
 * persistence ↔ domain boundary; unknown / corrupt values fall back to safe defaults instead of
 * throwing (RC.17.4).
 */

fun ImportBatchEntity.toDomain(): ImportBatch =
    ImportBatch(
        id = id,
        name = name,
        createdAt = createdAt,
        source = ImportSource.fromStorage(source),
        recognizedCount = recognizedCount,
        successCount = successCount,
        failureCount = failureCount,
    )

fun ImportBatch.toEntity(): ImportBatchEntity =
    ImportBatchEntity(
        id = id,
        name = name,
        createdAt = createdAt,
        source = source?.name,
        recognizedCount = recognizedCount,
        successCount = successCount,
        failureCount = failureCount,
    )

fun ImportItemEntity.toDomain(): ImportItem =
    ImportItem(
        id = id,
        batchId = batchId,
        rawText = rawText,
        parsedTitle = parsedTitle,
        workId = workId,
        matchConfidence = matchConfidence,
        status = ImportItemStatus.fromStorage(status),
    )

fun ImportItem.toEntity(): ImportItemEntity =
    ImportItemEntity(
        id = id,
        batchId = batchId,
        rawText = rawText,
        parsedTitle = parsedTitle,
        workId = workId,
        matchConfidence = matchConfidence,
        status = status.name,
    )
