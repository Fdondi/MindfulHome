package com.mindfulhome.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mindfulhome.R
import com.mindfulhome.locale.AppLanguage

/**
 * Language list. Fixed locales use [AppLanguage.nativeName]; System default uses a
 * translated label plus the currently resolved device language.
 */
@Composable
fun LanguagePickerList(
    selected: AppLanguage,
    onSelect: (AppLanguage) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        LanguagePickerRow(
            language = AppLanguage.SYSTEM,
            selected = selected,
            onSelect = onSelect,
        )
        AppLanguage.fixedEntries.forEach { language ->
            LanguagePickerRow(
                language = language,
                selected = selected,
                onSelect = onSelect,
            )
        }
    }
}

@Composable
private fun LanguagePickerRow(
    language: AppLanguage,
    selected: AppLanguage,
    onSelect: (AppLanguage) -> Unit,
) {
    val title = if (language == AppLanguage.SYSTEM) {
        stringResource(R.string.language_system_default)
    } else {
        language.nativeName
    }
    val subtitle = if (language == AppLanguage.SYSTEM) {
        language.resolve().nativeName
    } else {
        language.englishName
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelect(language) }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = language == selected,
            onClick = { onSelect(language) },
        )
        Column(modifier = Modifier.padding(start = 4.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Content only — parent ([OnboardingScreen]) already provides verticalScroll. */
@Composable
fun LanguagePickerStep(
    selected: AppLanguage,
    onSelect: (AppLanguage) -> Unit,
    onContinue: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.language_picker_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = stringResource(R.string.language_picker_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(24.dp))

        LanguagePickerList(
            selected = selected,
            onSelect = onSelect,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = onContinue,
            modifier = Modifier.fillMaxWidth(0.6f),
        ) {
            Text(stringResource(R.string.language_picker_continue))
        }
    }
}
