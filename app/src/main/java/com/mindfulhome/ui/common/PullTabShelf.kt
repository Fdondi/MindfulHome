package com.mindfulhome.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun PullTabShelf(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    showBodyWhenCollapsed: Boolean,
    modifier: Modifier = Modifier,
    contentDescriptionExpand: String,
    contentDescriptionCollapse: String,
    overlayContent: (@Composable BoxScope.() -> Unit)? = null,
    body: @Composable BoxScope.() -> Unit,
) {
    val bodyVisible = expanded || showBodyWhenCollapsed
    Box(modifier = modifier) {
        if (bodyVisible) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = if (expanded) 14.dp else 0.dp)
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .padding(PaddingValues(horizontal = 8.dp, vertical = 4.dp))
            ) {
                body()
                overlayContent?.invoke(this)
            }
        }

        Box(
            modifier = Modifier
                .align(if (bodyVisible) Alignment.TopEnd else Alignment.BottomEnd)
                .offset(x = 10.dp)
                .offset(y = if (bodyVisible) (-14).dp else 0.dp)
                .size(width = 88.dp, height = 56.dp)
                .background(
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    shape = RoundedCornerShape(12.dp)
                )
                .clickable { onExpandedChange(!expanded) },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (expanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp,
                contentDescription = if (expanded) contentDescriptionCollapse else contentDescriptionExpand,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
