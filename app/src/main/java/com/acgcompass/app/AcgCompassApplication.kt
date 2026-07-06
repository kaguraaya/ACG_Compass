package com.acgcompass.app

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import coil.request.CachePolicy
import com.acgcompass.data.datastore.SettingsDataStore
import com.acgcompass.data.remote.bangumi.BangumiTokenRefresher
import com.acgcompass.data.sync.SyncScheduler
import com.acgcompass.data.sync.TasteProfileAutoUpdater
import dagger.Lazy
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Application entry point.
 *
 * Annotated with [HiltAndroidApp] to trigger Hilt's code generation and serve as the
 * application-level dependency container (RC.00 local-first, no backend).
 *
 * Implements [Configuration.Provider] so WorkManager uses Hilt's [HiltWorkerFactory] to
 * construct `@HiltWorker` workers (H7 自动同步)。On startup we observe the user's auto-sync
 * interval and (re)schedule the periodic Bangumi sync accordingly.
 *
 * M4（L12）：实现 [ImageLoaderFactory] 统一配置 Coil 封面缓存——内存上限约 25% 可用内存、
 * 磁盘缓存上限 [DISK_CACHE_MAX_BYTES]（默认 100MB），并启用磁盘/内存缓存策略以**复用**封面，
 * 减少重复下载（缓存利用）。「清除缓存」入口可清空该缓存（见 AppNavHost）。
 */
@HiltAndroidApp
class AcgCompassApplication : Application(), Configuration.Provider, ImageLoaderFactory {

    @Inject lateinit var workerFactory: HiltWorkerFactory

    @Inject lateinit var settingsDataStore: SettingsDataStore

    // E：以下三个重依赖改用 dagger.Lazy——其依赖图（Room / 网络客户端 / 仓库）在首次 get()
    // 时才构建；启动时统一在后台 appScope 触发 get()，把构建成本移出主线程，避免冷启动 / 开屏掉帧。
    @Inject lateinit var syncScheduler: Lazy<SyncScheduler>

    @Inject lateinit var tasteProfileAutoUpdater: Lazy<TasteProfileAutoUpdater>

    @Inject lateinit var bangumiTokenRefresher: Lazy<BangumiTokenRefresher>

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .diskCachePolicy(CachePolicy.ENABLED)
            .crossfade(true)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.25)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(DISK_CACHE_MAX_BYTES)
                    .build()
            }
            .build()

    override fun onCreate() {
        super.onCreate()
        // E：把启动初始化整体移出主线程（后台 appScope / Dispatchers.Default）——依赖图构建
        //（Room / 网络客户端 / 仓库）与观察者注册都在后台完成，避免冷启动 / 开屏期间在主线程
        // 构建依赖图并与开屏动效争抢，减少掉帧。均为 best-effort，绝不阻塞 UI 起步。
        appScope.launch {
            // H7：自动同步间隔变化即时重新调度；0 表示关闭。
            settingsDataStore.autoSyncIntervalMinutes
                .distinctUntilChanged()
                .onEach { minutes -> syncScheduler.get().apply(minutes) }
                .launchIn(appScope)
            // P1-3：个人收藏变化（同步入库 / 详情页改状态 / 加待补池）→ 防抖后自动重算口味画像。
            tasteProfileAutoUpdater.get().start(appScope)
            // RC.02 4.6：Bangumi OAuth token 启动期自动续期（best-effort，静默；无 refresh_token 时跳过）。
            runCatching { bangumiTokenRefresher.get().refreshIfNeeded() }
        }
    }

    companion object {
        /** M4：封面磁盘缓存上限（100MB）。 */
        const val DISK_CACHE_MAX_BYTES: Long = 100L * 1024 * 1024
    }
}
