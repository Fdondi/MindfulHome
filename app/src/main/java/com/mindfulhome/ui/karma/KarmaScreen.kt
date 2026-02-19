package com.mindfulhome.ui.karma

import android.graphics.drawable.Drawable
import android.util.Log
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import com.mindfulhome.model.KarmaManager
import com.mindfulhome.util.PackageManagerHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class KarmaFilter { ALL, NEGATIVE, HIDDEN, OPTED_OUT }

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
    var filter by remember { mutableStateOf(KarmaFilter.ALL) }

    LaunchedEffect(Unit) {
        val apps = withContext(Dispatchers.IO) {
            PackageManagerHelper.getInstalledApps(context)
        }
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

    val displayedApps = remember(allKarma, filter, appLabels) {
        val valid = allKarma.filter { it.packageName.isNotBlank() }
        val filtered = when (filter) {
            KarmaFilter.ALL -> valid.filter { it.karmaScore != 0 || it.isHidden || it.isOptedOut }
            KarmaFilter.NEGATIVE -> valid.filter { it.karmaScore < 0 }
            KarmaFilter.HIDDEN -> valid.filter { it.isHidden }
            KarmaFilter.OPTED_OUT -> valid.filter { it.isOptedOut }
        }
        filtered.sortedWith(
            compareBy<AppKarma> { it.karmaScore }
                .thenBy { appLabels[it.packageName] ?: it.packageName }
        )
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
            }
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            KarmaFilter.entries.forEach { f ->
                FilterChip(
                    selected = filter == f,
                    onClick = { filter = f },
                    label = {
                        Text(
                            when (f) {
                                KarmaFilter.ALL -> "Active"
                                KarmaFilter.NEGATIVE -> "Negative"
                                KarmaFilter.HIDDEN -> "Hidden"
                                KarmaFilter.OPTED_OUT -> "Opted out"
                            },
                            style = MaterialTheme.typography.labelSmall
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                )
            }
        }

        if (displayedApps.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "No apps to show.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Karma is tracked as you use apps.\nApps with non-zero karma will appear here.",
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

                items(displayedApps, key = { it.packageName }) { karma ->
                    KarmaCard(
                        karma = karma,
                        label = appLabels[karma.packageName] ?: karma.packageName,
                        icon = appIcons[karma.packageName],
                        onForgive = {
                            scope.launch { karmaManager.forgiveApp(karma.packageName) }
                        },
                        onToggleOptOut = { optedOut ->
                            scope.launch { karmaManager.setOptedOut(karma.packageName, optedOut) }
                        }
                    )
                }

                item { Spacer(modifier = Modifier.height(8.dp)) }
            }
        }
    }
}

@Composable
private fun KarmaCard(
    karma: AppKarma,
    label: String,
    icon: Drawable?,
    onForgive: () -> Unit,
    onToggleOptOut: (Boolean) -> Unit
) {
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
                    Text(
                        text = label,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (karma.isOptedOut) {
                            Text(
                                text = "Opted out",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            Text(
                                text = "Karma: ${karma.karmaScore}",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium,
                                color = scoreColor
                            )
                            if (karma.isHidden) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.Default.VisibilityOff,
                                        contentDescription = null,
                                        modifier = Modifier.size(14.dp),
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                    Spacer(modifier = Modifier.width(2.dp))
                                    Text(
                                        text = "Hidden",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        }
                    }
                }

                Text(
                    text = if (karma.isOptedOut) "--" else "${karma.karmaScore}",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = scoreColor
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            if (!karma.isOptedOut && karma.totalOpens > 0) {
                Text(
                    text = "${karma.totalOpens} opens · ${karma.closedOnTimeCount} on time · ${karma.totalOverruns} overruns",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))
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

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Opt out",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Switch(
                        checked = karma.isOptedOut,
                        onCheckedChange = { onToggleOptOut(it) }
                    )
                }
            }
        }
    }
}
