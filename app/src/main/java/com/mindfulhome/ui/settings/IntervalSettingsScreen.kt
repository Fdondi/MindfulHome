package com.mindfulhome.ui.settings
import androidx.compose.ui.res.stringResource

import com.mindfulhome.R

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
import androidx.compose.runtime.mutableFloatStateOf
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
    var positiveKarmaMultiplier by remember {
        mutableFloatStateOf(SettingsManager.getQuickLaunchSemaphoreKarmaPositiveMultiplier(context))
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
        positiveKarmaMultiplier = SettingsManager.getQuickLaunchSemaphoreKarmaPositiveMultiplier(context)
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
            title = { Text(stringResource(R.string.timing_intervals), fontWeight = FontWeight.SemiBold) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
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
                text = stringResource(R.string.larger_steps_use_less_cpu_and_battery_values_inc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(12.dp))

            LongOptionCard(
                title = stringResource(R.string.quick_launch_polling),
                description = stringResource(R.string.how_often_mindfulhome_checks_which_app_is_in_the),
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
                title = stringResource(R.string.quick_launch_semaphore_per_color),
                description = "Time for each green, yellow, and red border phase before you are sent back to the timer " +
                    "(three phases total). Range 20s–2min.",
                options = SettingsManager.QUICK_LAUNCH_SEMAPHORE_PHASE_MS_OPTIONS,
                selected = quickLaunchSemaphorePhaseMs,
                onSelect = {
                    quickLaunchSemaphorePhaseMs = it
                    SettingsManager.setQuickLaunchSemaphorePhaseNormalMs(context, it)
                },
                labelFor = ::formatMsCompact,
            )

            Spacer(modifier = Modifier.height(8.dp))

            FloatOptionCard(
                title = stringResource(R.string.positive_karma_multiplier),
                description = stringResource(R.string.apps_with_positive_karma_get_extra_time_during_q),
                options = SettingsManager.QUICK_LAUNCH_SEMAPHORE_KARMA_POSITIVE_MULTIPLIER_OPTIONS,
                selected = positiveKarmaMultiplier,
                onSelect = {
                    positiveKarmaMultiplier = it
                    SettingsManager.setQuickLaunchSemaphoreKarmaPositiveMultiplier(context, it)
                },
                labelFor = { "${it}x" },
            )

            Spacer(modifier = Modifier.height(8.dp))

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.negative_karma_grace_divisor),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = "Each zero or negative karma point divides the total Quick Launch grace " +
                            "(green + yellow + red) by |karma|, minimum divisor 1. " +
                            "With a 60s grace: karma 0/-1 → 60s, -2 → 30s, -3 → 20s, -10 → 6s. " +
                            "Switching via recents no longer resets the clock.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            LongOptionCard(
                title = stringResource(R.string.usage_stats_cache),
                description = stringResource(R.string.how_long_to_reuse_a_foreground_app_result_before),
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
                title = stringResource(R.string.nudge_loop_tick),
                description = stringResource(R.string.how_often_the_service_wakes_to_check_idle_away_s),
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
                title = stringResource(R.string.timer_countdown_notification),
                description = stringResource(R.string.how_often_the_foreground_timer_notification_is_r),
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
                text = stringResource(R.string.nudge_timing),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(8.dp))

            IntOptionCard(
                title = stringResource(R.string.delay_before_bubbles),
                description = stringResource(R.string.after_the_first_notification_wait_this_long_befo),
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
                title = stringResource(R.string.bubble_interval),
                description = stringResource(R.string.time_between_new_floating_bubbles),
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
                title = stringResource(R.string.banner_interval),
                description = stringResource(R.string.how_often_full_width_banners_are_spawned),
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
                title = stringResource(R.string.typing_pause_timeout),
                description = stringResource(R.string.while_typing_or_shortly_after_nudge_timers_pause),
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
                title = stringResource(R.string.notification_interaction_watch),
                description = stringResource(R.string.after_tapping_a_bubble_wait_this_long_for_intera),
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

@Composable
private fun FloatOptionCard(
    title: String,
    description: String,
    options: FloatArray,
    selected: Float,
    onSelect: (Float) -> Unit,
    labelFor: (Float) -> String,
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
                        selected = kotlin.math.abs(option - selected) < 0.001f,
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
