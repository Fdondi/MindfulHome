package com.mindfulhome.ui.timer

import android.util.Log
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mindfulhome.model.AppInfo
import com.mindfulhome.service.UsageTracker
import com.mindfulhome.util.PackageManagerHelper
import java.text.DateFormat
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlin.math.abs

private const val MOST_USED_MAX_ITEMS = 15

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun TimerScreen(
    onTimerSet: (
        minutes: Int,
        reason: String,
        hardDeadlineMinutes: Int?,
        mostUsedAppsToday: List<UsageTracker.DailyAppUsage>,
        mostUsedAppsCapturedAtMs: Long?,
    ) -> Unit,
    onBackToDefault: (() -> Unit)? = null,
    initialMinutes: Int? = null,
    initialReason: String? = null,
    prefillToken: Long = 0L,
    onPrefillApplied: () -> Unit = {},
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val startButtonBringIntoViewRequester = remember { BringIntoViewRequester() }
    val imeBottomPx = WindowInsets.ime.getBottom(density)

    val items = (1..TIMER_MAX_MINUTES).toList()
    val listState = rememberLazyListState()
    val hardDeadlineItems = (1..TIMER_MAX_MINUTES).toList()
    val hardDeadlineListState = rememberLazyListState()
    var reason by remember { mutableStateOf("") }
    var hardDeadlineEnabled by remember { mutableStateOf(false) }
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    val clockFormatter = remember { DateFormat.getTimeInstance(DateFormat.SHORT) }

    val centerIndex by remember {
        derivedStateOf { pickerCenterIndex(listState) }
    }
    val selectedMinutes by remember {
        derivedStateOf { items.getOrElse(centerIndex) { 1 } }
    }
    val hardDeadlineCenterIndex by remember {
        derivedStateOf { pickerCenterIndex(hardDeadlineListState) }
    }
    val selectedHardDeadlineMinutes by remember {
        derivedStateOf { hardDeadlineItems.getOrElse(hardDeadlineCenterIndex) { 15 } }
    }

    LaunchedEffect(Unit) {
        snapshotFlow {
            listState.layoutInfo.viewportEndOffset - listState.layoutInfo.viewportStartOffset
        }.distinctUntilChanged().collect {
            if (!listState.isScrollInProgress) {
                scrollPickerToMinutes(listState, selectedMinutes, density)
            }
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            nowMs = System.currentTimeMillis()
            delay(msUntilNextMinuteBoundary(nowMs))
        }
    }

    LaunchedEffect(imeBottomPx) {
        if (imeBottomPx > 0) startButtonBringIntoViewRequester.bringIntoView()
    }

    var allApps by remember { mutableStateOf<List<AppInfo>>(emptyList()) }
    var hasUsagePermission by remember { mutableStateOf(false) }
    var mostUsedAppsToday by remember { mutableStateOf<List<UsageTracker.DailyAppUsage>>(emptyList()) }
    var mostUsedAppsCapturedAtMs by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(Unit) {
        allApps = PackageManagerHelper.getInstalledApps(context)
        hasUsagePermission = UsageTracker.hasUsageStatsPermission(context)
        if (hasUsagePermission) {
            mostUsedAppsToday = UsageTracker.getMostUsedAppsToday(context, MOST_USED_MAX_ITEMS)
            mostUsedAppsCapturedAtMs = System.currentTimeMillis()
        } else {
            mostUsedAppsToday = emptyList()
            mostUsedAppsCapturedAtMs = null
        }
    }

    LaunchedEffect(prefillToken) {
        applyTimerPrefill(
            prefillToken = prefillToken,
            initialReason = initialReason,
            initialMinutes = initialMinutes,
            setReason = { reason = it },
            scrollToMinutes = { minutes -> scrollPickerToMinutes(listState, minutes, density) },
            onPrefillApplied = onPrefillApplied,
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        TimerScreenHeader(onBackToDefault)
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "When should you be done?",
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(modifier = Modifier.height(24.dp))
        DurationMinutePicker(
            items = items,
            listState = listState,
            centerIndex = centerIndex,
            nowMs = nowMs,
            clockFormatter = clockFormatter,
        )
        Spacer(modifier = Modifier.height(12.dp))
        HardDeadlineToggleRow(
            enabled = hardDeadlineEnabled,
            onToggle = { hardDeadlineEnabled = !hardDeadlineEnabled },
        )
        if (hardDeadlineEnabled) {
            HardDeadlinePickerSection(
                items = hardDeadlineItems,
                listState = hardDeadlineListState,
                centerIndex = hardDeadlineCenterIndex,
                nowMs = nowMs,
                clockFormatter = clockFormatter,
                onHide = { hardDeadlineEnabled = false },
            )
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
                onTimerSet(
                    selectedMinutes,
                    reason.trim(),
                    hardDeadlineMinutesOrNull(hardDeadlineEnabled, selectedHardDeadlineMinutes),
                    mostUsedAppsToday,
                    mostUsedAppsCapturedAtMs,
                )
            },
            modifier = Modifier
                .bringIntoViewRequester(startButtonBringIntoViewRequester)
                .fillMaxWidth(0.6f)
                .height(56.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
            shape = MaterialTheme.shapes.large,
        ) {
            Text(text = "Start", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(modifier = Modifier.height(20.dp))
        MostUsedAppsTodaySection(
            usageItems = mostUsedAppsToday,
            allApps = allApps,
            hasUsagePermission = hasUsagePermission,
        )
    }
}

private fun pickerCenterIndex(listState: androidx.compose.foundation.lazy.LazyListState): Int {
    val layoutInfo = listState.layoutInfo
    val viewportCenter = layoutInfo.viewportStartOffset +
        (layoutInfo.viewportEndOffset - layoutInfo.viewportStartOffset) / 2
    return layoutInfo.visibleItemsInfo.minByOrNull {
        val itemCenter = it.offset + it.size / 2
        abs(itemCenter - viewportCenter)
    }?.index ?: 0
}

private suspend fun scrollPickerToMinutes(
    listState: androidx.compose.foundation.lazy.LazyListState,
    minutes: Int,
    density: androidx.compose.ui.unit.Density,
) {
    val targetIndex = minutes - 1
    if (targetIndex < 0) return
    val viewportHeightPx =
        listState.layoutInfo.viewportEndOffset - listState.layoutInfo.viewportStartOffset
    val itemHeightPx = with(density) { TIMER_ITEM_HEIGHT_DP.dp.roundToPx() }
    listState.scrollToItem(targetIndex, pickerCenterOffsetPx(viewportHeightPx, itemHeightPx))
}

internal suspend fun applyTimerPrefill(
    prefillToken: Long,
    initialReason: String?,
    initialMinutes: Int?,
    setReason: (String) -> Unit,
    scrollToMinutes: suspend (Int) -> Unit,
    onPrefillApplied: () -> Unit,
) {
    if (prefillToken <= 0L) return
    initialReason?.let(setReason)
    coercePrefillMinutes(initialMinutes)?.let { scrollToMinutes(it) }
    onPrefillApplied()
}
