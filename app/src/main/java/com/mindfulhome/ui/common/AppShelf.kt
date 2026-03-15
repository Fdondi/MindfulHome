package com.mindfulhome.ui.common

import android.graphics.drawable.Drawable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.toSize
import com.google.accompanist.drawablepainter.rememberDrawablePainter
import kotlin.math.ceil

private val SHELF_CELL_MIN_WIDTH = 64.dp
private val SHELF_ROW_HEIGHT = 72.dp
private val SHELF_MAX_EXPANDED_HEIGHT = 220.dp
private val SHELF_TILE_ICON_SIZE = 34.dp
private val SHELF_TILE_TEXT_WIDTH = 60.dp
private val SHELF_TILE_TEXT_SIZE = 8.sp
private val SHELF_TILE_PADDING = PaddingValues(horizontal = 2.dp, vertical = 2.dp)
private val SHELF_GRID_PADDING = PaddingValues(4.dp)
private val SHELF_TILE_HIGHLIGHT_SHAPE = RoundedCornerShape(10.dp)

data class AppShelfEntry(
    val key: String,
    val label: String,
    val icon: Drawable?,
    val isHighlighted: Boolean = false,
    val onClick: () -> Unit,
    val onLongClick: (() -> Unit)? = null,
    val onPositioned: ((topLeftX: Float, topLeftY: Float, width: Float, height: Float) -> Unit)? = null,
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AppShelf(
    entries: List<AppShelfEntry>,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    collapsedRows: Int,
    showBodyWhenCollapsed: Boolean,
    onAddClick: () -> Unit,
    addContentDescription: String,
    contentDescriptionExpand: String,
    contentDescriptionCollapse: String,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
) {
    PullTabShelf(
        expanded = expanded,
        onExpandedChange = onExpandedChange,
        showBodyWhenCollapsed = showBodyWhenCollapsed,
        modifier = modifier.background(containerColor),
        contentDescriptionExpand = contentDescriptionExpand,
        contentDescriptionCollapse = contentDescriptionCollapse,
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val columns = (maxWidth / SHELF_CELL_MIN_WIDTH).toInt().coerceAtLeast(1)
            val gridItemCount = entries.size + 1
            val totalRows = ceil(gridItemCount.toDouble() / columns.toDouble()).toInt().coerceAtLeast(1)
            val visibleRows = if (expanded) totalRows else collapsedRows.coerceAtLeast(0)
            val targetHeight = (SHELF_ROW_HEIGHT * visibleRows).coerceAtMost(SHELF_MAX_EXPANDED_HEIGHT)

            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = SHELF_CELL_MIN_WIDTH),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(targetHeight),
                contentPadding = SHELF_GRID_PADDING,
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                items(
                    items = entries,
                    key = { it.key }
                ) { entry ->
                    ShelfEntryItem(entry = entry)
                }
                item(key = "shared_add_tile") {
                    AddAppTile(
                        onClick = onAddClick,
                        contentDescription = addContentDescription,
                        iconSize = SHELF_TILE_ICON_SIZE,
                        textSize = SHELF_TILE_TEXT_SIZE,
                        textWidth = SHELF_TILE_TEXT_WIDTH,
                        modifier = Modifier.padding(SHELF_TILE_PADDING),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ShelfEntryItem(entry: AppShelfEntry) {
    val interactionModifier = if (entry.onLongClick != null) {
        Modifier.combinedClickable(
            onClick = entry.onClick,
            onLongClick = entry.onLongClick
        )
    } else {
        Modifier.clickable(onClick = entry.onClick)
    }

    Column(
        modifier = interactionModifier
            .onGloballyPositioned { coords ->
                val callback = entry.onPositioned ?: return@onGloballyPositioned
                val pos = coords.positionInRoot()
                val size = coords.size.toSize()
                callback(pos.x, pos.y, size.width, size.height)
            }
            .background(
                color = if (entry.isHighlighted) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                } else {
                    Color.Transparent
                },
                shape = SHELF_TILE_HIGHLIGHT_SHAPE
            )
            .padding(SHELF_TILE_PADDING),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (entry.icon != null) {
            Image(
                painter = rememberDrawablePainter(drawable = entry.icon),
                contentDescription = entry.label,
                modifier = Modifier.size(SHELF_TILE_ICON_SIZE)
            )
        }
        Text(
            text = entry.label,
            fontSize = SHELF_TILE_TEXT_SIZE,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.width(SHELF_TILE_TEXT_WIDTH)
        )
    }
}
