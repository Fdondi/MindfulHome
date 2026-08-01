package com.mindfulhome.logging

/** Pure helpers for [SessionLogger] CRAP reduction. */
fun shouldDebounceSessionStart(
    hasActiveOrStartingSession: Boolean,
    ageMs: Long,
    debounceMs: Long,
    eventCount: Int,
): Boolean =
    hasActiveOrStartingSession &&
        ageMs in 0 until debounceMs &&
        eventCount <= 1

sealed class SessionLogWriteDecision {
    data object Stale : SessionLogWriteDecision()
    data object QueuePending : SessionLogWriteDecision()
    data object QueueAndRecover : SessionLogWriteDecision()
    data class WriteNow(val sessionId: Long) : SessionLogWriteDecision()
}

fun decideSessionLogWrite(
    handleToken: Long,
    activeToken: Long,
    currentSessionId: Long,
    sessionStarting: Boolean,
): SessionLogWriteDecision {
    if (handleToken != activeToken) return SessionLogWriteDecision.Stale
    if (currentSessionId > 0L) return SessionLogWriteDecision.WriteNow(currentSessionId)
    return if (sessionStarting) {
        SessionLogWriteDecision.QueuePending
    } else {
        SessionLogWriteDecision.QueueAndRecover
    }
}
