package com.mindfulhome.service

/** Pure helpers for [UsageTracker] CRAP reduction. */
object UsageTrackerLogic {

    fun cachedPackageIfFresh(
        cachedPackage: String?,
        cachedObservedAtMs: Long?,
        nowMs: Long,
        cacheTtlMs: Long,
        bypassCache: Boolean,
    ): String? {
        if (bypassCache) return null
        val pkg = cachedPackage ?: return null
        val observed = cachedObservedAtMs ?: return null
        if (nowMs - observed > cacheTtlMs) return null
        return pkg
    }

    fun isForegroundTransitionEvent(eventType: Int, activityResumed: Int, moveToForeground: Int): Boolean =
        eventType == activityResumed || eventType == moveToForeground

    fun shouldReplaceLatestForeground(
        eventPackage: String?,
        eventTimestamp: Long,
        latestTimestamp: Long,
        isForegroundEvent: Boolean,
    ): Boolean =
        isForegroundEvent && eventPackage != null && eventTimestamp >= latestTimestamp

    fun isUserActivityEvent(
        eventType: Int,
        userInteraction: Int,
        activityResumed: Int,
        includeForegroundTransitions: Boolean,
    ): Boolean =
        eventType == userInteraction ||
            (includeForegroundTransitions && eventType == activityResumed)

    fun coerceLookbackMs(lookbackMs: Long, minimumMs: Long): Long =
        lookbackMs.coerceAtLeast(minimumMs)
}
