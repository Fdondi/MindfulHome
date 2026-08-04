package com.mindfulhome.ui.logs
import androidx.compose.ui.res.stringResource

import com.mindfulhome.R

import android.content.Context
import android.widget.Toast
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mindfulhome.logging.SessionLogger
import java.time.LocalDate
import java.time.ZoneId

@Composable
internal fun LogsLoadingState(
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

@Composable
internal fun LogsEmptyState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.no_sessions_recorded_yet),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.logs_will_appear_here_after_you_unlock_nyour_pho),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun LogsDayList(
    days: List<DayEntry>,
    listState: LazyListState,
    loadPhase: LogsLoadPhase,
    zone: ZoneId,
) {
    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
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

@Composable
internal fun DaySummaryCard(entry: DayEntry, zone: ZoneId) {
    var expanded by remember { mutableStateOf(false) }
    var sessions by remember { mutableStateOf<List<SessionLogger.SessionSummary>?>(null) }
    var sessionsLoading by remember { mutableStateOf(false) }
    val summaryPreview = remember(entry.summary, entry.tagline) {
        daySummaryPreview(entry.summary, entry.tagline)
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
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            DaySummaryHeader(entry = entry, expanded = expanded)
            Spacer(modifier = Modifier.height(6.dp))
            DaySummaryBody(
                entry = entry,
                expanded = expanded,
                summaryPreview = summaryPreview,
                sessions = sessions,
                sessionsLoading = sessionsLoading,
            )
        }
    }
}

@Composable
private fun DaySummaryHeader(entry: DayEntry, expanded: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
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
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
            contentDescription = if (expanded) "Collapse" else "Expand",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DaySummaryBody(
    entry: DayEntry,
    expanded: Boolean,
    summaryPreview: String,
    sessions: List<SessionLogger.SessionSummary>?,
    sessionsLoading: Boolean,
) {
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
        DaySessionsList(sessions = sessions, sessionsLoading = sessionsLoading)
    }
}

@Composable
private fun DaySessionsList(
    sessions: List<SessionLogger.SessionSummary>?,
    sessionsLoading: Boolean,
) {
    when {
        sessionsLoading -> {
            LogsLoadingState(
                message = "Loading sessions…",
                modifier = Modifier.fillMaxWidth(),
            )
        }
        sessions.isNullOrEmpty() -> {
            Text(
                text = stringResource(R.string.no_sessions_for_this_day),
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

@Composable
internal fun SessionCard(entry: SessionLogger.SessionSummary) {
    var expanded by remember { mutableStateOf(false) }
    var content by remember { mutableStateOf<String?>(null) }
    var contentLoading by remember { mutableStateOf(false) }
    @Suppress("DEPRECATION")
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current
    val bullets = remember(content) { sessionBulletLines(content) }
    val singleEvent = isSingleEventSession(entry.eventCount)

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
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
        ),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            SessionCardHeader(
                entry = entry,
                expanded = expanded,
                singleEvent = singleEvent,
                content = content,
                onCopy = {
                    @Suppress("DEPRECATION")
                    clipboardManager.setText(AnnotatedString(content.orEmpty()))
                    Toast.makeText(context, context.getString(R.string.session_log_copied), Toast.LENGTH_SHORT).show()
                },
            )
            SessionCardBody(
                entry = entry,
                expanded = expanded,
                singleEvent = singleEvent,
                contentLoading = contentLoading,
                bullets = bullets,
            )
        }
    }
}

@Composable
private fun SessionCardHeader(
    entry: SessionLogger.SessionSummary,
    expanded: Boolean,
    singleEvent: Boolean,
    content: String?,
    onCopy: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (singleEvent && !expanded) {
            SessionCardCompactTitle(entry.title)
        } else {
            SessionCardExpandedTitle(entry)
        }
        SessionCardHeaderActions(expanded = expanded, content = content, onCopy = onCopy)
        Icon(
            imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
            contentDescription = if (expanded) "Collapse" else "Expand",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.SessionCardExpandedTitle(
    entry: SessionLogger.SessionSummary,
) {
    Column(modifier = Modifier.weight(1f)) {
        Text(
            text = entry.title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = "${entry.eventCount} events",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SessionCardHeaderActions(
    expanded: Boolean,
    content: String?,
    onCopy: () -> Unit,
) {
    if (expanded && content != null) {
        IconButton(onClick = onCopy) {
            Icon(
                imageVector = Icons.Default.ContentCopy,
                contentDescription = stringResource(R.string.copy_session_log),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.SessionCardCompactTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f),
    )
}

@Composable
private fun SessionCardBody(
    entry: SessionLogger.SessionSummary,
    expanded: Boolean,
    singleEvent: Boolean,
    contentLoading: Boolean,
    bullets: List<String>,
) {
    if (!expanded) {
        SessionCardCollapsedPreview(entry = entry, singleEvent = singleEvent)
        return
    }
    if (contentLoading) {
        Spacer(modifier = Modifier.height(8.dp))
        CircularProgressIndicator()
        return
    }
    Spacer(modifier = Modifier.height(8.dp))
    bullets.forEach { line ->
        Text(
            text = line.removePrefix("- "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(bottom = 4.dp),
        )
    }
}

@Composable
private fun SessionCardCollapsedPreview(
    entry: SessionLogger.SessionSummary,
    singleEvent: Boolean,
) {
    if (singleEvent || entry.firstEventPreview.isBlank()) return
    Spacer(modifier = Modifier.height(4.dp))
    Text(
        text = entry.firstEventPreview,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

internal fun dayBoundsMs(day: String, zone: ZoneId): Pair<Long, Long> {
    val start = LocalDate.parse(day).atStartOfDay(zone).toInstant().toEpochMilli()
    val end = LocalDate.parse(day).plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    return start to end
}

internal suspend fun appendDayBatch(
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

internal suspend fun loadDayEntries(
    context: Context,
    dayKeys: List<String>,
    zone: ZoneId,
): List<DayEntry> {
    if (dayKeys.isEmpty()) return emptyList()
    val db = com.mindfulhome.data.AppDatabase.getInstance(context)
    val summaries = db.dailyLogSummaryDao().getByDays(dayKeys).associateBy { it.day }
    val sortedDays = dayKeys.sorted()
    val startMs = LocalDate.parse(sortedDays.first()).atStartOfDay(zone).toInstant().toEpochMilli()
    val endMs = LocalDate.parse(sortedDays.last()).plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
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
