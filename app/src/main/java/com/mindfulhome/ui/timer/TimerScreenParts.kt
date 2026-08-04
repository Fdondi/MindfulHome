package com.mindfulhome.ui.timer
import androidx.compose.ui.res.stringResource

import com.mindfulhome.R

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.accompanist.drawablepainter.rememberDrawablePainter
import com.mindfulhome.model.AppInfo
import com.mindfulhome.service.UsageTracker
import com.mindfulhome.ui.common.VersionLabel
import java.text.DateFormat

private const val MOST_USED_VISIBLE_ITEMS = 3
private const val MOST_USED_ROW_HEIGHT_DP = 44

@Composable
internal fun TimerScreenHeader(onBackToDefault: (() -> Unit)?) {
    if (onBackToDefault != null) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(onClick = onBackToDefault) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.back_to_home),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.width(4.dp))
                Icon(
                    imageVector = Icons.Default.Home,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            VersionLabel()
        }
    } else {
        VersionLabel(modifier = Modifier.fillMaxWidth())
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun DurationMinutePicker(
    items: List<Int>,
    listState: LazyListState,
    centerIndex: Int,
    nowMs: Long,
    clockFormatter: DateFormat,
) {
    BoxWithConstraints(
        modifier = Modifier
            .height((TIMER_ITEM_HEIGHT_DP * TIMER_VISIBLE_ITEMS).dp)
            .fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        val maxVisibleCount = (maxHeight / TIMER_ITEM_HEIGHT_DP.dp).toInt()
        val visibleCount = oddVisiblePickerCount(maxVisibleCount)
        val pickerHeight = (TIMER_ITEM_HEIGHT_DP * visibleCount).dp
        Box(
            modifier = Modifier
                .height(TIMER_ITEM_HEIGHT_DP.dp)
                .fillMaxWidth(0.6f)
                .background(
                    MaterialTheme.colorScheme.primaryContainer,
                    MaterialTheme.shapes.medium,
                ),
        )
        LazyColumn(
            state = listState,
            modifier = Modifier
                .height(pickerHeight)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            flingBehavior = rememberSnapFlingBehavior(lazyListState = listState),
            contentPadding = PaddingValues(
                vertical = (TIMER_ITEM_HEIGHT_DP * (visibleCount / 2)).dp,
            ),
        ) {
            items(items.size) { index ->
                DurationPickerRow(
                    distanceFromCenter = kotlin.math.abs(index - centerIndex),
                    endTimeText = formatTimerEndTime(nowMs, items[index], clockFormatter),
                    minutesLabel = formatTimerMinutes(items[index]),
                    emphasized = true,
                )
            }
        }
    }
}

@Composable
private fun DurationPickerRow(
    distanceFromCenter: Int,
    endTimeText: String,
    minutesLabel: String,
    emphasized: Boolean,
) {
    val alphaValue by animateFloatAsState(
        targetValue = distanceAlpha(distanceFromCenter),
        label = "alpha",
    )
    Box(
        modifier = Modifier
            .height(TIMER_ITEM_HEIGHT_DP.dp)
            .fillMaxWidth()
            .alpha(alphaValue),
        contentAlignment = Alignment.Center,
    ) {
        if (distanceFromCenter == 0) {
            DurationPickerCenteredRow(endTimeText, minutesLabel, emphasized)
        } else {
            Text(
                text = endTimeText,
                fontSize = if (emphasized) 22.sp else 20.sp,
                fontWeight = FontWeight.Normal,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }
    }
}

@Composable
private fun DurationPickerCenteredRow(
    endTimeText: String,
    minutesLabel: String,
    emphasized: Boolean,
) {
    val timeColor = if (emphasized) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onErrorContainer
    }
    val minutesColor = if (emphasized) {
        MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f)
    } else {
        MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.9f)
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(
            text = endTimeText,
            fontSize = if (emphasized) 32.sp else 28.sp,
            fontWeight = FontWeight.Bold,
            color = timeColor,
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = minutesLabel,
            fontSize = if (emphasized) 14.sp else 13.sp,
            fontWeight = FontWeight.Medium,
            color = minutesColor,
        )
    }
}

@Composable
internal fun HardDeadlineToggleRow(
    enabled: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth(0.8f)
            .background(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                shape = MaterialTheme.shapes.large,
            )
            .clickable(onClick = onToggle)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.hard_deadline),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.weight(1f))
        Icon(
            imageVector = if (enabled) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
            contentDescription = if (enabled) stringResource(R.string.hide_hard_deadline) else stringResource(R.string.show_hard_deadline),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun HardDeadlinePickerSection(
    items: List<Int>,
    listState: LazyListState,
    centerIndex: Int,
    nowMs: Long,
    clockFormatter: DateFormat,
    onHide: () -> Unit,
) {
    Spacer(modifier = Modifier.height(6.dp))
    Text(
        text = stringResource(R.string.when_must_you_be_done),
        style = MaterialTheme.typography.titleMedium,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.error,
    )
    Spacer(modifier = Modifier.height(12.dp))
    Box(
        modifier = Modifier
            .height((TIMER_ITEM_HEIGHT_DP * 3).dp)
            .fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .height(TIMER_ITEM_HEIGHT_DP.dp)
                .fillMaxWidth(0.55f)
                .background(
                    MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.75f),
                    MaterialTheme.shapes.medium,
                ),
        )
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .height((TIMER_ITEM_HEIGHT_DP * 3).dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            flingBehavior = rememberSnapFlingBehavior(lazyListState = listState),
            contentPadding = PaddingValues(vertical = TIMER_ITEM_HEIGHT_DP.dp),
        ) {
            items(items.size) { index ->
                DurationPickerRow(
                    distanceFromCenter = kotlin.math.abs(index - centerIndex),
                    endTimeText = formatTimerEndTime(nowMs, items[index], clockFormatter),
                    minutesLabel = formatTimerMinutes(items[index]),
                    emphasized = false,
                )
            }
        }
    }
    TextButton(onClick = onHide) { Text(stringResource(R.string.hide_hard_deadline)) }
}

@Composable
internal fun MostUsedAppsTodaySection(
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
                shape = MaterialTheme.shapes.medium,
            )
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Text(
            text = stringResource(R.string.most_used_apps_today),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(6.dp))
        MostUsedAppsBody(
            usageItems = usageItems,
            appsByPackage = appsByPackage,
            hasUsagePermission = hasUsagePermission,
        )
    }
}

@Composable
private fun MostUsedAppsBody(
    usageItems: List<UsageTracker.DailyAppUsage>,
    appsByPackage: Map<String, AppInfo>,
    hasUsagePermission: Boolean,
) {
    when {
        !hasUsagePermission -> {
            Text(
                text = stringResource(R.string.enable_usage_access_to_read_digital_wellbeing_st),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.9f),
            )
        }
        usageItems.isEmpty() -> {
            Text(
                text = stringResource(R.string.no_app_usage_recorded_yet_today),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.9f),
            )
        }
        else -> {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .height((MOST_USED_VISIBLE_ITEMS * MOST_USED_ROW_HEIGHT_DP).dp),
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
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Image(
                painter = rememberDrawablePainter(drawable = icon),
                contentDescription = appLabel,
                modifier = Modifier.size(22.dp),
                colorFilter = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) }),
            )
        } else {
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .background(MaterialTheme.colorScheme.surface, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = appLabel.take(1).uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.9f),
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
