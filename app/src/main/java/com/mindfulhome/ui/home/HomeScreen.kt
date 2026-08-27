package com.mindfulhome.ui.home

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import com.mindfulhome.ai.EmbeddingManager
import com.mindfulhome.data.AppRepository
import com.mindfulhome.logging.SessionLogger
import com.mindfulhome.model.AppInfo
import com.mindfulhome.model.KarmaManager
import com.mindfulhome.model.TimerState
import com.mindfulhome.service.TimerService
import com.mindfulhome.ui.coachmark.CoachmarkScreen
import com.mindfulhome.ui.coachmark.ScreenCoachmarkHost
import com.mindfulhome.ui.coachmark.coachmarkTargets
import com.mindfulhome.ui.coachmark.homeCoachmarkSpecs
import com.mindfulhome.ui.search.SearchOverlay
import com.mindfulhome.util.PackageManagerHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun HomeScreen(
    durationMinutes: Int,
    unlockReason: String = "",
    sessionHandle: SessionLogger.SessionHandle?,
    repository: AppRepository,
    karmaManager: KarmaManager,
    onRequestAi: (packageName: String) -> Unit,
    onTimerClick: () -> Unit = {},
    onOpenDefault: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onOpenLogs: () -> Unit = {},
    onOpenKarma: () -> Unit = {},
    onOpenHelp: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val catalogGen by PackageManagerHelper.catalogGeneration.collectAsState()
    val allApps = remember(catalogGen) { PackageManagerHelper.peekInstalledApps(context) }
    var showSearch by remember { mutableStateOf(false) }
    var favoritesStripExpanded by remember { mutableStateOf(false) }
    var suggestedApps by remember { mutableStateOf<List<AppInfo>>(emptyList()) }
    val karmaEntries by repository.allKarma().collectAsState(initial = emptyList())
    val layoutItems by repository.homeLayout().collectAsState(initial = emptyList())
    val favoritesEntries by repository.favoritesSlots().collectAsState(initial = emptyList())
    val allIntents by repository.allIntents().collectAsState(initial = emptyList())
    val negativeKarmaPackages = remember(karmaEntries) {
        negativeKarmaPackageSet(
            karmaEntries.associate { it.packageName to SimpleKarmaScore(it.packageName, it.karmaScore, it.isOptedOut) },
        )
    }
    val favoritePackages = remember(favoritesEntries) {
        favoritesEntries.flatMap { it.flattenPackages() }.toSet()
    }
    val baseGridItems = remember(allApps, layoutItems) { buildGridItems(allApps, layoutItems) }
    val gridItems = remember { mutableStateListOf<HomeGridItem>() }
    val dragDropState = rememberDragDropState()
    val gridState = rememberLazyGridState()

    HomeScreenEffects(
        unlockReason = unlockReason,
        allApps = allApps,
        allIntents = allIntents,
        baseGridItems = baseGridItems,
        dragDropState = dragDropState,
        gridItems = gridItems,
        sessionHandle = sessionHandle,
        onSuggestedApps = { suggestedApps = it },
    )

    val launchApp: (AppInfo) -> Unit = { appInfo ->
        scope.launch {
            launchHomeApp(
                appInfo = appInfo,
                unlockReason = unlockReason,
                sessionHandle = sessionHandle,
                karmaManager = karmaManager,
                repository = repository,
                trackApp = { TimerService.trackApp(context, it, sessionHandle) },
                launch = { PackageManagerHelper.launchApp(context, it) },
            )
        }
    }
    val handleAppTap: (AppInfo) -> Unit = { app ->
        dispatchHomeAppTap(app, negativeKarmaPackages, onRequestAi, launchApp)
    }

    HomeScreenScaffold(
        durationMinutes = durationMinutes,
        showSearch = showSearch,
        onShowSearchChange = { showSearch = it },
        suggestedApps = suggestedApps,
        negativeKarmaPackages = negativeKarmaPackages,
        allApps = allApps,
        favoritePackages = favoritePackages,
        gridItems = gridItems,
        gridState = gridState,
        dragDropState = dragDropState,
        favoritesStripExpanded = favoritesStripExpanded,
        onFavoritesExpandedChange = { favoritesStripExpanded = it },
        repository = repository,
        onAppTap = handleAppTap,
        onTimerClick = onTimerClick,
        onOpenDefault = onOpenDefault,
        onOpenSettings = onOpenSettings,
        onOpenLogs = onOpenLogs,
        onOpenKarma = onOpenKarma,
        onOpenHelp = onOpenHelp,
        onRequestAi = onRequestAi,
        onDragStarted = { item, local, topLeft ->
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            dragDropState.startDrag(item, topLeft, local)
        },
        onDragDelta = { dragDropState.updateDrag(it) },
        onDragEnded = {
            val captured = dragDropState.draggedItem
            val result = dragDropState.endDrag()
            if (captured != null) {
                scope.launch {
                    applyHomeDropAction(
                        action = resolveHomeDropAction(captured, result),
                        favoritePackages = favoritePackages,
                        gridKeys = gridItems.map { it.key },
                        addToFavorites = { repository.addToFavorites(it) },
                        mergeIntoFavorite = { slot, pkg ->
                            repository.mergePackageIntoFavoritesAt(slot, pkg)
                        },
                        reorderGrid = { reordered ->
                            val byKey = gridItems.associateBy { it.key }
                            gridItems.clear()
                            gridItems.addAll(reordered.mapNotNull { byKey[it] })
                            repository.updateGridPositions(layoutUpdatesFromGrid(gridItems))
                        },
                    )
                }
            }
        },
        onDragCancelled = { dragDropState.cancelDrag() },
        onAddToDock = { app ->
            scope.launch {
                if (app.packageName !in favoritePackages) repository.addToFavorites(app.packageName)
            }
        },
    )
}

@Composable
private fun HomeScreenEffects(
    unlockReason: String,
    allApps: List<AppInfo>,
    allIntents: List<com.mindfulhome.data.AppIntent>,
    baseGridItems: List<HomeGridItem>,
    dragDropState: DragDropState,
    gridItems: SnapshotStateList<HomeGridItem>,
    sessionHandle: SessionLogger.SessionHandle?,
    onSuggestedApps: (List<AppInfo>) -> Unit,
) {
    val context = LocalContext.current
    LaunchedEffect(unlockReason, allApps, allIntents) {
        if (!shouldComputeSuggestedApps(unlockReason, allApps.isEmpty())) {
            onSuggestedApps(emptyList())
            return@LaunchedEffect
        }
        onSuggestedApps(
            withContext(Dispatchers.Default) {
                val intentsByPkg = allIntents.groupBy { it.packageName }
                val appTexts = allApps.map { app ->
                    val past = intentsByPkg[app.packageName]?.joinToString(" ") { it.intentText }.orEmpty()
                    app.packageName to "${app.label} $past".trim()
                }
                EmbeddingManager.rankApps(unlockReason, appTexts).take(5).mapNotNull { (pkg, _) ->
                    allApps.find { it.packageName == pkg }
                }
            },
        )
    }
    LaunchedEffect(baseGridItems) {
        if (!dragDropState.isDragging) {
            gridItems.clear()
            gridItems.addAll(baseGridItems)
        }
    }
    LaunchedEffect(Unit) {
        if (TimerService.timerState.value !is TimerState.Idle) {
            TimerService.clearVisibleNudges(context, sessionHandle)
        }
        Log.d("HomeScreen", "Loading installed apps from shared cache...")
        val apps = PackageManagerHelper.getInstalledApps(context)
        Log.d("HomeScreen", "Loaded ${apps.size} apps")
    }
}

@Composable
private fun HomeScreenScaffold(
    durationMinutes: Int,
    showSearch: Boolean,
    onShowSearchChange: (Boolean) -> Unit,
    suggestedApps: List<AppInfo>,
    negativeKarmaPackages: Set<String>,
    allApps: List<AppInfo>,
    favoritePackages: Set<String>,
    gridItems: SnapshotStateList<HomeGridItem>,
    gridState: androidx.compose.foundation.lazy.grid.LazyGridState,
    dragDropState: DragDropState,
    favoritesStripExpanded: Boolean,
    onFavoritesExpandedChange: (Boolean) -> Unit,
    repository: AppRepository,
    onAppTap: (AppInfo) -> Unit,
    onTimerClick: () -> Unit,
    onOpenDefault: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenLogs: () -> Unit,
    onOpenKarma: () -> Unit,
    onOpenHelp: () -> Unit,
    onRequestAi: (String) -> Unit,
    onDragStarted: (HomeGridItem, androidx.compose.ui.geometry.Offset, androidx.compose.ui.geometry.Offset) -> Unit,
    onDragDelta: (androidx.compose.ui.geometry.Offset) -> Unit,
    onDragEnded: () -> Unit,
    onDragCancelled: () -> Unit,
    onAddToDock: (AppInfo) -> Unit,
) {
    val homeTourSteps = coachmarkTargets(homeCoachmarkSpecs())
    ScreenCoachmarkHost(
        screen = CoachmarkScreen.HOME,
        steps = homeTourSteps,
    ) { _ ->
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding(),
        ) {
            HomeTopBar(
                durationMinutes = durationMinutes,
                onTimerClick = onTimerClick,
                onHomeClick = onOpenDefault,
                onSearchClick = { onShowSearchChange(true) },
                onLogsClick = onOpenLogs,
                onKarmaClick = onOpenKarma,
                onTutorialClick = onOpenHelp,
                onSettingsClick = onOpenSettings,
                onAiClick = { onRequestAi("") },
            )
            HomeSuggestedAppsRow(suggestedApps, negativeKarmaPackages, onAppTap)
            HomeAppGrid(
                gridItems = gridItems,
                gridState = gridState,
                dragDropState = dragDropState,
                negativeKarmaPackages = negativeKarmaPackages,
                onAppTap = onAppTap,
                onDragStarted = onDragStarted,
                onDragDelta = onDragDelta,
                onDragEnded = onDragEnded,
                onDragCancelled = onDragCancelled,
                modifier = Modifier.weight(1f),
            )
            HomeFavoritesStrip(
                repository = repository,
                dragDropState = dragDropState,
                favoritesStripExpanded = favoritesStripExpanded,
                onExpandedChange = onFavoritesExpandedChange,
                onLaunchApp = { pkg -> allApps.find { it.packageName == pkg }?.let(onAppTap) },
            )
        }
        if (dragDropState.isDragging) {
            DragItemOverlay(dragDropState) { shouldRequestAi(it.packageName, negativeKarmaPackages) }
        }
        SearchOverlay(
            apps = allApps,
            dimmedPackages = negativeKarmaPackages,
            visible = showSearch,
            onAppClick = { app ->
                onShowSearchChange(false)
                onAppTap(app)
            },
            onDismiss = { onShowSearchChange(false) },
            onAddToDock = onAddToDock,
        )
    }
    }
}

internal fun dispatchHomeAppTap(
    app: AppInfo,
    negativeKarmaPackages: Set<String>,
    onRequestAi: (String) -> Unit,
    launchApp: (AppInfo) -> Unit,
) {
    if (shouldRequestAi(app.packageName, negativeKarmaPackages)) onRequestAi(app.packageName)
    else launchApp(app)
}

internal suspend fun launchHomeApp(
    appInfo: AppInfo,
    unlockReason: String,
    sessionHandle: SessionLogger.SessionHandle?,
    karmaManager: KarmaManager,
    repository: AppRepository,
    trackApp: (String) -> Unit,
    launch: (String) -> Unit,
) {
    SessionLogger.log(sessionHandle, "App opened: **${appInfo.label}** (`${appInfo.packageName}`)")
    karmaManager.onAppOpened(appInfo.packageName)
    trackApp(appInfo.packageName)
    if (unlockReason.isNotBlank()) {
        repository.recordIntent(appInfo.packageName, unlockReason)
        EmbeddingManager.invalidateCache()
    }
    launch(appInfo.packageName)
}
