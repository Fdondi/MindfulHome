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
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
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
    private var quickLaunchFrameView: View? = null
    private var bubbleView: View? = null
    private var bubbleBadgeView: TextView? = null

    var onDismissed: (() -> Unit)? = null
    var onBubbleTapped: (() -> Unit)? = null

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

    fun showQuickLaunchFrame() {
        handler.post { showQuickLaunchFrameInternal() }
    }

    fun dismissQuickLaunchFrame() {
        handler.post { dismissQuickLaunchFrameInternal() }
    }

    fun isBubbleShowing(): Boolean = bubbleView != null

    fun showBubble(nudgeCount: Int) {
        handler.post { showBubbleInternal(nudgeCount) }
    }

    fun updateBubbleCount(nudgeCount: Int) {
        handler.post { updateBubbleCountInternal(nudgeCount) }
    }

    fun dismissBubble() {
        handler.post { dismissBubbleInternal() }
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

    private fun showQuickLaunchFrameInternal() {
        if (quickLaunchFrameView != null) return
        if (!canDrawOverlay()) return

        val dp = { value: Int ->
            TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value.toFloat(),
                context.resources.displayMetrics
            ).toInt()
        }

        val frame = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                setStroke(dp(2), Color.parseColor("#40FFFFFF"))
                cornerRadius = dp(18).toFloat()
            }
        }

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }

        try {
            windowManager.addView(frame, params)
            quickLaunchFrameView = frame
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add quick-launch frame overlay", e)
        }
    }

    private fun dismissQuickLaunchFrameInternal() {
        quickLaunchFrameView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to remove quick-launch frame overlay", e)
            }
        }
        quickLaunchFrameView = null
    }

    // ── Chat head bubble ────────────────────────────────────────────

    @Suppress("ClickableViewAccessibility")
    private fun showBubbleInternal(nudgeCount: Int) {
        if (bubbleView != null) {
            updateBubbleCountInternal(nudgeCount)
            return
        }
        if (!canDrawOverlay()) {
            Log.w(TAG, "Cannot draw bubble — overlay permission not granted")
            return
        }

        val dp = { value: Int ->
            TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value.toFloat(),
                context.resources.displayMetrics
            ).toInt()
        }

        val bubbleSize = dp(56)
        val badgeSize = dp(22)
        val containerSize = bubbleSize + badgeSize / 2

        val container = FrameLayout(context)

        val circle = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#E6212121"))
                setStroke(dp(3), Color.parseColor("#FFFFAB40"))
            }
            elevation = dp(6).toFloat()
        }

        val label = TextView(context).apply {
            text = "M"
            setTextColor(Color.parseColor("#FFFFAB40"))
            textSize = 24f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        circle.addView(label, FrameLayout.LayoutParams(bubbleSize, bubbleSize))

        val circleParams = FrameLayout.LayoutParams(bubbleSize, bubbleSize).apply {
            gravity = Gravity.BOTTOM or Gravity.START
        }
        container.addView(circle, circleParams)

        val badge = TextView(context).apply {
            text = if (nudgeCount > 9) "9+" else nudgeCount.toString()
            setTextColor(Color.WHITE)
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#FFE53935"))
            }
            elevation = dp(8).toFloat()
        }
        bubbleBadgeView = badge

        val badgeParams = FrameLayout.LayoutParams(badgeSize, badgeSize).apply {
            gravity = Gravity.TOP or Gravity.END
        }
        container.addView(badge, badgeParams)

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE

        val metrics = context.resources.displayMetrics
        val params = WindowManager.LayoutParams(
            containerSize,
            containerSize,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = metrics.widthPixels - containerSize - dp(8)
            y = metrics.heightPixels / 3
        }

        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDragging = false
        val dragThreshold = dp(10)

        container.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - initialTouchX
                    val dy = event.rawY - initialTouchY
                    if (dx * dx + dy * dy > dragThreshold * dragThreshold) {
                        isDragging = true
                    }
                    params.x = initialX + dx.toInt()
                    params.y = initialY + dy.toInt()
                    try {
                        windowManager.updateViewLayout(container, params)
                    } catch (_: Exception) {}
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging) {
                        onBubbleTapped?.invoke()
                    }
                    true
                }
                else -> false
            }
        }

        try {
            windowManager.addView(container, params)
            bubbleView = container
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add bubble overlay", e)
        }
    }

    private fun updateBubbleCountInternal(nudgeCount: Int) {
        bubbleBadgeView?.text = if (nudgeCount > 9) "9+" else nudgeCount.toString()
    }

    private fun dismissBubbleInternal() {
        bubbleView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to remove bubble overlay", e)
            }
        }
        bubbleView = null
        bubbleBadgeView = null
    }

    companion object {
        private const val TAG = "OverlayNudgeManager"
    }
}
