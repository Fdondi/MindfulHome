package com.mindfulhome.ui.ai

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mindfulhome.R
import com.mindfulhome.ai.openLocalModelLicensePage

@Composable
fun LocalModelDownloadStatus(downloadPercent: Int, licenseBlocked: Boolean) {
    val context = LocalContext.current
    if (downloadPercent >= 0) {
        Spacer(modifier = Modifier.height(8.dp))
        LinearProgressIndicator(
            progress = { downloadPercent / 100f },
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = stringResource(R.string.onboarding_ai_downloading, downloadPercent),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (!licenseBlocked) return
    TextButton(onClick = { openLocalModelLicensePage(context) }) {
        Text(stringResource(R.string.onboarding_ai_open_huggingface))
    }
}
