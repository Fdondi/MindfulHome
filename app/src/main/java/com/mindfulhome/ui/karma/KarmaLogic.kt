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

/** Filter tracked karma groups by label or package substring (case-insensitive). */
fun filterKarmaGroupsByQuery(
    groups: KarmaAppGroups,
    appLabels: Map<String, String>,
    query: String,
): KarmaAppGroups {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return groups
    fun matches(karma: AppKarma): Boolean {
        val label = (appLabels[karma.packageName] ?: karma.packageName).lowercase()
        return label.contains(q) || karma.packageName.lowercase().contains(q)
    }
    return KarmaAppGroups(
        negative = groups.negative.filter(::matches),
        optedOut = groups.optedOut.filter(::matches),
        positive = groups.positive.filter(::matches),
        zero = groups.zero.filter(::matches),
    )
}

/** Which sections should expand when a search query is active. */
fun sectionsToExpandForQuery(groups: KarmaAppGroups, queryActive: Boolean): Set<String> {
    if (!queryActive) return setOf("negative")
    val open = mutableSetOf<String>()
    if (groups.negative.isNotEmpty()) open += "negative"
    if (groups.optedOut.isNotEmpty()) open += "optedOut"
    if (groups.positive.isNotEmpty()) open += "positive"
    if (groups.zero.isNotEmpty()) open += "zero"
    return open
}
