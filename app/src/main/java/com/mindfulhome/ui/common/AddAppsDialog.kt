package com.mindfulhome.ui.common
import androidx.compose.ui.res.stringResource

import com.mindfulhome.R

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.google.accompanist.drawablepainter.rememberDrawablePainter
import com.mindfulhome.data.AppSlotPlacement
import com.mindfulhome.model.AppInfo
import com.mindfulhome.ui.icons.MaterialSymbolGlyph
import kotlinx.coroutines.launch

@Composable
fun AddAppsDialog(
    title: String,
    apps: List<AppInfo>,
    placementByPackage: Map<String, List<AppSlotPlacement>>,
    onAdd: (String) -> Unit,
    onDismiss: () -> Unit,
    onRefresh: (suspend () -> Unit)? = null,
) {
    var searchQuery by remember { mutableStateOf("") }
    var refreshing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val filtered = remember(apps, searchQuery) {
        apps.filter {
            searchQuery.isBlank() ||
                it.label.contains(searchQuery, ignoreCase = true)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    modifier = Modifier.weight(1f),
                )
                if (onRefresh != null) {
                    if (refreshing) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .padding(end = 4.dp)
                                .size(24.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        IconButton(
                            onClick = {
                                if (refreshing) return@IconButton
                                refreshing = true
                                scope.launch {
                                    try {
                                        onRefresh()
                                    } finally {
                                        refreshing = false
                                    }
                                }
                            },
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = stringResource(R.string.refresh_app_list),
                            )
                        }
                    }
                }
            }
        },
        text = {
            LazyColumn(
                modifier = Modifier.heightIn(max = 400.dp)
            ) {
                item(key = "search_field") {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text(stringResource(R.string.search_apps)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { searchQuery = "" }) {
                                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.clear))
                                }
                            }
                        }
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
                items(
                    count = filtered.size,
                    key = { filtered[it].packageName }
                ) { index ->
                    val app = filtered[index]
                    val placements = placementByPackage[app.packageName].orEmpty()
                    AddAppListRow(
                        appInfo = app,
                        placements = placements,
                        onClick = { onAdd(app.packageName) }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.done)) }
        }
    )
}

@Composable
private fun AddAppListRow(
    appInfo: AppInfo,
    placements: List<AppSlotPlacement>,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (appInfo.icon != null) {
            Image(
                painter = rememberDrawablePainter(drawable = appInfo.icon),
                contentDescription = appInfo.label,
                modifier = Modifier.size(40.dp)
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = appInfo.label,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (placements.isNotEmpty()) {
            AddAppPlacementBadges(placements)
        }
    }
}

@Composable
private fun AddAppPlacementBadges(placements: List<AppSlotPlacement>) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Default.Check,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            placements.forEach { p ->
                when (p) {
                    is AppSlotPlacement.Root -> {
                        Icon(
                            imageVector = Icons.Outlined.Home,
                            contentDescription = stringResource(R.string.on_strip),
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    is AppSlotPlacement.InFolder -> {
                        MaterialSymbolGlyph(
                            symbolIconName = p.symbolIconName ?: "folder",
                            size = 14.dp,
                            contentDescription = stringResource(R.string.in_folder),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
