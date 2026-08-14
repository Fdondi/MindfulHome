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
import com.mindfulhome.R

enum class TutorialTopic(val id: String) {
    WELCOME("welcome"),
    HOW_IT_WORKS("how_it_works"),
    APP_TIERS("app_tiers"),
    LAYOUT("layout"),
    TODO("todo"),
    AI_MODEL("ai_model"),
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
                    title = stringResource(topicTitleRes(topic)),
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
                    stringResource(topicTitleRes(topic)),
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
    when (topic) {
        TutorialTopic.WELCOME -> {
            Text(
                text = stringResource(R.string.a_home_launcher_that_helps_you_use_your_phone_mo),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TutorialTopic.HOW_IT_WORKS -> TutorialBulletList(R.array.onboarding_philosophy_bullets)
        TutorialTopic.APP_TIERS -> TutorialBulletList(R.array.onboarding_app_tiers_bullets)
        TutorialTopic.LAYOUT -> TutorialBulletList(R.array.onboarding_layout_bullets)
        TutorialTopic.TODO -> TutorialBulletList(R.array.onboarding_todo_bullets)
        TutorialTopic.AI_MODEL -> {
            Text(
                text = stringResource(R.string.onboarding_ai_model_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
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

private fun topicTitleRes(topic: TutorialTopic): Int = when (topic) {
    TutorialTopic.WELCOME -> R.string.welcome_to_mindfulhome
    TutorialTopic.HOW_IT_WORKS -> R.string.how_it_works
    TutorialTopic.APP_TIERS -> R.string.onboarding_app_tiers_title
    TutorialTopic.LAYOUT -> R.string.onboarding_layout_title
    TutorialTopic.TODO -> R.string.onboarding_todo_title
    TutorialTopic.AI_MODEL -> R.string.ai_model_options
}
