package com.acgcompass.app.navigation

import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.navigation.NavBackStackEntry
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.acgcompass.feature.backlog.BacklogRoute
import com.acgcompass.feature.detail.DETAIL_ARG_WORK_ID
import com.acgcompass.feature.detail.DetailRoute
import com.acgcompass.feature.detail.detailRoute
import com.acgcompass.feature.discover.DiscoverRoute
import com.acgcompass.feature.home.HomeRoute
import com.acgcompass.feature.imports.ImportRoute
import com.acgcompass.feature.library.LIBRARY_ARG_TAB
import com.acgcompass.feature.library.MyLibraryRoute
import com.acgcompass.feature.library.libraryRoute
import com.acgcompass.feature.mine.AboutScreen
import com.acgcompass.feature.mine.MineRoute
import com.acgcompass.feature.onboarding.OnboardingRoute
import com.acgcompass.feature.onboarding.OnboardingViewModel
import com.acgcompass.feature.recommender.RECOMMENDER_ARG_TAGS
import com.acgcompass.feature.recommender.RecommenderRoute
import com.acgcompass.feature.recommender.recommenderRoute
import com.acgcompass.feature.settings.SettingsScreen
import com.acgcompass.feature.settings.SettingsSourceScreen
import com.acgcompass.feature.taste.TasteProfileRoute
import com.acgcompass.feature.timemachine.TimeMachineRoute

/**
 * 应用导航根（RC.03.02 / Requirements 5.2、5.3）。
 *
 * 由单 Activity 调用：装配 [Scaffold]（底部五栏导航）+ [AppNavHost]，并根据
 * [OnboardingViewModel.shouldShowOnboarding] 在首次启动时把用户引导到引导页。
 *
 * - 底栏仅在顶层目的地（五栏）显示；进入详情 / 设置 / 导入 / 推荐器 / 引导等嵌套路由时隐藏。
 * - 五栏之间切换维持自然返回栈：切换时 `popUpTo` 起始页并 `saveState`，避免栈无限增长（RC.03.02 / Req 5.3）。
 */
@Composable
fun AcgApp(
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
    onboardingViewModel: OnboardingViewModel = hiltViewModel(),
) {
    val shouldShowOnboarding by onboardingViewModel.shouldShowOnboarding.collectAsStateWithLifecycle()

    val currentBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = currentBackStackEntry?.destination?.route
    val currentTopLevel = TopLevelDestination.fromRoute(currentRoute)

    // 首启引导门控（RC.03.01）：当 DataStore 表明尚未展示引导时，导航到引导页。
    // 初始值为 false，老用户不会闪现引导页（见 OnboardingViewModel 注释）。
    LaunchedEffect(shouldShowOnboarding) {
        if (shouldShowOnboarding && currentRoute != AppDestination.Onboarding.route) {
            navController.navigate(AppDestination.Onboarding.route) {
                launchSingleTop = true
            }
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        // R96（重开 R61/R74）：统一 inset 单一来源模型。
        // 外层 Scaffold 用**默认** contentWindowInsets（消费状态栏 + 导航栏 + 底栏高度），
        // 把消费后的 inset 通过 innerPadding 下发，并用 consumeWindowInsets 标记已消费，
        // 使各页 TopAppBar / 内容统一用零 inset，不再各自消费状态栏（之前各页独立消费状态栏
        // 时机不一致，导致首张卡片/封面贴顶或被裁切）。
        bottomBar = {
            if (TopLevelDestination.isTopLevelRoute(currentRoute)) {
                AcgBottomNavigation(
                    currentDestination = currentTopLevel,
                    onSelect = { tab -> navController.navigateToTopLevel(tab) },
                )
            }
        },
    ) { innerPadding ->
        AppNavHost(
            navController = navController,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding),
        )
    }
}

/**
 * 应用 [NavHost]：声明五栏占位屏与嵌套路由（详情 / 设置 / 导入 / 推荐器 / 引导）。
 *
 * 起始目的地固定为首页；首启引导通过 [AcgApp] 的门控按需进入，完成后回到首页。
 */
@Composable
fun AppNavHost(
    navController: NavHostController,
    modifier: Modifier = Modifier,
) {
    NavHost(
        navController = navController,
        startDestination = AppDestination.Home.route,
        modifier = modifier,
        // 页面切换动画（RC.39 修复：此前旧页仅位移 it/4、新页整屏位移 it，两者不对称——数学上任意时刻
        // 视口内会有最高达 75% 屏宽的区域同时渲染新旧两页，在番剧卡片密集的列表页尤为明显的「重叠」显示 bug。
        // 现改为对称整屏滑动：新旧页任意时刻在屏幕上首尾相接、零重叠区域，不依赖合成层 z-order，
        // 这是原生 push 转场（含 Kotatsu）的标准做法，从根本上排除重叠可能）：
        // - 顶层五栏之间：无方向语义，用【瞬切】无过渡（同时避免 fade 透明合成层叠加长列表滚动导致的掉帧）。
        // - 进入嵌套下钻页（详情 / 推荐器 / 设置等）：新旧页整屏对称滑动，纯位移、无 alpha 渐变。
        //
        // RC.40 修复：「瞬切」只应在两端都是顶层五栏（真正的底部 Tab 切换）时生效。此前四个 transition 各自
        // 只单侧检查 targetState 或 initialState 是否顶层，导致「详情(嵌套)→首页(顶层)」返回时：
        // popEnterTransition 见 targetState=首页顶层 → 误判瞬切，返回页无动画瞬间出现；而 popExitTransition
        // 见 initialState=详情非顶层 → 仍播放滑出动画——两侧判断标准不一致，退回页提前静止显示、退出页还在滑，
        // 造成重叠。现改为「两端都顶层才瞬切」，只要有一端是嵌套页（无论进入还是返回）都对称滑动。
        enterTransition = {
            if (initialState.isTopLevel() && targetState.isTopLevel()) EnterTransition.None
            else slideInHorizontally(tween(NAV_SLIDE_MS)) { it }
        },
        exitTransition = {
            if (initialState.isTopLevel() && targetState.isTopLevel()) ExitTransition.None
            else slideOutHorizontally(tween(NAV_SLIDE_MS)) { -it }
        },
        popEnterTransition = {
            if (initialState.isTopLevel() && targetState.isTopLevel()) EnterTransition.None
            else slideInHorizontally(tween(NAV_SLIDE_MS)) { -it }
        },
        popExitTransition = {
            if (initialState.isTopLevel() && targetState.isTopLevel()) ExitTransition.None
            else slideOutHorizontally(tween(NAV_SLIDE_MS)) { it }
        },
    ) {
        // region 底部五栏
        composable(AppDestination.Home.route) {
            HomeRoute(
                onOpenRecommender = { presetTags ->
                    navController.navigate(recommenderRoute(presetTags))
                },
                // 搜索入口暂导航到发现页（Search_Module 真实页见后续任务）。
                onOpenSearch = {
                    navController.navigate(AppDestination.Discover.route)
                },
                onOpenImport = {
                    navController.navigate(AppDestination.Import.route)
                },
                onOpenWork = { workId ->
                    navController.navigate(detailRoute(workId))
                },
                onOpenSettings = {
                    navController.navigate(AppDestination.Settings.route)
                },
                onOpenHomeModules = {
                    navController.navigate(AppDestination.HomeModules.route)
                },
            )
        }
        composable(AppDestination.Discover.route) {
            DiscoverRoute(
                onOpenWork = { workId ->
                    navController.navigate(detailRoute(workId))
                },
                onOpenExploreQueue = {
                    navController.navigate(AppDestination.ExploreQueue.route)
                },
            )
        }
        composable(AppDestination.ExploreQueue.route) {
            com.acgcompass.feature.explore.ExploreQueueRoute(
                onOpenWork = { workId -> navController.navigate(detailRoute(workId)) },
                onBack = { navController.popBackStack() },
            )
        }
        composable(AppDestination.Backlog.route) {
            BacklogRoute(
                onOpenDetail = { workId ->
                    navController.navigate(detailRoute(workId))
                },
                onGoImport = {
                    navController.navigate(AppDestination.Import.route)
                },
                onGoSearch = {
                    navController.navigate(AppDestination.Discover.route)
                },
                onOpenDustMuseum = {
                    navController.navigate(AppDestination.DustMuseum.route)
                },
            )
        }
        composable(AppDestination.TimeMachine.route) {
            TimeMachineRoute(
                onOpenWork = { workId ->
                    navController.navigate(detailRoute(workId))
                },
            )
        }
        composable(AppDestination.Mine.route) {
            MineRoute(
                onNavigateToSettings = {
                    navController.navigate(AppDestination.Settings.route)
                },
                onOpenPrivacySettings = {
                    navController.navigate(settingsSourceRoute(com.acgcompass.feature.settings.PRIVACY_SOURCE_KEY))
                },
                onOpenTasteProfile = {
                    navController.navigate(AppDestination.TasteProfile.route)
                },
                onOpenAbout = {
                    navController.navigate(AppDestination.About.route)
                },
                onOpenLibrary = { tab ->
                    navController.navigate(libraryRoute(tab))
                },
            )
        }
        // endregion

        // region 嵌套路由
        nestedRoutes(navController)
        // endregion
    }
}

/**
 * 注册非底栏的嵌套路由。真实页面在后续任务接入；当前除设置页与引导页外为占位屏。
 */
private fun NavGraphBuilder.nestedRoutes(navController: NavHostController) {
    composable(
        route = AppDestination.Detail.route,
        arguments = listOf(
            navArgument(DETAIL_ARG_WORK_ID) { type = NavType.StringType },
        ),
    ) {
        // workId 由路由参数经 SavedStateHandle 注入 DetailViewModel（见 DetailViewModel）。
        DetailRoute(
            onOpenWork = { workId -> navController.navigate(detailRoute(workId)) },
        )
    }
    composable(AppDestination.Import.route) {
        ImportRoute(onBack = { navController.popBackStack() })
    }
    composable(
        route = AppDestination.Recommender.route,
        arguments = listOf(
            navArgument(RECOMMENDER_ARG_TAGS) {
                type = NavType.StringType
                nullable = true
                defaultValue = null
            },
        ),
    ) {
        // P2-8：presetTags 由路由参数经 SavedStateHandle 注入 RecommenderViewModel（首页今日状态预填）。
        RecommenderRoute(
            onOpenWork = { workId ->
                navController.navigate(detailRoute(workId))
            },
        )
    }
    composable(
        route = "${AppDestination.Settings.route}?section={section}",
        arguments = listOf(
            navArgument("section") {
                type = NavType.StringType
                nullable = true
                defaultValue = null
            },
        ),
    ) { entry ->
        val appContextForCache = androidx.compose.ui.platform.LocalContext.current
        SettingsScreen(
            onNavigateBack = { navController.popBackStack() },
            expandPrivacy = entry.arguments?.getString("section") == "privacy",
            onClearCache = {
                // M4（L12）：清空 Coil 内存 + 磁盘封面缓存，释放占用。
                val ctx = appContextForCache
                val loader = coil.Coil.imageLoader(ctx)
                loader.memoryCache?.clear()
                loader.diskCache?.clear()
                android.widget.Toast.makeText(ctx, "已清理图片缓存", android.widget.Toast.LENGTH_SHORT).show()
            },
            onOpenSource = { sourceKey ->
                navController.navigate(settingsSourceRoute(sourceKey))
            },
        )
    }
    composable(
        route = AppDestination.SettingsSource.route,
        arguments = listOf(
            navArgument("source") {
                type = NavType.StringType
                nullable = true
                defaultValue = null
            },
        ),
    ) { entry ->
        SettingsSourceScreen(
            sourceKey = entry.arguments?.getString("source").orEmpty(),
            onNavigateBack = { navController.popBackStack() },
            onBangumiLogin = { navController.navigate(AppDestination.BangumiOAuth.route) },
        )
    }
    composable(AppDestination.BangumiOAuth.route) {
        com.acgcompass.feature.auth.BangumiOAuthRoute(
            onBack = { navController.popBackStack() },
        )
    }
    composable(AppDestination.Onboarding.route) {
        OnboardingRoute(
            onFinished = {
                // 引导完成：返回首页并清除引导页，避免回退又回到引导。
                navController.navigate(AppDestination.Home.route) {
                    popUpTo(AppDestination.Onboarding.route) { inclusive = true }
                    launchSingleTop = true
                }
            },
        )
    }
    composable(AppDestination.About.route) {
        AboutScreen(onBack = { navController.popBackStack() })
    }
    composable(AppDestination.HomeModules.route) {
        com.acgcompass.feature.home.HomeModulesRoute(onBack = { navController.popBackStack() })
    }
    composable(AppDestination.DustMuseum.route) {
        com.acgcompass.feature.backlog.DustMuseumRoute(
            onOpenWork = { workId -> navController.navigate(detailRoute(workId)) },
            onBack = { navController.popBackStack() },
        )
    }
    composable(AppDestination.TasteProfile.route) {
        TasteProfileRoute(
            onBack = { navController.popBackStack() },
            onOpenSettings = { navController.navigate(AppDestination.Settings.route) },
        )
    }
    composable(
        route = AppDestination.Library.route,
        arguments = listOf(
            navArgument(LIBRARY_ARG_TAB) {
                type = NavType.StringType
                nullable = true
                defaultValue = null
            },
        ),
    ) {
        // 初始 Tab 由路由参数经 SavedStateHandle 注入 MyLibraryViewModel。
        MyLibraryRoute(
            onBack = { navController.popBackStack() },
            onOpenWork = { workId -> navController.navigate(detailRoute(workId)) },
        )
    }
}

/**
 * 切换底部五栏的标准导航行为（RC.03.02 / Req 5.3 自然返回栈）：
 * - `popUpTo` 图的起始目的地并 `saveState`，避免在栈中堆叠多个顶层页面。
 * - `launchSingleTop` 防止重复入栈同一页面。
 * - `restoreState` 恢复此前该栏目的滚动/选择状态。
 */
private fun NavHostController.navigateToTopLevel(tab: TopLevelDestination) {
    navigate(tab.route) {
        // 始终回到图的起始页（首页）之上重建目标栏，确保每个 Tab 必进正确页面（R13 / RC.03.02）。
        // 刻意不使用 saveState/restoreState：避免「点首页却回到发现页」的状态错配；
        // 各页内部状态（如搜索结果）由各自 ViewModel / 仓库缓存负责保留（R1），不依赖导航返回栈。
        popUpTo(graph.startDestinationId) {
            inclusive = false
        }
        launchSingleTop = true
    }
}

/** 下钻页（详情等）滑入 / 滑出时长（ms）：快速利落承载整屏位移，不拖沓（RC.38：纯滑动、无 fade）。 */
private const val NAV_SLIDE_MS = 220

/** 该目的地是否为底部五栏之一（顶层）。用于区分「同级切换（瞬切）」与「下钻（滑入）」动画。 */
private fun NavBackStackEntry.isTopLevel(): Boolean =
    TopLevelDestination.isTopLevelRoute(destination.route)
