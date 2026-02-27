package com.mindfulhome.service

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Manages a floating overlay that appears on top of other apps when the
 * session timer has expired. Uses the traditional View system because
 * Compose is not available in a Service context.
 *
 * The overlay is non-focusable so the user can still interact with the
 * underlying app, but provides a persistent visual nudge.
 */
class OverlayNudgeManager(private val context: Context) {

    private val handler = Handler(Looper.getMainLooper())
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var overlayView: View? = null
    private var messageView: TextView? = null

    var onDismissed: (() -> Unit)? = null

    fun canDrawOverlay(): Boolean = Settings.canDrawOverlays(context)

    fun isShowing(): Boolean = overlayView != null

    fun show(message: String) {
        handler.post { showInternal(message) }
    }

    fun update(message: String) {
        handler.post {
            if (overlayView != null) {
                messageView?.text = message
            } else {
                showInternal(message)
            }
        }
    }

    fun dismiss() {
        handler.post { dismissInternal() }
    }

    private fun showInternal(message: String) {
        if (overlayView != null) {
            messageView?.text = message
            return
        }
        if (!canDrawOverlay()) {
            Log.w(TAG, "Cannot draw overlay — permission not granted")
            return
        }

        val dp = { value: Int ->
            TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value.toFloat(),
                context.resources.displayMetrics
            ).toInt()
        }

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(16))
            val bg = GradientDrawable().apply {
                setColor(Color.parseColor("#E6212121"))
                cornerRadius = dp(16).toFloat()
            }
            background = bg
            elevation = dp(8).toFloat()
        }

        val title = TextView(context).apply {
            text = "MindfulHome"
            setTextColor(Color.parseColor("#FFFFAB40"))
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
        }
        card.addView(title)

        val msg = TextView(context).apply {
            text = message
            setTextColor(Color.WHITE)
            textSize = 15f
            setPadding(0, dp(6), 0, dp(8))
        }
        messageView = msg
        card.addView(msg)

        val btn = TextView(context).apply {
            text = "I'm done"
            setTextColor(Color.parseColor("#FF81C784"))
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(16), dp(8), dp(16), dp(8))
            val btnBg = GradientDrawable().apply {
                setColor(Color.parseColor("#33FFFFFF"))
                cornerRadius = dp(8).toFloat()
            }
            background = btnBg
            gravity = Gravity.CENTER
            setOnClickListener {
                dismissInternal()
                onDismissed?.invoke()
            }
        }
        val btnParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.END }
        card.addView(btn, btnParams)

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            x = 0
            y = dp(48)
        }

        try {
            windowManager.addView(card, params)
            overlayView = card
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add overlay view", e)
        }
    }

    private fun dismissInternal() {
        overlayView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to remove overlay view", e)
            }
        }
        overlayView = null
        messageView = null
    }

    companion object {
        private const val TAG = "OverlayNudgeManager"
    }
}
