package com.acgcompass.feature.mine

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.acgcompass.BuildConfig
import com.acgcompass.R
import com.acgcompass.core.common.AppInfo
import com.acgcompass.core.designsystem.AcgCompassTheme

/**
 * 关于页（RC.15.05 / Requirements 17.5 / RC.20.9a）。品牌头（应用图标 + Hoshimi 名 + 简介）+
 * 版本/作者/仓库信息卡（GitHub 按钮）+ 静态内容：数据源、开源许可、免责声明、不提供盗版声明。
 *
 * 合规要点（RC.00）：明确本应用不提供任何播放 / 下载 / 盗版资源，仅作个人补番管理；数据来自各官方
 * 开放 API，版权归原方所有。作者 / 仓库地址取自集中的 [AppInfo]（单一常量，改名只改一处，RC.20.9a）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text("关于") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                windowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(innerPadding)
                .padding(16.dp)
                // F1：标题栏下方额外 8dp 呼吸（共约 24dp），底部额外留白避免末项贴边。
                .padding(top = 8.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // RC.20.9a：品牌头——应用图标 + Hoshimi 名 + 一句话简介。
            AboutBrandHeader()
            // 版本 / 作者 / 仓库信息卡（RC.20.9a）。版本号读 BuildConfig（随构建自动同步）；
            // 作者 / 仓库地址取自集中的 AppInfo（单一常量），GitHub 按钮用系统浏览器打开仓库。
            AboutInfoSection(
                version = "${BuildConfig.VERSION_NAME}（build ${BuildConfig.VERSION_CODE}）",
                author = AppInfo.AUTHOR,
                repoUrl = AppInfo.REPO_URL,
            )
            AboutSection(
                title = "数据来源",
                body = "作品资料与评分来自各官方开放 API：Bangumi、AniList、Jikan（MyAnimeList）、" +
                    "MyAnimeList 官方、VNDB。版权与数据归各平台及原作者所有，本应用仅作个人管理与展示之用。",
            )
            AboutSection(
                title = "开源许可",
                body = "本应用使用了 Jetpack Compose、Kotlin Coroutines、Room、Retrofit/OkHttp、" +
                    "Apollo GraphQL、Coil、Hilt 等开源组件，相关许可归各项目所有，特此致谢。",
            )
            AboutSection(
                title = "免责声明",
                body = "应用内的评分、雷达、推荐等分析仅供参考，不构成任何客观结论；缺失数据一律标记" +
                    "「暂无数据」，不会编造内容。请以官方平台信息为准。",
            )
            AboutSection(
                title = "不提供盗版",
                body = "Hoshimi 不提供、不链接、不索引任何在线播放、下载或盗版资源，也不参与任何侵权行为。" +
                    "请通过正规渠道支持正版。",
            )
        }
    }
}

/**
 * RC.20.9a：品牌头——圆角应用图标（复用 launcher 前景）+ 应用名 Hoshimi + 一句话简介，居中排布。
 */
@Composable
private fun AboutBrandHeader() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Image(
            painter = painterResource(id = R.drawable.ic_launcher_foreground),
            contentDescription = "${AppInfo.APP_NAME} 应用图标",
            modifier = Modifier
                .size(96.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        )
        Text(
            text = AppInfo.APP_NAME,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "你的本地补番决策助手。聚合多平台资料，帮你决定「今晚看什么」，所有数据默认仅保存在本机。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * 版本 / 作者 / 仓库信息卡片（RC.20.9a）。附带 GitHub 按钮（logo + 文案），用 [LocalUriHandler]
 * 交系统浏览器打开仓库；仓库地址亦以文字展示，便于复制记忆。
 */
@Composable
private fun AboutInfoSection(version: String, author: String, repoUrl: String) {
    val uriHandler = LocalUriHandler.current
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("版本与开发", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            AboutInfoRow(label = "版本", value = version)
            AboutInfoRow(label = "作者", value = author)
            Text(
                text = "项目仓库：$repoUrl",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            )
            FilledTonalButton(
                onClick = { runCatching { uriHandler.openUri(repoUrl) } },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_github),
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("在 GitHub 上查看")
            }
        }
    }
}

@Composable
private fun AboutInfoRow(label: String, value: String) {
    Text(
        text = "$label：$value",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun AboutSection(title: String, body: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun AboutScreenPreview() {
    AcgCompassTheme {
        AboutScreen(onBack = {})
    }
}
