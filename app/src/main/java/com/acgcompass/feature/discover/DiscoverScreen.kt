package com.acgcompass.feature.discover

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.acgcompass.core.designsystem.AcgCompassTheme
import com.acgcompass.core.designsystem.WorkCard
import com.acgcompass.core.designsystem.WorkCardUiModel
import com.acgcompass.core.ui.AcgTopLevelTopBar
import com.acgcompass.core.ui.ScreenContentPadding
import com.acgcompass.core.ui.StateScaffold
import com.acgcompass.core.ui.UiState

/**
 * 发现 / 搜索页路由入口（RC.05.01/02/03）。连接 [DiscoverViewModel] 并把状态与导航回调下发给
 * 无状态的 [DiscoverScreen]。
 *
 * @param onOpenWork 点击结果进入作品详情（`detailRoute(workId)`）。
 */
@Composable
fun DiscoverRoute(
    onOpenWork: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: DiscoverViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val tab by viewModel.tab.collectAsStateWithLifecycle()
    val rankingBoards by viewModel.rankingBoards.collectAsStateWithLifecycle()
    val scoreDiffItems by viewModel.scoreDiffItems.collectAsStateWithLifecycle()
    val filter by viewModel.filter.collectAsStateWithLifecycle()
    val filterFacets by viewModel.filterFacets.collectAsStateWithLifecycle()
    val filteredCards by viewModel.filteredCards.collectAsStateWithLifecycle()
    val poolLoading by viewModel.poolLoading.collectAsStateWithLifecycle()
    val poolError by viewModel.poolError.collectAsStateWithLifecycle()
    val rankingScope by viewModel.rankingScope.collectAsStateWithLifecycle()
    val scopedRanking by viewModel.scopedRanking.collectAsStateWithLifecycle()

    DiscoverScreen(
        state = state,
        query = query,
        tab = tab,
        rankingBoards = rankingBoards,
        scoreDiffItems = scoreDiffItems,
        filter = filter,
        filterFacets = filterFacets,
        filteredCards = filteredCards,
        poolLoading = poolLoading,
        poolError = poolError,
        rankingScope = rankingScope,
        scopedRanking = scopedRanking,
        onSelectRankingScope = viewModel::onSelectRankingScope,
        onRetryRanking = viewModel::onRetryRanking,
        onLoadPool = viewModel::loadPublicPool,
        onQueryChange = viewModel::onQueryChange,
        onRetry = viewModel::onRetry,
        onOpenWork = onOpenWork,
        onOverrideMatch = viewModel::onOverrideMatch,
        onTabSelect = viewModel::onTabSelect,
        onFilterChange = viewModel::onFilterChange,
        onClearFilter = viewModel::onClearFilter,
        modifier = modifier,
    )
}

/**
 * 无状态发现 / 搜索界面（Material 3，RC.05）。
 *
 * 顶部为搜索框（支持中文 / 日文 / 罗马音 / 英文 / 别名，RC.05.01）；下方按 [UiState] 渲染搜索结果。
 * 每条结果为统一作品卡片并旁标来源 + Match_Confidence（RC.05.02）。低置信结果附「手动选择正确条目」
 * 入口，确认后回调 [onOverrideMatch]（RC.05.03）。
 *
 * 榜单 / 评分差异榜 / 高级筛选（P1）由任务 21.2 接入，此处保留占位提示。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscoverScreen(
    state: UiState<List<DiscoverResultItem>>,
    query: String,
    tab: DiscoverTab,
    rankingBoards: List<RankingBoard>,
    scoreDiffItems: List<ScoreDiffItem>,
    filter: DiscoverFilter,
    filterFacets: FilterFacets,
    filteredCards: List<RankedWork>,
    poolLoading: Boolean,
    poolError: String?,
    rankingScope: RankingScope,
    scopedRanking: UiState<List<RankedWork>>,
    onSelectRankingScope: (RankingScope) -> Unit,
    onRetryRanking: () -> Unit,
    onLoadPool: () -> Unit,
    onQueryChange: (String) -> Unit,
    onRetry: () -> Unit,
    onOpenWork: (String) -> Unit,
    onOverrideMatch: (String, com.acgcompass.domain.model.SourceRef) -> Unit,
    onTabSelect: (DiscoverTab) -> Unit,
    onFilterChange: (DiscoverFilter) -> Unit,
    onClearFilter: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
        topBar = { AcgTopLevelTopBar(title = "发现") },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            DiscoverTabRow(selected = tab, onTabSelect = onTabSelect)

            when (tab) {
                DiscoverTab.SEARCH -> SearchSection(
                    state = state,
                    query = query,
                    onQueryChange = onQueryChange,
                    onRetry = onRetry,
                    onOpenWork = onOpenWork,
                    onOverrideMatch = onOverrideMatch,
                )

                DiscoverTab.RANKING -> RankingSection(
                    boards = rankingBoards,
                    poolLoading = poolLoading,
                    poolError = poolError,
                    rankingScope = rankingScope,
                    scopedRanking = scopedRanking,
                    onSelectRankingScope = onSelectRankingScope,
                    onRetryRanking = onRetryRanking,
                    onLoadPool = onLoadPool,
                    onOpenWork = onOpenWork,
                )

                DiscoverTab.SCORE_DIFF -> ScoreDiffSection(
                    items = scoreDiffItems,
                    poolLoading = poolLoading,
                    poolError = poolError,
                    onLoadPool = onLoadPool,
                    onOpenWork = onOpenWork,
                )

                DiscoverTab.FILTER -> FilterSection(
                    filter = filter,
                    facets = filterFacets,
                    results = filteredCards,
                    onFilterChange = onFilterChange,
                    onClearFilter = onClearFilter,
                    onOpenWork = onOpenWork,
                )
            }
        }
    }
}

/** 发现页分区切换（搜索 / 本季榜单 / 评分差异 / 筛选）。 */
@Composable
private fun DiscoverTabRow(
    selected: DiscoverTab,
    onTabSelect: (DiscoverTab) -> Unit,
) {
    TabRow(selectedTabIndex = selected.ordinal) {
        DiscoverTab.entries.forEach { tab ->
            Tab(
                selected = tab == selected,
                onClick = { onTabSelect(tab) },
                // Q13：标签强制单行，避免「评分差异」被挤成两行。
                text = { Text(tab.label, maxLines = 1, softWrap = false, overflow = TextOverflow.Visible) },
            )
        }
    }
}

/** 搜索分区（RC.05.01/02/03，任务 21.1 行为保持不变）。 */
@Composable
private fun SearchSection(
    state: UiState<List<DiscoverResultItem>>,
    query: String,
    onQueryChange: (String) -> Unit,
    onRetry: () -> Unit,
    onOpenWork: (String) -> Unit,
    onOverrideMatch: (String, com.acgcompass.domain.model.SourceRef) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        SearchBar(query = query, onQueryChange = onQueryChange)

        // J12：移除「管理匹配」按钮（已无用）；多源合并已自动完成，低置信由提示卡引导。
        StateScaffold(
            state = state,
            modifier = Modifier.fillMaxSize(),
            onRetry = onRetry,
            // 空态 CTA：把焦点引导回搜索框（行为由界面消化，无需导航）。
            onCta = {},
        ) { results ->
            ResultList(
                results = results,
                manageMode = false,
                onOpenWork = onOpenWork,
                onOverrideMatch = onOverrideMatch,
            )
        }
    }
}

/** 搜索框（RC.05.01）：单行输入，支持清除；提交关键词由 ViewModel 防抖后触发搜索。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        singleLine = true,
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Filled.Close, contentDescription = "清除")
                }
            }
        },
        placeholder = { Text("搜索作品名（中 / 日 / 罗马音 / 别名）", maxLines = 1, overflow = TextOverflow.Ellipsis) },
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
            imeAction = ImeAction.Search,
        ),
    )
}

@Composable
private fun ResultList(
    results: List<DiscoverResultItem>,
    manageMode: Boolean,
    onOpenWork: (String) -> Unit,
    onOverrideMatch: (String, com.acgcompass.domain.model.SourceRef) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        // F1：底部留白保证最后一条结果不被底栏遮挡（顶层页）。
        contentPadding = PaddingValues(
            start = ScreenContentPadding.Horizontal,
            end = ScreenContentPadding.Horizontal,
            top = ScreenContentPadding.UnderBarTop,
            bottom = ScreenContentPadding.Bottom,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // R2：低置信提示只在顶部统一展示一处，不再每条结果一张大横幅。
        if (results.any { it.isLowConfidence }) {
            item(key = "low_conf_hint") {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    ),
                ) {
                    Text(
                        text = "部分结果匹配置信度较低，自动匹配可能不准确；可点击右上「管理匹配」后选择正确条目。",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }
        }
        items(results, key = { "${it.sourceId}_${it.workId}" }) { item ->
            DiscoverResultCard(
                item = item,
                candidates = results,
                manageMode = manageMode,
                onOpenWork = onOpenWork,
                onOverrideMatch = onOverrideMatch,
            )
        }
    }
}

/**
 * 单条搜索结果：统一作品卡片 + （低置信时）手动纠正横幅与入口（RC.05.02/03）。
 */
@Composable
private fun DiscoverResultCard(
    item: DiscoverResultItem,
    candidates: List<DiscoverResultItem>,
    manageMode: Boolean,
    onOpenWork: (String) -> Unit,
    onOverrideMatch: (String, com.acgcompass.domain.model.SourceRef) -> Unit,
) {
    var showPicker by remember(item.workId, item.sourceId) { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        WorkCard(model = item.card, onClick = { onOpenWork(item.workId) })

        if (manageMode && item.isLowConfidence) {
            // G3：「手动纠正」默认隐藏，仅在「管理匹配模式」下对低置信结果显示。
            TextButton(
                onClick = { showPicker = true },
                modifier = Modifier.align(androidx.compose.ui.Alignment.End),
            ) {
                Text("手动纠正", style = MaterialTheme.typography.labelMedium)
            }
        } else if (manageMode && item.mergeCandidates.size > 1) {
            // F5：多源合并卡的「调整」入口仅在「管理匹配模式」下出现，普通浏览不打扰。
            TextButton(
                onClick = { showPicker = true },
                modifier = Modifier.align(androidx.compose.ui.Alignment.End),
            ) {
                Text("已合并 ${item.mergeCandidates.size} 个来源 · 调整", style = MaterialTheme.typography.labelMedium)
            }
        }
    }

    if (showPicker) {
        MatchPickerDialog(
            target = item,
            candidates = item.mergeCandidates.ifEmpty { candidates },
            onDismiss = { showPicker = false },
            onChoose = { chosen ->
                // 把目标作品的源链接固定为用户选中的条目（userOverridden=true）。
                onOverrideMatch(item.workId, chosen.toSourceRef())
                showPicker = false
            },
        )
    }
}

/** 低置信横幅（RC.05.03）：提示匹配置信度较低，并提供「手动选择正确条目」入口。 */
@Composable
private fun LowConfidenceBanner(onPick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = "匹配置信度较低",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "自动匹配可能不准确，可手动选择正确条目以固定该作品的数据源。",
                style = MaterialTheme.typography.bodySmall,
            )
            TextButton(onClick = onPick) {
                Text("手动选择正确条目")
            }
        }
    }
}

/**
 * 手动纠正选择对话框（RC.05.03）。列出当前搜索的全部候选条目，用户选中一项即作为正确链接。
 * 由调用方写入 `overrideMatch`（`userOverridden=true`）。
 */
@Composable
private fun MatchPickerDialog(
    target: DiscoverResultItem,
    candidates: List<DiscoverResultItem>,
    onDismiss: () -> Unit,
    onChoose: (DiscoverResultItem) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择正确条目") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "为「${target.card.title}」选择正确的数据源条目。",
                    style = MaterialTheme.typography.bodyMedium,
                )
                candidates.forEach { candidate ->
                    OutlinedButton(
                        onClick = { onChoose(candidate) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = "${candidate.sourceId.discoverLabel()} · ${candidate.card.title} · " +
                                confidenceLabel(candidate.matchConfidence),
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@Preview(showBackground = true)
@Composable
private fun DiscoverScreenEmptyPreview() {
    AcgCompassTheme {
        DiscoverScreen(
            state = UiState.Empty(SEARCH_CTA),
            query = "",
            tab = DiscoverTab.SEARCH,
            rankingBoards = emptyList(),
            scoreDiffItems = emptyList(),
            filter = DiscoverFilter(),
            filterFacets = FilterFacets(),
            filteredCards = emptyList(),
            poolLoading = false,
            poolError = null,
            rankingScope = RankingScope.OVERALL,
            scopedRanking = UiState.Empty(SEARCH_CTA),
            onSelectRankingScope = {},
            onRetryRanking = {},
            onLoadPool = {},
            onQueryChange = {},
            onRetry = {},
            onOpenWork = {},
            onOverrideMatch = { _, _ -> },
            onTabSelect = {},
            onFilterChange = {},
            onClearFilter = {},
        )
    }
}

// region 榜单 / 评分差异 / 高级筛选界面（RC.05.04/05/06，任务 21.2）

/** 各源榜单分区（RC.05.04）：M2 顶部为 Bangumi 真实排行（总榜/今年/本季子分类），下方保留本地多源缓存榜单。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RankingSection(
    boards: List<RankingBoard>,
    poolLoading: Boolean,
    poolError: String?,
    rankingScope: RankingScope,
    scopedRanking: UiState<List<RankedWork>>,
    onSelectRankingScope: (RankingScope) -> Unit,
    onRetryRanking: () -> Unit,
    onLoadPool: () -> Unit,
    onOpenWork: (String) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = ScreenContentPadding.Horizontal,
            end = ScreenContentPadding.Horizontal,
            top = ScreenContentPadding.UnderBarTop,
            bottom = ScreenContentPadding.Bottom,
        ),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // M2：Bangumi 真实排行子分类切换。
        item(key = "scope_tabs") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Bangumi 排行榜（按真实排名）",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RankingScope.entries.forEach { scope ->
                        FilterChip(
                            selected = scope == rankingScope,
                            onClick = { onSelectRankingScope(scope) },
                            label = { Text(scope.label) },
                        )
                    }
                }
            }
        }
        // M2：当前范围真实排行（七态）。
        when (val s = scopedRanking) {
            is UiState.Loading -> item(key = "rank_loading") {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    androidx.compose.material3.CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                    )
                    Text("正在加载排行榜…", style = MaterialTheme.typography.bodyMedium)
                }
            }
            is UiState.Success -> {
                itemsIndexed(s.data, key = { _, rw -> "rank_${rw.workId}" }) { index, rw ->
                    RankedWorkCard(rank = index + 1, card = rw.card, onClick = { onOpenWork(rw.workId) })
                }
            }
            is UiState.Empty -> item(key = "rank_empty") {
                EmptyDataPlaceholder("该范围暂无排行数据，换个范围或稍后重试")
            }
            else -> item(key = "rank_error") {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    ),
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("排行榜加载失败", style = MaterialTheme.typography.bodyMedium)
                        OutlinedButton(onClick = onRetryRanking) { Text("重试") }
                    }
                }
            }
        }
        // N5：榜单仅保留 Bangumi 真实排行（其他源榜单后续再加）。
        item(key = "rank_note") {
            Text(
                text = "榜单基于 Bangumi 真实排名（排除未排名条目）。其他平台榜单后续接入。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 评分差异榜分区（RC.05.05）：中性说明 + 差距榜；无数据时显示「暂无数据」。 */
@Composable
private fun ScoreDiffSection(
    items: List<ScoreDiffItem>,
    poolLoading: Boolean,
    poolError: String?,
    onLoadPool: () -> Unit,
    onOpenWork: (String) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        // F1：底部留白保证末项不被底栏遮挡（顶层页）。
        contentPadding = PaddingValues(
            start = ScreenContentPadding.Horizontal,
            end = ScreenContentPadding.Horizontal,
            top = ScreenContentPadding.UnderBarTop,
            bottom = ScreenContentPadding.Bottom,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "pool_status") {
            PublicPoolStatusRow(poolLoading = poolLoading, poolError = poolError, onLoadPool = onLoadPool)
        }
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                ),
            ) {
                Text(
                    text = SCORE_DIFF_NEUTRAL_NOTE,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }
        if (items.isEmpty()) {
            item { EmptyDataPlaceholder("暂无评分差距较大的作品") }
        } else {
            items(items, key = { it.workId }) { diff ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    WorkCard(model = diff.card, onClick = { onOpenWork(diff.workId) })
                    Text(
                        text = "${diff.spreadLabel} · ${diff.perSourceLabels.joinToString(" / ")}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** 高级筛选分区（RC.05.06）：多组 chips 驱动筛选状态，应用到结果列表。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterSection(
    filter: DiscoverFilter,
    facets: FilterFacets,
    results: List<RankedWork>,
    onFilterChange: (DiscoverFilter) -> Unit,
    onClearFilter: () -> Unit,
    onOpenWork: (String) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        // F1：底部留白保证筛选结果末项不被底栏遮挡（顶层页）。
        contentPadding = PaddingValues(
            start = ScreenContentPadding.Horizontal,
            end = ScreenContentPadding.Horizontal,
            top = ScreenContentPadding.UnderBarTop,
            bottom = ScreenContentPadding.Bottom,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // L4：筛选条件默认折叠，避免顶部标签占位过大挤占结果展示空间。
                var expanded by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
                val activeCount = filter.activeDimensionCount()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { expanded = !expanded },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    Text(
                        text = if (activeCount > 0) "筛选条件（已选 $activeCount 项）" else "筛选条件",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = if (expanded) "收起 ▲" else "展开 ▼",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }

                if (expanded) {
                // 类型（RC.05.06）
                FilterGroup(title = "类型") {
                    com.acgcompass.domain.model.MediaType.entries.forEach { type ->
                        ToggleChip(
                            label = type.filterLabel(),
                            selected = type in filter.types,
                            onToggle = { onFilterChange(filter.copy(types = filter.types.toggle(type))) },
                        )
                    }
                }
                // L4：题材（传统分类：热血 / 校园 / 恋爱 …，社区标签 + 常见题材兜底）
                if (facets.genres.isNotEmpty()) {
                    FilterGroup(title = "题材") {
                        facets.genres.forEach { genre ->
                            ToggleChip(
                                label = genre,
                                selected = genre in filter.genres,
                                onToggle = { onFilterChange(filter.copy(genres = filter.genres.toggle(genre))) },
                            )
                        }
                    }
                }
                // 状态（RC.05.06）
                FilterGroup(title = "状态") {
                    com.acgcompass.domain.model.ReleaseStatus.entries.forEach { status ->
                        ToggleChip(
                            label = status.filterLabel(),
                            selected = status in filter.statuses,
                            onToggle = { onFilterChange(filter.copy(statuses = filter.statuses.toggle(status))) },
                        )
                    }
                }
                // 篇幅（RC.05.06）
                if (facets.lengths.isNotEmpty()) {
                    FilterGroup(title = "篇幅") {
                        facets.lengths.forEach { length ->
                            ToggleChip(
                                label = length,
                                selected = length in filter.lengths,
                                onToggle = { onFilterChange(filter.copy(lengths = filter.lengths.toggle(length))) },
                            )
                        }
                    }
                }
                // 评分（RC.05.06）：最低评分阈值（0~10 归一化）
                FilterGroup(title = "评分（不低于）") {
                    listOf(6f, 7f, 8f, 9f).forEach { threshold ->
                        ToggleChip(
                            label = formatScore(threshold),
                            selected = filter.minRating == threshold,
                            onToggle = {
                                val next = if (filter.minRating == threshold) null else threshold
                                onFilterChange(filter.copy(minRating = next))
                            },
                        )
                    }
                }
                // 年份（RC.05.06）
                if (facets.years.isNotEmpty()) {
                    FilterGroup(title = "年份") {
                        facets.years.forEach { year ->
                            ToggleChip(
                                label = year.toString(),
                                selected = year in filter.years,
                                onToggle = { onFilterChange(filter.copy(years = filter.years.toggle(year))) },
                            )
                        }
                    }
                }
                // 完结状态（RC.05.06）
                FilterGroup(title = "完结状态") {
                    FinishedFilter.entries.forEach { option ->
                        ToggleChip(
                            label = option.filterLabel(),
                            selected = filter.finished == option,
                            onToggle = { onFilterChange(filter.copy(finished = option)) },
                        )
                    }
                }
                // 来源平台（RC.05.06）
                FilterGroup(title = "来源平台") {
                    com.acgcompass.domain.model.SourceId.entries.forEach { source ->
                        ToggleChip(
                            label = source.discoverLabel(),
                            selected = source in filter.sources,
                            onToggle = { onFilterChange(filter.copy(sources = filter.sources.toggle(source))) },
                        )
                    }
                }
                // 风险标签（RC.05.06）
                if (facets.riskTags.isNotEmpty()) {
                    FilterGroup(title = "风险标签") {
                        facets.riskTags.forEach { tag ->
                            ToggleChip(
                                label = tag,
                                selected = tag in filter.riskTags,
                                onToggle = { onFilterChange(filter.copy(riskTags = filter.riskTags.toggle(tag))) },
                            )
                        }
                    }
                }
                // 心情标签（RC.05.06）
                if (facets.moodTags.isNotEmpty()) {
                    FilterGroup(title = "心情标签") {
                        facets.moodTags.forEach { tag ->
                            ToggleChip(
                                label = tag,
                                selected = tag in filter.moodTags,
                                onToggle = { onFilterChange(filter.copy(moodTags = filter.moodTags.toggle(tag))) },
                            )
                        }
                    }
                }

                if (!filter.isEmpty) {
                    TextButton(onClick = onClearFilter) { Text("清除全部筛选") }
                }
                } // expanded

                Text(
                    text = "筛选范围：已浏览 / 搜索过的多源作品（公共发现池）· 共 ${results.size} 部",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (results.isEmpty()) {
            item { EmptyDataPlaceholder("没有符合筛选条件的作品") }
        } else {
            itemsIndexed(results, key = { index, rw -> "${index}_${rw.workId}" }) { _, rw ->
                WorkCard(model = rw.card, onClick = { onOpenWork(rw.workId) })
            }
        }
    }
}

@Composable
private fun SourceHeader(label: String) {
    Text(
        text = label,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
    )
}

@Composable
private fun RankedWorkCard(
    rank: Int,
    card: WorkCardUiModel,
    onClick: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = "#$rank",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        WorkCard(model = card, onClick = onClick)
    }
}

/** F10：公共发现池加载状态行（加载中 / 错误+重试 / 提示）。 */
@Composable
private fun PublicPoolStatusRow(
    poolLoading: Boolean,
    poolError: String?,
    onLoadPool: () -> Unit,
) {
    when {
        poolLoading -> Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            androidx.compose.material3.CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
            )
            Text("正在加载本季 / 公共榜单…", style = MaterialTheme.typography.bodyMedium)
        }
        poolError != null -> Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            ),
        ) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("加载失败：$poolError", style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(onClick = onLoadPool) { Text("重试") }
            }
        }
        else -> Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            Text(
                "公共榜单（Jikan / MAL）无需登录即可浏览",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                onClick = onLoadPool,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = 12.dp,
                    vertical = 6.dp,
                ),
            ) {
                Text("刷新", maxLines = 1, softWrap = false)
            }
        }
    }
}

@Composable
private fun EmptyDataPlaceholder(message: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterGroup(
    title: String,
    content: @Composable FlowRowScope.() -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ToggleChip(
    label: String,
    selected: Boolean,
    onToggle: () -> Unit,
) {
    FilterChip(
        selected = selected,
        onClick = onToggle,
        label = { Text(label) },
    )
}

/** 卡片缺少作品 id，列表 key / 点击回调以标题兜底（界面层稳定标识，不影响领域数据）。 */
private fun WorkCardUiModel.titleKey(): String = title

private fun <T> Set<T>.toggle(value: T): Set<T> =
    if (value in this) this - value else this + value

private fun com.acgcompass.domain.model.MediaType.filterLabel(): String = when (this) {
    com.acgcompass.domain.model.MediaType.ANIME -> "动画"
    com.acgcompass.domain.model.MediaType.MANGA -> "漫画"
    com.acgcompass.domain.model.MediaType.NOVEL -> "小说"
    com.acgcompass.domain.model.MediaType.GAME -> "游戏"
    com.acgcompass.domain.model.MediaType.VN -> "视觉小说"
}

private fun com.acgcompass.domain.model.ReleaseStatus.filterLabel(): String = when (this) {
    com.acgcompass.domain.model.ReleaseStatus.UNKNOWN -> "未知"
    com.acgcompass.domain.model.ReleaseStatus.NOT_RELEASED -> "未发布"
    com.acgcompass.domain.model.ReleaseStatus.RELEASING -> "连载中"
    com.acgcompass.domain.model.ReleaseStatus.FINISHED -> "已完结"
    com.acgcompass.domain.model.ReleaseStatus.ON_HIATUS -> "搁置"
    com.acgcompass.domain.model.ReleaseStatus.CANCELLED -> "腰斩"
}

private fun FinishedFilter.filterLabel(): String = when (this) {
    FinishedFilter.ALL -> "全部"
    FinishedFilter.FINISHED -> "已完结"
    FinishedFilter.ONGOING -> "未完结"
}

// endregion
