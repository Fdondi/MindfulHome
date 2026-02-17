package com.mindfulhome.ui.timer

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mindfulhome.logging.SessionLogger
private const val MAX_MINUTES = 120
private const val VISIBLE_ITEMS = 5
private const val ITEM_HEIGHT_DP = 64

@Composable
fun TimerScreen(
    onTimerSet: (minutes: Int) -> Unit,
    savedAppLabel: String? = null,
    savedMinutes: Int = 0,
    onResumeSession: (() -> Unit)? = null,
) {
    val items = (1..MAX_MINUTES).toList()
    val listState = rememberLazyListState()

    val selectedIndex by remember {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            val viewportCenter = layoutInfo.viewportStartOffset +
                    (layoutInfo.viewportEndOffset - layoutInfo.viewportStartOffset) / 2
            layoutInfo.visibleItemsInfo.minByOrNull {
                val itemCenter = it.offset + it.size / 2
                kotlin.math.abs(itemCenter - viewportCenter)
            }?.index ?: 0
        }
    }

    val selectedMinutes = items.getOrElse(selectedIndex) { 1 }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "How long do you want\nto use your phone?",
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onBackground
        )

        Spacer(modifier = Modifier.height(48.dp))

        Box(
            modifier = Modifier
                .height((ITEM_HEIGHT_DP * VISIBLE_ITEMS).dp)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            // Selection highlight
            Box(
                modifier = Modifier
                    .height(ITEM_HEIGHT_DP.dp)
                    .fillMaxWidth(0.6f)
                    .background(
                        MaterialTheme.colorScheme.primaryContainer,
                        MaterialTheme.shapes.medium
                    )
            )

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .height((ITEM_HEIGHT_DP * VISIBLE_ITEMS).dp)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                flingBehavior = rememberSnapFlingBehavior(lazyListState = listState),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    vertical = (ITEM_HEIGHT_DP * (VISIBLE_ITEMS / 2)).dp
                )
            ) {
                items(items.size) { index ->
                    val distanceFromCenter = kotlin.math.abs(index - selectedIndex)
                    val alphaValue by animateFloatAsState(
                        targetValue = when (distanceFromCenter) {
                            0 -> 1f
                            1 -> 0.6f
                            else -> 0.3f
                        },
                        label = "alpha"
                    )

                    Box(
                        modifier = Modifier
                            .height(ITEM_HEIGHT_DP.dp)
                            .fillMaxWidth()
                            .alpha(alphaValue),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = formatMinutes(items[index]),
                            fontSize = if (distanceFromCenter == 0) 32.sp else 22.sp,
                            fontWeight = if (distanceFromCenter == 0) FontWeight.Bold else FontWeight.Normal,
                            color = if (distanceFromCenter == 0) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onBackground
                            }
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(48.dp))

        Button(
            onClick = {
                SessionLogger.log("Timer set: **$selectedMinutes min**")
                onTimerSet(selectedMinutes)
            },
            modifier = Modifier
                .fillMaxWidth(0.6f)
                .height(56.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary
            ),
            shape = MaterialTheme.shapes.large
        ) {
            Text(
                text = "Start",
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold
            )
        }

        // Resume last session button
        if (savedAppLabel != null && onResumeSession != null && savedMinutes > 0) {
            Spacer(modifier = Modifier.height(24.dp))

            OutlinedButton(
                onClick = {
                    SessionLogger.log(
                        "Resumed previous session: **$savedAppLabel** ($savedMinutes min)"
                    )
                    onResumeSession()
                },
                modifier = Modifier
                    .fillMaxWidth(0.8f)
                    .height(48.dp),
                shape = MaterialTheme.shapes.large
            ) {
                Text(
                    text = "Resume $savedAppLabel (${formatMinutes(savedMinutes)})",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

private fun formatMinutes(minutes: Int): String {
    return when {
        minutes < 60 -> "$minutes min"
        minutes % 60 == 0 -> "${minutes / 60} hr"
        else -> "${minutes / 60} hr ${minutes % 60} min"
    }
}
