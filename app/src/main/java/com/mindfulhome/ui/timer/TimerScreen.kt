package com.mindfulhome.ui.timer

import android.util.Log
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.material3.rememberStandardBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.accompanist.drawablepainter.rememberDrawablePainter
import com.mindfulhome.data.AppRepository
import com.mindfulhome.logging.SessionLogger
import com.mindfulhome.model.AppInfo
import com.mindfulhome.util.PackageManagerHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val MAX_MINUTES = 120
private const val VISIBLE_ITEMS = 5
private const val ITEM_HEIGHT_DP = 64

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimerScreen(
    onTimerSet: (minutes: Int, reason: String) -> Unit,
    savedAppLabel: String? = null,
    savedMinutes: Int = 0,
    onResumeSession: (() -> Unit)? = null,
    repository: AppRepository? = null,
    onShelfAppLaunch: ((minutes: Int, reason: String, packageName: String) -> Unit)? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val items = (1..MAX_MINUTES).toList()
    val listState = rememberLazyListState()
    var reason by remember { mutableStateOf("") }

    var selectedMinutes by remember { mutableStateOf(1) }

    val centerIndex by remember {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            val viewportCenter = layoutInfo.viewportStartOffset +
                    (layoutInfo.viewportEndOffset - layoutInfo.viewportStartOffset) / 2
            layoutInfo.visibleItemsInfo.minByOrNull {
                val itemCenter = it.offset + it.size / 2
                kotlin.math.abs(itemCenter - viewportCenter)
            }?.index ?: 0
        }
    }

    // Only commit selection when the user finishes an active scroll gesture
    LaunchedEffect(Unit) {
        var wasScrolling = false
        snapshotFlow { listState.isScrollInProgress to centerIndex }
            .collect { (scrolling, index) ->
                if (wasScrolling && !scrolling) {
                    selectedMinutes = items.getOrElse(index) { 1 }
                }
                wasScrolling = scrolling
            }
    }

    // Restore scroll position when viewport resizes (keyboard open/close)
    LaunchedEffect(Unit) {
        snapshotFlow {
            listState.layoutInfo.viewportEndOffset - listState.layoutInfo.viewportStartOffset
        }.distinctUntilChanged().collect {
            if (!listState.isScrollInProgress) {
                val targetIndex = selectedMinutes - 1
                if (targetIndex >= 0) {
                    listState.scrollToItem(targetIndex)
                }
            }
        }
    }

    // Shelf state
    val shelfItems by repository?.shelfApps()?.collectAsState(initial = emptyList())
        ?: remember { mutableStateOf(emptyList()) }
    var allApps by remember { mutableStateOf<List<AppInfo>>(emptyList()) }
    var showAddDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        allApps = withContext(Dispatchers.IO) {
            PackageManagerHelper.getInstalledApps(context)
        }
    }

    val shelfApps = remember(shelfItems, allApps) {
        shelfItems.mapNotNull { shelf ->
            allApps.find { it.packageName == shelf.packageName }
        }
    }

    val hasShelf = repository != null && onShelfAppLaunch != null

    val scaffoldState = rememberBottomSheetScaffoldState(
        bottomSheetState = rememberStandardBottomSheetState(
            initialValue = SheetValue.PartiallyExpanded,
            skipHiddenState = true
        )
    )

    BottomSheetScaffold(
        scaffoldState = scaffoldState,
        sheetPeekHeight = if (hasShelf) 48.dp else 0.dp,
        sheetContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        sheetShape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        sheetContent = {
            if (hasShelf) {
                ShelfContent(
                    shelfApps = shelfApps,
                    onAppClick = { appInfo ->
                        onShelfAppLaunch?.invoke(
                            selectedMinutes,
                            reason.trim(),
                            appInfo.packageName
                        )
                    },
                    onRemoveApp = { packageName ->
                        scope.launch { repository?.removeFromShelf(packageName) }
                    },
                    onAddClick = { showAddDialog = true }
                )
            }
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "How long do you want\nto use your phone?",
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onBackground
            )

            Spacer(modifier = Modifier.height(24.dp))

            BoxWithConstraints(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                val visibleCount = (maxHeight / ITEM_HEIGHT_DP.dp)
                    .toInt()
                    .coerceIn(1, VISIBLE_ITEMS)

                Box(
                    modifier = Modifier
                        .height(ITEM_HEIGHT_DP.dp)
                        .fillMaxWidth(0.6f)
                        .background(
                            MaterialTheme.colorScheme.primaryContainer,
                            MaterialTheme.shapes.medium
                        )
                )

                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .heightIn(max = (ITEM_HEIGHT_DP * visibleCount).dp)
                        .fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    flingBehavior = rememberSnapFlingBehavior(lazyListState = listState),
                    contentPadding = PaddingValues(
                        vertical = (ITEM_HEIGHT_DP * (visibleCount / 2)).dp
                    )
                ) {
                    items(items.size) { index ->
                        val distanceFromCenter = kotlin.math.abs(index - centerIndex)
                        val alphaValue by animateFloatAsState(
                            targetValue = when (distanceFromCenter) {
                                0 -> 1f
                                1 -> 0.6f
                                else -> 0.3f
                            },
                            label = "alpha"
                        )

                        Box(
                            modifier = Modifier
                                .height(ITEM_HEIGHT_DP.dp)
                                .fillMaxWidth()
                                .alpha(alphaValue),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = formatMinutes(items[index]),
                                fontSize = if (distanceFromCenter == 0) 32.sp else 22.sp,
                                fontWeight = if (distanceFromCenter == 0) FontWeight.Bold else FontWeight.Normal,
                                color = if (distanceFromCenter == 0) {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onBackground
                                }
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            OutlinedTextField(
                value = reason,
                onValueChange = { reason = it },
                modifier = Modifier.fillMaxWidth(0.8f),
                placeholder = { Text("Why are you unlocking? (optional)") },
                singleLine = false,
                maxLines = 2,
                shape = MaterialTheme.shapes.medium,
            )

            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = {
                    Log.d("TimerScreen", "Start clicked: selectedMinutes=$selectedMinutes reason='${reason.trim()}'")
                    val trimmedReason = reason.trim()
                    val logSuffix = if (trimmedReason.isNotEmpty()) " — $trimmedReason" else ""
                    SessionLogger.log("Timer set: **$selectedMinutes min**$logSuffix")
                    Log.d("TimerScreen", "Calling onTimerSet")
                    onTimerSet(selectedMinutes, trimmedReason)
                    Log.d("TimerScreen", "onTimerSet returned")
                },
                modifier = Modifier
                    .fillMaxWidth(0.6f)
                    .height(56.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                ),
                shape = MaterialTheme.shapes.large
            ) {
                Text(
                    text = "Start",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

            if (savedAppLabel != null && onResumeSession != null && savedMinutes > 0) {
                Spacer(modifier = Modifier.height(24.dp))

                OutlinedButton(
                    onClick = {
                        SessionLogger.log(
                            "Resumed previous session: **$savedAppLabel** ($savedMinutes min)"
                        )
                        onResumeSession()
                    },
                    modifier = Modifier
                        .fillMaxWidth(0.8f)
                        .height(48.dp),
                    shape = MaterialTheme.shapes.large
                ) {
                    Text(
                        text = "Resume $savedAppLabel (${formatMinutes(savedMinutes)})",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }

    if (showAddDialog && hasShelf) {
        AddToShelfDialog(
            allApps = allApps,
            shelfPackages = shelfItems.map { it.packageName }.toSet(),
            onAdd = { packageName ->
                scope.launch { repository?.addToShelf(packageName) }
            },
            onDismiss = { showAddDialog = false }
        )
    }
}

// ---------------------------------------------------------------------------
// Shelf content (inside the bottom sheet)
// ---------------------------------------------------------------------------

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ShelfContent(
    shelfApps: List<AppInfo>,
    onAppClick: (AppInfo) -> Unit,
    onRemoveApp: (String) -> Unit,
    onAddClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Quick Launch",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(12.dp))

        if (shelfApps.isEmpty()) {
            Text(
                text = "Swipe up and tap + to add apps",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.padding(vertical = 24.dp)
            )
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                modifier = Modifier.heightIn(max = 300.dp),
                contentPadding = PaddingValues(4.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(
                    count = shelfApps.size,
                    key = { shelfApps[it].packageName }
                ) { index ->
                    val app = shelfApps[index]
                    ShelfAppItem(
                        appInfo = app,
                        onClick = { onAppClick(app) },
                        onLongClick = { onRemoveApp(app.packageName) }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        IconButton(onClick = onAddClick) {
            Icon(
                Icons.Default.Add,
                contentDescription = "Add app to shelf",
                tint = MaterialTheme.colorScheme.primary
            )
        }

        Spacer(modifier = Modifier.height(8.dp))
    }
}

// ---------------------------------------------------------------------------
// Single shelf app icon
// ---------------------------------------------------------------------------

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ShelfAppItem(
    appInfo: AppInfo,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
            .padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (appInfo.icon != null) {
            Image(
                painter = rememberDrawablePainter(drawable = appInfo.icon),
                contentDescription = appInfo.label,
                modifier = Modifier.size(48.dp)
            )
        }
        Text(
            text = appInfo.label,
            fontSize = 10.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .padding(top = 2.dp)
                .width(60.dp)
        )
    }
}

// ---------------------------------------------------------------------------
// Dialog to add apps to the shelf
// ---------------------------------------------------------------------------

@Composable
private fun AddToShelfDialog(
    allApps: List<AppInfo>,
    shelfPackages: Set<String>,
    onAdd: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    val filtered = remember(allApps, searchQuery, shelfPackages) {
        allApps
            .filter { it.packageName !in shelfPackages }
            .filter {
                searchQuery.isBlank() ||
                        it.label.contains(searchQuery, ignoreCase = true)
            }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text("Add to Quick Launch")
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Search apps…") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Default.Close, contentDescription = "Clear")
                            }
                        }
                    }
                )
            }
        },
        text = {
            LazyColumn(
                modifier = Modifier.heightIn(max = 400.dp)
            ) {
                items(
                    count = filtered.size,
                    key = { filtered[it].packageName }
                ) { index ->
                    val app = filtered[index]
                    AppListRow(
                        appInfo = app,
                        onClick = {
                            onAdd(app.packageName)
                        }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done") }
        }
    )
}

// ---------------------------------------------------------------------------
// App row inside the add-to-shelf dialog
// ---------------------------------------------------------------------------

@Composable
private fun AppListRow(
    appInfo: AppInfo,
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
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private fun formatMinutes(minutes: Int): String {
    return when {
        minutes < 60 -> "$minutes min"
        minutes % 60 == 0 -> "${minutes / 60} hr"
        else -> "${minutes / 60} hr ${minutes % 60} min"
    }
}
