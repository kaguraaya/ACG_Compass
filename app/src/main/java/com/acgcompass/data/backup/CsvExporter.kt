package com.acgcompass.data.backup

/**
 * CSV 导出器（task 30.3 / RC.16.06 / Requirements 18.6）。把待补池 / 时光机 / 评分表导出为
 * 标准 CSV 文本（RFC 4180 转义：含逗号 / 双引号 / 换行的字段用双引号包裹，内部双引号转义为两个）。
 *
 * **纯函数、无 Android / IO 依赖**：仅做内存中的文本拼装，便于单元测试与复用；落盘由调用方负责。
 *
 * **隐私（RC.00 1.2/1.5）**：CSV 仅导出业务数据，**绝不**包含任何凭据 / key / token。
 *
 * 关于跨账号 / 多平台列表合并：合并所需的结构已在备份 schema 中预留——
 * [BackupSourceLink]（`SOURCE_LINK`，多源标识 → 同一作品的归一）与
 * [BackupImportBatch]（`IMPORT_BATCH`，批次来源标识）即为跨账号合并的数据落点（RC.16.07 / 18.7），
 * 见 [CrossAccountMergeReservation]。
 */
object CsvExporter {

    /** 待补池 CSV（RC.16.06）。列：作品ID, 标题, 优先级, 心情标签, 风险标签, 备注, 吃灰天数, 是否吃灰馆。 */
    fun exportBacklog(
        items: List<BackupBacklogItem>,
        titlesById: Map<String, String> = emptyMap(),
    ): String {
        val header = listOf(
            "作品ID", "标题", "优先级", "心情标签", "风险标签", "备注", "吃灰天数", "吃灰馆",
        )
        val rows = items.map { item ->
            listOf(
                item.workId,
                titlesById[item.workId].orEmpty(),
                item.priority,
                item.moodTags.joinToString("、"),
                item.riskTags.joinToString("、"),
                item.note.orEmpty(),
                item.dustDays.toString(),
                if (item.inDustMuseum) "是" else "否",
            )
        }
        return buildCsv(header, rows)
    }

    /** 评分表 CSV（RC.16.06）。列：作品ID, 来源, 评分, 投票数, 排名, 抓取时间, 缺失。 */
    fun exportRatings(ratings: List<BackupRating>): String {
        val header = listOf("作品ID", "来源", "评分", "投票数", "排名", "抓取时间", "缺失")
        val rows = ratings.map { r ->
            listOf(
                r.workId,
                r.sourceId,
                if (r.missing) "" else r.score.toString(),
                r.voteCount.toString(),
                r.rank?.toString().orEmpty(),
                r.fetchedAt.toString(),
                if (r.missing) "是" else "否",
            )
        }
        return buildCsv(header, rows)
    }

    /** 时光机变更 CSV（RC.16.06）。列：时间, 作品ID, 变更类型, 字段, 旧值, 新值。 */
    fun exportTimeMachine(changeLogs: List<BackupChangeLog>): String {
        val header = listOf("时间", "作品ID", "变更类型", "字段", "旧值", "新值")
        val rows = changeLogs
            .sortedByDescending { it.changedAt }
            .map { log ->
                listOf(
                    log.changedAt.toString(),
                    log.workId,
                    log.changeType,
                    log.field.orEmpty(),
                    log.oldValue.orEmpty(),
                    log.newValue.orEmpty(),
                )
            }
        return buildCsv(header, rows)
    }

    /** 拼装 CSV 文本：表头 + 数据行，行间以 `\n` 连接，字段按 RFC 4180 转义。 */
    private fun buildCsv(header: List<String>, rows: List<List<String>>): String {
        val sb = StringBuilder()
        sb.append(header.joinToString(",") { escape(it) })
        for (row in rows) {
            sb.append('\n')
            sb.append(row.joinToString(",") { escape(it) })
        }
        return sb.toString()
    }

    /** RFC 4180 字段转义：含逗号 / 双引号 / 换行时用双引号包裹，内部双引号转义为两个双引号。 */
    private fun escape(field: String): String {
        val needsQuote = field.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
        if (!needsQuote) return field
        val escaped = field.replace("\"", "\"\"")
        return "\"$escaped\""
    }
}

/**
 * 跨账号 / 多平台列表合并的结构预留说明（task 30.3 / RC.16.07 / Requirements 18.7）。
 *
 * 多平台（Bangumi / AniList / MAL / VNDB …）的同一作品通过 [BackupSourceLink]（`SOURCE_LINK`）的
 * `sourceId + sourceItemId → workId` 归一到同一规范化作品；不同账号 / 平台的导入来源由
 * [BackupImportBatch]（`IMPORT_BATCH`）的来源标识区分。二者共同构成跨账号合并的数据基础，
 * 合并时按业务主键（workId）去重不覆盖（见 [BackupMerger]）。
 *
 * 本类仅作文档化锚点，不承载运行时逻辑。
 */
object CrossAccountMergeReservation
