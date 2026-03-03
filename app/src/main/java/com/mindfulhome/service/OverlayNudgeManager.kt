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
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.mindfulhome.R
import kotlin.random.Random

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
    private var quickLaunchFrameView: View? = null
    private var conversationBannerView: View? = null
    private var conversationBannerBodyView: TextView? = null
    private val bubbleEntries = mutableListOf<BubbleEntry>()
    private var nextBubbleId = 1

    var onDismissed: (() -> Unit)? = null
    var onNotificationRequested: (() -> Unit)? = null
    var onBannerReplySubmitted: ((String) -> Unit)? = null

    fun canDrawOverlay(): Boolean = Settings.canDrawOverlays(context)

    fun showQuickLaunchFrame() {
        handler.post { showQuickLaunchFrameInternal() }
    }

    fun dismissQuickLaunchFrame() {
        handler.post { dismissQuickLaunchFrameInternal() }
    }

    fun showBubble(nudgeCount: Int) {
        handler.post { showBubbleInternal(nudgeCount) }
    }

    fun updateConversationMessage(@Suppress("UNUSED_PARAMETER") message: String, nudgeCount: Int) {
        handler.post {
            bubbleEntries.forEach { it.badge.text = badgeText(nudgeCount) }
        }
    }

    fun dismissAllNudges() {
        handler.post { dismissAllNudgesInternal() }
    }

    fun showConversationBanner(previewLines: List<String>) {
        handler.post { showConversationBannerInternal(previewLines) }
    }

    /**
     * Clears currently visible nudges synchronously when already on main thread.
     * Returns true only when at least one bubble was actually removed.
     */
    fun dismissAllNudgesIfPresent(): Boolean {
        return if (Looper.myLooper() == Looper.getMainLooper()) {
            dismissAllNudgesInternalIfPresent()
        } else {
            handler.post { dismissAllNudgesInternal() }
            false
        }
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

    private fun showConversationBannerInternal(previewLines: List<String>) {
        if (!canDrawOverlay()) return

        val content = (previewLines.takeLast(3).ifEmpty {
            listOf("MindfulHome has a new message.")
        }).joinToString("\n")
        val existingBanner = conversationBannerView
        if (existingBanner != null) {
            conversationBannerBodyView?.text = content
            return
        }

        val container = FrameLayout(context).apply {
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(Color.parseColor("#EE1D1F24"))
                setStroke(dp(1), Color.parseColor("#55FFFFFF"))
            }
            setPadding(dp(14), dp(12), dp(14), dp(12))
            isClickable = true
            isFocusable = false
            setOnClickListener {
                onNotificationRequested?.invoke()
            }
        }

        val title = TextView(context).apply {
            text = "MindfulHome conversation"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.DEFAULT_BOLD
        }
        val body = TextView(context).apply {
            text = content
            setTextColor(Color.parseColor("#FFF3F4F6"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            maxLines = 4
        }
        conversationBannerBodyView = body
        val footer = TextView(context).apply {
            text = "Use Send to reply, or tap title for notification"
            setTextColor(Color.parseColor("#FFFFCC80"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        }
        val replyInput = EditText(context).apply {
            hint = "Reply to MindfulHome..."
            setHintTextColor(Color.parseColor("#99FFFFFF"))
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(Color.parseColor("#402B2D34"))
                setStroke(dp(1), Color.parseColor("#44FFFFFF"))
            }
            setPadding(dp(10), dp(8), dp(10), dp(8))
            maxLines = 3
            imeOptions = EditorInfo.IME_ACTION_SEND
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEND) {
                    val payload = text?.toString()?.trim().orEmpty()
                    if (payload.isNotBlank()) {
                        onBannerReplySubmitted?.invoke(payload)
                        setText("")
                    }
                    true
                } else {
                    false
                }
            }
        }
        val sendButton = TextView(context).apply {
            text = "Send"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(Color.parseColor("#FF5C6BC0"))
            }
            setPadding(dp(14), dp(8), dp(14), dp(8))
            isClickable = true
            setOnClickListener {
                val payload = replyInput.text?.toString()?.trim().orEmpty()
                if (payload.isBlank()) return@setOnClickListener
                onBannerReplySubmitted?.invoke(payload)
                replyInput.setText("")
            }
        }
        val composerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            val gap = dp(8)
            setPadding(0, gap, 0, 0)
            addView(
                replyInput,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
            )
            addView(
                sendButton,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
        }

        card.addView(
            title,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        card.addView(
            body,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        card.addView(
            footer,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        card.addView(
            composerRow,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )

        container.addView(
            card,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            ),
        )

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayLayoutType(),
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = dp(12)
        }

        try {
            windowManager.addView(container, params)
            conversationBannerView = container
            Log.d(TAG, "Conversation banner shown")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add conversation banner overlay", e)
        }
    }

    // ── Chat head bubble ────────────────────────────────────────────

    @Suppress("ClickableViewAccessibility")
    private fun showBubbleInternal(nudgeCount: Int) {
        Log.d(
            TAG,
            "showBubbleInternal requested nudgeCount=$nudgeCount existing=${bubbleEntries.size}"
        )
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

        // Grow bubble radius by 5% at every nudge.
        val growthFactor = 1f + ((nudgeCount - 1).coerceAtLeast(0) * 0.05f)
        val bubbleSize = (dp(56) * growthFactor).toInt().coerceAtLeast(dp(56))
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

        val logo = ImageView(context).apply {
            setImageResource(R.drawable.ic_launcher_foreground)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        circle.addView(logo, FrameLayout.LayoutParams(bubbleSize, bubbleSize))

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
        val badgeParams = FrameLayout.LayoutParams(badgeSize, badgeSize).apply {
            gravity = Gravity.TOP or Gravity.END
        }
        container.addView(badge, badgeParams)

        val metrics = context.resources.displayMetrics
        val layoutType = overlayLayoutType()
        val params = WindowManager.LayoutParams(
            containerSize,
            containerSize,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            val xRangeMin = dp(8)
            val xRangeMax = (metrics.widthPixels - containerSize - dp(8)).coerceAtLeast(xRangeMin)
            val yRangeMin = dp(60)
            val yRangeMax = (metrics.heightPixels - containerSize - dp(120)).coerceAtLeast(yRangeMin)
            x = if (xRangeMax > xRangeMin) Random.nextInt(xRangeMin, xRangeMax + 1) else xRangeMin
            y = if (yRangeMax > yRangeMin) Random.nextInt(yRangeMin, yRangeMax + 1) else yRangeMin
            val attemptOffset = bubbleEntries.size * dp(14)
            x = (x + attemptOffset).coerceAtMost(xRangeMax)
            y = (y + attemptOffset).coerceAtMost(yRangeMax)
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
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to update bubble position while dragging", e)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging) {
                        onNotificationRequested?.invoke()
                    }
                    true
                }
                else -> false
            }
        }

        try {
            windowManager.addView(container, params)
            val id = nextBubbleId++
            bubbleEntries.add(
                BubbleEntry(
                    id = id,
                    container = container,
                    badge = badge,
                )
            )
            Log.d(
                TAG,
                "Bubble added id=$id count=${bubbleEntries.size} size=$bubbleSize x=${params.x} y=${params.y}"
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add bubble overlay", e)
        }
    }

    private fun dismissAllNudgesInternal() {
        bubbleEntries.forEach { entry ->
            try {
                windowManager.removeView(entry.container)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to remove bubble overlay", e)
            }
        }
        bubbleEntries.clear()
        dismissConversationBannerInternal()
    }

    private fun dismissAllNudgesInternalIfPresent(): Boolean {
        if (bubbleEntries.isEmpty()) return false
        dismissAllNudgesInternal()
        return true
    }

    private fun dp(value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            context.resources.displayMetrics,
        ).toInt()
    }

    private fun overlayLayoutType(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
    }

    private fun badgeText(nudgeCount: Int): String {
        return if (nudgeCount > 9) "9+" else nudgeCount.toString()
    }

    private fun dismissConversationBannerInternal() {
        conversationBannerView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to remove conversation banner overlay", e)
            }
        }
        conversationBannerView = null
        conversationBannerBodyView = null
    }

    private data class BubbleEntry(
        val id: Int,
        val container: View,
        val badge: TextView,
    )

    companion object {
        private const val TAG = "OverlayNudgeManager"
    }
}
