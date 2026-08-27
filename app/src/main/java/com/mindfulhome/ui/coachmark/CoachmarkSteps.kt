package com.mindfulhome.ui.coachmark

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.mindfulhome.R
import io.luminos.CoachmarkTarget
import io.luminos.CutoutShape

data class CoachmarkStepSpec(
    val id: String,
    @StringRes val titleRes: Int,
    @StringRes val bodyRes: Int,
    val cutout: CoachmarkCutout,
)

fun homeCoachmarkSpecs(): List<CoachmarkStepSpec> = listOf(
    CoachmarkStepSpec(
        CoachmarkIds.HOME_TIMER,
        R.string.coachmark_timer_title,
        R.string.coachmark_timer_body,
        CoachmarkCutout.RoundedRect,
    ),
    CoachmarkStepSpec(
        CoachmarkIds.HOME_NOTIFICATIONS,
        R.string.coachmark_notifications_title,
        R.string.coachmark_notifications_body,
        CoachmarkCutout.RoundedRect,
    ),
    CoachmarkStepSpec(
        CoachmarkIds.HOME_SEARCH,
        R.string.coachmark_search_title,
        R.string.coachmark_search_body,
        CoachmarkCutout.Circle,
    ),
    CoachmarkStepSpec(
        CoachmarkIds.HOME_KARMA,
        R.string.coachmark_karma_title,
        R.string.coachmark_karma_body,
        CoachmarkCutout.Circle,
    ),
    CoachmarkStepSpec(
        CoachmarkIds.HOME_SETTINGS,
        R.string.coachmark_settings_icon_title,
        R.string.coachmark_settings_icon_body,
        CoachmarkCutout.Circle,
    ),
    CoachmarkStepSpec(
        CoachmarkIds.HOME_AI,
        R.string.coachmark_ai_title,
        R.string.coachmark_ai_body,
        CoachmarkCutout.Circle,
    ),
    CoachmarkStepSpec(
        CoachmarkIds.HOME_APP_GRID,
        R.string.coachmark_app_grid_title,
        R.string.coachmark_app_grid_body,
        CoachmarkCutout.RoundedRect,
    ),
    CoachmarkStepSpec(
        CoachmarkIds.HOME_FAVORITES,
        R.string.coachmark_favorites_title,
        R.string.coachmark_favorites_body,
        CoachmarkCutout.RoundedRect,
    ),
)

fun defaultPageCoachmarkSpecs(hasOpenTodos: Boolean): List<CoachmarkStepSpec> {
    val byId = mapOf(
        CoachmarkIds.TODO_CARD to CoachmarkStepSpec(
            CoachmarkIds.TODO_CARD,
            R.string.coachmark_todo_card_title,
            R.string.coachmark_todo_card_body,
            CoachmarkCutout.RoundedRect,
        ),
        CoachmarkIds.TODO_ADD to CoachmarkStepSpec(
            CoachmarkIds.TODO_ADD,
            R.string.coachmark_todo_add_title,
            R.string.coachmark_todo_add_body,
            CoachmarkCutout.Circle,
        ),
        CoachmarkIds.TODO_START to CoachmarkStepSpec(
            CoachmarkIds.TODO_START,
            R.string.coachmark_todo_start_title,
            R.string.coachmark_todo_start_body,
            CoachmarkCutout.Circle,
        ),
        CoachmarkIds.QL_FOLDERS to CoachmarkStepSpec(
            CoachmarkIds.QL_FOLDERS,
            R.string.coachmark_ql_folders_title,
            R.string.coachmark_ql_folders_body,
            CoachmarkCutout.RoundedRect,
        ),
        CoachmarkIds.QL_SOMETHING_ELSE to CoachmarkStepSpec(
            CoachmarkIds.QL_SOMETHING_ELSE,
            R.string.coachmark_ql_something_else_title,
            R.string.coachmark_ql_something_else_body,
            CoachmarkCutout.RoundedRect,
        ),
    )
    return defaultPageStepIds(hasOpenTodos).map { id ->
        byId.getValue(id)
    }
}

fun settingsCoachmarkSpecs(): List<CoachmarkStepSpec> = listOf(
    CoachmarkStepSpec(
        CoachmarkIds.SETTINGS_LANGUAGE,
        R.string.coachmark_settings_language_title,
        R.string.coachmark_settings_language_body,
        CoachmarkCutout.RoundedRect,
    ),
    CoachmarkStepSpec(
        CoachmarkIds.SETTINGS_PERMISSIONS,
        R.string.coachmark_settings_permissions_title,
        R.string.coachmark_settings_permissions_body,
        CoachmarkCutout.RoundedRect,
    ),
    CoachmarkStepSpec(
        CoachmarkIds.SETTINGS_NOTIFICATIONS,
        R.string.coachmark_settings_notifications_title,
        R.string.coachmark_settings_notifications_body,
        CoachmarkCutout.RoundedRect,
    ),
    CoachmarkStepSpec(
        CoachmarkIds.SETTINGS_BEHAVIOR,
        R.string.coachmark_settings_behavior_title,
        R.string.coachmark_settings_behavior_body,
        CoachmarkCutout.RoundedRect,
    ),
    CoachmarkStepSpec(
        CoachmarkIds.SETTINGS_AI,
        R.string.coachmark_settings_ai_title,
        R.string.coachmark_settings_ai_body,
        CoachmarkCutout.RoundedRect,
    ),
    CoachmarkStepSpec(
        CoachmarkIds.SETTINGS_GATE,
        R.string.coachmark_settings_gate_title,
        R.string.coachmark_settings_gate_body,
        CoachmarkCutout.RoundedRect,
    ),
    CoachmarkStepSpec(
        CoachmarkIds.SETTINGS_DAILY,
        R.string.coachmark_settings_daily_title,
        R.string.coachmark_settings_daily_body,
        CoachmarkCutout.RoundedRect,
    ),
)

@Composable
fun coachmarkTargets(specs: List<CoachmarkStepSpec>): List<CoachmarkTarget> {
    val next = stringResource(R.string.coachmark_next)
    val gotIt = stringResource(R.string.coachmark_got_it)
    return specs.mapIndexed { index, spec ->
        CoachmarkTarget(
            id = spec.id,
            title = stringResource(spec.titleRes),
            description = stringResource(spec.bodyRes),
            shape = cutoutShape(spec.cutout),
            ctaText = if (index == specs.lastIndex) gotIt else next,
        )
    }
}

private fun cutoutShape(cutout: CoachmarkCutout): CutoutShape = when (cutout) {
    CoachmarkCutout.Circle -> CutoutShape.Circle()
    CoachmarkCutout.RoundedRect -> CutoutShape.RoundedRect()
}
