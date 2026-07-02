package com.acgcompass.feature.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.acgcompass.data.datastore.SettingsDataStore
import com.acgcompass.data.datastore.SettingsState
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

    /**
     * 用户完成引导（N6）：
     * - 把 Bangumi API 设为社区反代（[SettingsState.COMMUNITY_PROXY_BANGUMI_API_BASE_URL]）——官方地址在部分网络
     *   需特殊环境，反代通常可直连；**仅首启写入**（引导只展示一次），不影响已配置官方地址的老用户。
     * - [consentToProxyToken] 决定是否允许个人 Token 发往该非官方地址（R56）：未同意时仅公共搜索经反代、Token 不发送。
     * - 持久化 `onboardingShown = true`，引导不再展示。
     */
    fun onOnboardingComplete(consentToProxyToken: Boolean) {
        viewModelScope.launch {
            settingsDataStore.setBangumiApiBaseUrl(SettingsState.COMMUNITY_PROXY_BANGUMI_API_BASE_URL)
            settingsDataStore.setBangumiNonOfficialTokenConsent(consentToProxyToken)
            settingsDataStore.setOnboardingShown(true)
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
