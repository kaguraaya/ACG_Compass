package com.acgcompass.feature.auth

import android.annotation.SuppressLint
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Bangumi OAuth2 授权登录页（RC.02 4.6）。
 *
 * 用内嵌 [WebView] 加载 Bangumi 授权 URL，用户登录授权后 Bangumi 302 跳转到自定义 scheme 回调
 * （[com.acgcompass.core.network.NetworkConstants.BANGUMI_OAUTH_REDIRECT_URI]）。本页在
 * [WebViewClient.shouldOverrideUrlLoading] 拦截该回调（不放行加载未知 scheme），交
 * [BangumiOAuthViewModel.onRedirect] 解析授权码并换取 token。成功后自动返回上一页。
 *
 * 选用 WebView 内拦截而非系统 deep-link：自包含、无需 Manifest 注册，且规避浏览器无法回跳应用的故障。
 */
@Composable
fun BangumiOAuthRoute(
    onBack: () -> Unit,
    viewModel: BangumiOAuthViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(state) {
        // 登录成功：凭据已落盘，自动返回设置页。
        if (state is BangumiOAuthUiState.Success) onBack()
    }

    BangumiOAuthScreen(
        state = state,
        onBack = onBack,
        onRetry = viewModel::start,
        isRedirect = viewModel::isRedirect,
        onRedirect = viewModel::onRedirect,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BangumiOAuthScreen(
    state: BangumiOAuthUiState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    isRedirect: (String) -> Boolean,
    onRedirect: (String) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("用 Bangumi 登录") },
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
                .padding(innerPadding),
            contentAlignment = Alignment.Center,
        ) {
            when (state) {
                is BangumiOAuthUiState.Loading ->
                    StatusColumn(text = "正在准备授权…", showProgress = true)

                is BangumiOAuthUiState.Exchanging ->
                    StatusColumn(text = "正在登录并保存凭据…", showProgress = true)

                is BangumiOAuthUiState.Success ->
                    StatusColumn(text = "登录成功", showProgress = false)

                is BangumiOAuthUiState.Error ->
                    ErrorColumn(
                        message = state.message,
                        retryable = state.retryable,
                        onRetry = onRetry,
                        onBack = onBack,
                    )

                is BangumiOAuthUiState.Authorizing ->
                    OAuthWebView(
                        authorizeUrl = state.authorizeUrl,
                        isRedirect = isRedirect,
                        onRedirect = onRedirect,
                    )
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun OAuthWebView(
    authorizeUrl: String,
    isRedirect: (String) -> Boolean,
    onRedirect: (String) -> Unit,
) {
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        request: WebResourceRequest?,
                    ): Boolean {
                        val url = request?.url?.toString() ?: return false
                        if (isRedirect(url)) {
                            onRedirect(url)
                            return true
                        }
                        return false
                    }
                }
                loadUrl(authorizeUrl)
            }
        },
    )
}

@Composable
private fun StatusColumn(text: String, showProgress: Boolean) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (showProgress) CircularProgressIndicator()
        Text(text)
    }
}

@Composable
private fun ErrorColumn(
    message: String,
    retryable: Boolean,
    onRetry: () -> Unit,
    onBack: () -> Unit,
) {
    Column(
        modifier = Modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(text = message, textAlign = TextAlign.Center)
        if (retryable) {
            Button(onClick = onRetry) { Text("重试") }
        }
        OutlinedButton(onClick = onBack) { Text("返回设置") }
    }
}
