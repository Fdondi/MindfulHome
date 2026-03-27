package com.mindfulhome.ui.icons

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mindfulhome.R

private fun codepointToString(cp: Int): String = String(Character.toChars(cp))

/** Single Material Icons glyph at [size] (e.g. open-folder header: symbol only, no folder outline). */
@Composable
fun MaterialSymbolGlyph(
    symbolIconName: String,
    modifier: Modifier = Modifier,
    size: Dp = 42.dp,
    tint: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.primary,
    contentDescription: String,
) {
    val context = LocalContext.current
    val fontFamily = remember {
        FontFamily(Font(R.font.material_icons_outlined))
    }
    val cp = remember(symbolIconName) {
        MaterialIconCatalog.codepoint(context, symbolIconName)
    }
    val semanticsMod = if (contentDescription.isNotBlank()) {
        Modifier.semantics { this.contentDescription = contentDescription }
    } else {
        Modifier.clearAndSetSemantics { }
    }
    if (cp == null) return
    Box(
        modifier
            .size(size)
            .then(semanticsMod),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = codepointToString(cp),
            fontFamily = fontFamily,
            fontSize = (size.value * 0.95f).sp,
            color = tint,
        )
    }
}

/**
 * Outlined folder glyph with an optional Material Icons badge (snake_case names match fonts.google.com).
 * The badge uses ~60% of the folder’s linear size so it reads clearly on the tile.
 */
@Composable
fun MaterialFolderWithSymbolOverlay(
    symbolIconName: String?,
    contentDescription: String,
    modifier: Modifier = Modifier,
    folderSize: Dp = 42.dp,
    tintOnFolder: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.primary,
    tintOnBadge: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.primary,
) {
    val context = LocalContext.current
    val fontFamily = remember {
        FontFamily(Font(R.font.material_icons_outlined))
    }
    val folderCp = remember {
        MaterialIconCatalog.codepoint(context, "folder")
    }
    val badgeCp = remember(symbolIconName) {
        symbolIconName?.let { MaterialIconCatalog.codepoint(context, it) }
    }
    val folderText = folderCp?.let { codepointToString(it) }
    val semanticsMod = if (contentDescription.isNotBlank()) {
        Modifier.semantics { this.contentDescription = contentDescription }
    } else {
        Modifier.clearAndSetSemantics { }
    }
    Box(
        modifier
            .size(folderSize)
            .then(semanticsMod),
        contentAlignment = Alignment.Center,
    ) {
        if (folderText != null) {
            Text(
                text = folderText,
                fontFamily = fontFamily,
                fontSize = (folderSize.value * 0.95f).sp,
                color = tintOnFolder,
                modifier = Modifier.align(Alignment.Center),
            )
        }
        if (badgeCp != null && symbolIconName != null) {
            val badgeSp = (folderSize.value * 0.6f).sp
            Text(
                text = codepointToString(badgeCp),
                fontFamily = fontFamily,
                fontSize = badgeSp,
                color = tintOnBadge,
                modifier = Modifier.align(Alignment.BottomEnd),
            )
        }
    }
}
