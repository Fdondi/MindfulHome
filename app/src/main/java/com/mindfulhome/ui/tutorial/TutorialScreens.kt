package com.mindfulhome.ui.tutorial

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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.annotation.ArrayRes
import androidx.annotation.StringRes
import com.mindfulhome.R

enum class TutorialTopic(
    val id: String,
    @StringRes val titleRes: Int,
    @StringRes val paragraphRes: Int? = null,
    @ArrayRes val bulletsRes: Int? = null,
) {
    WELCOME(
        id = "welcome",
        titleRes = R.string.welcome_to_mindfulhome,
        paragraphRes = R.string.a_home_launcher_that_helps_you_use_your_phone_mo,
    ),
    HOW_IT_WORKS(
        id = "how_it_works",
        titleRes = R.string.how_it_works,
        bulletsRes = R.array.onboarding_philosophy_bullets,
    ),
    APP_TIERS(
        id = "app_tiers",
        titleRes = R.string.onboarding_app_tiers_title,
        bulletsRes = R.array.onboarding_app_tiers_bullets,
    ),
    LAYOUT(
        id = "layout",
        titleRes = R.string.onboarding_layout_title,
        bulletsRes = R.array.onboarding_layout_bullets,
    ),
    TODO(
        id = "todo",
        titleRes = R.string.onboarding_todo_title,
        bulletsRes = R.array.onboarding_todo_bullets,
    ),
    AI_MODEL(
        id = "ai_model",
        titleRes = R.string.ai_model_options,
        paragraphRes = R.string.onboarding_ai_model_body,
    ),
    ;

    companion object {
        fun fromId(id: String): TutorialTopic? = entries.find { it.id == id }
    }

    val previous: TutorialTopic?
        get() = entries.getOrNull(ordinal - 1)

    val next: TutorialTopic?
        get() = entries.getOrNull(ordinal + 1)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TutorialIndexScreen(
    onBack: () -> Unit,
    onOpenTopic: (TutorialTopic) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        TopAppBar(
            title = {
                Text(
                    stringResource(R.string.tutorial_title),
                    fontWeight = FontWeight.SemiBold,
                )
            },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.back),
                    )
                }
            },
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            Text(
                text = stringResource(R.string.tutorial_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 12.dp),
            )
            TutorialTopic.entries.forEachIndexed { index, topic ->
                if (index > 0) HorizontalDivider()
                TutorialIndexRow(
                    title = stringResource(topic.titleRes),
                    onClick = { onOpenTopic(topic) },
                )
            }
        }
    }
}

@Composable
private fun TutorialIndexRow(
    title: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f),
        )
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TutorialTopicScreen(
    topic: TutorialTopic,
    onBack: () -> Unit,
    onOpenTopic: (TutorialTopic) -> Unit,
) {
    val previous = topic.previous
    val next = topic.next
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        TopAppBar(
            title = {
                Text(
                    stringResource(topic.titleRes),
                    fontWeight = FontWeight.SemiBold,
                )
            },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.back),
                    )
                }
            },
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
        ) {
            TutorialTopicBody(topic = topic)
            Spacer(modifier = Modifier.height(24.dp))
        }
        HorizontalDivider()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                onClick = { previous?.let(onOpenTopic) },
                enabled = previous != null,
            ) {
                Text(stringResource(R.string.tutorial_previous))
            }
            Text(
                text = stringResource(
                    R.string.tutorial_page_of,
                    topic.ordinal + 1,
                    TutorialTopic.entries.size,
                ),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(
                onClick = { next?.let(onOpenTopic) },
                enabled = next != null,
            ) {
                Text(stringResource(R.string.tutorial_next))
            }
        }
    }
}

@Composable
private fun TutorialTopicBody(topic: TutorialTopic) {
    topic.paragraphRes?.let { res ->
        Text(
            text = stringResource(res),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    topic.bulletsRes?.let { TutorialBulletList(it) }
}

@Composable
private fun TutorialBulletList(bulletArrayRes: Int) {
    stringArrayResource(bulletArrayRes).forEach { point ->
        Text(
            text = "• $point",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp),
        )
    }
}
