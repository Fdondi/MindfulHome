package com.mindfulhome.ai

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.mindfulhome.ai.backend.backendSignInOutcomeMessage
import com.mindfulhome.ai.backend.runInteractiveSignInOnHost
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Hosts Credential Manager / GIS HiddenActivity off the HOME [com.mindfulhome.MainActivity] task.
 *
 * MainActivity is `singleTask` + `stateNotNeeded` (required for a default launcher).
 * On Android 10, Play Services sign-in starts a HiddenActivity in that task; when
 * the user adds a Google account, the system recreates HOME and the HiddenActivity
 * token dies, which Credential Manager reports as user cancellation.
 *
 * Callers start this activity and observe durable session state on resume.
 * Persistence happens here once; there is no process-global result deferred.
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
                val outcome = runInteractiveSignInOnHost(this@GoogleSignInActivity, forcePicker)
                backendSignInOutcomeMessage(this@GoogleSignInActivity, outcome)?.let { message ->
                    Toast.makeText(this@GoogleSignInActivity, message, Toast.LENGTH_LONG).show()
                }
            } catch (e: CancellationException) {
                throw e
            } finally {
                finish()
            }
        }
    }

    companion object {
        private const val EXTRA_FORCE_PICKER = "force_account_picker"

        fun start(context: Context, forceAccountPicker: Boolean = false) {
            val app = context.applicationContext
            app.startActivity(
                Intent(app, GoogleSignInActivity::class.java).apply {
                    putExtra(EXTRA_FORCE_PICKER, forceAccountPicker)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
            )
        }
    }
}
