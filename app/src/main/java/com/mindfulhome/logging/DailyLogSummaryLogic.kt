package com.mindfulhome.logging

import java.time.LocalDate

enum class DailySummaryDayGate {
    Ok,
    DayNotConcluded,
}

fun gateDailySummaryDay(day: LocalDate, today: LocalDate): DailySummaryDayGate =
    if (day.isBefore(today)) DailySummaryDayGate.Ok else DailySummaryDayGate.DayNotConcluded

fun buildDailySummaryPrompt(
    instructions: String,
    dayKey: String,
    sessionCount: Int,
    eventCount: Int,
    rawLogText: String,
): String = buildString {
    appendLine(instructions.trim())
    appendLine()
    appendLine(
        "Output a single JSON object only (no markdown fences). Use exactly two string keys, " +
            "in this order: first \"summary\", then \"tagline\". " +
            "The \"summary\" value is the full daily write-up. " +
            "The \"tagline\" value must be written last: a very short line used as the collapsed preview " +
            "and expanded title (compose it after the full summary is decided).",
    )
    appendLine()
    appendLine("Day: $dayKey")
    appendLine("Sessions: $sessionCount")
    appendLine("Events: $eventCount")
    appendLine()
    appendLine("Session logs:")
    appendLine(rawLogText)
}.trim()

fun shouldRunDailySummaryRegenerate(count: Int, newPromptVersion: Int): Boolean =
    count > 0 && newPromptVersion > 0
