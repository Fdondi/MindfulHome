package com.mindfulhome.ui.icons

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.mindfulhome.R

private fun codepointToString(cp: Int): String = String(Character.toChars(cp))

/**
 * Search and pick a Material Icons Outlined symbol (same catalog as fonts.google.com).
 * Tap an icon to apply and close, or use OK / Clear symbol / Cancel at the bottom.
 * [onConfirm] receives `null` to clear the folder badge.
 */
@Composable
fun MaterialSymbolPickerDialog(
    initialSelection: String?,
    onDismiss: () -> Unit,
    onConfirm: (symbolIconName: String?) -> Unit,
) {
    val context = LocalContext.current
    val fontFamily = remember {
        FontFamily(Font(R.font.material_icons_outlined))
    }
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(initialSelection) }

    val filtered = remember(query, context) {
        MaterialIconCatalog.filterNames(context, query)
    }

    LaunchedEffect(initialSelection) {
        selected = initialSelection
    }

    val screenHeightPx = LocalConfiguration.current.screenHeightDp
    val dialogHeight = (screenHeightPx * 0.88f).dp.coerceIn(320.dp, 560.dp)

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            tonalElevation = 4.dp,
            modifier = Modifier
                .fillMaxWidth()
                .height(dialogHeight),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
            ) {
                Text(
                    text = "Folder symbol",
                    style = MaterialTheme.typography.titleLarge,
                )
                Spacer(modifier = Modifier.size(8.dp))
                Text(
                    text = "Search Material Icons (snake_case, e.g. flight_takeoff, sms). Shown on the folder tile.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.size(8.dp))
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Search icons") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.size(8.dp))
                LazyVerticalGrid(
                    columns = GridCells.Fixed(4),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                ) {
                    items(filtered, key = { it }) { name ->
                        val cp = MaterialIconCatalog.codepoint(context, name) ?: return@items
                        val isSelected = selected == name
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .border(
                                    width = if (isSelected) 2.dp else 1.dp,
                                    color = if (isSelected) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
                                    },
                                    shape = RoundedCornerShape(10.dp),
                                )
                                .background(
                                    if (isSelected) {
                                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                                    } else {
                                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)
                                    },
                                    RoundedCornerShape(10.dp),
                                )
                                .clickable {
                                    selected = name
                                    onConfirm(name)
                                    onDismiss()
                                }
                                .padding(vertical = 6.dp, horizontal = 4.dp),
                        ) {
                            Text(
                                text = codepointToString(cp),
                                fontFamily = fontFamily,
                                fontSize = 28.sp,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = name,
                                maxLines = 2,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.size(12.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        onClick = {
                            onConfirm(null)
                            onDismiss()
                        },
                    ) {
                        Text("Clear symbol")
                    }
                    TextButton(onClick = onDismiss) {
                        Text("Cancel")
                    }
                    TextButton(
                        onClick = {
                            onConfirm(selected)
                            onDismiss()
                        },
                    ) {
                        Text("OK")
                    }
                }
            }
        }
    }
}
