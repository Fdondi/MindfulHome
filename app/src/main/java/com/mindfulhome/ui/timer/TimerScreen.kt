package com.mindfulhome.ui.timer

import android.util.Log
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
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
import com.mindfulhome.ui.common.AddAppsDialog
import com.mindfulhome.ui.common.AppShelf
import com.mindfulhome.ui.common.AppShelfEntry
import com.mindfulhome.util.PackageManagerHelper
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.math.ceil

private const val MAX_MINUTES = 120
private const val VISIBLE_ITEMS = 5
private const val ITEM_HEIGHT_DP = 64
private const val MOST_USED_VISIBLE_ITEMS = 3
private const val MOST_USED_MAX_ITEMS = 15
private const val MOST_USED_ROW_HEIGHT_DP = 44

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimerScreen(
    onTimerSet: (minutes: Int, reason: String, hardDeadlineMinutes: Int?) -> Unit,
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
    val hardDeadlineItems = (1..MAX_MINUTES).toList()
    val hardDeadlineListState = rememberLazyListState()
    var reason by remember { mutableStateOf("") }
    var hardDeadlineEnabled by remember { mutableStateOf(false) }
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
    val hardDeadlineCenterIndex by remember {
        derivedStateOf {
            val layoutInfo = hardDeadlineListState.layoutInfo
            val viewportCenter = layoutInfo.viewportStartOffset +
                (layoutInfo.viewportEndOffset - layoutInfo.viewportStartOffset) / 2
            layoutInfo.visibleItemsInfo.minByOrNull {
                val itemCenter = it.offset + it.size / 2
                kotlin.math.abs(itemCenter - viewportCenter)
            }?.index ?: 0
        }
    }
    val selectedHardDeadlineMinutes by remember {
        derivedStateOf { hardDeadlineItems.getOrElse(hardDeadlineCenterIndex) { 15 } }
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
                .verticalScroll(rememberScrollState())
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
                text = "When should you be done?",
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onBackground
            )

            Spacer(modifier = Modifier.height(24.dp))

            BoxWithConstraints(
                modifier = Modifier
                    .height((ITEM_HEIGHT_DP * VISIBLE_ITEMS).dp)
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

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth(0.8f)
                    .background(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                        shape = MaterialTheme.shapes.large
                    )
                    .clickable { hardDeadlineEnabled = !hardDeadlineEnabled }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "hard deadline?",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.weight(1f))
                Icon(
                    imageVector = if (hardDeadlineEnabled) {
                        Icons.Default.KeyboardArrowUp
                    } else {
                        Icons.Default.KeyboardArrowDown
                    },
                    contentDescription = if (hardDeadlineEnabled) {
                        "Hide hard deadline"
                    } else {
                        "Show hard deadline"
                    },
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (hardDeadlineEnabled) {
                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = "When MUST you be done?",
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.error
                )

                Spacer(modifier = Modifier.height(12.dp))

                Box(
                    modifier = Modifier
                        .height((ITEM_HEIGHT_DP * 3).dp)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .height(ITEM_HEIGHT_DP.dp)
                            .fillMaxWidth(0.55f)
                            .background(
                                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.75f),
                                MaterialTheme.shapes.medium
                            )
                    )

                    LazyColumn(
                        state = hardDeadlineListState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height((ITEM_HEIGHT_DP * 3).dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        flingBehavior = rememberSnapFlingBehavior(lazyListState = hardDeadlineListState),
                        contentPadding = PaddingValues(vertical = ITEM_HEIGHT_DP.dp)
                    ) {
                        items(hardDeadlineItems.size) { index ->
                            val distanceFromCenter = kotlin.math.abs(index - hardDeadlineCenterIndex)
                            val itemMinutes = hardDeadlineItems[index]
                            val endTimeText = formatEndTime(nowMs, itemMinutes, clockFormatter)
                            val alphaValue by animateFloatAsState(
                                targetValue = when (distanceFromCenter) {
                                    0 -> 1f
                                    1 -> 0.6f
                                    else -> 0.3f
                                },
                                label = "hard-deadline-alpha"
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
                                            fontSize = 28.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onErrorContainer
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = formatMinutes(itemMinutes),
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.9f)
                                        )
                                    }
                                } else {
                                    Text(
                                        text = endTimeText,
                                        fontSize = 20.sp,
                                        color = MaterialTheme.colorScheme.onBackground
                                    )
                                }
                            }
                        }
                    }
                }

                TextButton(onClick = { hardDeadlineEnabled = false }) {
                    Text("Hide hard deadline")
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
                    val hardDeadlineMinutes = if (hardDeadlineEnabled) selectedHardDeadlineMinutes else null
                    onTimerSet(selectedMinutes, reason.trim(), hardDeadlineMinutes)
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
                    onShelfAppLaunch.invoke(
                        selectedMinutes,
                        reason.trim(),
                        appInfo.packageName,
                        shelfApps.map { it.packageName }.toSet(),
                    )
                },
                onRemoveApp = { packageName ->
                    scope.launch { repository.removeFromShelf(packageName) }
                },
                onAddClick = { showAddDialog = true },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
            )
        }
    }

    if (showAddDialog && hasShelf) {
        AddAppsDialog(
            title = "Add to Quick Launch",
            apps = allApps,
            excludedPackages = shelfItems.map { it.packageName }.toSet(),
            onAdd = { packageName ->
                scope.launch { repository .addToShelf(packageName) }
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
    val entries = remember(shelfApps, onAppClick, onRemoveApp) {
        shelfApps.map { app ->
            AppShelfEntry(
                key = app.packageName,
                label = app.label,
                icon = app.icon,
                onClick = { onAppClick(app) },
                onLongClick = { onRemoveApp(app.packageName) },
            )
        }
    }

    AppShelf(
        entries = entries,
        expanded = expanded,
        onExpandedChange = onExpandedChange,
        collapsedRows = 0,
        showBodyWhenCollapsed = false,
        onAddClick = onAddClick,
        addContentDescription = "Add app to quick launch",
        contentDescriptionExpand = "Expand quick launch",
        contentDescriptionCollapse = "Collapse quick launch",
        modifier = modifier,
    )
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
