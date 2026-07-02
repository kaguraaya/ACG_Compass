package com.acgcompass.feature.explore

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** 探索队列路由（RC.05 续 / 算法文档「探索队列」）。route 无参。 */
const val EXPLORE_QUEUE_ROUTE: String = "explore_queue"

/**
 * 探索队列路由入口：连接 [ExploreQueueViewModel]，把状态与回调下发给无状态 [ExploreQueueScreen]。
 *
 * @param onOpenWork 点击卡片进入作品详情。
 * @param onBack 返回（顶栏返回箭头 / 空态返回）。
 */
@Composable
fun ExploreQueueRoute(
    onOpenWork: (String) -> Unit,
    onBack: () -> Unit,
    viewModel: ExploreQueueViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    ExploreQueueScreen(
        state = state,
        onLike = viewModel::onLike,
        onSkip = viewModel::onSkip,
        onRegenerate = viewModel::generate,
        onOpenWork = onOpenWork,
        onBack = onBack,
    )
}

/**
 * 探索队列页（无状态）。卡片堆叠 + 左右滑手势（左滑加入待补池 / 右滑暂不感兴趣）+ 底部操作按钮；
 * 口味匹配度为视觉核心（大字 + 进度条），社区评分弱化到详情页。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExploreQueueScreen(
    state: ExploreQueueUiState,
    onLike: () -> Unit,
    onSkip: () -> Unit,
    onRegenerate: () -> Unit,
    onOpenWork: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("探索队列", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 20.dp),
            contentAlignment = Alignment.Center,
        ) {
            when (state) {
                is ExploreQueueUiState.Loading ->
                    CircularProgressIndicator()

                is ExploreQueueUiState.Empty ->
                    EmptyOrError(message = state.message, onRetry = onRegenerate, onBack = onBack)

                is ExploreQueueUiState.Error ->
                    EmptyOrError(message = state.message, onRetry = onRegenerate, onBack = onBack)

                is ExploreQueueUiState.Finished ->
                    FinishedContent(liked = state.liked, skipped = state.skipped, onRegenerate = onRegenerate, onBack = onBack)

                is ExploreQueueUiState.Ready -> {
                    val card = state.cards.getOrNull(state.index)
                    if (card == null) {
                        FinishedContent(liked = 0, skipped = 0, onRegenerate = onRegenerate, onBack = onBack)
                    } else {
                        ReadyContent(
                            card = card,
                            position = state.index + 1,
                            total = state.cards.size,
                            onLike = onLike,
                            onSkip = onSkip,
                            onOpenWork = onOpenWork,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ReadyContent(
    card: ExploreCardUiModel,
    position: Int,
    total: Int,
    onLike: () -> Unit,
    onSkip: () -> Unit,
    onOpenWork: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    // key 绑定 workId：切换到下一张时重建归零，避免位移残留。
    val offsetX = remember(card.workId) { Animatable(0f) }
    val swipeThreshold = 220f

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .offset { IntOffset(offsetX.value.roundToInt(), 0) }
                .pointerInput(card.workId) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            scope.launch {
                                when {
                                    offsetX.value <= -swipeThreshold -> { offsetX.animateTo(-1600f); onLike() }
                                    offsetX.value >= swipeThreshold -> { offsetX.animateTo(1600f); onSkip() }
                                    else -> offsetX.animateTo(0f)
                                }
                            }
                        },
                        onHorizontalDrag = { _, dragAmount ->
                            scope.launch { offsetX.snapTo(offsetX.value + dragAmount) }
                        },
                    )
                }
                .clickable { onOpenWork(card.workId) },
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        ) {
            AsyncImage(
                model = card.coverUrl,
                contentDescription = card.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp)
                    .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)),
            )
            Column(modifier = Modifier.padding(16.dp)) {
                Text(card.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                if (card.meta.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(card.meta, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(14.dp))
                // 口味匹配度——视觉核心（核心差异化）。
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("口味匹配度", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        card.tastePercent,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { card.tasteFraction },
                    modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                )
                Spacer(Modifier.height(4.dp))
                Text(card.tasteQualitative, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(12.dp))
                Text(card.reason, style = MaterialTheme.typography.bodyMedium)
                if (card.tags.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    TagRow(card.tags)
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    "← 左滑加入待补池    右滑暂不感兴趣 →",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                )
            }
        }

        Spacer(Modifier.height(20.dp))
        Text("$position / $total", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(onClick = onSkip, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("暂不")
            }
            Button(onClick = onLike, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("加入待补池")
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagRow(tags: List<String>) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        tags.forEach { tag ->
            AssistChip(onClick = {}, label = { Text(tag) })
        }
    }
}

@Composable
private fun FinishedContent(
    liked: Int,
    skipped: Int,
    onRegenerate: () -> Unit,
    onBack: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("本批探索完成", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(
            "加入待补池 $liked 部 · 暂不 $skipped 部",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Button(onClick = onRegenerate, modifier = Modifier.fillMaxWidth()) { Text("再来一批") }
        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("返回发现") }
    }
}

@Composable
private fun EmptyOrError(
    message: String,
    onRetry: () -> Unit,
    onBack: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            message,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) { Text("再试一次") }
        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("返回发现") }
    }
}
