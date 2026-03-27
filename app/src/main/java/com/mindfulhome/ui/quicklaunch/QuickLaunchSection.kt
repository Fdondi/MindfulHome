package com.mindfulhome.ui.quicklaunch

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import com.mindfulhome.data.AppRepository

@Composable
fun QuickLaunchSection(
    repository: AppRepository,
    onQuickLaunchApp: (packageName: String, allowedPackages: Set<String>) -> Unit,
    modifier: Modifier = Modifier,
    onAppSlotBounds: (uiIndex: Int, topLeft: Offset, size: Size) -> Unit = { _, _, _ -> },
) {
    AppSlotStripSection(
        repository = repository,
        kind = AppSlotStripKind.QuickLaunch,
        onLaunchApp = onQuickLaunchApp,
        modifier = modifier,
        onAppSlotBounds = onAppSlotBounds,
    )
}
