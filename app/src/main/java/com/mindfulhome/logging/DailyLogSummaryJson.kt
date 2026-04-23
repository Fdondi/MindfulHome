package com.mindfulhome.logging

import org.json.JSONObject

/**
 * LLM output shape: `{"summary":"...","tagline":"..."}` (tagline last so the model has full summary in context).
 */
object DailyLogSummaryJson {

    fun buildJson(summary: String, tagline: String): String =
        JSONObject()
            .put("summary", summary)
            .put("tagline", tagline)
            .toString()

    /**
     * Parses model output; strips optional ```json fences.
     */
    fun parseModelOutput(raw: String): Result<Pair<String, String>> {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return Result.failure(IllegalArgumentException("empty"))
        val jsonBlock = stripMarkdownFence(trimmed)
        return try {
            val obj = JSONObject(jsonBlock)
            val summary = obj.optString("summary", "").trim()
            val tagline = obj.optString("tagline", "").trim()
            if (summary.isEmpty()) {
                Result.failure(IllegalArgumentException("missing summary"))
            } else {
                Result.success(summary to tagline)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun stripMarkdownFence(text: String): String {
        var t = text.trim()
        if (t.startsWith("```")) {
            val firstNl = t.indexOf('\n')
            if (firstNl >= 0) {
                t = t.substring(firstNl + 1)
            }
            val fence = t.lastIndexOf("```")
            if (fence >= 0) {
                t = t.substring(0, fence)
            }
        }
        return t.trim()
    }
}
