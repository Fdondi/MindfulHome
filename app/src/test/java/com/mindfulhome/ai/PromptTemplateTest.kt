package com.mindfulhome.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptTemplateTest {

    @Test
    fun optionalBlock_omittedWhenPlaceholderEmpty() {
        val result = PromptTemplates.applyTemplate(
            "Hello [[note: {appNote}]] world",
            mapOf("appNote" to ""),
        )
        assertEquals("Hello world", result)
    }

    @Test
    fun optionalBlock_includedWhenPlaceholderPresent() {
        val result = PromptTemplates.applyTemplate(
            "[[The user said: \"{appNote}\". ]]OK",
            mapOf("appNote" to "avoid doomscrolling"),
        )
        assertEquals("The user said: \"avoid doomscrolling\". OK", result)
    }

    @Test
    fun optionalBlock_omittedWhenGatePlaceholderEmpty() {
        val result = PromptTemplates.applyTemplate(
            "[[{cautionGate}Take caution. ]]Done",
            mapOf("cautionGate" to ""),
        )
        assertEquals("Done", result)
    }

    @Test
    fun optionalBlock_omittedWhenAnyInnerPlaceholderEmpty() {
        val result = PromptTemplates.applyTemplate(
            "[[{a}{b}together]]End",
            mapOf("a" to "yes", "b" to ""),
        )
        assertEquals("End", result)
    }

    @Test
    fun optionalBlock_whitespaceOnlyPlaceholderStillGatesBlock() {
        val result = PromptTemplates.applyTemplate(
            "[[{cautionGate}caution]]",
            mapOf("cautionGate" to " "),
        )
        assertEquals("caution", result.trim())
    }

    @Test
    fun optionalBlock_nullOrEmptyPlaceholderOmitsBlock() {
        val result = PromptTemplates.applyTemplate(
            "[[{appNote}]]",
            mapOf("appNote" to ""),
        )
        assertEquals("", result)
    }

    @Test
    fun placeholder_replacedInMultiplePlaces() {
        val result = PromptTemplates.applyTemplate(
            "{name} and {name}",
            mapOf("name" to "Maps"),
        )
        assertEquals("Maps and Maps", result)
    }

    @Test
    fun defaultGatekeeperTemplate_omitsAllOptionalSectionsWhenEmpty() {
        val result = applyDefaultGatekeeper(
            appNote = "",
            cautionGate = "",
            confrontationBrief = "",
        )
        assertFalse(result.contains("say about the app"))
        assertFalse(result.contains("cautionary language"))
        assertFalse(result.contains("Recent usage evidence"))
        assertTrue(result.contains("User wants to open Instagram"))
        assertTrue(result.contains("Do NOT call grantAccess before round 2"))
    }

    @Test
    fun defaultGatekeeperTemplate_includesAppNoteBlock() {
        val result = applyDefaultGatekeeper(
            appNote = "only for work",
            cautionGate = "",
            confrontationBrief = "",
        )
        assertTrue(result.contains("The user has this to say about the app: \"only for work\"."))
        assertFalse(result.contains("cautionary language"))
    }

    @Test
    fun defaultGatekeeperTemplate_includesCautionBlockWhenGateSet() {
        val result = applyDefaultGatekeeper(
            appNote = "don't doomscroll",
            cautionGate = " ",
            confrontationBrief = "",
        )
        assertTrue(result.contains("The user has this to say about the app:"))
        assertTrue(result.contains("cautionary language"))
    }

    @Test
    fun defaultGatekeeperTemplate_includesConfrontationBlock() {
        val brief = "ranked #1 with total 45m foreground"
        val result = applyDefaultGatekeeper(
            appNote = "",
            cautionGate = "",
            confrontationBrief = brief,
        )
        assertTrue(result.contains("Recent usage evidence: $brief"))
    }

    @Test
    fun defaultFocusGateTemplate_omitsDeclaredIntentWhenBlank() {
        val result = applyDefaultFocusGate(declaredIntent = "")
        assertFalse(result.contains("Declared intent"))
        assertTrue(result.contains("Focus time is active (Evening)"))
    }

    @Test
    fun defaultFocusGateTemplate_includesDeclaredIntentWhenPresent() {
        val result = applyDefaultFocusGate(declaredIntent = "reply to one email")
        assertTrue(result.contains("Declared intent: \"reply to one email\"."))
    }

    @Test
    fun customTemplate_userAddedOptionalBlock() {
        val template = """
            App {appName}.
            [[Bonus: {bonusHint}]]
            Done.
        """.trimIndent()
        val without = PromptTemplates.applyTemplate(
            template,
            mapOf("appName" to "Slack", "bonusHint" to ""),
        )
        assertFalse(without.contains("Bonus"))
        assertTrue(without.contains("App Slack."))
        assertTrue(without.contains("Done."))

        val withHint = PromptTemplates.applyTemplate(
            template,
            mapOf("appName" to "Slack", "bonusHint" to "standup only"),
        )
        assertTrue(withHint.contains("Bonus: standup only"))
    }

    @Test
    fun customTemplate_reorderedOptionalBlocks() {
        val template =
            "[[B:{appNote} ]][[C:{cautionGate}warn ]]X"
        val result = PromptTemplates.applyTemplate(
            template,
            mapOf("appNote" to "note", "cautionGate" to ""),
        )
        assertEquals("B:note X", result)
    }

    @Test
    fun customTemplate_modifiedIntroTextPreservesPlaceholders() {
        val template =
            "[[User note on file: \"{appNote}\" — read carefully. ]]"
        val result = PromptTemplates.applyTemplate(
            template,
            mapOf("appNote" to "bedtime only"),
        )
        assertEquals("User note on file: \"bedtime only\" — read carefully.", result)
    }

    @Test
    fun requiresExtraConfirmation_matchesCautionKeywords() {
        assertTrue(PromptTemplates.requiresExtraConfirmation("don't open at night"))
        assertTrue(PromptTemplates.requiresExtraConfirmation("avoid doomscroll"))
        assertFalse(PromptTemplates.requiresExtraConfirmation("work email"))
        assertFalse(PromptTemplates.requiresExtraConfirmation(null))
    }

    @Test
    fun cautionGateDerivedFromRequiresExtraConfirmationAndNote() {
        val note = "avoid doomscroll"
        val cautionGate = if (PromptTemplates.requiresExtraConfirmation(note) && note.isNotBlank()) " " else ""
        val result = applyDefaultGatekeeper(
            appNote = note,
            cautionGate = cautionGate,
            confrontationBrief = "",
        )
        assertTrue(result.contains("cautionary language"))
    }

    private fun applyDefaultGatekeeper(
        appNote: String,
        cautionGate: String,
        confrontationBrief: String,
    ): String = PromptTemplates.applyTemplate(
        PromptTemplates.DEFAULT_GATEKEEPER_CONTEXT_TEMPLATE,
        mapOf(
            "appName" to "Instagram",
            "karmaScore" to "-3",
            "totalOpens" to "12",
            "totalOverruns" to "4",
            "timesRequestedToday" to "0",
            "minRounds" to "2",
            "focusModeActive" to "false",
            "appNote" to appNote,
            "confrontationBrief" to confrontationBrief,
            "cautionGate" to cautionGate,
        ),
    )

    private fun applyDefaultFocusGate(declaredIntent: String): String =
        PromptTemplates.applyTemplate(
            PromptTemplates.DEFAULT_FOCUS_GATE_CONTEXT_TEMPLATE,
            mapOf(
                "durationMinutes" to "25",
                "declaredIntent" to declaredIntent,
                "focusWindowDescription" to "Evening",
                "minRounds" to "2",
            ),
        )
}
