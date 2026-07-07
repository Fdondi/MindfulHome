package com.mindfulhome.ui.logs

import android.content.Context
import android.widget.Toast
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mindfulhome.data.AppDatabase
import com.mindfulhome.logging.SessionLogger
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.distinctUntilChanged

private const val DAYS_PER_PAGE = 15

private enum class LogsLoadPhase {
    Initial,
    Ready,
    LoadingMore,
}

private data class DayEntry(
    val day: String, // yyyy-MM-dd
    val summary: String,
    /** From JSON `tagline`; collapsed preview and expanded title when set. */
    val tagline: String,
    val sessionCount: Int,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsScreen(
    onBack: () -> Unit
) {
    val days = remember { mutableStateListOf<DayEntry>() }
    val context = LocalContext.current
    val zone = remember { ZoneId.systemDefault() }
    val listState = rememberLazyListState()
    var loadPhase by remember { mutableStateOf(LogsLoadPhase.Initial) }
    var hasMoreOlderDays by remember { mutableStateOf(true) }
    var isLoadingMore by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val db = AppDatabase.getInstance(context)
        val dayKeys = db.sessionLogDao().getRecentDistinctLocalDaysWithLogs(DAYS_PER_PAGE)
        hasMoreOlderDays = dayKeys.size >= DAYS_PER_PAGE
        appendDayBatch(days, context, dayKeys, zone)
        loadPhase = LogsLoadPhase.Ready
    }

    LaunchedEffect(listState, hasMoreOlderDays) {
        snapshotFlow {
            val info = listState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            val total = info.totalItemsCount
            Triple(lastVisible, total, days.size)
        }
            .distinctUntilChanged()
            .collect { (lastVisible, total, _) ->
                if (
                    loadPhase != LogsLoadPhase.Ready ||
                    isLoadingMore ||
                    !hasMoreOlderDays ||
                    total == 0 ||
                    lastVisible < total - 3
                ) {
                    return@collect
                }
                val oldestDay = days.lastOrNull()?.day ?: return@collect
                isLoadingMore = true
                loadPhase = LogsLoadPhase.LoadingMore
                val db = AppDatabase.getInstance(context)
                val dayKeys = db.sessionLogDao()
                    .getDistinctLocalDaysWithLogsBefore(oldestDay, DAYS_PER_PAGE)
                hasMoreOlderDays = dayKeys.size >= DAYS_PER_PAGE
                appendDayBatch(days, context, dayKeys, zone)
                isLoadingMore = false
                loadPhase = LogsLoadPhase.Ready
            }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        TopAppBar(
            title = { Text("Session Logs", fontWeight = FontWeight.SemiBold) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            }
        )

        when {
            loadPhase == LogsLoadPhase.Initial -> {
                LogsLoadingState(
                    message = "Loading recent logs…",
                    modifier = Modifier.fillMaxSize(),
                )
            }

            days.isEmpty() -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "No sessions recorded yet.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Logs will appear here after you unlock\nyour phone and use apps.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            else -> {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item { Spacer(modifier = Modifier.height(4.dp)) }

                    items(days, key = { it.day }) { dayEntry ->
                        DaySummaryCard(dayEntry, zone)
                    }

                    if (loadPhase == LogsLoadPhase.LoadingMore) {
                        item(key = "loading-more") {
                            LogsLoadingState(
                                message = "Loading older logs…",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 16.dp),
                            )
                        }
                    }

                    item { Spacer(modifier = Modifier.height(8.dp)) }
                }
            }
        }
    }
}

@Composable
private fun LogsLoadingState(
    message: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private suspend fun appendDayBatch(
    days: MutableList<DayEntry>,
    context: Context,
    dayKeys: List<String>,
    zone: ZoneId,
) {
    if (dayKeys.isEmpty()) return
    val built = loadDayEntries(context, dayKeys, zone)
    val existing = days.map { it.day }.toSet()
    days.addAll(built.filter { it.day !in existing })
}

private suspend fun loadDayEntries(
    context: Context,
    dayKeys: List<String>,
    zone: ZoneId,
): List<DayEntry> {
    if (dayKeys.isEmpty()) return emptyList()

    val db = AppDatabase.getInstance(context)
    val summaries = db.dailyLogSummaryDao()
        .getByDays(dayKeys)
        .associateBy { it.day }

    val sortedDays = dayKeys.sorted()
    val startMs = LocalDate.parse(sortedDays.first())
        .atStartOfDay(zone)
        .toInstant()
        .toEpochMilli()
    val endMs = LocalDate.parse(sortedDays.last())
        .plusDays(1)
        .atStartOfDay(zone)
        .toInstant()
        .toEpochMilli()

    val countsByDay = db.sessionLogDao()
        .getSessionCountsByDayInRange(startMs, endMs)
        .associate { it.dayKey to it.sessionCount }

    return dayKeys.map { day ->
        val row = summaries[day]
        DayEntry(
            day = day,
            summary = row?.summary.orEmpty(),
            tagline = row?.tagline.orEmpty(),
            sessionCount = row?.sessionCount ?: countsByDay[day] ?: 0,
        )
    }
}

private fun dayBoundsMs(day: String, zone: ZoneId): Pair<Long, Long> {
    val start = LocalDate.parse(day).atStartOfDay(zone).toInstant().toEpochMilli()
    val end = LocalDate.parse(day).plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    return start to end
}

@Composable
private fun DaySummaryCard(entry: DayEntry, zone: ZoneId) {
    var expanded by remember { mutableStateOf(false) }
    var sessions by remember { mutableStateOf<List<SessionLogger.SessionSummary>?>(null) }
    var sessionsLoading by remember { mutableStateOf(false) }
    val summaryPreview = remember(entry.summary, entry.tagline) {
        entry.tagline.trim().ifBlank {
            entry.summary.lines().firstOrNull().orEmpty().trim()
        }.ifBlank { "No summary yet." }
    }

    LaunchedEffect(expanded, entry.day) {
        if (!expanded || sessions != null || sessionsLoading) return@LaunchedEffect
        sessionsLoading = true
        val (startMs, endMs) = dayBoundsMs(entry.day, zone)
        sessions = SessionLogger.getSessionSummariesInTimeRange(startMs, endMs)
        sessionsLoading = false
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize()
            .clickable { expanded = !expanded },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = entry.day,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "${entry.sessionCount} sessions",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(6.dp))
            if (expanded && entry.tagline.isNotBlank()) {
                Text(
                    text = entry.tagline.trim(),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.height(4.dp))
            }
            Text(
                text = if (expanded) entry.summary.ifBlank { "No summary yet." } else summaryPreview,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = if (expanded) 20 else 2,
                overflow = TextOverflow.Ellipsis,
            )

            if (expanded) {
                Spacer(modifier = Modifier.height(10.dp))
                when {
                    sessionsLoading -> {
                        LogsLoadingState(
                            message = "Loading sessions…",
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    sessions.isNullOrEmpty() -> {
                        Text(
                            text = "No sessions for this day.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    else -> {
                        sessions.orEmpty().forEach { session ->
                            SessionCard(session)
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionCard(entry: SessionLogger.SessionSummary) {
    var expanded by remember { mutableStateOf(false) }
    var content by remember { mutableStateOf<String?>(null) }
    var contentLoading by remember { mutableStateOf(false) }
    @Suppress("DEPRECATION")
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current

    val bullets = remember(content) {
        content.orEmpty().lines().filter { it.startsWith("- ") }
    }
    val isSingleEventSession = entry.eventCount == 1

    LaunchedEffect(expanded, entry.id) {
        if (!expanded || content != null || contentLoading) return@LaunchedEffect
        contentLoading = true
        content = SessionLogger.getSessionMarkdown(entry.id, entry.title)
        contentLoading = false
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize()
            .clickable { expanded = !expanded },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)
        )
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isSingleEventSession && !expanded) {
                    Text(
                        text = entry.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                } else {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = entry.title,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "${entry.eventCount} events",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (expanded && content != null) {
                    IconButton(onClick = {
                        @Suppress("DEPRECATION")
                        clipboardManager.setText(AnnotatedString(content.orEmpty()))
                        Toast.makeText(context, "Session log copied", Toast.LENGTH_SHORT).show()
                    }) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = "Copy session log",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (!expanded && !isSingleEventSession && entry.firstEventPreview.isNotBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = entry.firstEventPreview,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            } else if (expanded) {
                when {
                    contentLoading -> {
                        Spacer(modifier = Modifier.height(8.dp))
                        CircularProgressIndicator()
                    }

                    else -> {
                        Spacer(modifier = Modifier.height(8.dp))
                        bullets.forEach { line ->
                            Text(
                                text = line.removePrefix("- "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(bottom = 4.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
