package com.mindfulhome.ui.quicklaunch

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.mindfulhome.model.AppInfo
import com.mindfulhome.ui.icons.MaterialFolderWithSymbolOverlay
import com.mindfulhome.ui.icons.MaterialSymbolGlyph

/**
 * Single implementation for multi-app folder UI (grid + drag to extract / remove).
 * Used by QuickLaunch and Favorites with different titles, hints, and callbacks.
 */
@Composable
fun AppFolderDetailDialog(
    folder: QuickLaunchFolderOpen,
    onDismiss: () -> Unit,
    titleForFolder: (QuickLaunchFolderOpen) -> String,
    showRenameIcon: Boolean,
    onRenameIconClick: (() -> Unit)?,
    showSymbolIconButton: Boolean = true,
    onSymbolIconClick: (() -> Unit)? = null,
    onLaunchApp: (AppInfo) -> Unit,
    onDragRemove: (AppInfo) -> Unit,
    onDragExtractToOwnSlot: (AppInfo) -> Unit,
    dragHintText: String,
    removeDropContentDescription: String,
) {
    val titleLabel = titleForFolder(folder)
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.fillMaxWidth(),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (folder.folderSymbolIconName != null) {
                    MaterialSymbolGlyph(
                        symbolIconName = folder.folderSymbolIconName,
                        size = 40.dp,
                        contentDescription = titleLabel,
                        modifier = Modifier.padding(end = 8.dp),
                    )
                } else {
                    MaterialFolderWithSymbolOverlay(
                        symbolIconName = null,
                        contentDescription = "",
                        folderSize = 40.dp,
                        modifier = Modifier.padding(end = 8.dp),
                    )
                }
                Text(
                    text = titleLabel,
                    modifier = Modifier.weight(1f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (showSymbolIconButton && onSymbolIconClick != null) {
                    IconButton(onClick = onSymbolIconClick) {
                        Icon(
                            Icons.Outlined.Palette,
                            contentDescription = "Choose folder symbol",
                        )
                    }
                }
                if (showRenameIcon && onRenameIconClick != null) {
                    IconButton(onClick = onRenameIconClick) {
                        Icon(
                            Icons.Outlined.Edit,
                            contentDescription = "Rename folder",
                        )
                    }
                }
            }
        },
        text = {
            QuickLaunchFolderBody(
                apps = folder.apps,
                onLaunchApp = onLaunchApp,
                onDragRemove = onDragRemove,
                onDragExtractToOwnSlot = onDragExtractToOwnSlot,
                dragHintText = dragHintText,
                removeDropContentDescription = removeDropContentDescription,
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Done")
            }
        },
    )
}
