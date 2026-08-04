package com.mindfulhome.locale

import android.content.res.Resources
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.mindfulhome.R

/**
 * Preset intent-folder names are stored in English (stable keys for migration / matching).
 * Localize only for display; [canonicalize] maps a localized edit back to the English key.
 */
object IntentFolderNames {
    val CANONICAL_NAMES: List<String> = listOf(
        "Search",
        "Reflect",
        "Travel",
        "Learn",
        "Connect",
        "Organize",
        "Snap",
        "Util",
    )

    @StringRes
    fun stringResId(canonical: String): Int? = when (canonical) {
        "Search" -> R.string.intent_folder_search
        "Reflect" -> R.string.intent_folder_reflect
        "Travel" -> R.string.intent_folder_travel
        "Learn" -> R.string.intent_folder_learn
        "Connect" -> R.string.intent_folder_connect
        "Organize" -> R.string.intent_folder_organize
        "Snap" -> R.string.intent_folder_snap
        "Util" -> R.string.intent_folder_util
        else -> null
    }

    fun localize(name: String?, resolve: (Int) -> String): String? {
        if (name.isNullOrBlank()) return name
        val id = stringResId(name) ?: return name
        return resolve(id)
    }

    fun localize(name: String?, resources: Resources): String? =
        localize(name) { resources.getString(it) }

    fun canonicalize(raw: String?, resolve: (Int) -> String): String? {
        val trimmed = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        CANONICAL_NAMES.firstOrNull { it.equals(trimmed, ignoreCase = true) }?.let { return it }
        for (key in CANONICAL_NAMES) {
            val id = stringResId(key) ?: continue
            if (resolve(id).equals(trimmed, ignoreCase = true)) return key
        }
        return trimmed
    }

    fun canonicalize(raw: String?, resources: Resources): String? =
        canonicalize(raw) { resources.getString(it) }
}

@Composable
fun localizedIntentFolderName(name: String?): String? {
    if (name.isNullOrBlank()) return name
    val id = IntentFolderNames.stringResId(name) ?: return name
    return stringResource(id)
}
