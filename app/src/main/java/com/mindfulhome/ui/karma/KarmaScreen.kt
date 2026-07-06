package com.mindfulhome.ui.karma

import android.graphics.drawable.Drawable
import android.util.Log
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.google.accompanist.drawablepainter.rememberDrawablePainter
import com.mindfulhome.data.AppKarma
import com.mindfulhome.data.AppRepository
import com.mindfulhome.model.AppInfo
import com.mindfulhome.model.KarmaManager
import com.mindfulhome.settings.SettingsManager
import com.mindfulhome.util.PackageManagerHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KarmaScreen(
    repository: AppRepository,
    karmaManager: KarmaManager,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val allKarma by repository.allKarma().collectAsState(initial = emptyList())
    var appLabels by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var appIcons by remember { mutableStateOf<Map<String, Drawable?>>(emptyMap()) }
    var appsLoaded by remember { mutableStateOf(false) }
    var negativeExpanded by remember { mutableStateOf(true) }
    var optedOutExpanded by remember { mutableStateOf(false) }
    var positiveExpanded by remember { mutableStateOf(false) }
    var zeroExpanded by remember { mutableStateOf(false) }
    var allApps by remember { mutableStateOf<List<AppInfo>>(emptyList()) }
    var showPickAppDialog by remember { mutableStateOf(false) }
    var setKarmaTarget by remember { mutableStateOf<Pair<String, Int>?>(null) }

    LaunchedEffect(Unit) {
        val apps = PackageManagerHelper.getInstalledApps(context)
        allApps = apps
        appLabels = apps.associate { it.packageName to it.label }
        appIcons = apps.associate { it.packageName to it.icon }
        appsLoaded = true
    }

    // Secondary fallback: resolve labels for packages not in launcher list
    LaunchedEffect(allKarma, appsLoaded) {
        if (!appsLoaded) return@LaunchedEffect
        val missing = allKarma
            .map { it.packageName }
            .filter { it.isNotBlank() && it !in appLabels }
        if (missing.isNotEmpty()) {
            Log.w("KarmaScreen", "Karma entries without launcher match: $missing")
            val resolved = withContext(Dispatchers.IO) {
                missing.associateWith { pkg ->
                    val label = PackageManagerHelper.getAppLabel(context, pkg)
                    if (label == pkg) {
                        Log.w("KarmaScreen", "Could not resolve label for: $pkg")
                    }
                    label
                }
            }
            appLabels = appLabels + resolved
        }
    }

    val trackedApps = remember(allKarma) {
        val valid = allKarma.filter { it.packageName.isNotBlank() }
        valid.sortedBy { it.packageName }
    }

    val negativeApps = remember(trackedApps, appLabels) {
        trackedApps
            .filter { !it.isOptedOut && it.karmaScore < 0 }
            .sortedWith(compareBy<AppKarma> { it.karmaScore }.thenBy { appLabels[it.packageName] ?: it.packageName })
    }
    val optedOutApps = remember(trackedApps, appLabels) {
        trackedApps
            .filter { it.isOptedOut }
            .sortedBy { appLabels[it.packageName] ?: it.packageName }
    }
    val positiveApps = remember(trackedApps, appLabels) {
        trackedApps
            .filter { !it.isOptedOut && it.karmaScore > 0 }
            .sortedWith(compareByDescending<AppKarma> { it.karmaScore }.thenBy { appLabels[it.packageName] ?: it.packageName })
    }
    val zeroApps = remember(trackedApps, appLabels) {
        trackedApps
            .filter { !it.isOptedOut && it.karmaScore == 0 }
            .sortedBy { appLabels[it.packageName] ?: it.packageName }
    }

    val karmaByPackage = remember(allKarma) { allKarma.associateBy { it.packageName } }

    fun openSetKarma(packageName: String) {
        val current = karmaByPackage[packageName]?.karmaScore ?: 0
        setKarmaTarget = packageName to current
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        TopAppBar(
            title = { Text("Karma", fontWeight = FontWeight.SemiBold) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            },
            actions = {
                IconButton(onClick = { showPickAppDialog = true }) {
                    Icon(Icons.Default.Add, contentDescription = "Set karma for app")
                }
            },
        )

        if (trackedApps.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "No tracked apps yet.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Tap + to set karma for any installed app and test Quick Launch timing.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item { Spacer(modifier = Modifier.height(4.dp)) }

                item {
                    KarmaSection(
                        title = "Negative",
                        apps = negativeApps,
                        expanded = negativeExpanded,
                        onToggle = { negativeExpanded = !negativeExpanded },
                        appLabels = appLabels,
                        appIcons = appIcons,
                        onForgive = { packageName -> scope.launch { karmaManager.forgiveApp(packageName) } },
                        onSaveNote = { packageName, note -> scope.launch { repository.updateAppNote(packageName, note) } },
                        onToggleOptOut = { packageName, optedOut ->
                            scope.launch { karmaManager.setOptedOut(packageName, optedOut) }
                        },
                        onSetKarma = { packageName -> openSetKarma(packageName) },
                    )
                }
                item {
                    KarmaSection(
                        title = "Opted out",
                        apps = optedOutApps,
                        expanded = optedOutExpanded,
                        onToggle = { optedOutExpanded = !optedOutExpanded },
                        appLabels = appLabels,
                        appIcons = appIcons,
                        onForgive = { packageName -> scope.launch { karmaManager.forgiveApp(packageName) } },
                        onSaveNote = { packageName, note -> scope.launch { repository.updateAppNote(packageName, note) } },
                        onToggleOptOut = { packageName, optedOut ->
                            scope.launch { karmaManager.setOptedOut(packageName, optedOut) }
                        },
                        onSetKarma = { packageName -> openSetKarma(packageName) },
                    )
                }
                item {
                    KarmaSection(
                        title = "Positive",
                        apps = positiveApps,
                        expanded = positiveExpanded,
                        onToggle = { positiveExpanded = !positiveExpanded },
                        appLabels = appLabels,
                        appIcons = appIcons,
                        onForgive = { packageName -> scope.launch { karmaManager.forgiveApp(packageName) } },
                        onSaveNote = { packageName, note -> scope.launch { repository.updateAppNote(packageName, note) } },
                        onToggleOptOut = { packageName, optedOut ->
                            scope.launch { karmaManager.setOptedOut(packageName, optedOut) }
                        },
                        onSetKarma = { packageName -> openSetKarma(packageName) },
                    )
                }
                item {
                    KarmaSection(
                        title = "Zero",
                        apps = zeroApps,
                        expanded = zeroExpanded,
                        onToggle = { zeroExpanded = !zeroExpanded },
                        appLabels = appLabels,
                        appIcons = appIcons,
                        onForgive = { packageName -> scope.launch { karmaManager.forgiveApp(packageName) } },
                        onSaveNote = { packageName, note -> scope.launch { repository.updateAppNote(packageName, note) } },
                        onToggleOptOut = { packageName, optedOut ->
                            scope.launch { karmaManager.setOptedOut(packageName, optedOut) }
                        },
                        onSetKarma = { packageName -> openSetKarma(packageName) },
                    )
                }

                item { Spacer(modifier = Modifier.height(8.dp)) }
            }
        }
    }

    if (showPickAppDialog) {
        PickAppForKarmaDialog(
            apps = allApps,
            onDismiss = { showPickAppDialog = false },
            onPick = { packageName ->
                showPickAppDialog = false
                openSetKarma(packageName)
            },
        )
    }

    setKarmaTarget?.let { (packageName, currentScore) ->
        SetKarmaDialog(
            appLabel = appLabels[packageName] ?: packageName,
            initialScore = currentScore,
            onDismiss = { setKarmaTarget = null },
            onSave = { score ->
                scope.launch {
                    karmaManager.setKarmaScore(packageName, score)
                    setKarmaTarget = null
                }
            },
        )
    }
}

@Composable
private fun KarmaSection(
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
                text = "$title (${apps.size})",
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
private fun KarmaCard(
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
    val normalizedDraft = noteDraft.trim().ifBlank { null }
    val noteChanged = normalizedDraft != karma.appNote
    val scoreColor = when {
        karma.isOptedOut -> MaterialTheme.colorScheme.onSurfaceVariant
        karma.karmaScore > 0 -> MaterialTheme.colorScheme.primary
        karma.karmaScore < -5 -> MaterialTheme.colorScheme.error
        karma.karmaScore < 0 -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.onSurface
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(),
        colors = CardDefaults.cardColors(
            containerColor = if (karma.isOptedOut)
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
            else
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (icon != null) {
                    Image(
                        painter = rememberDrawablePainter(drawable = icon),
                        contentDescription = label,
                        modifier = Modifier.size(40.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                }

                Column(modifier = Modifier.weight(1f)) {
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
                            onClick = { isEditingNote = true },
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                horizontal = 6.dp,
                                vertical = 0.dp,
                            ),
                            modifier = Modifier.height(26.dp),
                        ) {
                            Text(
                                text = "Edit note",
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                    Text(
                        text = "${karma.totalOpens} opens · ${karma.totalOverruns} overruns",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
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

                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        onClick = onSetKarma,
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            horizontal = 4.dp,
                            vertical = 0.dp,
                        ),
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

            Spacer(modifier = Modifier.height(4.dp))

            if (isEditingNote) {
                OutlinedTextField(
                    value = noteDraft,
                    onValueChange = { noteDraft = it },
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
                    TextButton(
                        onClick = {
                            noteDraft = karma.appNote.orEmpty()
                            isEditingNote = false
                        },
                    ) {
                        Text("Cancel")
                    }
                    TextButton(
                        onClick = {
                            onSaveNote(normalizedDraft)
                            isEditingNote = false
                        },
                        enabled = noteChanged,
                    ) {
                        Text("Save note")
                    }
                }
            } else {
                if (!karma.appNote.isNullOrBlank()) {
                    Text(
                        text = "Note: ${karma.appNote}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (!karma.isOptedOut && karma.karmaScore < 0) {
                    TextButton(onClick = onForgive) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Forgive")
                    }
                } else {
                    Spacer(modifier = Modifier.width(1.dp))
                }
            }
        }
    }
}

@Composable
private fun SetKarmaDialog(
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
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = scoreText,
                    onValueChange = { raw ->
                        scoreText = raw.filter { it == '-' || it.isDigit() }.take(5)
                    },
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
                        TextButton(onClick = { scoreText = preset.toString() }) {
                            Text(preset.toString())
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { parsedScore?.let(onSave) },
                enabled = parsedScore != null,
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

@Composable
private fun PickAppForKarmaDialog(
    apps: List<AppInfo>,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(apps, query) {
        val normalized = query.trim().lowercase()
        if (normalized.isEmpty()) {
            apps.take(40)
        } else {
            apps.filter { app ->
                app.label.lowercase().contains(normalized) ||
                    app.packageName.lowercase().contains(normalized)
            }.take(40)
        }
    }

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
                    items(filtered.size) { index ->
                        val app = filtered[index]
                        TextButton(
                            onClick = { onPick(app.packageName) },
                            modifier = Modifier.fillMaxWidth(),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                horizontal = 8.dp,
                                vertical = 4.dp,
                            ),
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
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        },
    )
}
