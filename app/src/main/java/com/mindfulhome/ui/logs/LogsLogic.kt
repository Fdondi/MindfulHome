package com.mindfulhome.ui.logs

/** Collapsed preview text for a day summary card. */
fun daySummaryPreview(summary: String, tagline: String): String =
    tagline.trim().ifBlank {
        summary.lines().firstOrNull().orEmpty().trim()
    }.ifBlank { "No summary yet." }

/** Session bullet lines from markdown content (lines starting with "- "). */
fun sessionBulletLines(content: String?): List<String> =
    content.orEmpty().lines().filter { it.startsWith("- ") }

fun isSingleEventSession(eventCount: Int): Boolean = eventCount == 1
