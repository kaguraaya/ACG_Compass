package com.acgcompass.feature.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.acgcompass.data.datastore.SettingsDataStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 首启引导 ViewModel（RC.03.01 / Requirements 5.1）。MVVM + Hilt + StateFlow。
 *
 * 职责：
 * - 读取 [SettingsDataStore.onboardingShown]，对外暴露 [shouldShowOnboarding] 供导航层决定
 *   是否在进入主界面前先展示引导。
 * - 用户完成引导后调用 [onOnboardingComplete]，将 `onboardingShown` 持久化为 `true`，
 *   保证引导只展示一次。
 *
 * 引导文案为纯静态内容（[OnboardingUiState.DEFAULT]），不在此计算。
 */
@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val settingsDataStore: SettingsDataStore,
) : ViewModel() {

    /**
     * 是否需要展示首启引导。
     *
     * 初始值为 `false`：在首帧 DataStore 尚未发射前不展示引导，避免老用户「闪现」引导页；
     * 首次启动用户会在 DataStore 首次发射（`onboardingShown == false`）后切换为 `true`。
     */
    val shouldShowOnboarding: StateFlow<Boolean> =
        settingsDataStore.onboardingShown
            .map { shown -> shouldShowOnboarding(shown) }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
                initialValue = false,
            )

    /** 用户完成引导：持久化 `onboardingShown = true`，引导不再展示。 */
    fun onOnboardingComplete() {
        viewModelScope.launch {
            settingsDataStore.setOnboardingShown(true)
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
