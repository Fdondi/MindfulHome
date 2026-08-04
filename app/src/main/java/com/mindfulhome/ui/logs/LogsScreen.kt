package com.mindfulhome.ui.logs
import androidx.compose.ui.res.stringResource

import com.mindfulhome.R

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import com.mindfulhome.data.AppDatabase
import java.time.ZoneId
import kotlinx.coroutines.flow.distinctUntilChanged

private const val DAYS_PER_PAGE = 15

internal enum class LogsLoadPhase {
    Initial,
    Ready,
    LoadingMore,
}

internal data class DayEntry(
    val day: String,
    val summary: String,
    val tagline: String,
    val sessionCount: Int,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsScreen(
    onBack: () -> Unit,
) {
    val days = remember { mutableStateListOf<DayEntry>() }
    val context = LocalContext.current
    val zone = remember { ZoneId.systemDefault() }
    val listState = rememberLazyListState()
    var loadPhase by remember { mutableStateOf(LogsLoadPhase.Initial) }
    var hasMoreOlderDays by remember { mutableStateOf(true) }
    var isLoadingMore by remember { mutableStateOf(false) }

    LogsScreenEffects(
        days = days,
        context = context,
        zone = zone,
        listState = listState,
        loadPhase = loadPhase,
        hasMoreOlderDays = hasMoreOlderDays,
        isLoadingMore = isLoadingMore,
        onLoadPhase = { loadPhase = it },
        onHasMore = { hasMoreOlderDays = it },
        onLoadingMore = { isLoadingMore = it },
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        TopAppBar(
            title = { Text(stringResource(R.string.session_logs), fontWeight = FontWeight.SemiBold) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                }
            },
        )
        LogsScreenBody(
            loadPhase = loadPhase,
            days = days,
            listState = listState,
            zone = zone,
        )
    }
}

@Composable
private fun LogsScreenEffects(
    days: MutableList<DayEntry>,
    context: android.content.Context,
    zone: ZoneId,
    listState: androidx.compose.foundation.lazy.LazyListState,
    loadPhase: LogsLoadPhase,
    hasMoreOlderDays: Boolean,
    isLoadingMore: Boolean,
    onLoadPhase: (LogsLoadPhase) -> Unit,
    onHasMore: (Boolean) -> Unit,
    onLoadingMore: (Boolean) -> Unit,
) {
    LaunchedEffect(Unit) {
        val db = AppDatabase.getInstance(context)
        val dayKeys = db.sessionLogDao().getRecentDistinctLocalDaysWithLogs(DAYS_PER_PAGE)
        onHasMore(dayKeys.size >= DAYS_PER_PAGE)
        appendDayBatch(days, context, dayKeys, zone)
        onLoadPhase(LogsLoadPhase.Ready)
    }
    LaunchedEffect(listState, hasMoreOlderDays) {
        snapshotFlow {
            val info = listState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            Triple(lastVisible, info.totalItemsCount, days.size)
        }
            .distinctUntilChanged()
            .collect { (lastVisible, total, _) ->
                if (!shouldLoadMoreLogs(loadPhase, isLoadingMore, hasMoreOlderDays, total, lastVisible)) {
                    return@collect
                }
                val oldestDay = days.lastOrNull()?.day ?: return@collect
                onLoadingMore(true)
                onLoadPhase(LogsLoadPhase.LoadingMore)
                val db = AppDatabase.getInstance(context)
                val dayKeys = db.sessionLogDao()
                    .getDistinctLocalDaysWithLogsBefore(oldestDay, DAYS_PER_PAGE)
                onHasMore(dayKeys.size >= DAYS_PER_PAGE)
                appendDayBatch(days, context, dayKeys, zone)
                onLoadingMore(false)
                onLoadPhase(LogsLoadPhase.Ready)
            }
    }
}

internal fun shouldLoadMoreLogs(
    loadPhase: LogsLoadPhase,
    isLoadingMore: Boolean,
    hasMoreOlderDays: Boolean,
    total: Int,
    lastVisible: Int,
): Boolean =
    loadPhase == LogsLoadPhase.Ready &&
        !isLoadingMore &&
        hasMoreOlderDays &&
        total != 0 &&
        lastVisible >= total - 3

@Composable
private fun LogsScreenBody(
    loadPhase: LogsLoadPhase,
    days: List<DayEntry>,
    listState: androidx.compose.foundation.lazy.LazyListState,
    zone: ZoneId,
) {
    when {
        loadPhase == LogsLoadPhase.Initial -> {
            LogsLoadingState(
                message = "Loading recent logs…",
                modifier = Modifier.fillMaxSize(),
            )
        }
        days.isEmpty() -> LogsEmptyState(modifier = Modifier.fillMaxSize())
        else -> LogsDayList(days = days, listState = listState, loadPhase = loadPhase, zone = zone)
    }
}
