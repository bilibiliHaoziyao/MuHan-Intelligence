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
import com.muhan.intelligence.ui.screens.chat.ChatScreen
import com.muhan.intelligence.ui.screens.onboarding.OnboardingScreen
import com.muhan.intelligence.ui.screens.settings.AboutScreen
import com.muhan.intelligence.ui.screens.settings.GenerationSettingsScreen
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

        // Two chat routes rather than one optional arg: this keeps "new chat" and
        // "open existing thread" as distinct entries so returning from settings
        // restores the right conversation.
        composable(Routes.CHAT) {
            ChatRouteCommon(navController = navController)
        }

        composable(
            route = "${Routes.CHAT}/{${Routes.ARG_CONVERSATION_ID}}",
            arguments = listOf(
                navArgument(Routes.ARG_CONVERSATION_ID) { type = NavType.StringType },
            ),
        ) {
            ChatRouteCommon(navController = navController)
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onOpenProviders = { navController.navigate(Routes.PROVIDERS) },
                onOpenGeneration = { navController.navigate(Routes.GENERATION) },
                onOpenAbout = { navController.navigate(Routes.ABOUT) },
            )
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

        composable(Routes.GENERATION) {
            GenerationSettingsScreen(onBack = { navController.popBackStack() })
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
            navController.navigate(Routes.CHAT) {
                popUpTo(Routes.CHAT) { inclusive = true }
                launchSingleTop = true
            }
        },
        onSelectConversation = { id ->
            navController.navigate(Routes.chat(id)) {
                popUpTo(Routes.CHAT) { inclusive = true }
                launchSingleTop = true
            }
        },
    )
}
