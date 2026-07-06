package com.acgcompass.feature.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BrokenImage
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.acgcompass.core.designsystem.AcgCompassTheme
import com.acgcompass.core.ui.StateScaffold
import com.acgcompass.core.ui.UiState

/**
 * 导航参数键：作品详情页的 `workId`，由路由 [detailRoute] 携带，
 * 经 [androidx.lifecycle.SavedStateHandle] 注入 [DetailViewModel]。
 */
const val DETAIL_ARG_WORK_ID: String = "workId"

/** 详情页路由模板（含参数占位符），在导航图中注册时使用。 */
const val DETAIL_ROUTE_PATTERN: String = "detail/{$DETAIL_ARG_WORK_ID}"

/** 构造跳转到指定作品详情页的具体路由（对 [workId] 做 URL 编码兜底）。 */
fun detailRoute(workId: String): String = "detail/${workId.trim()}"

/**
 * 作品详情页路由入口（RC.07.01/02/03 / Requirements 9.1、9.2、9.3）。
 *
 * 通过 [hiltViewModel] 取得 [DetailViewModel]（`workId` 已由 SavedStateHandle 注入），
 * 观察其 `StateFlow<UiState<DetailUiState>>`，交由 [StateScaffold] 统一渲染七态；
 * 数据就绪（成功 / 字段缺失）时渲染 [DetailScreen]。
 */
@Composable
fun DetailRoute(
    modifier: Modifier = Modifier,
    onOpenDoc: (String) -> Unit = {},
    onOpenWork: (String) -> Unit = {},
    viewModel: DetailViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val aiMatch by viewModel.aiMatch.collectAsStateWithLifecycle()
    val recordMessage by viewModel.recordMessage.collectAsStateWithLifecycle()
    StateScaffold(
        state = state,
        modifier = modifier.fillMaxSize(),
        onRetry = viewModel::retry,
        onOpenDoc = onOpenDoc,
    ) { detail ->
        DetailScreen(
            state = detail,
            aiMatch = aiMatch,
            recordMessage = recordMessage,
            onToggleBacklog = viewModel::onToggleBacklog,
            onOpenWork = onOpenWork,
            onAddMainline = viewModel::onAddMainlineToBacklog,
            onAddAllSeries = viewModel::onAddAllSeriesToBacklog,
            onAnalyzeMatchWithAi = viewModel::onAnalyzeMatchWithAi,
            onUpdateMyRecord = viewModel::onUpdateMyRecord,
        )
    }
}

/**
 * 详情页内容（纯展示，无 ViewModel 依赖，便于预览与测试）。
 *
 * 当前覆盖：
 * - 顶部信息区（RC.07.01）：封面 + 标题 + 信息行。
 * - 评分区（RC.07.02 / RC.07.03）：四平台分别展示分数 / 人数，缺失平台显示「暂无数据」且不隐藏整区。
 * - 社区共识卡（RC.07.03 / 9.4）：稳定度 / 争议度 / 优先级；样本不足时低置信 / 暂无、不伪造。
 *
 * 个人区（RC.07.04）、决策区（RC.07.05）与详情 Tab（RC.07.06/07.07）由 task 19.2 / 19.3 接入完成。
 */
@Composable
fun DetailScreen(
    state: DetailUiState,
    modifier: Modifier = Modifier,
    aiMatch: AiMatchUi = AiMatchUi.Idle,
    recordMessage: String? = null,
    onToggleBacklog: () -> Unit = {},
    onOpenWork: (String) -> Unit = {},
    onAddMainline: () -> Unit = {},
    onAddAllSeries: () -> Unit = {},
    onAnalyzeMatchWithAi: () -> Unit = {},
    onUpdateMyRecord: (String?, Int?, Int?, String?, List<String>?, Boolean) -> Unit = { _, _, _, _, _, _ -> },
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            // R96：状态栏 inset 由外层 Scaffold 统一消费（单一来源模型），此处不再叠加 statusBarsPadding。
            // F1：顶部额外 8dp 呼吸（共约 24dp），底部保留 24dp 避免末项贴边。
            .padding(16.dp)
            .padding(top = 8.dp)
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        HeaderSection(header = state.header)
        RatingsSection(ratings = state.ratings)
        ConsensusSection(consensus = state.consensus)
        // P2-4：真实 Bangumi 社区标签区（取代原决策区「适合心情」的间接推断）。
        TagsSection(tags = state.tags)
        PersonalSection(personal = state.personal, recordMessage = recordMessage, onToggleBacklog = onToggleBacklog, onUpdateMyRecord = onUpdateMyRecord)
        DecisionSection(decision = state.decision, aiMatch = aiMatch, onAnalyzeMatchWithAi = onAnalyzeMatchWithAi)
        WatchRouteSection(
            entries = state.routeEntries,
            onOpenWork = onOpenWork,
            onAddMainline = onAddMainline,
            onAddAllSeries = onAddAllSeries,
        )
        DetailTabsSection(tabs = state.tabs)
        CompletionCostSection(completionCost = state.completionCost)
    }
}

/** H：观看路线区——主线必看 / 可选 / 可跳过分区的可点击作品行 + 一键加入待补池。 */
@Composable
private fun WatchRouteSection(
    entries: List<RouteEntryUi>,
    onOpenWork: (String) -> Unit,
    onAddMainline: () -> Unit,
    onAddAllSeries: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (entries.isEmpty()) return
    SectionCard(title = "观看路线（智能选择主线）", modifier = modifier) {
        val sections = listOf(
            RouteSection.MAIN to "主线必看",
            RouteSection.OPTIONAL to "可选",
            RouteSection.SKIPPABLE to "可跳过",
            RouteSection.DERIVED to "衍生作 / 原作（非动画）",
        )
        sections.forEach { (section, label) ->
            val rows = entries.filter { it.section == section }
            if (rows.isNotEmpty()) {
                Text(label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                rows.forEach { row ->
                    Text(
                        text = "· ${row.title}（${row.relationLabel}）",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpenWork(row.workId) }
                            .padding(vertical = 4.dp),
                    )
                }
            }
        }
        Text(
            "路线按关联作品的关系本地推断，仅供参考；不确定处保持「可选」。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onAddMainline) { Text("加入主线必看", maxLines = 1) }
            OutlinedButton(onClick = onAddAllSeries) { Text("加入全系列", maxLines = 1) }
        }
    }
}

// region 顶部信息区（RC.07.01）

@Composable
private fun HeaderSection(
    header: DetailHeader,
    modifier: Modifier = Modifier,
) {
    // L11：点击封面查看大图 + 下载。
    var showCover by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        CoverImage(
            coverUrl = header.coverUrl,
            contentDescription = header.title,
            onClick = if (header.coverUrl != null) {
                { showCover = true }
            } else {
                null
            },
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = header.title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                header.infoRows.forEach { row -> InfoRow(row) }
            }
        }
    }
    if (showCover && header.coverUrl != null) {
        CoverViewerDialog(
            coverUrl = header.coverUrl,
            title = header.title,
            onDismiss = { showCover = false },
            onMessage = { msg ->
                android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
            },
        )
    }
}

@Composable
private fun InfoRow(
    row: DetailInfoRow,
    modifier: Modifier = Modifier,
) {
    val valueColor = if (row.isMissing) {
        MaterialTheme.colorScheme.onSurfaceVariant
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = row.label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(96.dp),
        )
        Text(
            text = row.value,
            style = MaterialTheme.typography.bodyMedium,
            color = valueColor,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun CoverImage(
    coverUrl: String?,
    contentDescription: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier = modifier
            .size(width = 108.dp, height = 144.dp)
            .clip(shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        if (coverUrl != null) {
            AsyncImage(
                model = coverUrl,
                contentDescription = contentDescription,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.BrokenImage,
                    contentDescription = null,
                    modifier = Modifier.size(32.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = NO_DATA,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// endregion

// region 评分区（RC.07.02 / RC.07.03）

@Composable
private fun RatingsSection(
    ratings: List<PlatformRatingUiModel>,
    modifier: Modifier = Modifier,
) {
    SectionCard(title = "多平台评分", modifier = modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ratings.forEach { rating -> PlatformRatingRow(rating) }
        }
    }
}

@Composable
private fun PlatformRatingRow(
    rating: PlatformRatingUiModel,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = rating.platform,
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.width(120.dp),
        )
        Spacer(modifier = Modifier.width(8.dp))
        if (rating.available) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = rating.scoreText.orEmpty(),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
                val voteText = rating.voteText
                if (voteText != null) {
                    Text(
                        text = voteText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            // RC.07.03：缺失平台仍渲染该行并显示「暂无数据」，不隐藏整个评分区。
            Text(
                text = NO_DATA,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// endregion

// region 社区共识卡（RC.07.03 / 9.4）

@Composable
private fun ConsensusSection(
    consensus: ConsensusUiModel,
    modifier: Modifier = Modifier,
) {
    SectionCard(title = "社区共识", modifier = modifier) {
        when (consensus) {
            is ConsensusUiModel.Available -> {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    ConsensusMetricRow(consensus.stability)
                    ConsensusMetricRow(consensus.controversy)
                    ConsensusMetricRow(consensus.priority)
                }
            }

            ConsensusUiModel.Insufficient -> {
                // RC.07.03 / 9.4：样本不足不下结论，低置信 / 暂无，绝不伪造客观结论。
                Text(
                    text = "样本不足，暂不给出共识结论（$NO_DATA）",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ConsensusMetricRow(
    metric: ConsensusMetric,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = metric.label,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = "${metric.percentText} · ${metric.qualitativeText}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LinearProgressIndicator(
            progress = { metric.fraction },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

// endregion

// region 个人区（RC.07.04 / 9.5）

@Composable
private fun PersonalSection(
    personal: PersonalUiModel,
    onToggleBacklog: () -> Unit,
    modifier: Modifier = Modifier,
    recordMessage: String? = null,
    onUpdateMyRecord: (String?, Int?, Int?, String?, List<String>?, Boolean) -> Unit = { _, _, _, _, _, _ -> },
) {
    var showEdit by remember { mutableStateOf(false) }
    SectionCard(title = "我的记录", modifier = modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // F4：作品归属徽章——我的库状态 / 是否在待补池 / 是否在吃灰区，互不混淆。
            MembershipBadges(personal = personal)
            if (personal.recordEditable) {
                PersonalRow(label = "我的状态", value = personal.statusText)
                PersonalRow(label = "我的评分", value = personal.ratingText)
                // N3：仅动画展示「我的进度」（书籍/漫画/游戏隐藏）。
                if (personal.progressEditable) {
                    PersonalRow(label = "我的进度", value = personal.progressText)
                }
                PersonalRow(label = "我的短评", value = personal.reviewText)
                // 标签：无标签时显示「暂无数据」，否则以 chip 展示。
                if (personal.tags.isEmpty()) {
                    PersonalRow(label = "标签", value = null)
                } else {
                    Text(
                        text = "标签",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    ChipFlowRow(items = personal.tags)
                }
                // M：记录可见性——私密时明确标注「仅自己可见」，否则「公开」。
                PersonalRow(label = "可见性", value = if (personal.isPrivate) "仅自己可见（私密）" else "公开")
                // G8/G9/G13：编辑我的记录（状态/评分/进度/短评）并回写 Bangumi。
                OutlinedButton(onClick = { showEdit = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(text = "编辑我的记录")
                }
            } else {
                // N16：无 Bangumi 词条——无法同步个人状态，仅提供加入待补池。
                Text(
                    text = "该作品在 Bangumi 无对应词条，无法记录 / 同步「我的状态·评分」；可先加入待补池。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (recordMessage != null) {
                Text(
                    text = recordMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            // RC.07.04：根据是否已在待补池切换「加入 / 移出待补池」按钮形态。
            if (personal.inBacklog) {
                OutlinedButton(
                    onClick = onToggleBacklog,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(text = "移出待补池")
                }
            } else {
                Button(
                    onClick = onToggleBacklog,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(text = "加入待补池")
                }
            }
        }
    }
    if (showEdit) {
        EditMyRecordDialog(
            personal = personal,
            onDismiss = { showEdit = false },
            onSave = { status, rating, progress, comment, tags, private ->
                onUpdateMyRecord(status, rating, progress, comment, tags, private)
                showEdit = false
            },
        )
    }
}

/**
 * G8/G9/G13：编辑「我的记录」对话框——状态 / 评分 / 进度 / 短评，保存即回写 Bangumi。
 * 数据源统一 Bangumi（不涉及其他源）。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun EditMyRecordDialog(
    personal: PersonalUiModel,
    onDismiss: () -> Unit,
    onSave: (String?, Int?, Int?, String?, List<String>?, Boolean) -> Unit,
) {
    val statuses = listOf("想看", "在看", "看过", "搁置", "抛弃")
    val initRating: String = personal.ratingText?.takeWhile { it.isDigit() } ?: ""
    val initProgress: String = personal.progressText?.filter { it.isDigit() } ?: ""
    var status: String? by remember { mutableStateOf(personal.statusText) }
    var ratingText: String by remember { mutableStateOf(initRating) }
    var progressText: String by remember { mutableStateOf(initProgress) }
    var comment: String by remember { mutableStateOf(personal.reviewText ?: "") }
    var tagsText: String by remember { mutableStateOf(personal.tags.joinToString(" ")) }
    // M：可见性（仅自己可见 / 私密）——回显当前状态，避免保存时误将私密记录改回公开。
    var isPrivate: Boolean by remember { mutableStateOf(personal.isPrivate) }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑我的记录") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "提示：不选状态 = 清空（无状态）；评分 / 进度留空 = 清空。有进度会自动判为在看，看满总集数判为看过。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text("状态", style = MaterialTheme.typography.labelMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    statuses.forEach { s ->
                        androidx.compose.material3.FilterChip(
                            selected = status == s,
                            onClick = { status = if (status == s) null else s },
                            label = { Text(s) },
                        )
                    }
                }
                androidx.compose.material3.OutlinedTextField(
                    value = ratingText,
                    onValueChange = { v: String -> ratingText = v.filter { it.isDigit() }.take(2) },
                    label = { Text("评分 0-10") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (personal.progressEditable) {
                    androidx.compose.material3.OutlinedTextField(
                        value = progressText,
                        onValueChange = { v: String ->
                            val digits = v.filter { it.isDigit() }.take(4)
                            // I6：进度不得超过总集数上限。
                            val capped = digits.toIntOrNull()?.let { n ->
                                personal.totalEpisodes?.let { total -> n.coerceAtMost(total).toString() } ?: digits
                            } ?: digits
                            progressText = capped
                        },
                        label = {
                            Text(
                                personal.totalEpisodes?.let { "进度（话数，共 $it 话）" } ?: "进度（话数）",
                            )
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                androidx.compose.material3.OutlinedTextField(
                    value = comment,
                    onValueChange = { v: String -> comment = v },
                    label = { Text("短评") },
                    modifier = Modifier.fillMaxWidth(),
                )
                androidx.compose.material3.OutlinedTextField(
                    value = tagsText,
                    onValueChange = { v: String -> tagsText = v },
                    label = { Text("标签（空格分隔）") },
                    modifier = Modifier.fillMaxWidth(),
                )
                // M：仅自己可见（私密）开关——开启后本条评分 / 短评 / 标签在 Bangumi 不公开展示。
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("仅自己可见（私密）", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "开启后，本条评分 / 短评 / 标签在 Bangumi 个人主页不公开展示",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = isPrivate, onCheckedChange = { isPrivate = it })
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                val rating: Int? = ratingText.toIntOrNull()?.coerceIn(0, 10)
                val progress: Int? = progressText.toIntOrNull()?.coerceAtLeast(0)
                val commentArg: String? = if (comment.isBlank()) null else comment
                val tagsArg: List<String>? = tagsText.split(Regex("[\\s,，、]+"))
                    .map { it.trim() }.filter { it.isNotEmpty() }.takeIf { it.isNotEmpty() }
                onSave(status, rating, progress, commentArg, tagsArg, isPrivate)
            }) { Text("保存并同步") }
        },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/**
 * F4：作品归属徽章。一行内用 chip 表达三处归属，互不混淆：
 * - 我的库：有任意个人收藏记录则显示状态（如「我的库 · 在看」），否则「未入库」。
 * - 待补池：在 / 不在。
 * - 吃灰区：在吃灰区时才高亮提示。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MembershipBadges(
    personal: PersonalUiModel,
    modifier: Modifier = Modifier,
) {
    val libraryText = when {
        personal.statusText != null -> "我的库 · ${personal.statusText}"
        personal.inLibrary -> "已入我的库"
        else -> "未入我的库"
    }
    val backlogText = if (personal.inBacklog) "待补池 · 已加入" else "待补池 · 未加入"
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AssistChip(onClick = {}, label = { Text(text = libraryText) })
        AssistChip(onClick = {}, label = { Text(text = backlogText) })
        if (personal.inDustMuseum) {
            AssistChip(onClick = {}, label = { Text(text = "吃灰区") })
        }
    }
}

@Composable
private fun PersonalRow(
    label: String,
    value: String?,
    modifier: Modifier = Modifier,
) {
    val isMissing = value == null
    val display = value ?: NO_DATA
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(72.dp),
        )
        Text(
            text = display,
            style = MaterialTheme.typography.bodyMedium,
            color = if (isMissing) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            modifier = Modifier.weight(1f),
        )
    }
}

// endregion

// region 决策区（RC.07.05 / 9.6）

@Composable
private fun DecisionSection(
    decision: DecisionUiModel,
    modifier: Modifier = Modifier,
    aiMatch: AiMatchUi = AiMatchUi.Idle,
    onAnalyzeMatchWithAi: () -> Unit = {},
) {
    SectionCard(title = "决策助手", modifier = modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            TasteMatchBlock(tasteMatch = decision.tasteMatch, aiMatch = aiMatch, onAnalyzeMatchWithAi = onAnalyzeMatchWithAi)
            // F8：补番优先级（可解释：综合口味/社区/补完成本/情绪风险）。
            if (decision.priorityLevel != null) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(text = "补番优先级", style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = "优先级：${decision.priorityLevel}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    decision.priorityReason?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            ReasonsBlock(title = "推荐理由", reasons = decision.recommendReasons)
            ReasonsBlock(title = "需注意 / 不推荐", reasons = decision.notRecommendReasons)
            // 无剧透评价雷达（RC.09.04/05/06）：展示无剧透摘要 + 生成方式 / 剧透等级 / 来源标注 / 置信度；
            // 未生成时摘要为「暂无数据」，绝不伪造。
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(text = "无剧透评价雷达", style = MaterialTheme.typography.titleSmall)
                // 生成方式 + 剧透等级标注。
                val radarMeta = listOfNotNull(
                    decision.spoilerRadarGenerator,
                    decision.spoilerRadarLevel,
                    decision.spoilerRadarConfidenceText,
                ).joinToString(" · ")
                if (radarMeta.isNotEmpty()) {
                    Text(
                        text = radarMeta,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Text(
                    text = decision.spoilerRadarSummary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // 摘要来源标注（短评 / Reviews / 标签 / AI），帮助用户判断摘要可信度（RC.09.06）。
                if (decision.spoilerRadarSources.isNotEmpty()) {
                    Text(
                        text = "来源：${decision.spoilerRadarSources.joinToString("、")}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            // 补完成本一句话摘要。
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(text = "补完成本", style = MaterialTheme.typography.titleSmall)
                Text(
                    text = decision.completionCostText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** P2-4：作品标签区——展示真实 Bangumi 社区标签（按热度序），取代原「适合心情」的间接推断。空则不渲染。 */
@Composable
private fun TagsSection(
    tags: List<String>,
    modifier: Modifier = Modifier,
) {
    if (tags.isEmpty()) return
    SectionCard(title = "标签", modifier = modifier) {
        ChipFlowRow(items = tags)
    }
}

@Composable
private fun TasteMatchBlock(
    tasteMatch: TasteMatchUiModel,
    modifier: Modifier = Modifier,
    aiMatch: AiMatchUi = AiMatchUi.Idle,
    onAnalyzeMatchWithAi: () -> Unit = {},
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // G12：标题行内嵌「AI 分析」按钮——口味匹配度默认本地算法，可点击 AI 给出数值+简评。
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = "口味匹配度", style = MaterialTheme.typography.titleSmall)
            OutlinedButton(
                onClick = onAnalyzeMatchWithAi,
                enabled = aiMatch !is AiMatchUi.Loading,
            ) {
                Text(text = if (aiMatch is AiMatchUi.Loading) "分析中…" else "AI 分析", maxLines = 1)
            }
        }
        // H8：AI 分析完成后，用 AI 的匹配度数值**替换**主匹配度（顶替本地算法值）；否则展示本地算法值。
        if (aiMatch is AiMatchUi.Result) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = "AI 评估", style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = aiMatch.matchPercentText,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            LinearProgressIndicator(progress = { aiMatch.fraction }, modifier = Modifier.fillMaxWidth())
            aiMatch.likedReasons.forEach { Text(text = "+ $it", style = MaterialTheme.typography.bodySmall) }
            aiMatch.riskReasons.forEach {
                Text(text = "- $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                text = "${aiMatch.confidenceText} · 由 AI 分析得出，已替换内置算法估计",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            when (tasteMatch) {
                is TasteMatchUiModel.Available -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = tasteMatch.qualitativeText,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = tasteMatch.percentText,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    LinearProgressIndicator(
                        progress = { tasteMatch.fraction },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text = tasteMatch.reason,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                is TasteMatchUiModel.Unavailable -> {
                    // RC.10.03：未生成 / 样本不足时不伪造匹配度，显示具体原因（R80）。
                    Text(
                        text = tasteMatch.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            // Loading / NotConfigured / Error 提示（Result 已在上方替换主值）。
            AiMatchContent(aiMatch = aiMatch)
        }
    }
}

/**
 * G12/E：「AI 分析匹配度」结果内容（RC.10.03 / RC.14）。仅渲染状态，不含标题/按钮（按钮在
 * [TasteMatchBlock] 标题行）。失败或未配置引导回退本地模型，页面不无响应（RC.17.4）。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AiMatchContent(
    aiMatch: AiMatchUi,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        when (aiMatch) {
            is AiMatchUi.Idle -> Unit // 未触发：不额外显示。
            is AiMatchUi.Loading -> {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp))
                    Text(text = "AI 思考中…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            is AiMatchUi.Result -> {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(text = "AI · ${aiMatch.confidenceText}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(text = aiMatch.matchPercentText, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                }
                aiMatch.likedReasons.forEach { Text(text = "+ $it", style = MaterialTheme.typography.bodySmall) }
                aiMatch.riskReasons.forEach { Text(text = "- $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            is AiMatchUi.NotConfigured -> Text(text = aiMatch.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            is AiMatchUi.Error -> Text(text = aiMatch.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun ReasonsBlock(
    title: String,
    reasons: List<String>,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(text = title, style = MaterialTheme.typography.titleSmall)
        if (reasons.isEmpty()) {
            Text(
                text = NO_DATA,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            reasons.forEach { reason ->
                Text(
                    text = "· $reason",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

// endregion

// region 详情 Tab（RC.07.06 / 9.7）

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetailTabsSection(
    tabs: List<DetailTabUiModel>,
    modifier: Modifier = Modifier,
) {
    if (tabs.isEmpty()) return
    SectionCard(title = "详情", modifier = modifier) {
        var selectedIndex by rememberSaveable { mutableIntStateOf(0) }
        val safeIndex = selectedIndex.coerceIn(0, tabs.lastIndex)
        ScrollableTabRow(
            selectedTabIndex = safeIndex,
            modifier = Modifier.fillMaxWidth(),
            edgePadding = 0.dp,
        ) {
            tabs.forEachIndexed { index, tab ->
                Tab(
                    selected = index == safeIndex,
                    onClick = { selectedIndex = index },
                    text = { Text(text = tab.title) },
                )
            }
        }
        val selected = tabs[safeIndex]
        Text(
            text = selected.body,
            style = MaterialTheme.typography.bodyMedium,
            color = if (selected.available) {
                MaterialTheme.colorScheme.onSurface
            } else {
                // RC.07 9.3：未接入数据源的 Tab 显示「暂无数据」，绝不伪造。
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

// endregion

// region 补完成本（RC.07.07 / 9.8）

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CompletionCostSection(
    completionCost: CompletionCostUiModel,
    modifier: Modifier = Modifier,
) {
    SectionCard(title = "补完成本", modifier = modifier) {
        if (completionCost.available) {
            SuggestionChip(
                onClick = {},
                label = { Text(text = completionCost.bucketLabel) },
            )
            Text(
                text = completionCost.detailText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            // 无足够单位信息计算时显示「暂无数据」（绝不伪造，RC.07 9.8）。
            Text(
                text = NO_DATA,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// endregion

/** 标签 / 心情 chip 流式布局。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipFlowRow(
    items: List<String>,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items.forEach { item ->
            AssistChip(
                onClick = {},
                label = { Text(text = item) },
            )
        }
    }
}

/** 统一的区块卡片容器（标题 + 内容槽）。 */
@Composable
private fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            content()
        }
    }
}

// region 预览

@Preview(showBackground = true)
@Composable
private fun DetailScreenPreview() {
    AcgCompassTheme {
        DetailScreen(
            state = DetailUiState(
                header = DetailHeader(
                    coverUrl = null,
                    title = "葬送的芙莉莲",
                    infoRows = listOf(
                        DetailInfoRow("原名", "葬送のフリーレン", isMissing = false),
                        DetailInfoRow("别名", NO_DATA, isMissing = true),
                        DetailInfoRow("类型", "动画", isMissing = false),
                        DetailInfoRow("年份", "2023", isMissing = false),
                        DetailInfoRow("状态", "已完结", isMissing = false),
                        DetailInfoRow("集数 · 卷数 · 游玩时长", "28 集 · 单集约 24 分钟", isMissing = false),
                    ),
                ),
                ratings = listOf(
                    PlatformRatingUiModel("Bangumi", "8.5", "12,345 人评分"),
                    PlatformRatingUiModel("AniList", "92", "67,890 人评分"),
                    PlatformRatingUiModel("MAL·Jikan", "9.1", "100,000 人评分"),
                    PlatformRatingUiModel("VNDB", null, null),
                ),
                consensus = ConsensusUiModel.Available(
                    stability = ConsensusMetric("评分稳定度", "82%", "较稳定", 0.82f),
                    controversy = ConsensusMetric("争议程度", "18%", "争议较小", 0.18f),
                    priority = ConsensusMetric("补番优先级", "90%", "建议优先", 0.90f),
                ),
                personal = PersonalUiModel(
                    statusText = "在看",
                    ratingText = "9 / 10",
                    progressText = "已看 12 集",
                    reviewText = "节奏舒缓，画面很美。",
                    tags = listOf("奇幻", "治愈", "冒险"),
                    inBacklog = false,
                ),
                decision = DecisionUiModel(
                    tasteMatch = TasteMatchUiModel.Available(
                        percentText = "78%",
                        qualitativeText = "可能会喜欢",
                        fraction = 0.78f,
                        reason = "命中 2 个你高分作品常见的标签，倾向于符合你的口味",
                    ),
                    recommendReasons = listOf("口味匹配度较高，可能合你的胃口", "社区补番优先级较高"),
                    notRecommendReasons = emptyList(),
                    spoilerRadarSummary = NO_DATA,
                    suitableMoods = listOf("治愈", "放松"),
                    completionCostText = "周末补完 · 预计约 11 小时",
                ),
                tabs = listOf(
                    DetailTabUiModel("简介", NO_DATA),
                    DetailTabUiModel("平台数据", "Bangumi：8.5（12,345 人评分）"),
                    DetailTabUiModel("我的记录", "状态：在看\n进度：已看 12 集"),
                ),
                completionCost = CompletionCostUiModel(
                    available = true,
                    bucketLabel = "周末补完",
                    detailText = "预计约 11 小时",
                ),
            ),
        )
    }
}

@Preview(showBackground = true, name = "Insufficient consensus")
@Composable
private fun DetailScreenInsufficientPreview() {
    AcgCompassTheme {
        DetailScreen(
            state = DetailUiState(
                header = DetailHeader(
                    coverUrl = null,
                    title = "某冷门作品",
                    infoRows = listOf(
                        DetailInfoRow("原名", NO_DATA, isMissing = true),
                        DetailInfoRow("类型", "游戏", isMissing = false),
                    ),
                ),
                ratings = listOf(
                    PlatformRatingUiModel("Bangumi", null, null),
                    PlatformRatingUiModel("AniList", null, null),
                    PlatformRatingUiModel("MAL·Jikan", null, null),
                    PlatformRatingUiModel("VNDB", null, null),
                ),
                consensus = ConsensusUiModel.Insufficient,
                personal = PersonalUiModel(
                    statusText = null,
                    ratingText = null,
                    progressText = null,
                    reviewText = null,
                    tags = emptyList(),
                    inBacklog = false,
                ),
                decision = DecisionUiModel(
                    tasteMatch = TasteMatchUiModel.Unavailable("尚未生成口味画像"),
                    recommendReasons = emptyList(),
                    notRecommendReasons = emptyList(),
                    spoilerRadarSummary = NO_DATA,
                    suitableMoods = emptyList(),
                    completionCostText = NO_DATA,
                ),
                tabs = emptyList(),
                completionCost = CompletionCostUiModel(
                    available = false,
                    bucketLabel = NO_DATA,
                    detailText = NO_DATA,
                ),
            ),
        )
    }
}

// endregion
