package com.acgcompass.feature.detail

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * L11：详情页封面大图查看器。全屏展示封面，支持双指缩放查看，并提供「保存到相册」按钮。
 *
 * 下载经 Coil [ImageLoader] 复用缓存取回位图，再通过 [MediaStore] 写入系统相册（Pictures/ACGCompass），
 * 兼容 Android 10+ 分区存储（不需要 WRITE_EXTERNAL_STORAGE 权限）。失败时回调提示，不崩溃。
 */
@Composable
internal fun CoverViewerDialog(
    coverUrl: String,
    title: String,
    onDismiss: () -> Unit,
    onMessage: (String) -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var scale by remember { mutableStateOf(1f) }
    var saving by remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.92f))
                // N7：点击图片以外区域关闭（系统返回手势同样可关闭）。
                .pointerInput("dismiss") {
                    detectTapGestures(onTap = { onDismiss() })
                },
            contentAlignment = Alignment.Center,
        ) {
            coil.compose.AsyncImage(
                model = coverUrl,
                contentDescription = title,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
                    .graphicsLayer(scaleX = scale, scaleY = scale)
                    // 图片自身消费缩放手势，避免触发外层「点击关闭」。
                    .pointerInput("zoom") {
                        detectTransformGestures { _, _, zoom, _ ->
                            scale = (scale * zoom).coerceIn(1f, 5f)
                        }
                    },
            )

            // N7：仅保留「保存到相册」按钮。
            Button(
                enabled = !saving,
                onClick = {
                    saving = true
                    scope.launch {
                        val ok = saveCoverToGallery(context, coverUrl, title)
                        saving = false
                        onMessage(if (ok) "已保存到相册" else "保存失败，请稍后重试")
                    }
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(24.dp),
            ) {
                Text(if (saving) "保存中…" else "保存到相册")
            }
        }
    }
}

/** 经 Coil 取回封面位图并写入系统相册。返回是否成功。 */
private suspend fun saveCoverToGallery(
    context: Context,
    url: String,
    title: String,
): Boolean = withContext(Dispatchers.IO) {
    runCatching {
        val request = ImageRequest.Builder(context)
            .data(url)
            .allowHardware(false)
            .build()
        val result = context.imageLoader.execute(request)
        if (result !is SuccessResult) return@withContext false
        val bitmap = (result.drawable as? BitmapDrawable)?.bitmap ?: return@withContext false

        val safeName = title.replace(Regex("[^\\p{L}\\p{N}_-]"), "_").take(40).ifBlank { "cover" }
        val fileName = "ACGCompass_${safeName}_${System.currentTimeMillis()}.jpg"

        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(
                    MediaStore.Images.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_PICTURES + "/ACGCompass",
                )
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: return@withContext false
        resolver.openOutputStream(uri)?.use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
        } ?: return@withContext false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        }
        true
    }.getOrDefault(false)
}
