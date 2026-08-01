package com.mindfulhome.data

/** Validates inputs for [AppRepository.upsertTodo]. Returns an error message, or null if ok. */
fun validateTodoUpsert(
    intentText: String,
    expectedDurationMinutes: Int?,
    deadlineEpochMs: Long?,
    priority: Int,
): String? {
    if (intentText.isBlank()) return "Intent is required"
    if (deadlineEpochMs != null && (expectedDurationMinutes == null || expectedDurationMinutes <= 0)) {
        return "Duration is required when deadline is set"
    }
    if (priority !in 1..4) return "Priority must be 1..4"
    return null
}
