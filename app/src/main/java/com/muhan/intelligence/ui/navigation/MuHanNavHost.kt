package com.muhan.intelligence.ui.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.muhan.intelligence.ui.screens.agent.AgentScreen
import com.muhan.intelligence.ui.screens.chat.ChatScreen
import com.muhan.intelligence.ui.screens.onboarding.OnboardingScreen
import com.muhan.intelligence.ui.screens.settings.AboutScreen
import com.muhan.intelligence.ui.screens.settings.LogsScreen
import com.muhan.intelligence.ui.screens.settings.ProviderEditorScreen
import com.muhan.intelligence.ui.screens.settings.ProviderListScreen
import com.muhan.intelligence.ui.screens.settings.SettingsScreen

/**
 * Navigation graph.
 *
 * Chat is the only destination that models a stack of its own: switching threads
 * replaces the chat route rather than pushing, so the back button never walks
 * backwards through a browsing history of conversations.
 */
@Composable
fun MuHanNavHost(
    navController: NavHostController,
    startDestination: String,
) {
    NavHost(
        navController = navController,
        startDestination = startDestination,
        enterTransition = {
            slideInHorizontally(
                initialOffsetX = { it / 8 },
                animationSpec = tween(260),
            ) + fadeIn(tween(260))
        },
        exitTransition = {
            slideOutHorizontally(
                targetOffsetX = { -it / 12 },
                animationSpec = tween(260),
            ) + fadeOut(tween(180))
        },
        popEnterTransition = {
            slideInHorizontally(
                initialOffsetX = { -it / 12 },
                animationSpec = tween(260),
            ) + fadeIn(tween(260))
        },
        popExitTransition = {
            slideOutHorizontally(
                targetOffsetX = { it / 8 },
                animationSpec = tween(260),
            ) + fadeOut(tween(180))
        },
    ) {
        composable(Routes.ONBOARDING) {
            OnboardingScreen(
                onFinished = {
                    navController.navigate(Routes.CHAT) {
                        popUpTo(Routes.ONBOARDING) { inclusive = true }
                        launchSingleTop = true
                    }
                },
            )
        }

        // 单一会话路由 + 可选 conversationId 参数（详见 Routes.CHAT_ROUTE 注释）。
        // 新建与打开已有会话共用此路由，使 popUpTo(CHAT_ROUTE) 能稳定替换当前会话，
        // 避免切换/新建时层层堆叠导致多份 ChatViewModel 共存而闪退、记录错乱。
        composable(
            route = Routes.CHAT_ROUTE,
            arguments = listOf(
                navArgument(Routes.ARG_CONVERSATION_ID) {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
            // 3.0.0：Chat / Agent 是同一应用内的两种模式，切换只做淡入淡出，
            // 不做左右滑动，避免「换了一个页面」的撕裂感。
            enterTransition = { fadeIn(tween(180)) },
            exitTransition = { fadeOut(tween(140)) },
            popEnterTransition = { fadeIn(tween(180)) },
            popExitTransition = { fadeOut(tween(140)) },
        ) {
            ChatRouteCommon(navController = navController)
        }

        // 3.0.0：Agent 模式，与 Chat 平级、会话独立。
        composable(
            route = Routes.AGENT_ROUTE,
            arguments = listOf(
                navArgument(Routes.ARG_CONVERSATION_ID) {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
            enterTransition = { fadeIn(tween(180)) },
            exitTransition = { fadeOut(tween(140)) },
            popEnterTransition = { fadeIn(tween(180)) },
            popExitTransition = { fadeOut(tween(140)) },
        ) {
            AgentRouteCommon(navController = navController)
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onOpenProviders = { navController.navigate(Routes.PROVIDERS) },
                onOpenAbout = { navController.navigate(Routes.ABOUT) },
                onOpenLogs = { navController.navigate(Routes.LOGS) },
            )
        }

        composable(Routes.LOGS) {
            LogsScreen(onBack = { navController.popBackStack() })
        }

        composable(Routes.PROVIDERS) {
            ProviderListScreen(
                onBack = { navController.popBackStack() },
                onAddProvider = { navController.navigate(Routes.providerEditor()) },
                onEditProvider = { id -> navController.navigate(Routes.providerEditor(id)) },
            )
        }

        composable(Routes.PROVIDER_EDITOR) {
            ProviderEditorScreen(
                onBack = { navController.popBackStack() },
                onSaved = { navController.popBackStack() },
            )
        }

        composable(
            route = "${Routes.PROVIDER_EDITOR}/{${Routes.ARG_PROVIDER_ID}}",
            arguments = listOf(
                navArgument(Routes.ARG_PROVIDER_ID) { type = NavType.StringType },
            ),
        ) {
            ProviderEditorScreen(
                onBack = { navController.popBackStack() },
                onSaved = { navController.popBackStack() },
            )
        }

        composable(Routes.ABOUT) {
            AboutScreen(onBack = { navController.popBackStack() })
        }
    }
}

@Composable
private fun ChatRouteCommon(navController: NavHostController) {
    ChatScreen(
        onOpenSettings = { navController.navigate(Routes.SETTINGS) },
        onNewChat = {
            // 替换当前会话而非堆叠：popUpTo 命中单一路由 CHAT_ROUTE。
            navController.navigate(Routes.CHAT) {
                popUpTo(Routes.CHAT_ROUTE) { inclusive = true }
                launchSingleTop = true
            }
        },
        onSelectConversation = { id ->
            navController.navigate(Routes.chat(id)) {
                popUpTo(Routes.CHAT_ROUTE) { inclusive = true }
                launchSingleTop = true
            }
        },
        onSwitchToAgent = {
            // 不销毁 Chat 页面：切换只是压栈，返回即可回到原来的会话与草稿。
            navController.navigate(Routes.AGENT) {
                launchSingleTop = true
            }
        },
    )
}

@Composable
private fun AgentRouteCommon(navController: NavHostController) {
    AgentScreen(
        onOpenSettings = { navController.navigate(Routes.SETTINGS) },
        onNewChat = {
            navController.navigate(Routes.AGENT) {
                popUpTo(Routes.AGENT_ROUTE) { inclusive = true }
                launchSingleTop = true
            }
        },
        onSelectConversation = { id ->
            navController.navigate(Routes.agent(id)) {
                popUpTo(Routes.AGENT_ROUTE) { inclusive = true }
                launchSingleTop = true
            }
        },
        onSwitchToChat = {
            // 返回到栈中的 Chat 页面，保留其会话与滚动位置。
            if (!navController.popBackStack()) {
                navController.navigate(Routes.CHAT) { launchSingleTop = true }
            }
        },
    )
}
