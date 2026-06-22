package com.acgcompass.feature.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.acgcompass.core.ui.StateScaffold
import com.acgcompass.core.ui.ScreenContentPadding
import com.acgcompass.core.ui.UiState
import com.acgcompass.data.credential.SourceId
import kotlinx.coroutines.launch

/**
 * 设置页（Settings_Screen）的回调集合（RC.02 4.1–4.13）。
 *
 * 凭据/开关相关回调路由到 [SettingsViewModel]；数据清除 / 缓存清除 / 导入 / 导出为**导航级**入口，
 * 由宿主（NavGraph）提供具体实现（RC.15.04），本页只暴露入口。
 */
data class SettingsActions(
    val onNavigateBack: () -> Unit = {},
    val onOpenDoc: (String) -> Unit = {},
    // 数据源卡片
    val onToggleExpand: (SourceId) -> Unit = {},
    val onFieldChange: (SourceId, CredentialFieldKey, String) -> Unit = { _, _, _ -> },
    val onToggleReveal: (SourceId, CredentialFieldKey) -> Unit = { _, _ -> },
    val onSave: (SourceId) -> Unit = {},
    val onClear: (SourceId) -> Unit = {},
    val onTestConnection: (SourceId) -> Unit = {},
    val onToggleChange: (String, Boolean) -> Unit = { _, _ -> },
    // AI Provider 卡片
    val onToggleAiExpand: () -> Unit = {},
    val onAiProviderSelect: (AiProviderOption) -> Unit = {},
    val onAiFieldChange: (CredentialFieldKey, String) -> Unit = { _, _ -> },
    val onToggleAiReveal: (CredentialFieldKey) -> Unit = {},
    val onAiSave: () -> Unit = {},
    val onAiClear: () -> Unit = {},
    val onAiTestConnection: () -> Unit = {},
    // 隐私卡片
    val onTogglePrivacyExpand: () -> Unit = {},
    val onClearLocalData: () -> Unit = {},
    val onClearCache: () -> Unit = {},
    val onImport: () -> Unit = {},
    val onExport: () -> Unit = {},
    val onExportCsv: (com.acgcompass.domain.repository.CsvKind) -> Unit = {},
    val onDismissSaveNotice: () -> Unit = {},
    // Bangumi API 地址（R55/R56）
    val onUseOfficialBangumiApi: () -> Unit = {},
    val onSelectBangumiCandidate: (String) -> Unit = {},
    val onBangumiCustomUrlChange: (String) -> Unit = {},
    val onSaveBangumiCustomUrl: () -> Unit = {},
    val onBangumiConsentChange: (Boolean) -> Unit = {},
    // H7：自动同步间隔（分钟，0=关闭）
    val onSetAutoSyncInterval: (Int) -> Unit = {},
    // Phase④：推荐社区均分下限 / 口味匹配度阈值
    val onSetRecommendMinScore: (Float) -> Unit = {},
    val onSetTasteMatchThreshold: (Float) -> Unit = {},
)

/**
 * 设置页有状态入口：注入 [SettingsViewModel]，收集 [SettingsUiState] 并装配回调（RC.02）。
 *
 * 导航级入口（返回 / 打开文档 / 清除数据 / 缓存 / 导入 / 导出）由调用方传入。
 *
 * _Requirements: 4.1, 4.2, 4.3, 4.4, 4.5, 4.6, 4.7, 4.8, 4.9, 4.10, 4.11, 4.12, 4.13, 16.1_
 */
@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
    onNavigateBack: () -> Unit = {},
    onOpenDoc: (String) -> Unit = {},
    onClearLocalData: () -> Unit = {},
    onClearCache: () -> Unit = {},
    onOpenSource: (String) -> Unit = {},
    expandPrivacy: Boolean = false,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // R10：从「隐私与数据管理」深链进入时自动展开隐私区（兼容旧深链；新结构隐私为独立二级页）。
    androidx.compose.runtime.LaunchedEffect(expandPrivacy) {
        if (expandPrivacy) viewModel.expandPrivacy()
    }
    val actions = rememberSettingsActions(
        viewModel = viewModel,
        onNavigateBack = onNavigateBack,
        onOpenDoc = onOpenDoc,
        onClearLocalData = onClearLocalData,
        onClearCache = onClearCache,
    )
    SettingsScreenContent(
        state = state,
        actions = actions,
        onOpenSource = onOpenSource,
        modifier = modifier,
    )
}

/**
 * F12：设置二级页（Settings_Source_Screen）。
 *
 * 复用主设置页同一套 [SettingsViewModel] 行为与既有卡片 Composable，仅渲染所选数据源的完整编辑器
 * （强制展开），返回后主页面摘要自动刷新（StateFlow 实时驱动）。Bangumi 二级页额外渲染既有的
 * [BangumiApiConfigCard]（官方/社区候选/自定义 + 非官方 Token 风险确认），底层 Token 保护 /
 * 连接测试逻辑保持不变。
 *
 * @param sourceKey 数据源键：[SourceId] 枚举名（含 AI_PROVIDER）或 [PRIVACY_SOURCE_KEY]。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSourceScreen(
    sourceKey: String,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
    onNavigateBack: () -> Unit = {},
    onOpenDoc: (String) -> Unit = {},
    onClearLocalData: () -> Unit = {},
    onClearCache: () -> Unit = {},
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val actions = rememberSettingsActions(
        viewModel = viewModel,
        onNavigateBack = onNavigateBack,
        onOpenDoc = onOpenDoc,
        onClearLocalData = onClearLocalData,
        onClearCache = onClearCache,
    )

    // 「凭据仅保存在本机」提示：与主页面一致（RC.02 4.4）。
    val snackbarHostState = remember { SnackbarHostState() }
    val saveNoticeVisible = (state as? UiState.Success)?.data?.saveNoticeVisible == true
    LaunchedEffect(saveNoticeVisible) {
        if (saveNoticeVisible) {
            snackbarHostState.showSnackbar(SAVE_NOTICE_TEXT)
            actions.onDismissSaveNotice()
        }
    }

    com.acgcompass.core.ui.AcgScreenScaffold(
        title = settingsSourceTitle(sourceKey),
        modifier = modifier,
        largeTitle = false,
        navigationIcon = {
            IconButton(onClick = onNavigateBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        StateScaffold(
            state = state,
            modifier = Modifier.padding(innerPadding),
            onOpenDoc = actions.onOpenDoc,
        ) { data ->
            SettingsSourceDetail(sourceKey = sourceKey, data = data, actions = actions)
        }
    }
}

/**
 * 装配 [SettingsActions]：备份 / 导入 / CSV 的 SAF 启动器、官方文档打开兜底、数据操作结果 Toast。
 * 主设置页与二级页共用同一实现，保证两处行为一致并复用同一套凭据逻辑（F12 / RC.02 4.13）。
 */
@Composable
private fun rememberSettingsActions(
    viewModel: SettingsViewModel,
    onNavigateBack: () -> Unit,
    onOpenDoc: (String) -> Unit,
    onClearLocalData: () -> Unit,
    onClearCache: () -> Unit,
): SettingsActions {
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    // R6：导出备份 — 系统「新建文档」保存器；写入 ViewModel 生成的零凭据 JSON。
    val exportBackupLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val json = viewModel.buildBackupJson()
                if (json != null) {
                    runCatching {
                        context.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) }
                    }
                    viewModel.notifyExported("备份")
                }
            }
        }
    }
    // R6：导入备份 — 系统「打开文档」选择器；读取后合并不覆盖。
    val importBackupLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val json = runCatching {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
                }.getOrNull()
                if (json != null) viewModel.importBackupJson(json)
            }
        }
    }
    // R6：CSV 导出 — 待选种类暂存，文档创建返回后写入。
    var pendingCsvKind by remember { mutableStateOf(com.acgcompass.domain.repository.CsvKind.BACKLOG) }
    val exportCsvLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("text/csv"),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val csv = viewModel.buildCsv(pendingCsvKind)
                if (csv != null) {
                    runCatching {
                        context.contentResolver.openOutputStream(uri)?.use { it.write(csv.toByteArray()) }
                    }
                    viewModel.notifyExported("CSV")
                }
            }
        }
    }

    // 数据操作结果提示（Toast 形式，避免打断）。
    val dataOpMessage by viewModel.dataOpMessage.collectAsStateWithLifecycle()
    androidx.compose.runtime.LaunchedEffect(dataOpMessage) {
        dataOpMessage?.let {
            android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_LONG).show()
            viewModel.clearDataOpMessage()
        }
    }

    // 「查看官方文档」默认用系统浏览器 / Custom Tab 打开；调用方未显式覆盖时生效（RC.02 4.13 / R12）。
    // 绝不携带任何 key/token。打不开时给出明确提示并把链接复制到剪贴板，不再无反应。
    val effectiveOpenDoc: (String) -> Unit = remember(onOpenDoc, uriHandler, context) {
        { url ->
            if (url.isNotBlank()) {
                val opened = runCatching {
                    val intent = android.content.Intent(
                        android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse(url),
                    ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                }.isSuccess
                if (!opened) {
                    val fallback = runCatching { uriHandler.openUri(url) }.isSuccess
                    if (!fallback) {
                        runCatching {
                            val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                                as? android.content.ClipboardManager
                            clipboard?.setPrimaryClip(
                                android.content.ClipData.newPlainText("doc-url", url),
                            )
                        }
                        android.widget.Toast.makeText(
                            context,
                            "未找到可用浏览器，链接已复制到剪贴板：$url",
                            android.widget.Toast.LENGTH_LONG,
                        ).show()
                    }
                }
            }
            onOpenDoc(url)
        }
    }
    val actions = remember(viewModel, effectiveOpenDoc) {
        SettingsActions(
            onNavigateBack = onNavigateBack,
            onOpenDoc = effectiveOpenDoc,
            onToggleExpand = viewModel::onToggleExpand,
            onFieldChange = viewModel::onFieldChange,
            onToggleReveal = viewModel::onToggleReveal,
            onSave = viewModel::onSave,
            onClear = viewModel::onClear,
            onTestConnection = viewModel::onTestConnection,
            onToggleChange = viewModel::onToggleChange,
            onToggleAiExpand = viewModel::onToggleAiExpand,
            onAiProviderSelect = viewModel::onAiProviderSelect,
            onAiFieldChange = viewModel::onAiFieldChange,
            onToggleAiReveal = viewModel::onToggleAiReveal,
            onAiSave = viewModel::onAiSave,
            onAiClear = viewModel::onAiClear,
            onAiTestConnection = viewModel::onAiTestConnection,
            onTogglePrivacyExpand = viewModel::onTogglePrivacyExpand,
            onClearLocalData = onClearLocalData,
            onClearCache = onClearCache,
            onImport = { importBackupLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) },
            onExport = { exportBackupLauncher.launch("acgcompass-backup.json") },
            onExportCsv = { kind ->
                pendingCsvKind = kind
                val name = when (kind) {
                    com.acgcompass.domain.repository.CsvKind.BACKLOG -> "acgcompass-backlog.csv"
                    com.acgcompass.domain.repository.CsvKind.RATINGS -> "acgcompass-ratings.csv"
                    com.acgcompass.domain.repository.CsvKind.TIME_MACHINE -> "acgcompass-timemachine.csv"
                }
                exportCsvLauncher.launch(name)
            },
            onDismissSaveNotice = viewModel::onDismissSaveNotice,
            onUseOfficialBangumiApi = viewModel::onUseOfficialBangumiApi,
            onSelectBangumiCandidate = viewModel::onSelectBangumiCandidate,
            onBangumiCustomUrlChange = viewModel::onBangumiCustomUrlChange,
            onSaveBangumiCustomUrl = viewModel::onSaveBangumiCustomUrl,
            onBangumiConsentChange = viewModel::onBangumiConsentChange,
            onSetAutoSyncInterval = viewModel::onSetAutoSyncInterval,
            onSetRecommendMinScore = viewModel::onSetRecommendMinScore,
            onSetTasteMatchThreshold = viewModel::onSetTasteMatchThreshold,
        )
    }
    return actions
}

/** 「凭据仅保存在本机」提示文案（RC.02 4.4 / RC.00）。 */
private const val SAVE_NOTICE_TEXT = "凭据仅保存在本机，用于直接向第三方服务请求数据"

/** Phase④：推荐社区均分下限选项（标签 to 分值）。0=不限。 */
private val RecommendMinScoreOptions: List<Pair<String, Float>> = listOf(
    "不限" to 0f,
    "5.0" to 5.0f,
    "6.0" to 6.0f,
    "6.5" to 6.5f,
    "7.0" to 7.0f,
    "7.5" to 7.5f,
    "8.0" to 8.0f,
)

/** Phase④：口味匹配度阈值选项（标签 to 阈值）。0=关闭过滤（不自动重 roll）。 */
private val TasteMatchThresholdOptions: List<Pair<String, Float>> = listOf(
    "关闭" to 0f,
    "40%" to 0.4f,
    "55%" to 0.55f,
    "65%" to 0.65f,
    "75%" to 0.75f,
)

/** H7：自动同步间隔选项（标签 to 分钟）。0=关闭；WorkManager 周期下限为 15 分钟。 */
private val AutoSyncIntervalOptions: List<Pair<String, Int>> = listOf(
    "关闭" to 0,
    "6 小时" to 360,
    "12 小时" to 720,
    "24 小时" to 1440,
)

/**
 * 设置页无状态内容：顶部栏 + 各源/AI/隐私可折叠卡片 + 保存提示（RC.02 4.1）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreenContent(
    state: UiState<SettingsUiState>,
    actions: SettingsActions,
    onOpenSource: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val saveNoticeVisible = (state as? UiState.Success)?.data?.saveNoticeVisible == true
    LaunchedEffect(saveNoticeVisible) {
        if (saveNoticeVisible) {
            snackbarHostState.showSnackbar(SAVE_NOTICE_TEXT)
            actions.onDismissSaveNotice()
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = actions.onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                windowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        StateScaffold(
            state = state,
            modifier = Modifier.padding(padding),
            onOpenDoc = actions.onOpenDoc,
        ) { data ->
            SettingsList(data = data, actions = actions, onOpenSource = onOpenSource)
        }
    }
}

/**
 * F12：主设置页改为「摘要行」列表（RC.02）。每行展示源名 + 状态（公共搜索可用 / 个人同步是否配置）+
 * 最后测试时间 + 进入按钮，点击进入对应二级页编辑完整凭据；不再在主页内联展开所有编辑器。
 */
@Composable
private fun SettingsList(
    data: SettingsUiState,
    actions: SettingsActions,
    onOpenSource: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        // F1：父级已消费标题栏 innerPadding；统一留白（顶部 16dp 呼吸 + 底部 96dp 不被底栏/手势条遮挡）。
        contentPadding = PaddingValues(
            start = ScreenContentPadding.Horizontal,
            end = ScreenContentPadding.Horizontal,
            top = ScreenContentPadding.Top,
            bottom = ScreenContentPadding.Bottom,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(items = data.cards, key = { it.sourceId.name }) { card ->
            SettingsSummaryRow(
                title = card.title,
                statusText = card.statusText,
                lastTestedAtText = card.lastTestedAtText,
                onClick = { onOpenSource(card.sourceId.name) },
            )
        }
        item(key = "ai_provider") {
            SettingsSummaryRow(
                title = "AI Provider",
                statusText = data.aiProvider.statusText,
                lastTestedAtText = data.aiProvider.lastTestedAtText,
                onClick = { onOpenSource(SourceId.AI_PROVIDER.name) },
            )
        }
        item(key = "privacy") {
            SettingsSummaryRow(
                title = "隐私与数据",
                statusText = "数据管理、备份导入导出与隐私开关",
                lastTestedAtText = null,
                onClick = { onOpenSource(PRIVACY_SOURCE_KEY) },
            )
        }
    }
}

/** 设置二级页的标识键：[SourceId] 枚举名复用为源键；隐私页用独立常量。 */
internal const val PRIVACY_SOURCE_KEY = "PRIVACY"

/** 二级页标题映射（静态，避免依赖异步状态；与各源卡片标题保持一致）。 */
internal fun settingsSourceTitle(sourceKey: String): String = when (sourceKey) {
    SourceId.BANGUMI.name -> "Bangumi"
    SourceId.ANILIST.name -> "AniList"
    SourceId.JIKAN.name -> "Jikan"
    SourceId.MAL.name -> "MyAnimeList（官方）"
    SourceId.VNDB.name -> "VNDB"
    SourceId.AI_PROVIDER.name -> "AI Provider"
    PRIVACY_SOURCE_KEY -> "隐私与数据"
    else -> "设置"
}

/**
 * 主设置页的摘要行（F12）：源名 + 状态文案 + 最后测试时间 + 「进入」按钮，整行可点。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsSummaryRow(
    title: String,
    statusText: String?,
    lastTestedAtText: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(onClick = onClick, modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
                if (!statusText.isNullOrBlank()) {
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                if (!lastTestedAtText.isNullOrBlank()) {
                    Text(
                        text = "最后测试：$lastTestedAtText",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            TextButton(onClick = onClick) {
                Text("进入", maxLines = 1)
                Icon(
                    imageVector = Icons.Outlined.ChevronRight,
                    contentDescription = null,
                )
            }
        }
    }
}

/**
 * 二级页内容：按 [sourceKey] 渲染对应的既有详细卡片，并强制展开。
 * Bangumi 额外渲染 [BangumiApiConfigCard]，保留官方/社区/自定义下拉与非官方 Token 风险确认。
 */
@Composable
private fun SettingsSourceDetail(
    sourceKey: String,
    data: SettingsUiState,
    actions: SettingsActions,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = ScreenContentPadding.Horizontal,
            end = ScreenContentPadding.Horizontal,
            top = ScreenContentPadding.Top,
            bottom = ScreenContentPadding.Bottom,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        when (sourceKey) {
            SourceId.AI_PROVIDER.name -> item(key = "ai_provider") {
                AiProviderSettingsCard(card = data.aiProvider.copy(expanded = true), actions = actions)
            }
            PRIVACY_SOURCE_KEY -> item(key = "privacy") {
                PrivacySettingsCard(card = data.privacy.copy(expanded = true), actions = actions)
            }
            else -> {
                val card = data.cards.firstOrNull { it.sourceId.name == sourceKey }
                if (card != null) {
                    item(key = "source") {
                        SourceSettingsCard(card = card.copy(expanded = true), actions = actions)
                    }
                    if (card.sourceId == SourceId.BANGUMI) {
                        item(key = "bangumi_api") {
                            BangumiApiConfigCard(state = data.bangumiApi, actions = actions)
                        }
                    }
                }
            }
        }
    }
}

// region 可复用基础块

/** 可折叠卡片骨架：标题行（标题 + 展开箭头）+ 状态行（独立一行，不挤压标题）+ 展开内容（RC.02 4.1 / R26）。 */
@Composable
internal fun CollapsibleCard(
    title: String,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    modifier: Modifier = Modifier,
    statusText: String? = null,
    content: @Composable () -> Unit,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            // 行1：标题 + 展开箭头。标题占满剩余宽度并单行省略，绝不被状态文字挤成竖排（R26）。
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = if (expanded) "$title 已展开" else "$title 已折叠" },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onToggleExpand) {
                    Icon(
                        imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (expanded) "折叠" else "展开",
                    )
                }
            }
            // 行2：状态文案（独立成行，可换行，不再用会挤压标题的等宽 Chip）。
            if (statusText != null) {
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 2.dp),
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    content()
                }
            }
        }
    }
}

/**
 * 凭据输入框：敏感字段默认掩码（[PasswordVisualTransformation]），尾部提供临时显示开关（RC.02 4.2/4.3）。
 * 非敏感字段（Base URL / 模型名）始终明文。
 */
@Composable
internal fun CredentialField(
    field: CredentialFieldState,
    onValueChange: (String) -> Unit,
    onToggleReveal: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = field.value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        label = { Text(field.label + if (field.optional) "（可选）" else "") },
        placeholder = {
            // R-new2：已保存的敏感字段以掩码占位提示，引导用户「重新输入以更换」。
            val ph = if (field.savedMasked) "已保存 ••••••（重新输入以更换）" else field.placeholder
            if (ph.isNotEmpty()) Text(ph)
        },
        singleLine = true,
        visualTransformation = if (field.sensitive && !field.revealed) {
            PasswordVisualTransformation()
        } else {
            VisualTransformation.None
        },
        trailingIcon = if (field.sensitive && !field.savedMasked) {
            {
                IconButton(onClick = onToggleReveal) {
                    Icon(
                        imageVector = if (field.revealed) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = if (field.revealed) "隐藏 ${field.label}" else "临时显示 ${field.label}",
                    )
                }
            }
        } else {
            null
        },
    )
}

/** 单个开关项（数据源启用 / 同步 / 列表读取 / 成人内容 / 隐私开关等）。 */
@Composable
internal fun ToggleRow(
    label: String,
    description: String?,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, style = MaterialTheme.typography.bodyLarge)
            if (description != null) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

/**
 * 连接测试三态结果（RC.02 4.4/4.5）：
 * - TESTING → 进度指示 + 「测试中」。
 * - SUCCESS → 成功文案。
 * - FAILED → 失败原因 + 「查看文档」入口。
 * - IDLE → 不渲染。
 */
@Composable
internal fun ConnectionTestResultRow(
    test: ConnectionTestState,
    onOpenDoc: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    when (test.status) {
        ConnectionTestStatus.IDLE -> Unit
        ConnectionTestStatus.TESTING -> Row(
            modifier = modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            Text("连接测试中…", style = MaterialTheme.typography.bodyMedium)
        }

        ConnectionTestStatus.SUCCESS -> Row(
            modifier = modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = test.message ?: "连接成功",
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        ConnectionTestStatus.FAILED -> Column(
            modifier = modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.ErrorOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
                Text(
                    text = test.message ?: "连接失败",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            val docUrl = test.docUrl
            if (!docUrl.isNullOrEmpty()) {
                DocLinkButton(url = docUrl, onOpenDoc = onOpenDoc)
            }
        }
    }
}

/** 「查看官方文档」入口按钮（RC.02 4.13）。绝不携带任何 key/token。 */
@Composable
internal fun DocLinkButton(
    url: String,
    onOpenDoc: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String = "查看官方文档",
) {
    TextButton(onClick = { onOpenDoc(url) }, modifier = modifier) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.OpenInNew,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = label,
            modifier = Modifier.padding(start = 6.dp),
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
    }
}

// endregion

// region 数据源卡片（RC.02 4.6–4.9/4.13）

/**
 * 单个数据源的可折叠设置卡片：用户名（Bangumi）、凭据字段（掩码 + 临时显示）、开关、提示信息、
 * 连接测试三态、保存 / 清除按钮、官方文档链接（RC.02 4.6–4.9/4.13）。
 */
@Composable
internal fun SourceSettingsCard(
    card: SourceCardState,
    actions: SettingsActions,
    modifier: Modifier = Modifier,
) {
    CollapsibleCard(
        title = card.title,
        statusText = card.statusText,
        expanded = card.expanded,
        onToggleExpand = { actions.onToggleExpand(card.sourceId) },
        modifier = modifier,
    ) {
        // 当前用户名（仅 Bangumi，RC.02 4.6）；暂无能力时显示「暂无数据」。
        if (card.showUsername) {
            Text(
                text = "当前用户名：" + (card.username ?: "暂无数据"),
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        card.lastTestedAtText?.let {
            Text(
                text = "最后测试：$it",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // 凭据输入字段（默认掩码 + 临时显示，RC.02 4.2/4.3）。
        card.fields.forEach { field ->
            CredentialField(
                field = field,
                onValueChange = { actions.onFieldChange(card.sourceId, field.key, it) },
                onToggleReveal = { actions.onToggleReveal(card.sourceId, field.key) },
            )
        }

        // 源相关开关（启用 / 同步 / 列表读取 / 成人内容；MAL 仅配置后可用，RC.02 4.8）。
        card.toggles.forEach { toggle ->
            ToggleRow(
                label = toggle.label,
                description = toggle.description,
                checked = toggle.checked,
                enabled = toggle.enabled,
                onCheckedChange = { actions.onToggleChange(toggle.key, it) },
            )
        }

        // 提示信息（Jikan 频率/降级、MAL OAuth/PKCE、VNDB 分级过滤等）。
        card.infoNotes.forEach { note ->
            Text(
                text = note,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        ConnectionTestResultRow(test = card.test, onOpenDoc = actions.onOpenDoc)

        HorizontalDivider()

        // 操作区：保存（有凭据字段时）/ 连接测试 / 清除 / 文档链接。
        if (card.fields.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { actions.onSave(card.sourceId) },
                    enabled = card.hasDraft,
                ) {
                    Text("保存")
                }
                OutlinedButton(
                    onClick = { actions.onTestConnection(card.sourceId) },
                    enabled = card.test.status != ConnectionTestStatus.TESTING,
                ) {
                    Text("连接测试")
                }
            }
            if (card.configured) {
                TextButton(onClick = { actions.onClear(card.sourceId) }) {
                    Text(text = if (card.sourceId == SourceId.BANGUMI) "清除授权" else "清除")
                }
            }
        } else {
            // 无凭据源（Jikan）：仅提供连接测试。
            OutlinedButton(
                onClick = { actions.onTestConnection(card.sourceId) },
                enabled = card.test.status != ConnectionTestStatus.TESTING,
            ) {
                Text("连接测试")
            }
        }

        DocLinkButton(url = card.docUrl, onOpenDoc = actions.onOpenDoc)
    }
}

// endregion

// region AI Provider 卡片（RC.02 4.10 / RC.14.01）

/**
 * AI Provider 设置卡片：Provider 选择、API key（掩码）、Base URL、模型名、连接测试、预算提醒、
 * 保存 / 清除 / 文档链接（RC.02 4.10 / RC.14.01）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AiProviderSettingsCard(
    card: AiProviderCardState,
    actions: SettingsActions,
    modifier: Modifier = Modifier,
) {
    CollapsibleCard(
        title = "AI Provider",
        statusText = card.statusText,
        expanded = card.expanded,
        onToggleExpand = actions.onToggleAiExpand,
        modifier = modifier,
    ) {
        // Provider 选择下拉（OpenAI / Gemini / DeepSeek / OpenRouter / 自定义）。
        var dropdownExpanded by remember { mutableStateOf(false) }
        ExposedDropdownMenuBox(
            expanded = dropdownExpanded,
            onExpandedChange = { dropdownExpanded = it },
        ) {
            OutlinedTextField(
                value = card.selectedProvider.displayName,
                onValueChange = {},
                readOnly = true,
                label = { Text("Provider") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = dropdownExpanded) },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable),
            )
            ExposedDropdownMenu(
                expanded = dropdownExpanded,
                onDismissRequest = { dropdownExpanded = false },
            ) {
                card.options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option.displayName) },
                        onClick = {
                            actions.onAiProviderSelect(option)
                            dropdownExpanded = false
                        },
                    )
                }
            }
        }

        card.lastTestedAtText?.let {
            Text(
                text = "最后测试：$it",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // API key（掩码）+ Base URL + 模型名。
        card.fields.forEach { field ->
            CredentialField(
                field = field,
                onValueChange = { actions.onAiFieldChange(field.key, it) },
                onToggleReveal = { actions.onToggleAiReveal(field.key) },
            )
        }

        // 预算/用量提醒（RC.14.01）。
        Text(
            text = card.budgetReminder,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        ConnectionTestResultRow(test = card.test, onOpenDoc = actions.onOpenDoc)

        HorizontalDivider()

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = actions.onAiSave, enabled = card.hasDraft) {
                Text("保存")
            }
            OutlinedButton(
                onClick = actions.onAiTestConnection,
                enabled = card.test.status != ConnectionTestStatus.TESTING,
            ) {
                Text("测试")
            }
        }
        if (card.configured) {
            TextButton(onClick = actions.onAiClear) { Text("清除") }
        }

        DocLinkButton(url = card.docUrl, onOpenDoc = actions.onOpenDoc)
    }
}

// endregion

// region 隐私卡片（RC.02 4.11/4.12 / RC.15.04）

/**
 * 隐私控制卡片：是否允许 AI 分析评论、是否记录本地时光机快照两个开关，
 * 以及本地数据清除 / 缓存清除 / 导入 / 导出导航入口（RC.02 4.11/4.12 / RC.15.04）。
 */
@Composable
internal fun PrivacySettingsCard(
    card: PrivacyCardState,
    actions: SettingsActions,
    modifier: Modifier = Modifier,
) {
    CollapsibleCard(
        title = "隐私与数据",
        expanded = card.expanded,
        onToggleExpand = actions.onTogglePrivacyExpand,
        modifier = modifier,
    ) {
        ToggleRow(
            label = "允许 AI 分析评论",
            description = "关闭时不会将评论内容发送给 AI Provider（默认关闭）",
            checked = card.allowAiAnalyzeReviews,
            enabled = true,
            onCheckedChange = { actions.onToggleChange(ToggleKeys.ALLOW_AI_ANALYZE_REVIEWS, it) },
        )
        ToggleRow(
            label = "记录本地时光机快照",
            description = "在本机记录评分、短评与状态变化，用于年度报告",
            checked = card.recordTimeMachineSnapshots,
            enabled = true,
            onCheckedChange = { actions.onToggleChange(ToggleKeys.RECORD_TIME_MACHINE_SNAPSHOTS, it) },
        )

        HorizontalDivider()

        // H7：自动同步间隔。0=关闭；其余为周期（WorkManager 最小 15 分钟）。
        Text(text = "自动同步 Bangumi", style = MaterialTheme.typography.titleSmall)
        Text(
            text = "按所选间隔在后台自动拉取最新收藏（需已配置 Bangumi 账号、联网）。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        ) {
            AutoSyncIntervalOptions.forEach { (label, minutes) ->
                FilterChip(
                    selected = card.autoSyncIntervalMinutes == minutes,
                    onClick = { actions.onSetAutoSyncInterval(minutes) },
                    label = { Text(label, maxLines = 1) },
                )
            }
        }

        HorizontalDivider()

        // Phase④：推荐与口味偏好——推荐最低社区分 + 口味匹配度阈值（低于则不推，相当于自动重 roll）。
        Text(text = "推荐与口味", style = MaterialTheme.typography.titleSmall)
        Text(
            text = "「今晚看什么」只推荐社区均分 ≥ 下限、且口味匹配度 ≥ 阈值的作品；阈值越高越严格（结果可能更少）。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text = "推荐最低社区分", style = MaterialTheme.typography.bodyMedium)
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        ) {
            RecommendMinScoreOptions.forEach { (label, score) ->
                FilterChip(
                    selected = kotlin.math.abs(card.recommendMinCommunityScore - score) < 0.01f,
                    onClick = { actions.onSetRecommendMinScore(score) },
                    label = { Text(label, maxLines = 1) },
                )
            }
        }
        Text(text = "口味匹配度阈值（低于则不推）", style = MaterialTheme.typography.bodyMedium)
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        ) {
            TasteMatchThresholdOptions.forEach { (label, threshold) ->
                FilterChip(
                    selected = kotlin.math.abs(card.tasteMatchThreshold - threshold) < 0.01f,
                    onClick = { actions.onSetTasteMatchThreshold(threshold) },
                    label = { Text(label, maxLines = 1) },
                )
            }
        }

        HorizontalDivider()

        Text(
            text = "数据管理",
            style = MaterialTheme.typography.titleSmall,
        )
        OutlinedButton(onClick = actions.onExport, modifier = Modifier.fillMaxWidth()) {
            Text("导出备份（默认不含 key）")
        }
        OutlinedButton(onClick = actions.onImport, modifier = Modifier.fillMaxWidth()) {
            Text("导入备份")
        }
        OutlinedButton(
            onClick = { actions.onExportCsv(com.acgcompass.domain.repository.CsvKind.BACKLOG) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("导出 CSV · 待补池", maxLines = 1) }
        OutlinedButton(
            onClick = { actions.onExportCsv(com.acgcompass.domain.repository.CsvKind.RATINGS) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("导出 CSV · 评分", maxLines = 1) }
        OutlinedButton(
            onClick = { actions.onExportCsv(com.acgcompass.domain.repository.CsvKind.TIME_MACHINE) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("导出 CSV · 时光机", maxLines = 1) }
        OutlinedButton(onClick = actions.onClearCache, modifier = Modifier.fillMaxWidth()) {
            Text("清除缓存")
        }
        OutlinedButton(onClick = actions.onClearLocalData, modifier = Modifier.fillMaxWidth()) {
            Text("清除本地数据")
        }
    }
}

// endregion

// region Bangumi API 地址（R55/R56）

/**
 * Bangumi API 地址配置卡（R55/R56 + 第 6 轮续修：并列按钮改为单个下拉框）。
 * 下拉选项：官方 API（默认）/ bgmapi.anibt.net / api.bangumi.one / 自定义 API 地址。
 * 选「自定义」才显示输入框；非官方地址显示风险提示与 Token 同意开关（底层保护逻辑不变）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BangumiApiConfigCard(
    state: BangumiApiUiState,
    actions: SettingsActions,
    modifier: Modifier = Modifier,
) {
    val officialOption = "官方 API（默认）"
    val customOption = "自定义 API 地址"
    // 下拉项：官方 + 候选 + 自定义。
    val options = buildList {
        add(officialOption)
        state.candidates.forEach { (label, _) -> add("$label（社区反代）") }
        add(customOption)
    }
    val currentLabel = when (state.mode) {
        BangumiApiMode.OFFICIAL -> officialOption
        BangumiApiMode.CUSTOM -> customOption
        BangumiApiMode.COMMUNITY -> state.candidates
            .firstOrNull { it.second.trimEnd('/') == state.baseUrl.trimEnd('/') }
            ?.let { "${it.first}（社区反代）" } ?: customOption
    }
    var customMode by remember(state.mode) { mutableStateOf(state.mode == BangumiApiMode.CUSTOM) }
    var expanded by remember { mutableStateOf(false) }

    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Bangumi API 地址", style = MaterialTheme.typography.titleMedium)
            Text(
                "当前：${state.baseUrl}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "官方地址在部分网络环境可能无法直连，可改用社区反代或自建反代。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { expanded = it },
            ) {
                OutlinedTextField(
                    value = currentLabel,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("API 地址模式") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                )
                ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    options.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option) },
                            onClick = {
                                expanded = false
                                when (option) {
                                    officialOption -> { customMode = false; actions.onUseOfficialBangumiApi() }
                                    customOption -> customMode = true
                                    else -> {
                                        customMode = false
                                        val idx = options.indexOf(option) - 1
                                        state.candidates.getOrNull(idx)?.let { actions.onSelectBangumiCandidate(it.second) }
                                    }
                                }
                            },
                        )
                    }
                }
            }

            if (customMode) {
                OutlinedTextField(
                    value = state.customDraft,
                    onValueChange = actions.onBangumiCustomUrlChange,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("自定义 API 地址") },
                    placeholder = { Text("https://your-proxy.example.com/") },
                )
                Button(onClick = actions.onSaveBangumiCustomUrl, modifier = Modifier.fillMaxWidth()) {
                    Text("保存自定义地址", maxLines = 1)
                }
            }

            if (!state.isOfficial) {
                HorizontalDivider()
                Text(
                    "你正在使用非官方 Bangumi API 地址。个人 Token 会发送到该地址，请确认你信任该服务。" +
                        "建议优先使用官方 API、自己的代理或自建反代。公共搜索可用非官方地址；个人同步需在下方确认后才生效。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                ToggleRow(
                    label = "我已知晓并同意个人 Token 发往该非官方地址",
                    description = "用于个人同步（getMe / 收藏）。不勾选则仅公共搜索使用该地址。",
                    checked = state.consent,
                    enabled = true,
                    onCheckedChange = actions.onBangumiConsentChange,
                )
            }
        }
    }
}

// endregion
