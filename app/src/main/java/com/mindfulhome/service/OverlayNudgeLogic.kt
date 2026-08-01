package com.mindfulhome.service

import android.view.Gravity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/** Bird variants shown by the overlay nudge manager. */
enum class NudgeBirdType {
    GREEN_NOW,
    PURPLE_SOFT,
    RED_HARD,
    PREDATORY,
}

data class BubbleSizeLayout(
    val birdSizePx: Int,
    val badgeWidthPx: Int,
    val badgeHeightPx: Int,
) {
    val containerWidthPx: Int get() = birdSizePx + badgeWidthPx / 2
    val containerHeightPx: Int get() = birdSizePx + badgeHeightPx / 2
}

data class BubbleSpawnRanges(
    val minX: Int,
    val maxX: Int,
    val minY: Int,
    val maxY: Int,
)

data class BubbleSpawnPoint(
    val x: Int,
    val y: Int,
    val ranges: BubbleSpawnRanges,
)

/**
 * Pure helpers extracted from [OverlayNudgeManager] for unit testing and CRAP reduction.
 */
object OverlayNudgeLogic {

    const val DEFAULT_BANNER_PREVIEW = "MindfulHome has a new message."

    fun formatBannerPreviewText(
        previewLines: List<String>,
        fallback: String = DEFAULT_BANNER_PREVIEW,
    ): String {
        return (previewLines.takeLast(3).ifEmpty { listOf(fallback) }).joinToString("\n")
    }

    fun bubbleSizeLayout(
        isPredatory: Boolean,
        dp: (Int) -> Int,
    ): BubbleSizeLayout {
        return BubbleSizeLayout(
            birdSizePx = dp(if (isPredatory) 82 else 56),
            badgeWidthPx = dp(if (isPredatory) 84 else 56),
            badgeHeightPx = dp(if (isPredatory) 24 else 18),
        )
    }

    fun computeSpawnRanges(
        screenWidthPx: Int,
        screenHeightPx: Int,
        containerWidthPx: Int,
        containerHeightPx: Int,
        padXPx: Int,
        padYTopPx: Int,
        padYBottomPx: Int,
    ): BubbleSpawnRanges {
        val minX = padXPx
        val maxX = (screenWidthPx - containerWidthPx - padXPx).coerceAtLeast(minX)
        val minY = padYTopPx
        val maxY = (screenHeightPx - containerHeightPx - padYBottomPx).coerceAtLeast(minY)
        return BubbleSpawnRanges(minX = minX, maxX = maxX, minY = minY, maxY = maxY)
    }

    /**
     * Picks a spawn point inside [ranges], then nudges by [attemptIndex] * [attemptOffsetStepPx]
     * without exceeding the range maxima.
     *
     * [nextIntInclusive] is `(from, toInclusive) -> Int` so tests can inject a deterministic RNG.
     */
    fun clampSpawnPosition(
        ranges: BubbleSpawnRanges,
        attemptIndex: Int,
        attemptOffsetStepPx: Int,
        nextIntInclusive: (from: Int, toInclusive: Int) -> Int,
    ): BubbleSpawnPoint {
        val xBase = if (ranges.maxX > ranges.minX) {
            nextIntInclusive(ranges.minX, ranges.maxX)
        } else {
            ranges.minX
        }
        val yBase = if (ranges.maxY > ranges.minY) {
            nextIntInclusive(ranges.minY, ranges.maxY)
        } else {
            ranges.minY
        }
        val attemptOffset = attemptIndex * attemptOffsetStepPx
        return BubbleSpawnPoint(
            x = (xBase + attemptOffset).coerceAtMost(ranges.maxX),
            y = (yBase + attemptOffset).coerceAtMost(ranges.maxY),
            ranges = ranges,
        )
    }

    fun formatBirdBadgeTime(timestampMs: Long, locale: Locale = Locale.getDefault()): String {
        val formatter = SimpleDateFormat("HH:mm", locale)
        return formatter.format(Date(timestampMs))
    }

    fun badgeTextForType(
        type: NudgeBirdType,
        nowMs: Long,
        softDeadlineAtMs: Long?,
        hardDeadlineAtMs: Long?,
        formatNowTime: (Long) -> String = { formatBirdBadgeTime(it) },
    ): String {
        return when (type) {
            NudgeBirdType.GREEN_NOW -> formatNowTime(nowMs)
            NudgeBirdType.PURPLE_SOFT -> {
                val softAt = softDeadlineAtMs
                if (softAt == null) {
                    "+0m"
                } else {
                    val deltaMs = (nowMs - softAt).coerceAtLeast(0L)
                    "+${deltaMs / 60_000L}m"
                }
            }
            NudgeBirdType.RED_HARD -> {
                val hardAt = hardDeadlineAtMs ?: return "hi"
                val diffMs = hardAt - nowMs
                val absMinutes = (abs(diffMs) + 59_999L) / 60_000L
                val sign = if (diffMs >= 0L) "-" else "+"
                "$sign${absMinutes}m"
            }
            NudgeBirdType.PREDATORY -> "-1 KARMA"
        }
    }

    /** Whether an existing conversation banner should only refresh body text. */
    fun shouldRefreshExistingBanner(bannerAlreadyShown: Boolean): Boolean = bannerAlreadyShown

    fun isPredatoryBird(type: NudgeBirdType): Boolean = type == NudgeBirdType.PREDATORY

    fun birdPaddingDp(type: NudgeBirdType): Int =
        if (isPredatoryBird(type)) 4 else 6

    fun badgeTextSizeSp(type: NudgeBirdType): Float =
        if (isPredatoryBird(type)) 10f else 9f

    fun exceededDragThreshold(dx: Float, dy: Float, thresholdPx: Int): Boolean {
        val t = thresholdPx.toFloat()
        return dx * dx + dy * dy > t * t
    }

    data class QuickLaunchBorderEdge(val name: String, val gravity: Int)

    /** Top and bottom full-bleed border edges for the Quick Launch frame. */
    fun quickLaunchBorderEdges(): List<QuickLaunchBorderEdge> = listOf(
        QuickLaunchBorderEdge("top", Gravity.TOP or Gravity.START),
        QuickLaunchBorderEdge("bottom", Gravity.BOTTOM or Gravity.START),
    )

    fun conversationBannerTitle(): String = "MindfulHome conversation"

    fun conversationBannerFooter(): String =
        "Tap field to type here, or tap title for notification"

    data class BadgeStyleColors(
        val backgroundColor: Int,
        val strokeColor: Int,
        val textColor: Int,
        val strokeWidthDp: Int,
    )

    fun badgeStyleColors(type: NudgeBirdType): BadgeStyleColors {
        val (bg, stroke) = when (type) {
            NudgeBirdType.GREEN_NOW -> 0xFFDCFCE7.toInt() to 0xFF22C55E.toInt()
            NudgeBirdType.PURPLE_SOFT -> 0xFFF3E8FF.toInt() to 0xFFA855F7.toInt()
            NudgeBirdType.RED_HARD -> 0xFFFEE2E2.toInt() to 0xFFEF4444.toInt()
            NudgeBirdType.PREDATORY -> 0xFFFCA5A5.toInt() to 0xFFB91C1C.toInt()
        }
        val text = if (type == NudgeBirdType.PREDATORY) {
            0xFFFFFFFF.toInt()
        } else {
            0xFF0F172A.toInt()
        }
        return BadgeStyleColors(
            backgroundColor = bg,
            strokeColor = stroke,
            textColor = text,
            strokeWidthDp = if (type == NudgeBirdType.PREDATORY) 2 else 1,
        )
    }

    fun shouldSkipAwayShieldShow(alreadyShowing: Boolean, canDraw: Boolean): Boolean =
        alreadyShowing || !canDraw

    /**
     * Banner focus flag update: returns null when no layout update is needed.
     */
    fun nextBannerFocusableFlags(
        currentlyFocusable: Boolean,
        focusable: Boolean,
        requestInputFocus: Boolean,
        flags: Int,
        notFocusableFlag: Int,
    ): Int? {
        if (currentlyFocusable == focusable && !requestInputFocus) return null
        return if (focusable) {
            flags and notFocusableFlag.inv()
        } else {
            flags or notFocusableFlag
        }
    }

    fun shouldShowSoftInputAfterFocus(focusable: Boolean, requestInputFocus: Boolean): Boolean =
        focusable && requestInputFocus

    fun shouldClearInputFocus(focusable: Boolean): Boolean = !focusable
}
