package com.mindfulhome.ai

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.mindfulhome.ai.backend.ApiKeyManager
import com.mindfulhome.ai.backend.BackendSignInOutcome
import com.mindfulhome.ai.backend.performBackendSignIn
import com.mindfulhome.settings.SettingsManager
import java.io.File

sealed class LocalModelSetupResult {
    data object Installed : LocalModelSetupResult()
    data class Failed(val licenseBlocked: Boolean) : LocalModelSetupResult()
}

suspend fun downloadAndEnableLocalModel(
    context: Context,
    modelsDir: File,
    onProgress: (Int) -> Unit,
): LocalModelSetupResult {
    val result = LocalModelDownloader.download(modelsDir, onProgress)
    return result.fold(
        onSuccess = {
            SettingsManager.setAIMode(context, AiMode.ON_DEVICE)
            LocalModelSetupResult.Installed
        },
        onFailure = { error ->
            val failure = error as? LocalModelDownloadFailure
            LocalModelSetupResult.Failed(
                licenseBlocked = failure?.statusCode?.let {
                    AiSetupLogic.isLicenseBlockedStatus(it)
                } == true,
            )
        },
    )
}

fun openLocalModelLicensePage(context: Context) {
    context.startActivity(
        Intent(Intent.ACTION_VIEW, Uri.parse(LocalModelDownloader.LICENSE_PAGE_URL)),
    )
}

suspend fun startGoogleAiSetup(context: Context): BackendSignInOutcome {
    SettingsManager.setPendingGoogleAiSetup(context, true)
    val outcome = performBackendSignIn(context)
    if (outcome is BackendSignInOutcome.Success) {
        SettingsManager.setPendingGoogleAiSetup(context, false)
        SettingsManager.setAIMode(context, AiMode.BACKEND)
    }
    return outcome
}

suspend fun consumeCompletedGoogleAiSetup(context: Context): Boolean {
    if (!SettingsManager.isPendingGoogleAiSetup(context)) return false
    if (!ApiKeyManager.isSignedIn(context)) {
        SettingsManager.setPendingGoogleAiSetup(context, false)
        return false
    }
    SettingsManager.setPendingGoogleAiSetup(context, false)
    SettingsManager.setAIMode(context, AiMode.BACKEND)
    return true
}
