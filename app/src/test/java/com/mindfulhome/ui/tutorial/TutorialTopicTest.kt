package com.mindfulhome.ui.tutorial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TutorialTopicTest {

    @Test
    fun fromId_includesNotificationsAndAi() {
        assertEquals(TutorialTopic.NOTIFICATIONS, TutorialTopic.fromId("notifications"))
        assertEquals(TutorialTopic.EXTENSIONS, TutorialTopic.fromId("extensions"))
        assertEquals(TutorialTopic.AI_MODEL, TutorialTopic.fromId("ai_model"))
        assertNull(TutorialTopic.fromId("missing"))
    }

    @Test
    fun entries_keepLegacyPagesThenNewHelpPages() {
        assertEquals(
            listOf(
                TutorialTopic.WELCOME,
                TutorialTopic.HOW_IT_WORKS,
                TutorialTopic.APP_TIERS,
                TutorialTopic.LAYOUT,
                TutorialTopic.TODO,
                TutorialTopic.AI_MODEL,
                TutorialTopic.NOTIFICATIONS,
                TutorialTopic.EXTENSIONS,
            ),
            TutorialTopic.entries.toList(),
        )
    }
}
