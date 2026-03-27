package com.mindfulhome.ui.defaultpage

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stars
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mindfulhome.AppVersion
import com.mindfulhome.data.AppRepository
import com.mindfulhome.data.TodoItem
import com.mindfulhome.ui.quicklaunch.QuickLaunchSection
import java.text.DateFormat
import java.util.Calendar
import java.util.Date
import kotlinx.coroutines.launch

private data class TodoEditorState(
    val id: Long? = null,
    val intent: String = "",
    val durationMinutes: String = "",
    val deadlineEpochMs: Long? = null,
    val priority: Int = 2,
)

@Composable
fun DefaultPageScreen(
    repository: AppRepository,
    onQuickLaunchApp: (packageName: String, allowedPackages: Set<String>) -> Unit,
    resumeSessionLabel: String? = null,
    resumeSessionMinutes: Int = 0,
    onResumeSession: (() -> Unit)? = null,
    onOpenTimerPlain: () -> Unit,
    onOpenLogs: () -> Unit = {},
    onOpenKarma: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onStartTodo: (minutes: Int?, intent: String) -> Unit,
    onScreenShown: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val appVersion = AppVersion.versionName
    val todoItems by repository.sortedOpenTodos().collectAsState(initial = emptyList())
    var editor by remember { mutableStateOf<TodoEditorState?>(null) }

    LaunchedEffect(Unit) {
        onScreenShown()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "v$appVersion",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onOpenLogs) {
                Icon(
                    Icons.AutoMirrored.Filled.Article,
                    contentDescription = "Session logs",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onOpenKarma) {
                Icon(
                    Icons.Default.Stars,
                    contentDescription = "Karma",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onOpenSettings) {
                Icon(
                    Icons.Default.Settings,
                    contentDescription = "Settings",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)),
            colors = CardDefaults.cardColors(
                containerColor = androidx.compose.ui.graphics.Color.Transparent
            ),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Todo Widget",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(
                        onClick = {
                            editor = TodoEditorState(
                                deadlineEpochMs = next6pmEpochMs()
                            )
                        },
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "Add todo")
                    }
                }
                if (todoItems.isEmpty()) {
                    Text(
                        "No open items yet.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.height(180.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        items(todoItems.take(6), key = { it.id }) { item ->
                            TodoRow(
                                item = item,
                                onComplete = {
                                    scope.launch { repository.setTodoCompleted(item.id, true) }
                                },
                                onEdit = {
                                    editor = TodoEditorState(
                                        id = item.id,
                                        intent = item.intentText,
                                        durationMinutes = item.expectedDurationMinutes?.toString() ?: "",
                                        deadlineEpochMs = item.deadlineEpochMs,
                                        priority = item.priority,
                                    )
                                },
                                onStart = { onStartTodo(item.expectedDurationMinutes, item.intentText) },
                            )
                        }
                    }
                }
            }
        }

        if (resumeSessionLabel != null && onResumeSession != null && resumeSessionMinutes > 0) {
            OutlinedButton(
                onClick = onResumeSession,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
            ) {
                Text("Resume $resumeSessionLabel (${formatMinutes(resumeSessionMinutes)})")
            }
        }

        QuickLaunchSection(
            repository = repository,
            onQuickLaunchApp = onQuickLaunchApp,
        )

        Button(
            onClick = onOpenTimerPlain,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("something else?")
        }
        Spacer(modifier = Modifier.height(12.dp))
    }

    editor?.let { current ->
        TodoEditorDialog(
            state = current,
            onDismiss = { editor = null },
            onSave = { edited ->
                scope.launch {
                    val duration = edited.durationMinutes.toIntOrNull()
                    val result = repository.upsertTodo(
                        id = edited.id,
                        intentText = edited.intent,
                        expectedDurationMinutes = duration,
                        deadlineEpochMs = edited.deadlineEpochMs,
                        priority = edited.priority,
                    )
                    if (result.isSuccess) {
                        editor = null
                    }
                }
            },
        )
    }
}
@Composable
private fun TodoRow(
    item: TodoItem,
    onComplete: () -> Unit,
    onEdit: () -> Unit,
    onStart: () -> Unit,
) {
    val formatter = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT) }
    val deadlineLabel = item.deadlineEpochMs?.let { formatter.format(Date(it)) } ?: "No deadline"
    val durationLabel = item.expectedDurationMinutes?.let { "${it}m" } ?: "n/a"

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f), RoundedCornerShape(10.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(item.intentText, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "P${item.priority} | $durationLabel | $deadlineLabel",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onComplete) { Icon(Icons.Default.Check, contentDescription = "Complete") }
        IconButton(onClick = onEdit) { Icon(Icons.Default.Edit, contentDescription = "Edit") }
        IconButton(onClick = onStart) { Icon(Icons.Default.PlayArrow, contentDescription = "Start") }
    }
}

@Composable
private fun TodoEditorDialog(
    state: TodoEditorState,
    onDismiss: () -> Unit,
    onSave: (TodoEditorState) -> Unit,
) {
    val context = LocalContext.current
    val formatter = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT) }
    var local by remember(state) { mutableStateOf(state) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (state.id == null) "Add todo" else "Edit todo") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = local.intent,
                    onValueChange = { local = local.copy(intent = it) },
                    label = { Text("Intent") },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = local.durationMinutes,
                    onValueChange = { local = local.copy(durationMinutes = it.filter(Char::isDigit)) },
                    label = { Text("Duration (minutes)") },
                    modifier = Modifier.fillMaxWidth(),
                )
                val deadlineLabel = local.deadlineEpochMs?.let { formatter.format(Date(it)) } ?: "No deadline"
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "Deadline: $deadlineLabel",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    TextButton(
                        onClick = {
                            val initial = local.deadlineEpochMs ?: next6pmEpochMs()
                            pickDateTime(context, initial) { selected ->
                                local = local.copy(deadlineEpochMs = selected)
                            }
                        }
                    ) {
                        Text("Pick")
                    }
                    TextButton(
                        onClick = { local = local.copy(deadlineEpochMs = null) }
                    ) {
                        Text("Clear")
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    (1..4).forEach { p ->
                        Button(onClick = { local = local.copy(priority = p) }) {
                            Text("P$p${if (local.priority == p) "*" else ""}")
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(local) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun next6pmEpochMs(nowMs: Long = System.currentTimeMillis()): Long {
    val cal = Calendar.getInstance().apply {
        timeInMillis = nowMs
        set(Calendar.HOUR_OF_DAY, 18)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
        if (timeInMillis <= nowMs) {
            add(Calendar.DAY_OF_YEAR, 1)
        }
    }
    return cal.timeInMillis
}

private fun formatMinutes(minutes: Int): String {
    return when {
        minutes < 60 -> "${minutes}m"
        minutes % 60 == 0 -> "${minutes / 60}h"
        else -> "${minutes / 60}h ${minutes % 60}m"
    }
}

private fun pickDateTime(
    context: android.content.Context,
    initialEpochMs: Long,
    onPicked: (Long) -> Unit,
) {
    val start = Calendar.getInstance().apply { timeInMillis = initialEpochMs }
    DatePickerDialog(
        context,
        { _, year, month, dayOfMonth ->
            val pickedDate = Calendar.getInstance().apply {
                set(Calendar.YEAR, year)
                set(Calendar.MONTH, month)
                set(Calendar.DAY_OF_MONTH, dayOfMonth)
                set(Calendar.HOUR_OF_DAY, start.get(Calendar.HOUR_OF_DAY))
                set(Calendar.MINUTE, start.get(Calendar.MINUTE))
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            TimePickerDialog(
                context,
                { _, hourOfDay, minute ->
                    pickedDate.set(Calendar.HOUR_OF_DAY, hourOfDay)
                    pickedDate.set(Calendar.MINUTE, minute)
                    onPicked(pickedDate.timeInMillis)
                },
                pickedDate.get(Calendar.HOUR_OF_DAY),
                pickedDate.get(Calendar.MINUTE),
                true,
            ).show()
        },
        start.get(Calendar.YEAR),
        start.get(Calendar.MONTH),
        start.get(Calendar.DAY_OF_MONTH),
    ).show()
}
