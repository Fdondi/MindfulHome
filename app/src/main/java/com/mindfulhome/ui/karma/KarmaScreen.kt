package com.mindfulhome.ui.karma
import androidx.compose.ui.res.stringResource

import com.mindfulhome.R

import android.graphics.drawable.Drawable
import android.util.Log
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mindfulhome.data.AppRepository
import com.mindfulhome.model.AppInfo
import com.mindfulhome.model.KarmaManager
import com.mindfulhome.util.PackageManagerHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KarmaScreen(
    repository: AppRepository,
    karmaManager: KarmaManager,
    onBack: () -> Unit,
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
    var searchQuery by remember { mutableStateOf("") }

    KarmaScreenEffects(
        allKarma = allKarma,
        appsLoaded = appsLoaded,
        appLabels = appLabels,
        onAppsLoaded = { apps ->
            allApps = apps
            appLabels = apps.associate { it.packageName to it.label }
            appIcons = apps.associate { it.packageName to it.icon }
            appsLoaded = true
        },
        onLabelsResolved = { appLabels = appLabels + it },
        loadInstalled = { PackageManagerHelper.getInstalledApps(context) },
        resolveLabel = { PackageManagerHelper.getAppLabel(context, it) },
    )

    val groups = remember(allKarma, appLabels) { partitionKarmaApps(allKarma, appLabels) }
    val filteredGroups = remember(groups, appLabels, searchQuery) {
        filterKarmaGroupsByQuery(groups, appLabels, searchQuery)
    }
    val queryActive = searchQuery.trim().isNotEmpty()
    LaunchedEffect(filteredGroups, queryActive) {
        if (!queryActive) return@LaunchedEffect
        val expand = sectionsToExpandForQuery(filteredGroups, true)
        negativeExpanded = "negative" in expand
        optedOutExpanded = "optedOut" in expand
        positiveExpanded = "positive" in expand
        zeroExpanded = "zero" in expand
    }
    val karmaByPackage = remember(allKarma) { allKarma.associateBy { it.packageName } }
    val trackedEmpty = remember(allKarma) {
        allKarma.none { it.packageName.isNotBlank() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        TopAppBar(
            title = { Text(stringResource(R.string.karma), fontWeight = FontWeight.SemiBold) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                }
            },
            actions = {
                IconButton(onClick = { showPickAppDialog = true }) {
                    Icon(Icons.Default.Add, contentDescription = stringResource(R.string.set_karma_for_app))
                }
            },
        )
        if (!trackedEmpty) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                singleLine = true,
                label = { Text(stringResource(R.string.search_apps_2)) },
            )
        }
        KarmaScreenBody(
            trackedEmpty = trackedEmpty,
            groups = filteredGroups,
            negativeExpanded = negativeExpanded,
            optedOutExpanded = optedOutExpanded,
            positiveExpanded = positiveExpanded,
            zeroExpanded = zeroExpanded,
            onNegativeToggle = { negativeExpanded = !negativeExpanded },
            onOptedOutToggle = { optedOutExpanded = !optedOutExpanded },
            onPositiveToggle = { positiveExpanded = !positiveExpanded },
            onZeroToggle = { zeroExpanded = !zeroExpanded },
            appLabels = appLabels,
            appIcons = appIcons,
            onForgive = { packageName -> scope.launch { karmaManager.forgiveApp(packageName) } },
            onSaveNote = { packageName, note ->
                scope.launch { repository.updateAppNote(packageName, note) }
            },
            onToggleOptOut = { packageName, optedOut ->
                scope.launch { karmaManager.setOptedOut(packageName, optedOut) }
            },
            onSetKarma = { packageName ->
                val current = karmaByPackage[packageName]?.karmaScore ?: 0
                setKarmaTarget = packageName to current
            },
        )
    }

    if (showPickAppDialog) {
        PickAppForKarmaDialog(
            apps = allApps,
            onDismiss = { showPickAppDialog = false },
            onPick = { packageName ->
                showPickAppDialog = false
                val current = karmaByPackage[packageName]?.karmaScore ?: 0
                setKarmaTarget = packageName to current
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
private fun KarmaScreenBody(
    trackedEmpty: Boolean,
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
    if (trackedEmpty) {
        KarmaEmptyState()
        return
    }
    KarmaSectionsList(
        groups = groups,
        negativeExpanded = negativeExpanded,
        optedOutExpanded = optedOutExpanded,
        positiveExpanded = positiveExpanded,
        zeroExpanded = zeroExpanded,
        onNegativeToggle = onNegativeToggle,
        onOptedOutToggle = onOptedOutToggle,
        onPositiveToggle = onPositiveToggle,
        onZeroToggle = onZeroToggle,
        appLabels = appLabels,
        appIcons = appIcons,
        onForgive = onForgive,
        onSaveNote = onSaveNote,
        onToggleOptOut = onToggleOptOut,
        onSetKarma = onSetKarma,
    )
}

@Composable
private fun KarmaScreenEffects(
    allKarma: List<com.mindfulhome.data.AppKarma>,
    appsLoaded: Boolean,
    appLabels: Map<String, String>,
    onAppsLoaded: (List<AppInfo>) -> Unit,
    onLabelsResolved: (Map<String, String>) -> Unit,
    loadInstalled: suspend () -> List<AppInfo>,
    resolveLabel: (String) -> String,
) {
    LaunchedEffect(Unit) {
        onAppsLoaded(loadInstalled())
    }
    LaunchedEffect(allKarma, appsLoaded) {
        if (!appsLoaded) return@LaunchedEffect
        val missing = packagesMissingLabels(allKarma, appLabels)
        if (missing.isEmpty()) return@LaunchedEffect
        Log.w("KarmaScreen", "Karma entries without launcher match: $missing")
        val resolved = withContext(Dispatchers.IO) {
            missing.associateWith { resolveLabel(it) }
        }
        onLabelsResolved(resolved)
    }
}
