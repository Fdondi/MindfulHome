package com.mindfulhome.ui.defaultpage

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stars
import androidx.compose.material.icons.automirrored.filled.Article
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
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import com.google.accompanist.drawablepainter.rememberDrawablePainter
import com.mindfulhome.AppVersion
import com.mindfulhome.data.AppRepository
import com.mindfulhome.data.QuickLaunchFolder
import com.mindfulhome.data.QuickLaunchGridKey
import com.mindfulhome.data.TodoItem
import com.mindfulhome.model.AppInfo
import com.mindfulhome.ui.common.AddAppsDialog
import com.mindfulhome.util.PackageManagerHelper
import java.text.DateFormat
import java.util.Calendar
import java.util.Date
import kotlinx.coroutines.launch
import androidx.compose.foundation.BorderStroke
import kotlin.math.roundToInt

// ---------------------------------------------------------------------------
// QuickLaunch grid data types
// ---------------------------------------------------------------------------

private sealed class QuickLaunchGridEntry {
    abstract val sortPosition: Int
    abstract val key: String

    data class App(val appInfo: AppInfo, val position: Int) : QuickLaunchGridEntry() {
        override val sortPosition = position
        override val key = "app:${appInfo.packageName}"
    }

    data class Folder(
        val folderId: Long,
        val name: String,
        val apps: List<AppInfo>,
        val position: Int,
    ) : QuickLaunchGridEntry() {
        override val sortPosition = position
        override val key = "folder:$folderId"
    }
}

private fun buildQuickLaunchGridEntries(
    allItems: List<com.mindfulhome.data.QuickLaunchItem>,
    folders: List<QuickLaunchFolder>,
    installedApps: List<AppInfo>,
): List<QuickLaunchGridEntry> {
    val appsByPackage = installedApps.associateBy { it.packageName }
    val itemsByFolder = allItems.filter { it.folderId != null }.groupBy { it.folderId!! }

    val appEntries = allItems
        .filter { it.folderId == null }
        .mapNotNull { item ->
            val appInfo = appsByPackage[item.packageName] ?: return@mapNotNull null
            QuickLaunchGridEntry.App(appInfo, item.position)
        }

    val folderEntries = folders.map { folder ->
        val folderApps = (itemsByFolder[folder.id] ?: emptyList())
            .sortedBy { it.position }
            .mapNotNull { appsByPackage[it.packageName] }
        QuickLaunchGridEntry.Folder(folder.id, folder.name, folderApps, folder.position)
    }

    return (appEntries + folderEntries).sortedBy { it.sortPosition }
}

// ---------------------------------------------------------------------------
// Drag state
// ---------------------------------------------------------------------------

private class QuickLaunchDragState {
    var isDragging by mutableStateOf(false)
        private set
    var draggedEntry: QuickLaunchGridEntry? by mutableStateOf(null)
        private set
    var draggedIndex: Int by mutableStateOf(-1)
        private set
    var overlayOffset by mutableStateOf(Offset.Zero)
        private set
    var hoverIndex by mutableStateOf(-1)
        private set
    var isLongHover by mutableStateOf(false)
        private set

    private val cellBounds = mutableMapOf<Int, Rect>()
    private var fingerPos = Offset.Zero
    private var grabOffset = Offset.Zero
    private var hoverStartMs = 0L

    fun registerCellBounds(index: Int, topLeft: Offset, size: Size) {
        cellBounds[index] = Rect(topLeft, size)
    }

    fun startDrag(entry: QuickLaunchGridEntry, index: Int, itemTopLeft: Offset, localTouch: Offset) {
        draggedEntry = entry
        draggedIndex = index
        grabOffset = localTouch
        fingerPos = itemTopLeft + localTouch
        overlayOffset = itemTopLeft
        isDragging = true
        hoverIndex = -1
        isLongHover = false
        hoverStartMs = 0L
    }

    fun updateDrag(delta: Offset) {
        fingerPos += delta
        overlayOffset = fingerPos - grabOffset

        val newHover = cellBounds.entries
            .filter { (idx, _) -> idx != draggedIndex }
            .firstOrNull { (_, bounds) -> bounds.contains(fingerPos) }
            ?.key ?: -1

        if (newHover != hoverIndex) {
            hoverIndex = newHover
            hoverStartMs = if (newHover >= 0) System.currentTimeMillis() else 0L
            isLongHover = false
        } else if (hoverIndex >= 0) {
            isLongHover = (System.currentTimeMillis() - hoverStartMs) >= 600L
        }
    }

    fun endDrag(): Pair<Int, Boolean> {
        val result = Pair(hoverIndex, isLongHover)
        reset()
        return result
    }

    fun cancelDrag() = reset()

    private fun reset() {
        isDragging = false
        draggedEntry = null
        draggedIndex = -1
        overlayOffset = Offset.Zero
        hoverIndex = -1
        isLongHover = false
        fingerPos = Offset.Zero
        grabOffset = Offset.Zero
        hoverStartMs = 0L
        cellBounds.clear()
    }
}

// ---------------------------------------------------------------------------
// Internal state models
// ---------------------------------------------------------------------------

private data class TodoEditorState(
    val id: Long? = null,
    val intent: String = "",
    val durationMinutes: String = "",
    val deadlineEpochMs: Long? = null,
    val priority: Int = 2,
)

// ---------------------------------------------------------------------------
// DefaultPageScreen
// ---------------------------------------------------------------------------

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
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val appVersion = AppVersion.versionName

    val allQuickLaunchItems by repository.quickLaunchApps().collectAsState(initial = emptyList())
    val quickLaunchFolders by repository.quickLaunchFolders().collectAsState(initial = emptyList())
    val todoItems by repository.sortedOpenTodos().collectAsState(initial = emptyList())

    val quickLaunchPackages = remember(allQuickLaunchItems) {
        allQuickLaunchItems.map { it.packageName }.toSet()
    }

    var installedApps by remember { mutableStateOf(emptyList<AppInfo>()) }
    var showAddDialog by remember { mutableStateOf(false) }
    var isRemoveMode by remember { mutableStateOf(false) }
    var editor by remember { mutableStateOf<TodoEditorState?>(null) }
    var openFolderEntry by remember { mutableStateOf<QuickLaunchGridEntry.Folder?>(null) }
    var pendingFolderRename by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(Unit) {
        installedApps = PackageManagerHelper.getInstalledApps(context)
    }
    LaunchedEffect(Unit) {
        onScreenShown()
    }

    val baseGridEntries = remember(allQuickLaunchItems, quickLaunchFolders, installedApps) {
        buildQuickLaunchGridEntries(allQuickLaunchItems, quickLaunchFolders, installedApps)
    }
    val gridEntries = remember { mutableStateListOf<QuickLaunchGridEntry>() }
    val dragState = remember { QuickLaunchDragState() }

    LaunchedEffect(baseGridEntries) {
        if (!dragState.isDragging) {
            gridEntries.clear()
            gridEntries.addAll(baseGridEntries)
        }
    }

    // Keep the open folder in sync with DB updates
    val currentOpenFolder = openFolderEntry?.let { of ->
        gridEntries.filterIsInstance<QuickLaunchGridEntry.Folder>().find { it.folderId == of.folderId }
    }

    fun handleDrop(dragged: QuickLaunchGridEntry, hoverIdx: Int, isLong: Boolean) {
        val target = gridEntries.getOrNull(hoverIdx) ?: return
        if (isLong && dragged is QuickLaunchGridEntry.App) {
            when (target) {
                is QuickLaunchGridEntry.App -> {
                    // Create a new folder from two apps
                    scope.launch {
                        val folderId = repository.createQuickLaunchFolder(
                            pkg1 = dragged.appInfo.packageName,
                            pkg2 = target.appInfo.packageName,
                            folderPosition = target.position,
                        )
                        pendingFolderRename = folderId
                    }
                }
                is QuickLaunchGridEntry.Folder -> {
                    // Add app to existing folder
                    scope.launch {
                        repository.addAppToQuickLaunchFolder(dragged.appInfo.packageName, target.folderId)
                    }
                }
            }
        } else {
            // Reorder
            val fromIdx = gridEntries.indexOf(dragged)
            val toIdx = hoverIdx
            if (fromIdx >= 0 && toIdx >= 0 && fromIdx != toIdx) {
                val moved = gridEntries.removeAt(fromIdx)
                gridEntries.add(toIdx, moved)
                scope.launch {
                    repository.reorderQuickLaunch(gridEntries.map { entry ->
                        when (entry) {
                            is QuickLaunchGridEntry.App -> QuickLaunchGridKey.App(entry.appInfo.packageName)
                            is QuickLaunchGridEntry.Folder -> QuickLaunchGridKey.Folder(entry.folderId)
                        }
                    })
                }
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState(), enabled = !dragState.isDragging)
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
                                editor = TodoEditorState(deadlineEpochMs = next6pmEpochMs())
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

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "QuickLaunch",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                if (isRemoveMode) {
                    TextButton(onClick = { isRemoveMode = false }) {
                        Text("Done")
                    }
                } else {
                    IconButton(onClick = { isRemoveMode = true }) {
                        Icon(
                            Icons.Default.Edit,
                            contentDescription = "Edit QuickLaunch",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            QuickLaunchWrappedRow(
                gridEntries = gridEntries,
                dragState = dragState,
                quickLaunchPackages = quickLaunchPackages,
                onQuickLaunchApp = onQuickLaunchApp,
                onAddQuickLaunch = { showAddDialog = true },
                isRemoveMode = isRemoveMode,
                onRemoveApp = { packageName ->
                    scope.launch { repository.removeFromQuickLaunch(packageName) }
                },
                onRemoveFolder = { folderId ->
                    scope.launch { repository.deleteQuickLaunchFolder(folderId) }
                },
                onOpenFolder = { folder -> openFolderEntry = folder },
                onDrop = { hoverIdx, isLong ->
                    val dragged = dragState.draggedEntry
                    if (dragged != null) handleDrop(dragged, hoverIdx, isLong)
                },
                onDragStarted = { entry, index, itemTopLeft, localTouch ->
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    dragState.startDrag(entry, index, itemTopLeft, localTouch)
                },
                onDragDelta = { delta -> dragState.updateDrag(delta) },
                onDragCancelled = { dragState.cancelDrag() },
            )

            Button(
                onClick = onOpenTimerPlain,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("something else?")
            }
            Spacer(modifier = Modifier.height(12.dp))
        }

        // Floating drag overlay
        if (dragState.isDragging) {
            val dragged = dragState.draggedEntry
            if (dragged != null) {
                Box(
                    modifier = Modifier
                        .offset {
                            IntOffset(
                                dragState.overlayOffset.x.roundToInt(),
                                dragState.overlayOffset.y.roundToInt(),
                            )
                        }
                        .size(74.dp)
                        .graphicsLayer {
                            scaleX = 1.15f
                            scaleY = 1.15f
                            shadowElevation = 8f
                            alpha = 0.9f
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    when (dragged) {
                        is QuickLaunchGridEntry.App -> {
                            if (dragged.appInfo.icon != null) {
                                Image(
                                    painter = rememberDrawablePainter(dragged.appInfo.icon),
                                    contentDescription = null,
                                    modifier = Modifier.size(42.dp),
                                )
                            }
                        }
                        is QuickLaunchGridEntry.Folder -> {
                            FolderIconGrid(apps = dragged.apps, size = 42.dp)
                        }
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        AddAppsDialog(
            title = "Add to QuickLaunch",
            apps = installedApps,
            excludedPackages = quickLaunchPackages,
            onAdd = { packageName ->
                scope.launch { repository.addToQuickLaunch(packageName) }
            },
            onDismiss = { showAddDialog = false },
        )
    }

    // Folder rename dialog shown right after creating a folder via drag-and-drop
    pendingFolderRename?.let { folderId ->
        FolderRenameDialog(
            initialName = "Folder",
            onConfirm = { name ->
                scope.launch { repository.renameQuickLaunchFolder(folderId, name) }
                pendingFolderRename = null
            },
            onDismiss = { pendingFolderRename = null },
        )
    }

    // Folder contents dialog
    currentOpenFolder?.let { folder ->
        FolderContentsDialog(
            folder = folder,
            onLaunchApp = { packageName ->
                openFolderEntry = null
                onQuickLaunchApp(packageName, quickLaunchPackages)
            },
            onRemoveFromFolder = { packageName ->
                scope.launch { repository.removeAppFromQuickLaunchFolder(packageName, folder.folderId) }
            },
            onRename = { name ->
                scope.launch { repository.renameQuickLaunchFolder(folder.folderId, name) }
            },
            onDismiss = { openFolderEntry = null },
        )
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

// ---------------------------------------------------------------------------
// QuickLaunch grid
// ---------------------------------------------------------------------------

@Composable
private fun QuickLaunchWrappedRow(
    gridEntries: List<QuickLaunchGridEntry>,
    dragState: QuickLaunchDragState,
    quickLaunchPackages: Set<String>,
    onQuickLaunchApp: (packageName: String, allowedPackages: Set<String>) -> Unit,
    onAddQuickLaunch: () -> Unit,
    isRemoveMode: Boolean,
    onRemoveApp: (String) -> Unit,
    onRemoveFolder: (Long) -> Unit,
    onOpenFolder: (QuickLaunchGridEntry.Folder) -> Unit,
    onDrop: (hoverIndex: Int, isLongHover: Boolean) -> Unit,
    onDragStarted: (QuickLaunchGridEntry, Int, Offset, Offset) -> Unit,
    onDragDelta: (Offset) -> Unit,
    onDragCancelled: () -> Unit,
) {
    data class Tile(
        val entry: QuickLaunchGridEntry? = null,
        val isAdd: Boolean = false,
        val isPlaceholder: Boolean = false,
        val index: Int = -1,
    )

    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val minCellWidth = 74.dp
        val columns = (maxWidth / minCellWidth).toInt().coerceAtLeast(1)
        val horizontalGap = if (columns > 1) {
            ((maxWidth - (minCellWidth * columns)) / (columns - 1)).coerceAtLeast(0.dp)
        } else {
            0.dp
        }

        val tilesWithIndex = run {
            val tiles = gridEntries.mapIndexed { idx, entry -> Tile(entry = entry, index = idx) } +
                Tile(isAdd = true)
            val rows = tiles.chunked(columns)
            rows.map { row ->
                if (row.size >= columns) row
                else row + List(columns - row.size) { Tile(isPlaceholder = true) }
            }
        }

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            tilesWithIndex.forEach { rowTiles ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(horizontalGap),
                ) {
                    rowTiles.forEach { tile ->
                        Box(
                            modifier = Modifier.weight(1f),
                            contentAlignment = Alignment.Center,
                        ) {
                            when {
                                tile.isPlaceholder -> {
                                    Spacer(modifier = Modifier.width(minCellWidth).height(1.dp))
                                }
                                tile.isAdd -> {
                                    OutlinedButton(
                                        onClick = onAddQuickLaunch,
                                        border = BorderStroke(
                                            1.dp,
                                            MaterialTheme.colorScheme.outline.copy(alpha = 0.6f),
                                        ),
                                        modifier = Modifier.width(minCellWidth),
                                    ) {
                                        Icon(Icons.Default.Add, contentDescription = "Add QuickLaunch app")
                                    }
                                }
                                tile.entry != null -> {
                                    val entry = tile.entry
                                    val entryIndex = tile.index
                                    val isDragged = dragState.draggedEntry?.key == entry.key
                                    val isHoverTarget = dragState.hoverIndex == entryIndex
                                    val isLongHoverTarget = isHoverTarget && dragState.isLongHover
                                    var itemRootPosition by remember { mutableStateOf(Offset.Zero) }

                                    val borderColor = when {
                                        isLongHoverTarget -> MaterialTheme.colorScheme.tertiary
                                        isHoverTarget -> MaterialTheme.colorScheme.primary
                                        else -> Color.Transparent
                                    }

                                    Box(
                                        modifier = Modifier
                                            .width(minCellWidth)
                                            .onGloballyPositioned { coords ->
                                                val pos = coords.positionInRoot()
                                                itemRootPosition = pos
                                                dragState.registerCellBounds(entryIndex, pos, coords.size.toSize())
                                            }
                                            .graphicsLayer { alpha = if (isDragged) 0f else 1f }
                                            .border(
                                                width = if (isHoverTarget) 2.dp else 0.dp,
                                                color = borderColor,
                                                shape = RoundedCornerShape(10.dp),
                                            )
                                            .pointerInput(entry.key) {
                                                detectDragGesturesAfterLongPress(
                                                    onDragStart = { localOffset ->
                                                        onDragStarted(entry, entryIndex, itemRootPosition, localOffset)
                                                    },
                                                    onDrag = { change, delta ->
                                                        change.consume()
                                                        onDragDelta(delta)
                                                    },
                                                    onDragEnd = {
                                                        val (hoverIdx, isLong) = dragState.endDrag()
                                                        onDrop(hoverIdx, isLong)
                                                    },
                                                    onDragCancel = { onDragCancelled() },
                                                )
                                            },
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        when (entry) {
                                            is QuickLaunchGridEntry.App -> {
                                                AppTile(
                                                    entry = entry,
                                                    isRemoveMode = isRemoveMode,
                                                    quickLaunchPackages = quickLaunchPackages,
                                                    onQuickLaunchApp = onQuickLaunchApp,
                                                    onRemoveApp = onRemoveApp,
                                                )
                                            }
                                            is QuickLaunchGridEntry.Folder -> {
                                                FolderTile(
                                                    entry = entry,
                                                    isRemoveMode = isRemoveMode,
                                                    onClick = { onOpenFolder(entry) },
                                                    onRemove = { onRemoveFolder(entry.folderId) },
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AppTile(
    entry: QuickLaunchGridEntry.App,
    isRemoveMode: Boolean,
    quickLaunchPackages: Set<String>,
    onQuickLaunchApp: (String, Set<String>) -> Unit,
    onRemoveApp: (String) -> Unit,
) {
    Box(
        modifier = Modifier.clickable {
            if (isRemoveMode) onRemoveApp(entry.appInfo.packageName)
            else onQuickLaunchApp(entry.appInfo.packageName, quickLaunchPackages)
        },
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (entry.appInfo.icon != null) {
                Image(
                    painter = rememberDrawablePainter(entry.appInfo.icon),
                    contentDescription = entry.appInfo.label,
                    modifier = Modifier.size(42.dp),
                )
            }
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = entry.appInfo.label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelSmall,
            )
        }
        if (isRemoveMode) {
            RemoveBadge(modifier = Modifier.align(Alignment.TopEnd))
        }
    }
}

@Composable
private fun FolderTile(
    entry: QuickLaunchGridEntry.Folder,
    isRemoveMode: Boolean,
    onClick: () -> Unit,
    onRemove: () -> Unit,
) {
    Box(
        modifier = Modifier.clickable { if (isRemoveMode) onRemove() else onClick() },
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .background(
                        MaterialTheme.colorScheme.surfaceVariant,
                        RoundedCornerShape(10.dp),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                FolderIconGrid(apps = entry.apps, size = 36.dp)
            }
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = entry.name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelSmall,
            )
        }
        if (isRemoveMode) {
            RemoveBadge(modifier = Modifier.align(Alignment.TopEnd))
        }
    }
}

@Composable
private fun FolderIconGrid(apps: List<AppInfo>, size: androidx.compose.ui.unit.Dp) {
    val iconSize = size / 2.2f
    val displayApps = apps.take(4)
    val rows = when (displayApps.size) {
        1 -> listOf(displayApps)
        2 -> listOf(displayApps)
        else -> listOf(displayApps.take(2), displayApps.drop(2).take(2))
    }
    Column(
        modifier = Modifier.size(size),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        rows.forEach { rowApps ->
            Row(
                horizontalArrangement = Arrangement.Center,
            ) {
                rowApps.forEach { app ->
                    if (app.icon != null) {
                        Image(
                            painter = rememberDrawablePainter(app.icon),
                            contentDescription = null,
                            modifier = Modifier.size(iconSize).padding(1.dp),
                        )
                    } else {
                        Spacer(modifier = Modifier.size(iconSize))
                    }
                }
            }
        }
    }
}

@Composable
private fun RemoveBadge(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(18.dp)
            .background(Color.Red, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Default.Close,
            contentDescription = "Remove",
            modifier = Modifier.size(12.dp),
            tint = Color.White,
        )
    }
}

// ---------------------------------------------------------------------------
// Folder dialogs
// ---------------------------------------------------------------------------

@Composable
private fun FolderRenameDialog(
    initialName: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Name your folder") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Folder name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name.trim().ifBlank { "Folder" }) }) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun FolderContentsDialog(
    folder: QuickLaunchGridEntry.Folder,
    onLaunchApp: (String) -> Unit,
    onRemoveFromFolder: (String) -> Unit,
    onRename: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var isRenaming by remember { mutableStateOf(false) }
    var nameInput by remember(folder.name) { mutableStateOf(folder.name) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            if (isRenaming) {
                OutlinedTextField(
                    value = nameInput,
                    onValueChange = { nameInput = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(folder.name, modifier = Modifier.weight(1f))
                    IconButton(onClick = { isRenaming = true }) {
                        Icon(Icons.Default.Edit, contentDescription = "Rename folder")
                    }
                }
            }
        },
        text = {
            if (folder.apps.isEmpty()) {
                Text("No apps in this folder.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    folder.apps.forEach { app ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (app.icon != null) {
                                Image(
                                    painter = rememberDrawablePainter(app.icon),
                                    contentDescription = null,
                                    modifier = Modifier.size(32.dp),
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                            }
                            TextButton(
                                onClick = { onLaunchApp(app.packageName) },
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(
                                    text = app.label,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            TextButton(onClick = { onRemoveFromFolder(app.packageName) }) {
                                Text("Remove")
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (isRenaming) {
                TextButton(
                    onClick = {
                        onRename(nameInput.trim().ifBlank { folder.name })
                        isRenaming = false
                    },
                ) {
                    Text("Save")
                }
            } else {
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        },
        dismissButton = if (isRenaming) {
            { TextButton(onClick = { isRenaming = false; nameInput = folder.name }) { Text("Cancel") } }
        } else null,
    )
}

// ---------------------------------------------------------------------------
// Todo UI
// ---------------------------------------------------------------------------

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

// ---------------------------------------------------------------------------
// Utilities
// ---------------------------------------------------------------------------

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
