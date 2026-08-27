package com.mindfulhome.ui.coachmark

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.mindfulhome.R
import com.mindfulhome.settings.SettingsManager
import io.luminos.CoachmarkConfig
import io.luminos.CoachmarkController
import io.luminos.CoachmarkHost
import io.luminos.CoachmarkState
import io.luminos.CoachmarkTarget
import io.luminos.HighlightAnimation
import io.luminos.LocalCoachmarkController
import io.luminos.ScrimTapBehavior
import io.luminos.coachmarkTarget
import io.luminos.rememberCoachmarkController
import kotlinx.coroutines.delay

fun Modifier.coachmarkTargetIf(
    controller: CoachmarkController?,
    id: String,
): Modifier = if (controller != null) this.coachmarkTarget(controller, id) else this

@Composable
fun ScreenCoachmarkHost(
    screen: CoachmarkScreen,
    steps: List<CoachmarkTarget>,
    content: @Composable (startTour: () -> Unit) -> Unit,
) {
    val context = LocalContext.current
    val controller = rememberCoachmarkController()
    val state by controller.state.collectAsState()
    var didAutoStart by remember { mutableStateOf(false) }
    val skipLabel = stringResource(R.string.coachmark_skip)
    val startTour: () -> Unit = {
        if (steps.isNotEmpty()) {
            controller.showSequence(steps)
        }
    }

    LaunchedEffect(screen, steps, state) {
        if (steps.isEmpty()) return@LaunchedEffect
        if (state !is CoachmarkState.Hidden) return@LaunchedEffect
        val pendingReplay = SettingsManager.takePendingCoachmarkReplay(context, screen.storageKey)
        if (!pendingReplay && didAutoStart) return@LaunchedEffect
        val done = SettingsManager.isCoachmarkTourDone(context, screen.storageKey)
        if (!shouldStartTour(done, alreadyShowing = false, pendingReplay)) return@LaunchedEffect
        delay(COACHMARK_AUTO_START_DELAY_MS)
        didAutoStart = true
        controller.showSequence(steps)
    }

    CoachmarkHost(
        controller = controller,
        config = CoachmarkConfig(
            showSkipButton = true,
            skipButtonText = skipLabel,
            delayBeforeShow = 300L,
            showTooltipCard = true,
            scrimTapBehavior = ScrimTapBehavior.ADVANCE,
            highlightAnimation = HighlightAnimation.PULSE,
        ),
        onDismiss = {
            SettingsManager.setCoachmarkTourDone(context, screen.storageKey, true)
        },
    ) {
        CompositionLocalProvider(LocalCoachmarkController provides controller) {
            content(startTour)
        }
    }
}
