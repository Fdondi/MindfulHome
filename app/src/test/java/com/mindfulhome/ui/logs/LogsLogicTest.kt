package com.mindfulhome.ui.logs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogsLogicTest {

    @Test
    fun daySummaryPreview_prefersTaglineThenFirstLine() {
        assertEquals("Tag", daySummaryPreview("line1\nline2", "Tag"))
        assertEquals("line1", daySummaryPreview("line1\nline2", "  "))
        assertEquals("No summary yet.", daySummaryPreview("", ""))
    }

    @Test
    fun sessionBulletLines_filtersDashPrefixes() {
        assertEquals(
            listOf("- a", "- b"),
            sessionBulletLines("intro\n- a\nplain\n- b"),
        )
        assertTrue(sessionBulletLines(null).isEmpty())
    }

    @Test
    fun isSingleEventSession_and_shouldLoadMore() {
        assertTrue(isSingleEventSession(1))
        assertFalse(isSingleEventSession(2))
        assertTrue(
            shouldLoadMoreLogs(
                loadPhase = LogsLoadPhase.Ready,
                isLoadingMore = false,
                hasMoreOlderDays = true,
                total = 10,
                lastVisible = 8,
            ),
        )
        assertFalse(
            shouldLoadMoreLogs(
                loadPhase = LogsLoadPhase.Ready,
                isLoadingMore = false,
                hasMoreOlderDays = true,
                total = 10,
                lastVisible = 5,
            ),
        )
        assertFalse(
            shouldLoadMoreLogs(
                loadPhase = LogsLoadPhase.LoadingMore,
                isLoadingMore = false,
                hasMoreOlderDays = true,
                total = 10,
                lastVisible = 9,
            ),
        )
    }
}
