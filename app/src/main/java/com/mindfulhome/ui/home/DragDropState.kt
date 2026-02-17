package com.mindfulhome.ui.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size

const val MAX_DOCK_SLOTS = 5
private const val FOLDER_CREATE_THRESHOLD_MS = 600L

class DragDropState {
    var draggedItem: HomeGridItem? by mutableStateOf(null)
        private set
    var overlayOffset: Offset by mutableStateOf(Offset.Zero)
        private set
    var isDragging: Boolean by mutableStateOf(false)
        private set
    var hoverTarget: DropTarget by mutableStateOf(DropTarget.None)
        private set
    var isLongHover: Boolean by mutableStateOf(false)
        private set

    private var fingerPosition: Offset = Offset.Zero
    private var grabOffset: Offset = Offset.Zero
    private var hoverOnKey: String? = null
    private var hoverStartMs: Long = 0L

    private val itemBounds = mutableMapOf<String, Rect>()
    var dockBounds: Rect by mutableStateOf(Rect.Zero)

    fun registerItemBounds(key: String, topLeft: Offset, size: Size) {
        itemBounds[key] = Rect(topLeft, size)
    }

    fun startDrag(item: HomeGridItem, itemTopLeft: Offset, localTouchOffset: Offset) {
        draggedItem = item
        grabOffset = localTouchOffset
        fingerPosition = itemTopLeft + localTouchOffset
        overlayOffset = itemTopLeft
        isDragging = true
    }

    fun updateDrag(delta: Offset) {
        fingerPosition += delta
        overlayOffset = fingerPosition - grabOffset

        val newTarget = findTargetAt(fingerPosition)

        when (newTarget) {
            is DropTarget.OnItem -> {
                if (newTarget.key != hoverOnKey) {
                    hoverOnKey = newTarget.key
                    hoverStartMs = System.currentTimeMillis()
                    isLongHover = false
                } else {
                    isLongHover =
                        (System.currentTimeMillis() - hoverStartMs) >= FOLDER_CREATE_THRESHOLD_MS
                }
            }
            else -> {
                hoverOnKey = null
                hoverStartMs = 0L
                isLongHover = false
            }
        }

        hoverTarget = newTarget
    }

    fun endDrag(): DropResult {
        val result = DropResult(hoverTarget, isLongHover)
        reset()
        return result
    }

    fun cancelDrag() {
        reset()
    }

    private fun reset() {
        draggedItem = null
        overlayOffset = Offset.Zero
        isDragging = false
        hoverTarget = DropTarget.None
        isLongHover = false
        fingerPosition = Offset.Zero
        grabOffset = Offset.Zero
        hoverOnKey = null
        hoverStartMs = 0L
    }

    private fun findTargetAt(position: Offset): DropTarget {
        if (dockBounds.contains(position)) {
            return DropTarget.Dock
        }
        val draggedKey = draggedItem?.key ?: return DropTarget.None
        for ((key, bounds) in itemBounds) {
            if (key != draggedKey && bounds.contains(position)) {
                return DropTarget.OnItem(key)
            }
        }
        return DropTarget.None
    }
}

sealed class DropTarget {
    data object None : DropTarget()
    data object Dock : DropTarget()
    data class OnItem(val key: String) : DropTarget()
}

data class DropResult(
    val target: DropTarget,
    val wasLongHover: Boolean
)

@Composable
fun rememberDragDropState(): DragDropState {
    return remember { DragDropState() }
}
