package com.mindfulhome.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.mindfulhome.settings.SettingsManager

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IntervalSettingsScreen(
    onBack: () -> Unit,
) {
    val context = LocalContext.current

    var quickLaunchMs by remember {
        mutableLongStateOf(SettingsManager.getQuickLaunchMonitorMs(context))
    }
    var quickLaunchSemaphorePhaseMs by remember {
        mutableLongStateOf(SettingsManager.getQuickLaunchSemaphorePhaseNormalMs(context))
    }
    var cacheTtlMs by remember {
        mutableLongStateOf(SettingsManager.getUsageForegroundCacheTtlMs(context))
    }
    var nudgeLoopMs by remember {
        mutableLongStateOf(SettingsManager.getNudgeLoopTickMs(context))
    }
    var timerTickMs by remember {
        mutableLongStateOf(SettingsManager.getTimerCountdownTickMs(context))
    }
    var initialDelayMin by remember {
        mutableIntStateOf(SettingsManager.getNudgeInitialNotificationDelayMinutes(context))
    }
    var bubbleSec by remember {
        mutableIntStateOf(SettingsManager.getNudgeBubbleIntervalSeconds(context))
    }
    var bannerMin by remember {
        mutableIntStateOf(SettingsManager.getNudgeBannerIntervalMinutes(context))
    }
    var typingMin by remember {
        mutableIntStateOf(SettingsManager.getNudgeTypingIdleTimeoutMinutes(context))
    }
    var watchMin by remember {
        mutableIntStateOf(SettingsManager.getNudgeInteractionWatchTimeoutMinutes(context))
    }

    fun reloadFromPrefs() {
        quickLaunchMs = SettingsManager.getQuickLaunchMonitorMs(context)
        quickLaunchSemaphorePhaseMs = SettingsManager.getQuickLaunchSemaphorePhaseNormalMs(context)
        cacheTtlMs = SettingsManager.getUsageForegroundCacheTtlMs(context)
        nudgeLoopMs = SettingsManager.getNudgeLoopTickMs(context)
        timerTickMs = SettingsManager.getTimerCountdownTickMs(context)
        initialDelayMin = SettingsManager.getNudgeInitialNotificationDelayMinutes(context)
        bubbleSec = SettingsManager.getNudgeBubbleIntervalSeconds(context)
        bannerMin = SettingsManager.getNudgeBannerIntervalMinutes(context)
        typingMin = SettingsManager.getNudgeTypingIdleTimeoutMinutes(context)
        watchMin = SettingsManager.getNudgeInteractionWatchTimeoutMinutes(context)
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        reloadFromPrefs()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        TopAppBar(
            title = { Text("Timing & intervals", fontWeight = FontWeight.SemiBold) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            },
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            Text(
                text = "Larger steps use less CPU and battery. Values increase roughly exponentially.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(12.dp))

            LongOptionCard(
                title = "Quick Launch polling",
                description = "How often MindfulHome checks which app is in the foreground during Quick Launch.",
                options = SettingsManager.QUICK_LAUNCH_MONITOR_MS_OPTIONS,
                selected = quickLaunchMs,
                onSelect = {
                    quickLaunchMs = it
                    SettingsManager.setQuickLaunchMonitorMs(context, it)
                },
                labelFor = ::formatMsCompact,
            )

            Spacer(modifier = Modifier.height(8.dp))

            LongOptionCard(
                title = "Quick Launch semaphore (per color)",
                description = "Time for each green, yellow, and red border phase before you are sent back to the timer " +
                    "(three phases total). Apps with negative karma use half this time per phase. Range 20s–2min.",
                options = SettingsManager.QUICK_LAUNCH_SEMAPHORE_PHASE_MS_OPTIONS,
                selected = quickLaunchSemaphorePhaseMs,
                onSelect = {
                    quickLaunchSemaphorePhaseMs = it
                    SettingsManager.setQuickLaunchSemaphorePhaseNormalMs(context, it)
                },
                labelFor = ::formatMsCompact,
            )

            Spacer(modifier = Modifier.height(8.dp))

            LongOptionCard(
                title = "Usage stats cache",
                description = "How long to reuse a foreground-app result before querying usage stats again.",
                options = SettingsManager.USAGE_FOREGROUND_CACHE_TTL_MS_OPTIONS,
                selected = cacheTtlMs,
                onSelect = {
                    cacheTtlMs = it
                    SettingsManager.setUsageForegroundCacheTtlMs(context, it)
                },
                labelFor = ::formatMsCompact,
            )

            Spacer(modifier = Modifier.height(8.dp))

            LongOptionCard(
                title = "Nudge loop tick",
                description = "How often the service wakes to check idle/away state and advance nudge timing after the timer expires.",
                options = SettingsManager.NUDGE_LOOP_TICK_MS_OPTIONS,
                selected = nudgeLoopMs,
                onSelect = {
                    nudgeLoopMs = it
                    SettingsManager.setNudgeLoopTickMs(context, it)
                },
                labelFor = ::formatMsCompact,
            )

            Spacer(modifier = Modifier.height(8.dp))

            LongOptionCard(
                title = "Timer countdown notification",
                description = "How often the foreground timer notification is refreshed while counting down.",
                options = SettingsManager.TIMER_COUNTDOWN_TICK_MS_OPTIONS,
                selected = timerTickMs,
                onSelect = {
                    timerTickMs = it
                    SettingsManager.setTimerCountdownTickMs(context, it)
                },
                labelFor = ::formatMsCompact,
            )

            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Nudge timing",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(8.dp))

            IntOptionCard(
                title = "Delay before bubbles",
                description = "After the first notification, wait this long before floating bubbles.",
                options = SettingsManager.NUDGE_INITIAL_NOTIFICATION_DELAY_MINUTES_OPTIONS,
                selected = initialDelayMin,
                onSelect = {
                    initialDelayMin = it
                    SettingsManager.setNudgeInitialNotificationDelayMinutes(context, it)
                },
                labelFor = { m -> if (m == 0) "0 min (immediate)" else "$m min" },
            )

            Spacer(modifier = Modifier.height(8.dp))

            IntOptionCard(
                title = "Bubble interval",
                description = "Time between new floating bubbles.",
                options = SettingsManager.NUDGE_BUBBLE_INTERVAL_SECONDS_OPTIONS,
                selected = bubbleSec,
                onSelect = {
                    bubbleSec = it
                    SettingsManager.setNudgeBubbleIntervalSeconds(context, it)
                },
                labelFor = { s -> "${s}s" },
            )

            Spacer(modifier = Modifier.height(8.dp))

            IntOptionCard(
                title = "Banner interval",
                description = "How often full-width banners are spawned.",
                options = SettingsManager.NUDGE_BANNER_INTERVAL_MINUTES_OPTIONS,
                selected = bannerMin,
                onSelect = {
                    bannerMin = it
                    SettingsManager.setNudgeBannerIntervalMinutes(context, it)
                },
                labelFor = { m -> "$m min" },
            )

            Spacer(modifier = Modifier.height(8.dp))

            IntOptionCard(
                title = "Typing pause timeout",
                description = "While typing (or shortly after), nudge timers pause.",
                options = SettingsManager.NUDGE_TYPING_IDLE_TIMEOUT_MINUTES_OPTIONS,
                selected = typingMin,
                onSelect = {
                    typingMin = it
                    SettingsManager.setNudgeTypingIdleTimeoutMinutes(context, it)
                },
                labelFor = { m -> "$m min" },
            )

            Spacer(modifier = Modifier.height(8.dp))

            IntOptionCard(
                title = "Notification interaction watch",
                description = "After tapping a bubble, wait this long for interaction before arming banner fallback.",
                options = SettingsManager.NUDGE_INTERACTION_WATCH_TIMEOUT_MINUTES_OPTIONS,
                selected = watchMin,
                onSelect = {
                    watchMin = it
                    SettingsManager.setNudgeInteractionWatchTimeoutMinutes(context, it)
                },
                labelFor = { m -> "$m min" },
            )
        }
    }
}

@Composable
private fun LongOptionCard(
    title: String,
    description: String,
    options: LongArray,
    selected: Long,
    onSelect: (Long) -> Unit,
    labelFor: (Long) -> String,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            options.forEach { option ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(option) }
                        .padding(top = 6.dp),
                ) {
                    RadioButton(
                        selected = option == selected,
                        onClick = { onSelect(option) },
                    )
                    Text(
                        text = labelFor(option),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun IntOptionCard(
    title: String,
    description: String,
    options: IntArray,
    selected: Int,
    onSelect: (Int) -> Unit,
    labelFor: (Int) -> String,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            options.forEach { option ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(option) }
                        .padding(top = 6.dp),
                ) {
                    RadioButton(
                        selected = option == selected,
                        onClick = { onSelect(option) },
                    )
                    Text(
                        text = labelFor(option),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
            }
        }
    }
}

private fun formatMsCompact(ms: Long): String {
    if (ms >= 60_000L && ms % 60_000L == 0L) {
        val m = ms / 60_000L
        return "$m min"
    }
    return "${ms / 1000L} s"
}
