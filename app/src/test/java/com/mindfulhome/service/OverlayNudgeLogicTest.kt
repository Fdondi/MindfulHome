package com.mindfulhome.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import java.util.TimeZone

class OverlayNudgeLogicTest {

    @Test
    fun formatBannerPreviewText_takesLastThreeJoined() {
        val text = OverlayNudgeLogic.formatBannerPreviewText(
            listOf("a", "b", "c", "d", "e"),
        )
        assertEquals("c\nd\ne", text)
    }

    @Test
    fun formatBannerPreviewText_emptyUsesFallback() {
        val text = OverlayNudgeLogic.formatBannerPreviewText(emptyList())
        assertEquals(OverlayNudgeLogic.DEFAULT_BANNER_PREVIEW, text)
    }

    @Test
    fun formatBannerPreviewText_fewerThanThreeKeepsAll() {
        assertEquals("only\none", OverlayNudgeLogic.formatBannerPreviewText(listOf("only", "one")))
    }

    @Test
    fun badgeText_greenUsesFormatter() {
        val text = OverlayNudgeLogic.badgeTextForType(
            type = NudgeBirdType.GREEN_NOW,
            nowMs = 1_700_000_000_000L,
            softDeadlineAtMs = null,
            hardDeadlineAtMs = null,
            formatNowTime = { "12:34" },
        )
        assertEquals("12:34", text)
    }

    @Test
    fun badgeText_purpleNullSoftIsZero() {
        val text = OverlayNudgeLogic.badgeTextForType(
            type = NudgeBirdType.PURPLE_SOFT,
            nowMs = 10_000L,
            softDeadlineAtMs = null,
            hardDeadlineAtMs = null,
        )
        assertEquals("+0m", text)
    }

    @Test
    fun badgeText_purpleCountsWholeMinutesPastSoft() {
        val softAt = 1_000L
        val now = softAt + 3 * 60_000L + 59_999L
        val text = OverlayNudgeLogic.badgeTextForType(
            type = NudgeBirdType.PURPLE_SOFT,
            nowMs = now,
            softDeadlineAtMs = softAt,
            hardDeadlineAtMs = null,
        )
        assertEquals("+3m", text)
    }

    @Test
    fun badgeText_purpleDoesNotGoNegativeBeforeSoft() {
        val text = OverlayNudgeLogic.badgeTextForType(
            type = NudgeBirdType.PURPLE_SOFT,
            nowMs = 1_000L,
            softDeadlineAtMs = 5_000L,
            hardDeadlineAtMs = null,
        )
        assertEquals("+0m", text)
    }

    @Test
    fun badgeText_redNullHardIsHi() {
        val text = OverlayNudgeLogic.badgeTextForType(
            type = NudgeBirdType.RED_HARD,
            nowMs = 1_000L,
            softDeadlineAtMs = null,
            hardDeadlineAtMs = null,
        )
        assertEquals("hi", text)
    }

    @Test
    fun badgeText_redBeforeDeadlineUsesMinusCeilMinutes() {
        // 1ms remaining → ceil to 1 minute
        val text = OverlayNudgeLogic.badgeTextForType(
            type = NudgeBirdType.RED_HARD,
            nowMs = 1_000L,
            softDeadlineAtMs = null,
            hardDeadlineAtMs = 1_001L,
        )
        assertEquals("-1m", text)
    }

    @Test
    fun badgeText_redPastDeadlineUsesPlusCeilMinutes() {
        val text = OverlayNudgeLogic.badgeTextForType(
            type = NudgeBirdType.RED_HARD,
            nowMs = 10_000L,
            softDeadlineAtMs = null,
            hardDeadlineAtMs = 1_000L,
        )
        assertEquals("+1m", text)
    }

    @Test
    fun badgeText_predatoryIsKarma() {
        val text = OverlayNudgeLogic.badgeTextForType(
            type = NudgeBirdType.PREDATORY,
            nowMs = 0L,
            softDeadlineAtMs = null,
            hardDeadlineAtMs = null,
        )
        assertEquals("-1 KARMA", text)
    }

    @Test
    fun formatBirdBadgeTime_usesLocaleTimezone() {
        val previous = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            // 1970-01-01T01:02:00Z
            val formatted = OverlayNudgeLogic.formatBirdBadgeTime(
                timestampMs = (1 * 3600 + 2 * 60) * 1000L,
                locale = Locale.US,
            )
            assertEquals("01:02", formatted)
        } finally {
            TimeZone.setDefault(previous)
        }
    }

    @Test
    fun computeSpawnRanges_clampsWhenContainerLargerThanScreen() {
        val ranges = OverlayNudgeLogic.computeSpawnRanges(
            screenWidthPx = 100,
            screenHeightPx = 200,
            containerWidthPx = 200,
            containerHeightPx = 300,
            padXPx = 8,
            padYTopPx = 60,
            padYBottomPx = 120,
        )
        assertEquals(8, ranges.minX)
        assertEquals(8, ranges.maxX)
        assertEquals(60, ranges.minY)
        assertEquals(60, ranges.maxY)
    }

    @Test
    fun computeSpawnRanges_normalRoom() {
        val ranges = OverlayNudgeLogic.computeSpawnRanges(
            screenWidthPx = 1080,
            screenHeightPx = 1920,
            containerWidthPx = 100,
            containerHeightPx = 80,
            padXPx = 8,
            padYTopPx = 60,
            padYBottomPx = 120,
        )
        assertEquals(8, ranges.minX)
        assertEquals(1080 - 100 - 8, ranges.maxX)
        assertEquals(60, ranges.minY)
        assertEquals(1920 - 80 - 120, ranges.maxY)
    }

    @Test
    fun clampSpawnPosition_usesInjectedRandomAndAttemptOffset() {
        val ranges = BubbleSpawnRanges(minX = 10, maxX = 100, minY = 20, maxY = 200)
        val point = OverlayNudgeLogic.clampSpawnPosition(
            ranges = ranges,
            attemptIndex = 2,
            attemptOffsetStepPx = 14,
            nextIntInclusive = { from, _ -> from + 5 },
        )
        assertEquals((10 + 5) + 2 * 14, point.x)
        assertEquals((20 + 5) + 2 * 14, point.y)
    }

    @Test
    fun clampSpawnPosition_capsOffsetAtMax() {
        val ranges = BubbleSpawnRanges(minX = 0, maxX = 30, minY = 0, maxY = 30)
        val point = OverlayNudgeLogic.clampSpawnPosition(
            ranges = ranges,
            attemptIndex = 10,
            attemptOffsetStepPx = 14,
            nextIntInclusive = { from, _ -> from },
        )
        assertEquals(30, point.x)
        assertEquals(30, point.y)
    }

    @Test
    fun clampSpawnPosition_degenerateRangeUsesMin() {
        val ranges = BubbleSpawnRanges(minX = 8, maxX = 8, minY = 60, maxY = 60)
        var calls = 0
        val point = OverlayNudgeLogic.clampSpawnPosition(
            ranges = ranges,
            attemptIndex = 0,
            attemptOffsetStepPx = 14,
            nextIntInclusive = { _, _ ->
                calls++
                error("should not randomize degenerate range")
            },
        )
        assertEquals(0, calls)
        assertEquals(8, point.x)
        assertEquals(60, point.y)
    }

    @Test
    fun bubbleSizeLayout_predatoryVsSmall() {
        val dp = { v: Int -> v * 2 }
        val predatory = OverlayNudgeLogic.bubbleSizeLayout(isPredatory = true, dp = dp)
        val small = OverlayNudgeLogic.bubbleSizeLayout(isPredatory = false, dp = dp)
        assertEquals(164, predatory.birdSizePx)
        assertEquals(112, small.birdSizePx)
        assertEquals(164 + 84, predatory.containerWidthPx) // badgeWidth/2 = 84
        assertEquals(112 + 56, small.containerWidthPx)
    }

    @Test
    fun exceededDragThreshold_andPredatoryHelpers() {
        assertFalse(OverlayNudgeLogic.exceededDragThreshold(3f, 4f, thresholdPx = 6))
        assertTrue(OverlayNudgeLogic.exceededDragThreshold(3f, 4f, thresholdPx = 4))
        assertTrue(OverlayNudgeLogic.isPredatoryBird(NudgeBirdType.PREDATORY))
        assertEquals(4, OverlayNudgeLogic.birdPaddingDp(NudgeBirdType.PREDATORY))
        assertEquals(6, OverlayNudgeLogic.birdPaddingDp(NudgeBirdType.GREEN_NOW))
        assertEquals(10f, OverlayNudgeLogic.badgeTextSizeSp(NudgeBirdType.PREDATORY), 0f)
        assertTrue(OverlayNudgeLogic.shouldRefreshExistingBanner(true))
        assertFalse(OverlayNudgeLogic.shouldRefreshExistingBanner(false))
    }

    @Test
    fun quickLaunchBorderEdges() {
        val edges = OverlayNudgeLogic.quickLaunchBorderEdges()
        assertEquals(2, edges.size)
        assertEquals("top", edges[0].name)
        assertEquals("bottom", edges[1].name)
    }

    @Test
    fun badgeStyleColors_andBannerFocusHelpers() {
        val green = OverlayNudgeLogic.badgeStyleColors(NudgeBirdType.GREEN_NOW)
        assertEquals(1, green.strokeWidthDp)
        val predatory = OverlayNudgeLogic.badgeStyleColors(NudgeBirdType.PREDATORY)
        assertEquals(2, predatory.strokeWidthDp)
        assertTrue(OverlayNudgeLogic.shouldSkipAwayShieldShow(alreadyShowing = true, canDraw = true))
        assertTrue(OverlayNudgeLogic.shouldSkipAwayShieldShow(alreadyShowing = false, canDraw = false))
        assertFalse(OverlayNudgeLogic.shouldSkipAwayShieldShow(alreadyShowing = false, canDraw = true))
        assertNull(
            OverlayNudgeLogic.nextBannerFocusableFlags(
                currentlyFocusable = true,
                focusable = true,
                requestInputFocus = false,
                flags = 0,
                notFocusableFlag = 8,
            ),
        )
        assertEquals(
            8,
            OverlayNudgeLogic.nextBannerFocusableFlags(
                currentlyFocusable = true,
                focusable = false,
                requestInputFocus = false,
                flags = 0,
                notFocusableFlag = 8,
            ),
        )
        assertTrue(OverlayNudgeLogic.shouldShowSoftInputAfterFocus(true, true))
        assertFalse(OverlayNudgeLogic.shouldShowSoftInputAfterFocus(true, false))
        assertTrue(OverlayNudgeLogic.shouldClearInputFocus(false))
    }
}
