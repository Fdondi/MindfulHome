package com.mindfulhome.ui.tutorial

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import com.mindfulhome.settings.SettingsManager

fun NavGraphBuilder.tutorialRoutes(navCtrl: NavHostController) {
    composable("help/{screenKey}") { entry ->
        val screenKey = entry.arguments?.getString("screenKey").orEmpty()
        val context = LocalContext.current
        TutorialIndexScreen(
            onBack = { navCtrl.popBackStack() },
            onReplayOverlayTour = {
                SettingsManager.requestCoachmarkReplay(context, screenKey)
                navCtrl.popBackStack()
            },
            onOpenTopic = { topic -> navCtrl.navigate("help/$screenKey/${topic.id}") },
        )
    }
    composable("help/{screenKey}/{topicId}") { entry ->
        val screenKey = entry.arguments?.getString("screenKey").orEmpty()
        val topic = TutorialTopic.fromId(
            entry.arguments?.getString("topicId").orEmpty(),
        )
        if (topic == null) {
            LaunchedEffect(Unit) { navCtrl.popBackStack() }
        } else {
            TutorialTopicScreen(
                topic = topic,
                onBack = { navCtrl.popBackStack() },
                onOpenTopic = { nextTopic ->
                    navCtrl.navigate("help/$screenKey/${nextTopic.id}") {
                        popUpTo("help/$screenKey") { inclusive = false }
                        launchSingleTop = true
                    }
                },
            )
        }
    }
}
