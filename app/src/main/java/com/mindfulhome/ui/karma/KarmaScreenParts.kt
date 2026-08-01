package com.mindfulhome.ui.karma

import android.graphics.drawable.Drawable
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.google.accompanist.drawablepainter.rememberDrawablePainter
import com.mindfulhome.data.AppKarma
import com.mindfulhome.model.AppInfo
import com.mindfulhome.model.KarmaManager
import com.mindfulhome.settings.SettingsManager

@Composable
internal fun KarmaEmptyState() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "No tracked apps yet.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Tap + to set karma for any installed app and test Quick Launch timing.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun KarmaSectionsList(
    groups: KarmaAppGroups,
    negativeExpanded: Boolean,
    optedOutExpanded: Boolean,
    positiveExpanded: Boolean,
    zeroExpanded: Boolean,
    onNegativeToggle: () -> Unit,
    onOptedOutToggle: () -> Unit,
    onPositiveToggle: () -> Unit,
    onZeroToggle: () -> Unit,
    appLabels: Map<String, String>,
    appIcons: Map<String, Drawable?>,
    onForgive: (String) -> Unit,
    onSaveNote: (String, String?) -> Unit,
    onToggleOptOut: (String, Boolean) -> Unit,
    onSetKarma: (String) -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item { Spacer(modifier = Modifier.height(4.dp)) }
        item {
            KarmaSection(
                title = "Negative",
                apps = groups.negative,
                expanded = negativeExpanded,
                onToggle = onNegativeToggle,
                appLabels = appLabels,
                appIcons = appIcons,
                onForgive = onForgive,
                onSaveNote = onSaveNote,
                onToggleOptOut = onToggleOptOut,
                onSetKarma = onSetKarma,
            )
        }
        item {
            KarmaSection(
                title = "Opted out",
                apps = groups.optedOut,
                expanded = optedOutExpanded,
                onToggle = onOptedOutToggle,
                appLabels = appLabels,
                appIcons = appIcons,
                onForgive = onForgive,
                onSaveNote = onSaveNote,
                onToggleOptOut = onToggleOptOut,
                onSetKarma = onSetKarma,
            )
        }
        item {
            KarmaSection(
                title = "Positive",
                apps = groups.positive,
                expanded = positiveExpanded,
                onToggle = onPositiveToggle,
                appLabels = appLabels,
                appIcons = appIcons,
                onForgive = onForgive,
                onSaveNote = onSaveNote,
                onToggleOptOut = onToggleOptOut,
                onSetKarma = onSetKarma,
            )
        }
        item {
            KarmaSection(
                title = "Zero",
                apps = groups.zero,
                expanded = zeroExpanded,
                onToggle = onZeroToggle,
                appLabels = appLabels,
                appIcons = appIcons,
                onForgive = onForgive,
                onSaveNote = onSaveNote,
                onToggleOptOut = onToggleOptOut,
                onSetKarma = onSetKarma,
            )
        }
        item { Spacer(modifier = Modifier.height(8.dp)) }
    }
}

@Composable
internal fun KarmaSection(
    title: String,
    apps: List<AppKarma>,
    expanded: Boolean,
    onToggle: () -> Unit,
    appLabels: Map<String, String>,
    appIcons: Map<String, Drawable?>,
    onForgive: (String) -> Unit,
    onSaveNote: (String, String?) -> Unit,
    onToggleOptOut: (String, Boolean) -> Unit,
    onSetKarma: (String) -> Unit,
) {
    KarmaSectionHeader(title = title, count = apps.size, expanded = expanded, onToggle = onToggle)
    if (!expanded) return
    if (apps.isEmpty()) {
        Text(
            text = "No apps in this group.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
        )
        return
    }
    apps.forEach { karma ->
        KarmaCard(
            karma = karma,
            label = appLabels[karma.packageName] ?: karma.packageName,
            icon = appIcons[karma.packageName],
            onForgive = { onForgive(karma.packageName) },
            onSaveNote = { note -> onSaveNote(karma.packageName, note) },
            onToggleOptOut = { optedOut -> onToggleOptOut(karma.packageName, optedOut) },
            onSetKarma = { onSetKarma(karma.packageName) },
        )
    }
}

@Composable
private fun KarmaSectionHeader(
    title: String,
    count: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "$title ($count)",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = if (expanded) "Collapse $title" else "Expand $title",
            )
        }
    }
}

@Composable
internal fun KarmaCard(
    karma: AppKarma,
    label: String,
    icon: Drawable?,
    onForgive: () -> Unit,
    onSaveNote: (String?) -> Unit,
    onToggleOptOut: (Boolean) -> Unit,
    onSetKarma: () -> Unit,
) {
    var isEditingNote by remember(karma.packageName) { mutableStateOf(false) }
    var noteDraft by remember(karma.packageName, karma.appNote) { mutableStateOf(karma.appNote.orEmpty()) }
    val normalizedDraft = normalizeNoteDraft(noteDraft)
    val noteChanged = noteDraftChanged(noteDraft, karma.appNote)
    val scoreColor = when (karmaScoreColorKey(karma.isOptedOut, karma.karmaScore)) {
        0 -> MaterialTheme.colorScheme.onSurfaceVariant
        1 -> MaterialTheme.colorScheme.primary
        2 -> MaterialTheme.colorScheme.error
        3 -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.onSurface
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(),
        colors = CardDefaults.cardColors(
            containerColor = karmaCardContainerColor(karma.isOptedOut),
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            KarmaCardTopRow(
                karma = karma,
                label = label,
                icon = icon,
                scoreColor = scoreColor,
                onEditNote = { isEditingNote = true },
                onSetKarma = onSetKarma,
                onToggleOptOut = onToggleOptOut,
            )
            Spacer(modifier = Modifier.height(4.dp))
            KarmaCardNoteSection(
                karma = karma,
                isEditingNote = isEditingNote,
                noteDraft = noteDraft,
                onNoteDraftChange = { noteDraft = it },
                noteChanged = noteChanged,
                normalizedDraft = normalizedDraft,
                onCancelEdit = {
                    noteDraft = karma.appNote.orEmpty()
                    isEditingNote = false
                },
                onSave = {
                    onSaveNote(normalizedDraft)
                    isEditingNote = false
                },
            )
            KarmaCardForgiveRow(karma = karma, onForgive = onForgive)
        }
    }
}

@Composable
private fun karmaCardContainerColor(isOptedOut: Boolean) =
    if (isOptedOut) {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
    }

@Composable
private fun KarmaCardTopRow(
    karma: AppKarma,
    label: String,
    icon: Drawable?,
    scoreColor: androidx.compose.ui.graphics.Color,
    onEditNote: () -> Unit,
    onSetKarma: () -> Unit,
    onToggleOptOut: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Image(
                painter = rememberDrawablePainter(drawable = icon),
                contentDescription = label,
                modifier = Modifier.size(40.dp),
            )
            Spacer(modifier = Modifier.width(12.dp))
        }
        KarmaCardIdentity(
            karma = karma,
            label = label,
            onEditNote = onEditNote,
            modifier = Modifier.weight(1f),
        )
        KarmaCardScoreControls(
            karma = karma,
            scoreColor = scoreColor,
            onSetKarma = onSetKarma,
            onToggleOptOut = onToggleOptOut,
        )
    }
}

@Composable
private fun KarmaCardIdentity(
    karma: AppKarma,
    label: String,
    onEditNote: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = onEditNote,
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                modifier = Modifier.height(26.dp),
            ) {
                Text(text = "Edit note", style = MaterialTheme.typography.labelSmall)
            }
        }
        Text(
            text = "${karma.totalOpens} opens · ${karma.totalOverruns} overruns",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        KarmaStatusBadges(karma = karma)
    }
}

@Composable
private fun KarmaStatusBadges(karma: AppKarma) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (karma.isOptedOut) {
            Text(
                text = "Opted out",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (karma.isHidden) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.VisibilityOff,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.error,
                )
                Spacer(modifier = Modifier.width(2.dp))
                Text(
                    text = "Hidden",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun KarmaCardScoreControls(
    karma: AppKarma,
    scoreColor: androidx.compose.ui.graphics.Color,
    onSetKarma: () -> Unit,
    onToggleOptOut: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(
            onClick = onSetKarma,
            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
            modifier = Modifier.height(36.dp),
        ) {
            Text(
                text = if (karma.isOptedOut) "Set karma" else "${karma.karmaScore}",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = scoreColor,
            )
            Spacer(modifier = Modifier.width(2.dp))
            Icon(
                Icons.Default.Edit,
                contentDescription = "Set karma",
                modifier = Modifier.size(14.dp),
                tint = scoreColor,
            )
        }
        Spacer(modifier = Modifier.width(10.dp))
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "Opt out",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Switch(
                checked = karma.isOptedOut,
                onCheckedChange = { onToggleOptOut(it) },
            )
        }
    }
}

@Composable
private fun KarmaCardNoteSection(
    karma: AppKarma,
    isEditingNote: Boolean,
    noteDraft: String,
    onNoteDraftChange: (String) -> Unit,
    noteChanged: Boolean,
    normalizedDraft: String?,
    onCancelEdit: () -> Unit,
    onSave: () -> Unit,
) {
    if (isEditingNote) {
        OutlinedTextField(
            value = noteDraft,
            onValueChange = onNoteDraftChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("App note") },
            placeholder = { Text("Add context for future app-open decisions") },
            singleLine = false,
            maxLines = 3,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onCancelEdit) { Text("Cancel") }
            TextButton(onClick = onSave, enabled = noteChanged) { Text("Save note") }
        }
        return
    }
    if (!karma.appNote.isNullOrBlank()) {
        Text(
            text = "Note: ${karma.appNote}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun KarmaCardForgiveRow(karma: AppKarma, onForgive: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!karma.isOptedOut && karma.karmaScore < 0) {
            TextButton(onClick = onForgive) {
                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Forgive")
            }
        } else {
            Spacer(modifier = Modifier.width(1.dp))
        }
    }
}

@Composable
internal fun SetKarmaDialog(
    appLabel: String,
    initialScore: Int,
    onDismiss: () -> Unit,
    onSave: (Int) -> Unit,
) {
    val context = LocalContext.current
    val baseGraceMs = SettingsManager.getQuickLaunchSemaphorePhaseNormalMs(context) * 3L
    var scoreText by remember(appLabel, initialScore) { mutableStateOf(initialScore.toString()) }
    val parsedScore = scoreText.toIntOrNull()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Set karma for $appLabel") },
        text = {
            SetKarmaDialogBody(
                scoreText = scoreText,
                onScoreTextChange = { scoreText = sanitizeKarmaScoreInput(it) },
                parsedScore = parsedScore,
                baseGraceMs = baseGraceMs,
            )
        },
        confirmButton = {
            TextButton(
                onClick = { parsedScore?.let(onSave) },
                enabled = parsedScore != null,
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun SetKarmaDialogBody(
    scoreText: String,
    onScoreTextChange: (String) -> Unit,
    parsedScore: Int?,
    baseGraceMs: Long,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = scoreText,
            onValueChange = onScoreTextChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Karma score") },
            placeholder = { Text("e.g. -10") },
            singleLine = true,
        )
        if (parsedScore != null) {
            val stayMs = KarmaManager.quickLaunchAllowedStayMs(parsedScore, baseGraceMs)
            val cheatMs = KarmaManager.cheatScreenDurationMs(parsedScore)
            Text(
                text = buildString {
                    append("Quick Launch grace: ${stayMs / 1_000}s")
                    if (cheatMs != null) append(" · cheat screen: ${cheatMs / 1_000}s")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf(0, -1, -3, -10, -15).forEach { preset ->
                TextButton(onClick = { onScoreTextChange(preset.toString()) }) {
                    Text(preset.toString())
                }
            }
        }
    }
}

@Composable
internal fun PickAppForKarmaDialog(
    apps: List<AppInfo>,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(apps, query) { filterAppsForKarmaPick(apps, query) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Pick app") },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Search apps") },
                    singleLine = true,
                )
                Spacer(modifier = Modifier.height(8.dp))
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(280.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(filtered) { app ->
                        TextButton(
                            onClick = { onPick(app.packageName) },
                            modifier = Modifier.fillMaxWidth(),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        ) {
                            Text(
                                text = app.label,
                                modifier = Modifier.fillMaxWidth(),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}

internal fun filterAppsForKarmaPick(apps: List<AppInfo>, query: String): List<AppInfo> {
    val normalized = query.trim().lowercase()
    return if (normalized.isEmpty()) {
        apps.take(40)
    } else {
        apps.filter { app ->
            app.label.lowercase().contains(normalized) ||
                app.packageName.lowercase().contains(normalized)
        }.take(40)
    }
}
