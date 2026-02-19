package com.mindfulhome.ui.home

import com.mindfulhome.model.AppInfo

sealed class HomeGridItem {
    abstract val position: Int
    abstract val key: String

    data class AppEntry(
        val appInfo: AppInfo,
        override val position: Int
    ) : HomeGridItem() {
        override val key: String get() = "app:${appInfo.packageName}"
    }
}
