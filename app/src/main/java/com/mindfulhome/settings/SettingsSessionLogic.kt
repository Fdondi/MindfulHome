package com.mindfulhome.settings

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val snapshotJson = Json { ignoreUnknownKeys = true }

@Serializable
private data class TimerUsageSnapshotDto(
    val capturedAtMs: Long = 0L,
    val topApps: List<TimerUsageAppDto> = emptyList(),
)

@Serializable
private data class TimerUsageAppDto(
    val packageName: String = "",
    val foregroundTimeMs: Long = 0L,
    @SerialName("longestSessionsMsDesc")
    val longestSessionsMsDesc: List<Long> = emptyList(),
)

/** Pure JSON parse for [SettingsManager.getLastTimerUsageSnapshot]. */
fun parseLastTimerUsageSnapshotJson(raw: String): SettingsManager.LastTimerUsageSnapshot? {
    return try {
        val payload = snapshotJson.decodeFromString(TimerUsageSnapshotDto.serializer(), raw)
        if (payload.capturedAtMs <= 0L) return null
        val apps = payload.topApps.mapNotNull { dto ->
            val packageName = dto.packageName.trim()
            if (packageName.isBlank() || dto.foregroundTimeMs <= 0L) return@mapNotNull null
            SettingsManager.LastTimerUsageApp(
                packageName = packageName,
                foregroundTimeMs = dto.foregroundTimeMs,
                longestSessionsMsDesc = dto.longestSessionsMsDesc.filter { it > 0L },
            )
        }
        if (apps.isEmpty()) return null
        SettingsManager.LastTimerUsageSnapshot(
            capturedAtMs = payload.capturedAtMs,
            topApps = apps,
        )
    } catch (_: Exception) {
        null
    }
}

fun resolvePersistedSuspendedAtMs(
    suspendedAtMs: Long?,
    isSameSession: Boolean,
    existingSuspendedAtMs: Long,
): Long = when {
    suspendedAtMs != null -> suspendedAtMs
    isSameSession && existingSuspendedAtMs > 0L -> existingSuspendedAtMs
    else -> 0L
}

fun isSameSavedSession(
    existingPackage: String?,
    existingTotalMs: Long,
    existingStartedAtMs: Long,
    packageName: String,
    totalDurationMs: Long,
    startedAtMs: Long,
): Boolean =
    existingPackage == packageName &&
        existingTotalMs == totalDurationMs &&
        existingStartedAtMs == startedAtMs

fun buildSavedSession(
    pkg: String,
    totalDurationMs: Long,
    startedAtMs: Long,
    suspendedAtMsRaw: Long,
    nowMs: Long,
): SettingsManager.SavedSession? {
    if (pkg.isEmpty() || totalDurationMs <= 0L || startedAtMs <= 0L) return null
    val referenceNowMs = if (suspendedAtMsRaw > 0L) suspendedAtMsRaw else nowMs
    val elapsedMs = (referenceNowMs - startedAtMs).coerceAtLeast(0L)
    val remainingMs = (totalDurationMs - elapsedMs).coerceAtLeast(0L)
    if (remainingMs <= 0L) return null
    val remainingMinutes = ((remainingMs + 59_999L) / 60_000L).toInt()
    return SettingsManager.SavedSession(
        packageName = pkg,
        remainingMs = remainingMs,
        remainingMinutes = remainingMinutes,
        totalDurationMs = totalDurationMs,
        startedAtMs = startedAtMs,
        suspendedAtMs = suspendedAtMsRaw.takeIf { it > 0L },
    )
}
