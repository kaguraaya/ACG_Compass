package com.acgcompass.data.repository.di

import com.acgcompass.core.common.DefaultDispatcherProvider
import com.acgcompass.core.common.DispatcherProvider
import com.acgcompass.core.network.DataSourceOrchestrator
import com.acgcompass.core.network.DefaultDataSourceOrchestrator
import com.acgcompass.data.repository.BacklogRepositoryImpl
import com.acgcompass.data.repository.BackupRepositoryImpl
import com.acgcompass.data.repository.ImportRepositoryImpl
import com.acgcompass.data.repository.RouteMapRepositoryImpl
import com.acgcompass.data.repository.TasteProfileRepositoryImpl
import com.acgcompass.data.repository.TimeMachineRepositoryImpl
import com.acgcompass.data.repository.WorkFeatureRepositoryImpl
import com.acgcompass.data.repository.WorkRepositoryImpl
import com.acgcompass.domain.repository.BacklogRepository
import com.acgcompass.domain.repository.BackupRepository
import com.acgcompass.domain.repository.ImportRepository
import com.acgcompass.domain.repository.RouteMapRepository
import com.acgcompass.domain.repository.TasteProfileRepository
import com.acgcompass.domain.repository.TimeMachineRepository
import com.acgcompass.domain.repository.WorkRepository
import com.acgcompass.domain.taste.WorkFeatureRepository
import com.acgcompass.domain.usecase.AggregateRatingsUseCase
import com.acgcompass.domain.usecase.ScoringHabitCalculator
import com.acgcompass.domain.usecase.TasteStatsCalculator
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * 仓库层 Hilt 绑定（task 13.1）。
 *
 * - [bindWorkRepository]：把领域契约 [WorkRepository] 绑定到 data 层实现
 *   [WorkRepositoryImpl]，使表现层只依赖领域接口（依赖方向 UI → Domain ← Data）。
 * - 伴生对象提供 [WorkRepositoryImpl] 所需、尚无其它模块提供的协作者：
 *   [DispatcherProvider]、[DataSourceOrchestrator]、[AggregateRatingsUseCase]
 *   （均为纯实现，单例即可，无 Android 依赖）。
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindWorkRepository(impl: WorkRepositoryImpl): WorkRepository

    @Binds
    @Singleton
    abstract fun bindBacklogRepository(impl: BacklogRepositoryImpl): BacklogRepository

    @Binds
    @Singleton
    abstract fun bindImportRepository(impl: ImportRepositoryImpl): ImportRepository

    @Binds
    @Singleton
    abstract fun bindTimeMachineRepository(impl: TimeMachineRepositoryImpl): TimeMachineRepository

    @Binds
    @Singleton
    abstract fun bindTasteProfileRepository(impl: TasteProfileRepositoryImpl): TasteProfileRepository

    @Binds
    @Singleton
    abstract fun bindBackupRepository(impl: BackupRepositoryImpl): BackupRepository

    @Binds
    @Singleton
    abstract fun bindRouteMapRepository(impl: RouteMapRepositoryImpl): RouteMapRepository

    @Binds
    @Singleton
    abstract fun bindWorkFeatureRepository(impl: WorkFeatureRepositoryImpl): WorkFeatureRepository

    companion object {

        @Provides
        @Singleton
        fun provideDispatcherProvider(): DispatcherProvider = DefaultDispatcherProvider()

        @Provides
        @Singleton
        fun provideDataSourceOrchestrator(): DataSourceOrchestrator = DefaultDataSourceOrchestrator()

        @Provides
        @Singleton
        fun provideAggregateRatingsUseCase(): AggregateRatingsUseCase = AggregateRatingsUseCase()

        @Provides
        @Singleton
        fun provideTasteStatsCalculator(): TasteStatsCalculator = TasteStatsCalculator()

        @Provides
        @Singleton
        fun provideScoringHabitCalculator(): ScoringHabitCalculator = ScoringHabitCalculator()
    }
}
