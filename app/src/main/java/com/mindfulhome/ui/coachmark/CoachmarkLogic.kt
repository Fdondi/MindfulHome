package com.mindfulhome.ui.coachmark

internal const val COACHMARK_AUTO_START_DELAY_MS = 450L
internal const val COACHMARK_REVEAL_TOP_PX = 160f

fun shouldAutoStartTour(done: Boolean, alreadyShowing: Boolean): Boolean =
    shouldStartTour(done = done, alreadyShowing = alreadyShowing, pendingReplay = false)

fun shouldStartTour(done: Boolean, alreadyShowing: Boolean, pendingReplay: Boolean): Boolean {
    if (alreadyShowing) return false
    return pendingReplay || !done
}

fun defaultPageStepIds(hasOpenTodos: Boolean): List<String> = buildList {
    add(CoachmarkIds.TODO_CARD)
    add(CoachmarkIds.TODO_ADD)
    if (hasOpenTodos) add(CoachmarkIds.TODO_START)
    add(CoachmarkIds.QL_FOLDERS)
    add(CoachmarkIds.QL_SOMETHING_ELSE)
}

fun scrollOffsetToReveal(
    currentScroll: Int,
    targetTopInRoot: Float,
    desiredTop: Float = COACHMARK_REVEAL_TOP_PX,
): Int = (currentScroll + (targetTopInRoot - desiredTop).toInt()).coerceAtLeast(0)
