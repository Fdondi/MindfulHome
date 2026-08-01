package com.mindfulhome.ui.karma

import com.mindfulhome.data.AppKarma

data class KarmaAppGroups(
    val negative: List<AppKarma>,
    val optedOut: List<AppKarma>,
    val positive: List<AppKarma>,
    val zero: List<AppKarma>,
)

fun partitionKarmaApps(
    allKarma: List<AppKarma>,
    appLabels: Map<String, String>,
): KarmaAppGroups {
    val tracked = allKarma.filter { it.packageName.isNotBlank() }.sortedBy { it.packageName }
    val labelOf: (AppKarma) -> String = { appLabels[it.packageName] ?: it.packageName }
    return KarmaAppGroups(
        negative = tracked
            .filter { !it.isOptedOut && it.karmaScore < 0 }
            .sortedWith(compareBy<AppKarma> { it.karmaScore }.thenBy(labelOf)),
        optedOut = tracked
            .filter { it.isOptedOut }
            .sortedBy(labelOf),
        positive = tracked
            .filter { !it.isOptedOut && it.karmaScore > 0 }
            .sortedWith(compareByDescending<AppKarma> { it.karmaScore }.thenBy(labelOf)),
        zero = tracked
            .filter { !it.isOptedOut && it.karmaScore == 0 }
            .sortedBy(labelOf),
    )
}

fun karmaScoreColorKey(isOptedOut: Boolean, karmaScore: Int): Int = when {
    isOptedOut -> 0
    karmaScore > 0 -> 1
    karmaScore < -5 -> 2
    karmaScore < 0 -> 3
    else -> 4
}

fun noteDraftChanged(draft: String, existingNote: String?): Boolean {
    val normalized = draft.trim().ifBlank { null }
    return normalized != existingNote
}

fun normalizeNoteDraft(draft: String): String? = draft.trim().ifBlank { null }

fun sanitizeKarmaScoreInput(raw: String): String =
    raw.filter { it == '-' || it.isDigit() }.take(5)

fun packagesMissingLabels(
    allKarma: List<AppKarma>,
    appLabels: Map<String, String>,
): List<String> =
    allKarma
        .map { it.packageName }
        .filter { it.isNotBlank() && it !in appLabels }
