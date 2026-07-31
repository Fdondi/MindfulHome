package com.mindfulhome.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.mindfulhome.AppVersion
import com.mindfulhome.service.ForegroundAppAccessibilityService

private val AccessibilityOnGreen = Color(0xFF2E7D32)
private val AccessibilityOffRed = Color(0xFFC62828)

/**
 * App version with a tiny green/red dot for accessibility app-switch detection status.
 * Tap opens system Accessibility settings.
 */
@Composable
fun VersionLabel(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var accessibilityEnabled by remember {
        mutableStateOf(ForegroundAppAccessibilityService.isEnabled(context))
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        accessibilityEnabled = ForegroundAppAccessibilityService.isEnabled(context)
    }

    val statusDescription = if (accessibilityEnabled) {
        "App-switch detection on"
    } else {
        "App-switch detection off"
    }

    Row(
        modifier = modifier
            .clickable {
                try {
                    context.startActivity(ForegroundAppAccessibilityService.settingsIntent())
                } catch (_: Exception) {
                    // Some devices lack a direct Accessibility settings activity.
                }
            }
            .semantics(mergeDescendants = true) {
                contentDescription = "Version ${AppVersion.versionName}. $statusDescription"
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Text(
            text = "v${AppVersion.versionName}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Box(
            modifier = Modifier
                .size(7.dp)
                .background(
                    color = if (accessibilityEnabled) AccessibilityOnGreen else AccessibilityOffRed,
                    shape = CircleShape,
                ),
        )
    }
}
