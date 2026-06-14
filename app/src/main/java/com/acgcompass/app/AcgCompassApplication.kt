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
import com.acgcompass.data.sync.SyncScheduler
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.launchIn
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

    @Inject lateinit var syncScheduler: SyncScheduler

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
        // H7：间隔变化即时重新调度；0 表示关闭。
        settingsDataStore.autoSyncIntervalMinutes
            .distinctUntilChanged()
            .onEach { minutes -> syncScheduler.apply(minutes) }
            .launchIn(appScope)
    }

    companion object {
        /** M4：封面磁盘缓存上限（100MB）。 */
        const val DISK_CACHE_MAX_BYTES: Long = 100L * 1024 * 1024
    }
}
