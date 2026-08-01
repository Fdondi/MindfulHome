package com.mindfulhome.ui.quicklaunch

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.accompanist.drawablepainter.rememberDrawablePainter
import com.mindfulhome.model.AppInfo
import kotlin.math.roundToInt

internal class FolderDragHost(
    val appCoords: MutableMap<String, LayoutCoordinates>,
    val getDraggingPackage: () -> String?,
    val setDraggingPackage: (String?) -> Unit,
    val getLastPointerInRoot: () -> Offset,
    val setLastPointerInRoot: (Offset) -> Unit,
    val getRemoveZoneBounds: () -> Rect?,
    val getSecondaryDropZoneBounds: () -> Rect?,
    val setHoveringRemove: (Boolean) -> Unit,
    val setHoveringSecondary: (Boolean) -> Unit,
    val onUpdateHover: () -> Unit,
    val onDropResolved: (AppInfo, FolderDropAction) -> Unit,
    val findApp: (String) -> AppInfo?,
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun FolderAppCell(
    app: AppInfo,
    apps: List<AppInfo>,
    minCellWidth: Dp,
    limitMinutes: Int?,
    isDraggingThis: Boolean,
    dragHost: FolderDragHost,
    onLaunchApp: (AppInfo) -> Unit,
) {
    val pkg = app.packageName
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(minCellWidth)
            .pointerInput(pkg, apps) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { startLocal ->
                        handleFolderDragStart(pkg, startLocal, dragHost)
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        handleFolderDrag(pkg, change.position, dragHost)
                    },
                    onDragCancel = { handleFolderDragCancel(dragHost) },
                    onDragEnd = { handleFolderDragEnd(dragHost) },
                )
            }
            .combinedClickable(
                onClick = {
                    if (dragHost.getDraggingPackage() != null) return@combinedClickable
                    onLaunchApp(app)
                },
            ),
    ) {
        Box(
            modifier = Modifier.onGloballyPositioned { coords ->
                dragHost.appCoords[pkg] = coords
                if (dragHost.getDraggingPackage() != null) dragHost.onUpdateHover()
            },
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(modifier = Modifier.size(42.dp)) {
                    if (app.icon != null) {
                        Image(
                            painter = rememberDrawablePainter(app.icon),
                            contentDescription = app.label,
                            modifier = Modifier
                                .size(42.dp)
                                .then(if (isDraggingThis) Modifier.alpha(0.22f) else Modifier),
                        )
                    }
                    if (limitMinutes != null) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .offset(x = (-3).dp, y = (-3).dp)
                                .background(Color.Red, RoundedCornerShape(3.dp))
                                .padding(horizontal = 2.dp, vertical = 1.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = formatFolderAppLimitBadge(limitMinutes),
                                color = Color.White,
                                fontSize = 8.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = app.label,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

internal fun handleFolderDragStart(pkg: String, startLocal: Offset, dragHost: FolderDragHost) {
    dragHost.setDraggingPackage(pkg)
    val coords = dragHost.appCoords[pkg]
    dragHost.setLastPointerInRoot(coords?.localToRoot(startLocal) ?: Offset.Zero)
    dragHost.onUpdateHover()
}

internal fun handleFolderDrag(pkg: String, position: Offset, dragHost: FolderDragHost) {
    val coords = dragHost.appCoords[pkg]
    if (coords != null) {
        dragHost.setLastPointerInRoot(coords.localToRoot(position))
    }
    dragHost.onUpdateHover()
}

internal fun handleFolderDragCancel(dragHost: FolderDragHost) {
    dragHost.setDraggingPackage(null)
    dragHost.setHoveringRemove(false)
    dragHost.setHoveringSecondary(false)
}

internal fun handleFolderDragEnd(dragHost: FolderDragHost) {
    val draggedPkg = dragHost.getDraggingPackage() ?: return
    val droppedApp = dragHost.findApp(draggedPkg)
    dragHost.setDraggingPackage(null)
    dragHost.setHoveringRemove(false)
    dragHost.setHoveringSecondary(false)
    if (droppedApp == null) return
    val action = resolveFolderDrop(
        dragHost.getLastPointerInRoot(),
        dragHost.getRemoveZoneBounds(),
        dragHost.getSecondaryDropZoneBounds(),
    )
    dragHost.onDropResolved(droppedApp, action)
}

@Composable
internal fun FolderDropZones(
    dragHintText: String,
    removeDropContentDescription: String,
    useEditTimerDrop: Boolean,
    hoveringSecondary: Boolean,
    hoveringRemove: Boolean,
    onSecondaryBounds: (Rect) -> Unit,
    onRemoveBounds: (Rect) -> Unit,
    onBoundsUpdated: () -> Unit,
) {
    Text(
        text = dragHintText,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.onGloballyPositioned { coords ->
                onSecondaryBounds(
                    Rect(
                        coords.positionInRoot(),
                        Size(coords.size.width.toFloat(), coords.size.height.toFloat()),
                    ),
                )
                onBoundsUpdated()
            },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(
                        if (hoveringSecondary) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                        CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (useEditTimerDrop) {
                    FolderDropSecondaryIcon(
                        hovering = hoveringSecondary,
                        timer = true,
                    )
                } else {
                    FolderDropSecondaryIcon(
                        hovering = hoveringSecondary,
                        timer = false,
                    )
                }
            }
        }
        Box(
            modifier = Modifier.onGloballyPositioned { coords ->
                onRemoveBounds(
                    Rect(
                        coords.positionInRoot(),
                        Size(coords.size.width.toFloat(), coords.size.height.toFloat()),
                    ),
                )
                onBoundsUpdated()
            },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(
                        if (hoveringRemove) Color.Red else MaterialTheme.colorScheme.surfaceVariant,
                        CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = removeDropContentDescription,
                    tint = if (hoveringRemove) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun FolderDropSecondaryIcon(hovering: Boolean, timer: Boolean) {
    val tint = if (hovering) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    if (timer) {
        Icon(Icons.Outlined.Timer, contentDescription = "Drop to edit timer", tint = tint)
    } else {
        Icon(
            Icons.AutoMirrored.Filled.ArrowForward,
            contentDescription = "Drop to move out of folder",
            tint = tint,
        )
    }
}

@Composable
internal fun FolderDragGhost(
    draggedApp: AppInfo,
    pointerInRoot: Offset,
    bodyRoot: Offset,
) {
    val rel = pointerInRoot - bodyRoot - Offset(30f, 30f)
    Box(
        Modifier.offset { IntOffset(rel.x.roundToInt(), rel.y.roundToInt()) },
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .background(
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.22f),
                    RoundedCornerShape(12.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (draggedApp.icon != null) {
                Image(
                    painter = rememberDrawablePainter(draggedApp.icon),
                    contentDescription = draggedApp.label,
                    modifier = Modifier.size(36.dp),
                )
            }
        }
    }
}
