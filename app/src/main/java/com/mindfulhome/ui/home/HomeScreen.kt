package com.mindfulhome.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import com.mindfulhome.data.AppRepository
import com.mindfulhome.logging.SessionLogger
import com.mindfulhome.model.AppInfo
import com.mindfulhome.model.KarmaManager
import com.mindfulhome.service.TimerService
import com.mindfulhome.ui.search.SearchOverlay
import com.mindfulhome.util.PackageManagerHelper
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(
    durationMinutes: Int,
    repository: AppRepository,
    karmaManager: KarmaManager,
    onRequestAi: (packageName: String) -> Unit,
    onOpenSettings: () -> Unit = {},
    onOpenLogs: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var allApps by remember { mutableStateOf<List<AppInfo>>(emptyList()) }
    var showSearch by remember { mutableStateOf(false) }

    val hiddenApps by repository.hiddenApps().collectAsState(initial = emptyList())
    val hiddenPackages = remember(hiddenApps) { hiddenApps.map { it.packageName }.toSet() }

    val visibleApps = remember(allApps, hiddenPackages) {
        allApps.filter { it.packageName !in hiddenPackages }
    }

    LaunchedEffect(Unit) {
        allApps = PackageManagerHelper.getInstalledApps(context)
    }

    fun launchApp(appInfo: AppInfo) {
        scope.launch {
            SessionLogger.log("App opened: **${appInfo.label}** (`${appInfo.packageName}`)")
            karmaManager.onAppOpened(appInfo.packageName)
            TimerService.start(context, durationMinutes, appInfo.packageName)
            PackageManagerHelper.launchApp(context, appInfo.packageName)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
        ) {
            // Top bar with search, timer, logs, and settings
            TopBar(
                durationMinutes = durationMinutes,
                onSearchClick = { showSearch = true },
                onLogsClick = onOpenLogs,
                onSettingsClick = onOpenSettings
            )

            // App grid
            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp),
                contentPadding = PaddingValues(vertical = 8.dp),
                verticalArrangement = Arrangement.Top,
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                items(visibleApps, key = { it.packageName }) { app ->
                    AppItem(
                        appInfo = app,
                        onClick = { launchApp(app) },
                        onLongClick = { /* TODO: folder management */ }
                    )
                }
            }

            // Bottom dock
            BottomDock(
                modifier = Modifier.navigationBarsPadding()
            )
        }

        // FAB to talk to AI (access hidden apps)
        FloatingActionButton(
            onClick = { onRequestAi("") },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 16.dp, bottom = 80.dp)
                .navigationBarsPadding(),
            containerColor = MaterialTheme.colorScheme.primary
        ) {
            Icon(
                Icons.Default.Chat,
                contentDescription = "Talk to AI",
                tint = MaterialTheme.colorScheme.onPrimary
            )
        }

        // Search overlay
        SearchOverlay(
            apps = visibleApps,
            visible = showSearch,
            onAppClick = { app ->
                showSearch = false
                launchApp(app)
            },
            onDismiss = { showSearch = false }
        )
    }
}

@Composable
private fun TopBar(
    durationMinutes: Int,
    onSearchClick: () -> Unit,
    onLogsClick: () -> Unit,
    onSettingsClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "$durationMinutes min",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold
        )

        Spacer(modifier = Modifier.weight(1f))

        IconButton(onClick = onSearchClick) {
            Icon(
                Icons.Default.Search,
                contentDescription = "Search apps",
                tint = MaterialTheme.colorScheme.onBackground
            )
        }

        IconButton(onClick = onLogsClick) {
            Icon(
                Icons.Default.Article,
                contentDescription = "Session logs",
                tint = MaterialTheme.colorScheme.onBackground
            )
        }

        IconButton(onClick = onSettingsClick) {
            Icon(
                Icons.Default.Settings,
                contentDescription = "Settings",
                tint = MaterialTheme.colorScheme.onBackground
            )
        }
    }
}

@Composable
private fun BottomDock(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(72.dp)
            .background(
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            )
            .padding(horizontal = 24.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Dock slots are populated from HomeLayout DB; placeholder for now
        repeat(4) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(
                        MaterialTheme.colorScheme.surfaceVariant,
                        CircleShape
                    )
            )
        }
    }
}
