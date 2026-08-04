package com.mindfulhome.locale

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IntentFolderNamesTest {
    private val italian = mapOf(
        "Search" to "Cercare",
        "Reflect" to "Riflettere",
        "Travel" to "Viaggiare",
        "Learn" to "Imparare",
        "Connect" to "Connettere",
        "Organize" to "Organizzare",
        "Snap" to "Scatta",
        "Util" to "Util",
    )

    private fun resolve(id: Int): String {
        val key = IntentFolderNames.CANONICAL_NAMES.first {
            IntentFolderNames.stringResId(it) == id
        }
        return italian.getValue(key)
    }

    @Test
    fun localize_mapsKnownEnglishKeys() {
        assertEquals("Imparare", IntentFolderNames.localize("Learn", ::resolve))
        assertEquals("Custom", IntentFolderNames.localize("Custom", ::resolve))
        assertNull(IntentFolderNames.localize(null, ::resolve))
    }

    @Test
    fun canonicalize_mapsLocalizedOrEnglishBackToKey() {
        assertEquals("Learn", IntentFolderNames.canonicalize("Imparare", ::resolve))
        assertEquals("Learn", IntentFolderNames.canonicalize(" learn ", ::resolve))
        assertEquals("My goal", IntentFolderNames.canonicalize("My goal", ::resolve))
        assertNull(IntentFolderNames.canonicalize("  ", ::resolve))
    }

    @Test
    fun stringResId_coversAllCanonicalNames() {
        IntentFolderNames.CANONICAL_NAMES.forEach { name ->
            org.junit.Assert.assertNotNull(
                "missing string res for $name",
                IntentFolderNames.stringResId(name),
            )
        }
    }
}
