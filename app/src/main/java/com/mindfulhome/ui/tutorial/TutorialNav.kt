package com.mindfulhome.ui.tutorial

import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable

fun NavGraphBuilder.tutorialRoutes(navCtrl: NavHostController) {
    composable("help") {
        TutorialIndexScreen(
            onBack = { navCtrl.popBackStack() },
            onOpenTopic = { topic -> navCtrl.navigate("help/${topic.id}") },
        )
    }
    composable("help/{topicId}") { entry ->
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
                    navCtrl.navigate("help/${nextTopic.id}") {
                        popUpTo("help") { inclusive = false }
                        launchSingleTop = true
                    }
                },
            )
        }
    }
}
