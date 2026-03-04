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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.accompanist.drawablepainter.rememberDrawablePainter
import com.mindfulhome.AppVersion
import com.mindfulhome.data.AppRepository
import com.mindfulhome.model.AppInfo
import com.mindfulhome.service.UsageTracker
import com.mindfulhome.util.PackageManagerHelper
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

private const val MAX_MINUTES = 120
private const val VISIBLE_ITEMS = 5
private const val ITEM_HEIGHT_DP = 64
private const val MOST_USED_VISIBLE_ITEMS = 3
private const val MOST_USED_MAX_ITEMS = 15
private const val MOST_USED_ROW_HEIGHT_DP = 44
private const val SHELF_CELL_MIN_WIDTH_DP = 64
private const val SHELF_MAX_EXPANDED_HEIGHT_DP = 220

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimerScreen(
    onTimerSet: (minutes: Int, reason: String) -> Unit,
    savedAppLabel: String? = null,
    savedMinutes: Int = 0,
    onResumeSession: (() -> Unit)? = null,
    repository: AppRepository? = null,
    onShelfAppLaunch: ((
        minutes: Int,
        reason: String,
        packageName: String,
        quickLaunchPackages: Set<String>,
    ) -> Unit)? = null,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val appVersion = AppVersion.versionName

    val items = (1..MAX_MINUTES).toList()
    val listState = rememberLazyListState()
    var reason by remember { mutableStateOf("") }
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    val clockFormatter = remember { DateFormat.getTimeInstance(DateFormat.SHORT) }

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

    // Keep all actions relative to the same highlighted center row.
    val selectedMinutes by remember {
        derivedStateOf { items.getOrElse(centerIndex) { 1 } }
    }

    // Restore scroll position when viewport resizes (keyboard open/close)
    LaunchedEffect(Unit) {
        snapshotFlow {
            listState.layoutInfo.viewportEndOffset - listState.layoutInfo.viewportStartOffset
        }.distinctUntilChanged().collect {
            if (!listState.isScrollInProgress) {
                val targetIndex = selectedMinutes - 1
                if (targetIndex >= 0) {
                    val viewportHeightPx =
                        listState.layoutInfo.viewportEndOffset - listState.layoutInfo.viewportStartOffset
                    val itemHeightPx = with(density) {
                        ITEM_HEIGHT_DP.dp.roundToPx()
                    }
                    val centerOffset = -((viewportHeightPx - itemHeightPx) / 2)
                    listState.scrollToItem(targetIndex, centerOffset)
                }
            }
        }
    }

    // Keep end-time labels current while user is picking a duration.
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000L)
            nowMs = System.currentTimeMillis()
        }
    }

    // Shelf state
    val shelfItems by repository?.shelfApps()?.collectAsState(initial = emptyList())
        ?: remember { mutableStateOf(emptyList()) }
    var allApps by remember { mutableStateOf<List<AppInfo>>(emptyList()) }
    var showAddDialog by remember { mutableStateOf(false) }
    var hasUsagePermission by remember { mutableStateOf(false) }
    var mostUsedAppsToday by remember { mutableStateOf<List<UsageTracker.DailyAppUsage>>(emptyList()) }

    LaunchedEffect(Unit) {
        allApps = PackageManagerHelper.getInstalledApps(context)
        hasUsagePermission = UsageTracker.hasUsageStatsPermission(context)
        mostUsedAppsToday = if (hasUsagePermission) {
            UsageTracker.getMostUsedAppsToday(context, MOST_USED_MAX_ITEMS)
        } else {
            emptyList()
        }
    }

    val shelfApps = remember(shelfItems, allApps) {
        shelfItems.mapNotNull { shelf ->
            allApps.find { it.packageName == shelf.packageName }
        }
    }

    val hasShelf = repository != null && onShelfAppLaunch != null
    var quickLaunchExpanded by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .imePadding()
                .padding(bottom = if (hasShelf) 20.dp else 0.dp)
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "v$appVersion",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Start,
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Until when do you want\nto use your phone?",
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
                val maxVisibleCount = (maxHeight / ITEM_HEIGHT_DP.dp)
                    .toInt()
                    .coerceIn(1, VISIBLE_ITEMS)
                // Picker needs a single center row. Force an odd row count so
                // highlight and snapped item stay anchored to the same vertical center.
                val visibleCount = when {
                    maxVisibleCount == 1 -> 1
                    maxVisibleCount % 2 == 0 -> maxVisibleCount - 1
                    else -> maxVisibleCount
                }
                val pickerHeight = (ITEM_HEIGHT_DP * visibleCount).dp

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
                        .height(pickerHeight)
                        .fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    flingBehavior = rememberSnapFlingBehavior(lazyListState = listState),
                    contentPadding = PaddingValues(
                        vertical = (ITEM_HEIGHT_DP * (visibleCount / 2)).dp
                    )
                ) {
                    items(items.size) { index ->
                        val distanceFromCenter = kotlin.math.abs(index - centerIndex)
                        val itemMinutes = items[index]
                        val endTimeText = formatEndTime(nowMs, itemMinutes, clockFormatter)
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
                            if (distanceFromCenter == 0) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    Text(
                                        text = endTimeText,
                                        fontSize = 32.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = formatMinutes(itemMinutes),
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f)
                                    )
                                }
                            } else {
                                Text(
                                    text = endTimeText,
                                    fontSize = 22.sp,
                                    fontWeight = FontWeight.Normal,
                                    color = MaterialTheme.colorScheme.onBackground
                                )
                            }
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
                    Log.d("TimerScreen", "Calling onTimerSet")
                    onTimerSet(selectedMinutes, reason.trim())
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

            Spacer(modifier = Modifier.height(20.dp))

            MostUsedAppsTodaySection(
                usageItems = mostUsedAppsToday,
                allApps = allApps,
                hasUsagePermission = hasUsagePermission
            )
        }

        if (hasShelf) {
            QuickLaunchDock(
                shelfApps = shelfApps,
                expanded = quickLaunchExpanded,
                onExpandedChange = { quickLaunchExpanded = it },
                onAppClick = { appInfo ->
                    onShelfAppLaunch?.invoke(
                        selectedMinutes,
                        reason.trim(),
                        appInfo.packageName,
                        shelfApps.map { it.packageName }.toSet(),
                    )
                },
                onRemoveApp = { packageName ->
                    scope.launch { repository?.removeFromShelf(packageName) }
                },
                onAddClick = { showAddDialog = true },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .navigationBarsPadding()
            )
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
// Quick launch shelf
// ---------------------------------------------------------------------------

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun QuickLaunchDock(
    shelfApps: List<AppInfo>,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onAppClick: (AppInfo) -> Unit,
    onRemoveApp: (String) -> Unit,
    onAddClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val gridHeight = if (expanded) SHELF_MAX_EXPANDED_HEIGHT_DP.dp else 0.dp
    Box(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = SHELF_CELL_MIN_WIDTH_DP.dp),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = gridHeight),
            contentPadding = PaddingValues(4.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalArrangement = Arrangement.spacedBy(2.dp)
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

        IconButton(
            onClick = onAddClick,
            modifier = Modifier.align(Alignment.CenterStart)
        ) {
            Icon(
                Icons.Default.Add,
                contentDescription = "Add app to shelf",
                tint = MaterialTheme.colorScheme.primary
            )
        }

        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .offset(x = 10.dp)
                .size(width = 22.dp, height = 56.dp)
                .background(
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    shape = RoundedCornerShape(topStart = 12.dp, bottomStart = 12.dp)
                )
                .clickable { onExpandedChange(!expanded) },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (expanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp,
                contentDescription = if (expanded) "Collapse quick launch" else "Expand quick launch",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
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
                    placeholder = { Text("Search apps\u2026") },
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

@Composable
private fun MostUsedAppsTodaySection(
    usageItems: List<UsageTracker.DailyAppUsage>,
    allApps: List<AppInfo>,
    hasUsagePermission: Boolean,
) {
    val appsByPackage = remember(allApps) { allApps.associateBy { it.packageName } }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                shape = MaterialTheme.shapes.medium
            )
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Text(
            text = "Most used apps today",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(6.dp))

        when {
            !hasUsagePermission -> {
                Text(
                    text = "Enable Usage Access to read Digital Wellbeing stats.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.9f)
                )
            }

            usageItems.isEmpty() -> {
                Text(
                    text = "No app usage recorded yet today.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.9f)
                )
            }

            else -> {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height((MOST_USED_VISIBLE_ITEMS * MOST_USED_ROW_HEIGHT_DP).dp)
                ) {
                    items(usageItems.size) { index ->
                        val usage = usageItems[index]
                        val appInfo = appsByPackage[usage.packageName]
                        MostUsedAppRow(
                            appLabel = appInfo?.label ?: usage.packageName,
                            icon = appInfo?.icon,
                            foregroundTimeMs = usage.foregroundTimeMs,
                            timeChunksMsDesc = usage.timeChunksMsDesc,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MostUsedAppRow(
    appLabel: String,
    icon: android.graphics.drawable.Drawable?,
    foregroundTimeMs: Long,
    timeChunksMsDesc: List<Long>,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(MOST_USED_ROW_HEIGHT_DP.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Image(
                painter = rememberDrawablePainter(drawable = icon),
                contentDescription = appLabel,
                modifier = Modifier.size(22.dp),
                colorFilter = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })
            )
        } else {
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .background(MaterialTheme.colorScheme.surface, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = appLabel.take(1).uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.9f)
                )
            }
        }

        Spacer(modifier = Modifier.width(8.dp))

        Text(
            text = appLabel,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
            modifier = Modifier.width(84.dp),
        )

        Spacer(modifier = Modifier.width(6.dp))

        Text(
            text = formatUsageBreakdown(foregroundTimeMs, timeChunksMsDesc),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
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

private fun formatEndTime(nowMs: Long, minutesFromNow: Int, formatter: DateFormat): String {
    val endMs = nowMs + minutesFromNow.coerceAtLeast(1) * 60_000L
    return formatter.format(Date(endMs))
}

private fun formatUsageDuration(durationMs: Long): String {
    val totalMinutes = TimeUnit.MILLISECONDS.toMinutes(durationMs).coerceAtLeast(0L)
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return when {
        hours == 0L -> "${minutes}m"
        minutes == 0L -> "${hours}h"
        else -> "${hours}h ${minutes}m"
    }
}

private fun formatUsageBreakdown(
    totalDurationMs: Long,
    timeChunksMsDesc: List<Long>,
    maxShownChunks: Int = 3,
): String {
    val total = formatUsageDuration(totalDurationMs)
    if (timeChunksMsDesc.size <= 1) return total
    val shownChunks = timeChunksMsDesc.take(maxShownChunks).map(::formatUsageDuration)
    val hasMore = timeChunksMsDesc.size > maxShownChunks
    val joined = buildString {
        append(shownChunks.joinToString(" + "))
        if (hasMore) append(" + ...")
    }
    return "$total ($joined)"
}
