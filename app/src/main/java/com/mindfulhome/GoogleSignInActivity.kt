package com.mindfulhome

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ProgressBar
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.mindfulhome.ai.backend.AuthManager
import com.mindfulhome.ai.backend.persistSignedInGoogleAccount
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch

/**
 * Hosts Credential Manager / GIS HiddenActivity off the HOME [MainActivity] task.
 *
 * MainActivity is `singleTask` + `stateNotNeeded` (required for a default launcher).
 * On Android 10, Play Services sign-in starts a HiddenActivity in that task; when
 * the user adds a Google account, the system recreates HOME and the HiddenActivity
 * token dies, which Credential Manager reports as user cancellation.
 */
class GoogleSignInActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(centeredProgressBar())
        if (savedInstanceState != null) {
            finish()
            return
        }
        startSignIn()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) completePending(Result.success(null))
    }

    private fun centeredProgressBar(): FrameLayout {
        val size = (48 * resources.displayMetrics.density).toInt()
        val bar = ProgressBar(this).apply {
            isIndeterminate = true
            layoutParams = FrameLayout.LayoutParams(size, size, Gravity.CENTER)
        }
        return FrameLayout(this).apply { addView(bar) }
    }

    private fun startSignIn() {
        val forcePicker = intent.getBooleanExtra(EXTRA_FORCE_PICKER, false)
        lifecycleScope.launch {
            try {
                completePending(Result.success(signInAndPersist(forcePicker)))
                finish()
            } catch (e: CancellationException) {
                completePending(Result.success(null))
                throw e
            } catch (e: Exception) {
                completePending(Result.failure(e))
                finish()
            }
        }
    }

    private suspend fun signInAndPersist(forcePicker: Boolean): AuthManager.SignInResult? {
        val google = AuthManager.signInWithCredentialManager(this, forcePicker) ?: return null
        persistSignedInGoogleAccount(applicationContext, google)
        return google
    }

        companion object {
        private const val EXTRA_FORCE_PICKER = "force_account_picker"

        @Volatile
        private var pending: CompletableDeferred<Result<AuthManager.SignInResult?>>? = null

        fun start(context: Context, forceAccountPicker: Boolean = false) {
            startSignInActivity(context, forceAccountPicker)
        }

        suspend fun awaitSignIn(
            context: Context,
            forceAccountPicker: Boolean,
        ): AuthManager.SignInResult? {
            pending?.takeIf { it.isActive }?.let { return it.await().getOrThrow() }
            val deferred = CompletableDeferred<Result<AuthManager.SignInResult?>>()
            pending = deferred
            startSignInActivity(context, forceAccountPicker)
            return deferred.await().getOrThrow()
        }

        private fun startSignInActivity(context: Context, forceAccountPicker: Boolean) {
            val app = context.applicationContext
            app.startActivity(
                Intent(app, GoogleSignInActivity::class.java).apply {
                    putExtra(EXTRA_FORCE_PICKER, forceAccountPicker)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
            )
        }

        private fun completePending(result: Result<AuthManager.SignInResult?>) {
            pending?.takeIf { it.isActive }?.complete(result)
            pending = null
        }
    }
}
