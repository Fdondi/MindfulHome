package com.mindfulhome.ai

/**
 * Dumpsys / uiautomator probes for the Android 10 HOME-task GIS false-cancel reproduction.
 * Test-only; not shipped in the app. Keep in sync with the copy under src/androidTest.
 */
object PreUSignInProbe {

    fun logContainsCredentialCancellation(logcat: String): Boolean =
        logcat.contains("GetCredentialCancellationException") ||
            logcat.contains("activity is cancelled by the user")

    enum class Verdict {
        UiStillShowing,
        SignedIn,
        FalseUserCancel,
        EmptyGisUi,
        EmptyHostOnly,
        EndedWithoutUi,
    }

    fun verdict(
        cancellationLogged: Boolean,
        signInUiShowing: Boolean,
        emptyHostOnly: Boolean,
        gotCredential: Boolean,
        accountChooserVisible: Boolean,
    ): Verdict = when {
        gotCredential -> Verdict.SignedIn
        cancellationLogged -> Verdict.FalseUserCancel
        signInUiShowing && accountChooserVisible -> Verdict.UiStillShowing
        signInUiShowing -> Verdict.EmptyGisUi
        emptyHostOnly -> Verdict.EmptyHostOnly
        else -> Verdict.EndedWithoutUi
    }

    fun windowDumpShowsAccountChooser(xml: String): Boolean {
        val markers = listOf(
            "Choose an account",
            "Add another account",
            "Add account",
            "account_name",
            "com.google.android.gms:id/account",
        )
        return markers.any { xml.contains(it, ignoreCase = true) }
    }

    fun dumpsysShowsGoogleIdPicker(dump: String): Boolean {
        val markers = listOf(
            "SignInHubActivity",
            "com.google.android.gms.auth.api.signin",
            "com.google.android.gms/.auth.api.signin",
            "assistedsignin.ui.GoogleSignInActivity",
            "com.google.android.gms/.auth.api.credentials",
        )
        return markers.any { dump.contains(it) }
    }

    fun dumpsysShowsEmptySignInHost(dump: String): Boolean {
        val ourHost = dump.contains("com.mindfulhome/.ai.GoogleSignInActivity") ||
            dump.contains("com.mindfulhome.ai.GoogleSignInActivity")
        return ourHost && !dumpsysShowsGoogleIdPicker(dump)
    }
}
