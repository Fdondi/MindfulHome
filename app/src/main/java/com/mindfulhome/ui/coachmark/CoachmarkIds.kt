package com.mindfulhome.ui.coachmark

object CoachmarkIds {
    const val HOME_TIMER = "home_timer"
    const val HOME_NOTIFICATIONS = "home_notifications"
    const val HOME_SEARCH = "home_search"
    const val HOME_KARMA = "home_karma"
    const val HOME_SETTINGS = "home_settings"
    const val HOME_AI = "home_ai"
    const val HOME_APP_GRID = "home_app_grid"
    const val HOME_FAVORITES = "home_favorites"

    const val TODO_CARD = "todo_card"
    const val TODO_ADD = "todo_add"
    const val TODO_START = "todo_start"
    const val QL_FOLDERS = "ql_folders"
    const val QL_SOMETHING_ELSE = "ql_something_else"

    const val SETTINGS_LANGUAGE = "settings_language"
    const val SETTINGS_PERMISSIONS = "settings_permissions"
    const val SETTINGS_NOTIFICATIONS = "settings_notifications"
    const val SETTINGS_BEHAVIOR = "settings_behavior"
    const val SETTINGS_AI = "settings_ai"
    const val SETTINGS_GATE = "settings_gate"
    const val SETTINGS_DAILY = "settings_daily"
}

enum class CoachmarkScreen(val storageKey: String) {
    HOME("home"),
    DEFAULT_PAGE("default_page"),
    SETTINGS("settings"),
    ;

    companion object {
        fun fromStorageKey(key: String): CoachmarkScreen? = entries.find { it.storageKey == key }
    }
}

enum class CoachmarkCutout {
    Circle,
    RoundedRect,
}
