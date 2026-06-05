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

private data class ResolvedSymbol(val text: String, val useMaterialFont: Boolean)

private fun resolveSymbolText(symbolToken: String?, context: android.content.Context): ResolvedSymbol? {
    val token = symbolToken?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val cp = MaterialIconCatalog.codepoint(context, token)
    return if (cp != null) {
        ResolvedSymbol(codepointToString(cp), useMaterialFont = true)
    } else {
        ResolvedSymbol(token, useMaterialFont = false)
    }
}

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
    val symbolText = remember(symbolIconName) { resolveSymbolText(symbolIconName, context) }
    val semanticsMod = if (contentDescription.isNotBlank()) {
        Modifier.semantics { this.contentDescription = contentDescription }
    } else {
        Modifier.clearAndSetSemantics { }
    }
    if (symbolText == null) return
    Box(
        modifier
            .size(size)
            .then(semanticsMod),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = symbolText.text,
            fontFamily = if (symbolText.useMaterialFont) fontFamily else null,
            fontSize = (size.value * 0.95f).sp,
            color = tint,
        )
    }
}

/** Material symbol glyph only — no generic folder outline. */
@Composable
fun MaterialFolderWithSymbolOverlay(
    symbolIconName: String?,
    contentDescription: String,
    modifier: Modifier = Modifier,
    folderSize: Dp = 42.dp,
    tintOnFolder: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.primary,
    tintOnBadge: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.primary,
) {
    val token = symbolIconName?.trim()?.takeIf { it.isNotEmpty() } ?: return
    MaterialSymbolGlyph(
        symbolIconName = token,
        modifier = modifier,
        size = folderSize,
        tint = tintOnBadge,
        contentDescription = contentDescription,
    )
}
