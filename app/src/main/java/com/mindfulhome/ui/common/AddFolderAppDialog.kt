package com.mindfulhome.ui.common
import androidx.compose.ui.res.stringResource

import com.mindfulhome.R

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.google.accompanist.drawablepainter.rememberDrawablePainter
import com.mindfulhome.data.QuickLaunchFolderApp
import com.mindfulhome.model.AppInfo

@Composable
fun AddFolderAppDialog(
    appInfo: AppInfo,
    title: String = "Add to folder",
    confirmLabel: String = "Add",
    initialLimitMinutes: Int? = QuickLaunchFolderApp.DEFAULT_LIMIT_MINUTES,
    onConfirm: (limitMinutes: Int?) -> Unit,
    onDismiss: () -> Unit,
) {
    var unlimited by remember(initialLimitMinutes) { mutableStateOf(initialLimitMinutes == null) }
    var minutesText by remember(initialLimitMinutes) {
        mutableStateOf(
            (initialLimitMinutes ?: QuickLaunchFolderApp.DEFAULT_LIMIT_MINUTES).toString(),
        )
    }

    val parsedMinutes = minutesText.trim().toIntOrNull()?.coerceAtLeast(1)
    val canConfirm = unlimited || parsedMinutes != null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (appInfo.icon != null) {
                        Image(
                            painter = rememberDrawablePainter(drawable = appInfo.icon),
                            contentDescription = appInfo.label,
                            modifier = Modifier.size(40.dp),
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                    }
                    Text(
                        text = appInfo.label,
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                Text(
                    text = stringResource(R.string.timed_apps_launch_right_away_and_start_a_backgro),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.unlimited_no_timer))
                    Switch(
                        checked = unlimited,
                        onCheckedChange = { unlimited = it },
                    )
                }
                if (!unlimited) {
                    OutlinedTextField(
                        value = minutesText,
                        onValueChange = { minutesText = it.filter { ch -> ch.isDigit() } },
                        label = { Text(stringResource(R.string.minutes)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(if (unlimited) null else parsedMinutes)
                },
                enabled = canConfirm,
            ) {
                Text(confirmLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}
