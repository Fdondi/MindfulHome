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
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.mindfulhome.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
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
    private val quickLaunchBorderViews = mutableListOf<View>()
    private var conversationBannerView: View? = null
    private var conversationBannerParams: WindowManager.LayoutParams? = null
    private var conversationBannerBodyView: TextView? = null
    private var conversationBannerReplyInput: EditText? = null
    private var awayShieldView: View? = null
    private var awayShieldPromptButton: TextView? = null
    private var awayShieldEscalationRunnable: Runnable? = null
    private val bubbleEntries = mutableListOf<BubbleEntry>()
    private var nextBubbleId = 1
    private var birdTickerRunning = false
    private var softDeadlineAtMs: Long? = null
    private var hardDeadlineAtMs: Long? = null
    private var lastBadgeRefreshSecond: Long = -1L

    var onDismissed: (() -> Unit)? = null
    var onNotificationRequested: (() -> Unit)? = null
    var onBannerReplySubmitted: ((String) -> Unit)? = null
    var onAwayShieldTapped: (() -> Unit)? = null
    var onAwayReturnRequested: (() -> Unit)? = null

    fun canDrawOverlay(): Boolean = Settings.canDrawOverlays(context)

    fun showQuickLaunchFrame() {
        handler.post { showQuickLaunchFrameInternal() }
    }

    fun dismissQuickLaunchFrame() {
        handler.post { dismissQuickLaunchFrameInternal() }
    }

    fun showBubble(nudgeCount: Int, isPredatory: Boolean = false) {
        handler.post { showBubbleInternal(nudgeCount, isPredatory) }
    }

    fun setDeadlineState(softDeadlineAtMs: Long?, hardDeadlineAtMs: Long?) {
        handler.post {
            this.softDeadlineAtMs = softDeadlineAtMs
            this.hardDeadlineAtMs = hardDeadlineAtMs
            refreshBubbleBadges(nowMs = System.currentTimeMillis(), force = true)
        }
    }

    fun updateConversationMessage(
        @Suppress("UNUSED_PARAMETER") message: String,
        @Suppress("UNUSED_PARAMETER") nudgeCount: Int,
    ) {
        handler.post {
            refreshBubbleBadges(nowMs = System.currentTimeMillis(), force = true)
        }
    }

    fun dismissAllNudges() {
        handler.post { dismissAllNudgesInternal() }
    }

    fun showConversationBanner(previewLines: List<String>) {
        handler.post { showConversationBannerInternal(previewLines) }
    }

    fun showAwayShield() {
        handler.post { showAwayShieldInternal() }
    }

    fun dismissAwayShield() {
        handler.post { dismissAwayShieldInternal() }
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
        if (quickLaunchBorderViews.isNotEmpty()) return
        if (!canDrawOverlay()) return

        val borderThickness = dp(4)
        val borderColor = Color.parseColor("#D0FF1A1A")
        val layoutType = overlayLayoutType()

        val edges = listOf(
            // Top
            Triple(
                View(context).apply { setBackgroundColor(borderColor) },
                WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    borderThickness,
                    layoutType,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT,
                ).apply {
                    gravity = Gravity.TOP or Gravity.START
                    x = 0
                    y = 0
                },
                "top",
            ),
            // Bottom
            Triple(
                View(context).apply { setBackgroundColor(borderColor) },
                WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    borderThickness,
                    layoutType,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT,
                ).apply {
                    gravity = Gravity.BOTTOM or Gravity.START
                    x = 0
                    y = 0
                },
                "bottom",
            ),
        )

        edges.forEach { (view, params, edgeName) ->
            try {
                windowManager.addView(view, params)
                quickLaunchBorderViews.add(view)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to add quick-launch $edgeName border overlay", e)
            }
        }
    }

    private fun dismissQuickLaunchFrameInternal() {
        quickLaunchBorderViews.forEach { borderView ->
            try {
                windowManager.removeView(borderView)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to remove quick-launch frame overlay", e)
            }
        }
        quickLaunchBorderViews.clear()
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
            text = "Tap field to type here, or tap title for notification"
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
                    setConversationBannerFocusable(false)
                    true
                } else {
                    false
                }
            }
            setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_DOWN) {
                    setConversationBannerFocusable(true, requestInputFocus = true)
                }
                false
            }
            setOnFocusChangeListener { _, hasFocus ->
                if (!hasFocus) {
                    setConversationBannerFocusable(false)
                }
            }
        }
        conversationBannerReplyInput = replyInput
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
                setConversationBannerFocusable(false)
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
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
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
            conversationBannerParams = params
            Log.d(TAG, "Conversation banner shown")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add conversation banner overlay", e)
        }
    }

    private fun showAwayShieldInternal() {
        if (awayShieldView != null) return
        if (!canDrawOverlay()) return

        val container = FrameLayout(context).apply {
            setBackgroundColor(Color.parseColor(AWAY_SHIELD_PASSIVE_COLOR))
            isClickable = true
            isFocusable = true
            // Passive phase: any tap means user is present, dismiss immediately.
            setOnClickListener {
                onAwayShieldTapped?.invoke()
                dismissAwayShieldInternal()
            }
        }

        val promptButton = TextView(context).apply {
            text = "you're back?"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(Color.parseColor("#E61F2937"))
                setStroke(dp(1), Color.parseColor("#66FFFFFF"))
            }
            setPadding(dp(20), dp(12), dp(20), dp(12))
            isClickable = true
            visibility = View.GONE
            setOnClickListener {
                onAwayReturnRequested?.invoke()
            }
        }

        container.addView(
            promptButton,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(28)
            },
        )

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayLayoutType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }

        try {
            windowManager.addView(container, params)
            awayShieldView = container
            awayShieldPromptButton = promptButton
            scheduleAwayShieldEscalation()
            Log.d(TAG, "Away shield overlay shown")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add away shield overlay", e)
        }
    }

    private fun scheduleAwayShieldEscalation() {
        awayShieldEscalationRunnable?.let { handler.removeCallbacks(it) }
        val runnable = Runnable {
            val activeView = awayShieldView as? FrameLayout ?: return@Runnable
            // Escalated phase: stronger dim + explicit confirmation button.
            activeView.setBackgroundColor(Color.parseColor(AWAY_SHIELD_ACTIVE_COLOR))
            activeView.setOnClickListener(null)
            activeView.setOnTouchListener { _, _ -> true }
            awayShieldPromptButton?.visibility = View.VISIBLE
            Log.d(TAG, "Away shield escalated to active mode")
        }
        awayShieldEscalationRunnable = runnable
        handler.postDelayed(runnable, AWAY_SHIELD_ESCALATION_DELAY_MS)
    }

    // ── Flying birds ────────────────────────────────────────────────

    @Suppress("ClickableViewAccessibility")
    private fun showBubbleInternal(nudgeCount: Int, isPredatory: Boolean) {
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

        val birdType = if (isPredatory) BirdType.PREDATORY else randomSmallBirdType()
        val badgeText = badgeTextForType(birdType, System.currentTimeMillis())

        val birdSize = dp(if (isPredatory) 82 else 56)
        val badgeWidth = dp(if (isPredatory) 84 else 56)
        val badgeHeight = dp(if (isPredatory) 24 else 18)
        val containerWidth = birdSize + badgeWidth / 2
        val containerHeight = birdSize + badgeHeight / 2

        val container = FrameLayout(context)

        val bird = ImageView(context).apply {
            setImageResource(birdDrawableResIdForType(birdType))
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            elevation = dp(6).toFloat()
            val birdPadding = dp(if (birdType == BirdType.PREDATORY) 4 else 6)
            setPadding(birdPadding, birdPadding, birdPadding, birdPadding)
            if (birdType == BirdType.PREDATORY) {
                // Let the custom predatory vector drive the form.
                rotation = -18f
                scaleX = 1.08f
                scaleY = 1.08f
            }
        }
        applyBirdTint(bird, birdType)

        val birdParams = FrameLayout.LayoutParams(birdSize, birdSize).apply {
            gravity = Gravity.BOTTOM or Gravity.START
        }
        container.addView(bird, birdParams)

        val badge = TextView(context).apply {
            text = badgeText
            setTextColor(Color.parseColor("#FF0F172A"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (birdType == BirdType.PREDATORY) 10f else 9f)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(9).toFloat()
                setColor(Color.parseColor("#1A0F172A"))
                setStroke(dp(1), Color.parseColor("#55334155"))
            }
            setPadding(dp(4), 0, dp(4), 0)
            elevation = dp(7).toFloat()
            if (birdType == BirdType.PREDATORY) {
                typeface = Typeface.DEFAULT_BOLD
            }
        }
        applyBadgeStyle(badge, birdType)
        val badgeParams = FrameLayout.LayoutParams(badgeWidth, badgeHeight).apply {
            gravity = Gravity.TOP or Gravity.END
        }
        container.addView(badge, badgeParams)

        val metrics = context.resources.displayMetrics
        val layoutType = overlayLayoutType()
        val params = WindowManager.LayoutParams(
            containerWidth,
            containerHeight,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            val xRangeMin = dp(8)
            val xRangeMax = (metrics.widthPixels - containerWidth - dp(8)).coerceAtLeast(xRangeMin)
            val yRangeMin = dp(60)
            val yRangeMax = (metrics.heightPixels - containerHeight - dp(120)).coerceAtLeast(yRangeMin)
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
                        Log.w(TAG, "Failed to update bird position while dragging", e)
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
                    bird = bird,
                    badge = badge,
                    birdType = birdType,
                    params = params,
                    minX = dp(8),
                    maxX = (metrics.widthPixels - containerWidth - dp(8)).coerceAtLeast(dp(8)),
                    minY = dp(60),
                    maxY = (metrics.heightPixels - containerHeight - dp(120)).coerceAtLeast(dp(60)),
                    velocityX = randomBirdVelocityPx(),
                    velocityY = randomBirdVelocityPx(),
                )
            )
            refreshBubbleBadges(nowMs = System.currentTimeMillis(), force = true)
            ensureBirdTicker()
            Log.d(
                TAG,
                "Bird added id=$id count=${bubbleEntries.size} x=${params.x} y=${params.y}"
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add bird overlay", e)
        }
    }

    private fun dismissAllNudgesInternal() {
        bubbleEntries.forEach { entry ->
            try {
                windowManager.removeView(entry.container)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to remove bird overlay", e)
            }
        }
        bubbleEntries.clear()
        birdTickerRunning = false
        softDeadlineAtMs = null
        hardDeadlineAtMs = null
        lastBadgeRefreshSecond = -1L
        dismissConversationBannerInternal()
        dismissAwayShieldInternal()
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

    private fun ensureBirdTicker() {
        if (birdTickerRunning) return
        birdTickerRunning = true
        handler.post(birdTicker)
    }

    private val birdTicker = object : Runnable {
        override fun run() {
            if (bubbleEntries.isEmpty()) {
                birdTickerRunning = false
                return
            }

            bubbleEntries.toList().forEach { entry ->
                var nextX = entry.params.x + entry.velocityX
                var nextY = entry.params.y + entry.velocityY

                if (nextX < entry.minX || nextX > entry.maxX) {
                    entry.velocityX = -entry.velocityX
                    nextX = nextX.coerceIn(entry.minX, entry.maxX)
                }
                if (nextY < entry.minY || nextY > entry.maxY) {
                    entry.velocityY = -entry.velocityY
                    nextY = nextY.coerceIn(entry.minY, entry.maxY)
                }

                entry.params.x = nextX
                entry.params.y = nextY
                try {
                    windowManager.updateViewLayout(entry.container, entry.params)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to animate bird #${entry.id}", e)
                }
            }

            refreshBubbleBadges(nowMs = System.currentTimeMillis())

            handler.postDelayed(this, BIRD_FRAME_DELAY_MS)
        }
    }

    private fun randomBirdVelocityPx(): Int {
        val speedPx = dp(Random.nextInt(MIN_BIRD_SPEED_DP, MAX_BIRD_SPEED_DP + 1))
        val direction = if (Random.nextBoolean()) 1 else -1
        return speedPx * direction
    }

    private fun birdDrawableResIdForType(type: BirdType): Int {
        return when (type) {
            BirdType.GREEN_NOW -> R.drawable.ic_nudge_bird
            BirdType.PURPLE_SOFT -> R.drawable.ic_nudge_bird_alt1
            BirdType.RED_HARD -> R.drawable.ic_nudge_bird_alt2
            BirdType.PREDATORY -> R.drawable.ic_nudge_bird_predatory
        }
    }

    private fun randomSmallBirdType(): BirdType {
        val types = arrayOf(BirdType.GREEN_NOW, BirdType.PURPLE_SOFT, BirdType.RED_HARD)
        return types[Random.nextInt(types.size)]
    }

    private fun formatBirdBadgeTime(timestampMs: Long): String {
        val formatter = SimpleDateFormat("HH:mm", Locale.getDefault())
        return formatter.format(Date(timestampMs))
    }

    private fun refreshBubbleBadges(nowMs: Long, force: Boolean = false) {
        val nowSecond = nowMs / 1000L
        if (!force && nowSecond == lastBadgeRefreshSecond) return
        lastBadgeRefreshSecond = nowSecond
        bubbleEntries.forEach { entry ->
            entry.badge.text = badgeTextForType(entry.birdType, nowMs)
        }
    }

    private fun badgeTextForType(type: BirdType, nowMs: Long): String {
        return when (type) {
            BirdType.GREEN_NOW -> formatBirdBadgeTime(nowMs)
            BirdType.PURPLE_SOFT -> {
                val softAt = softDeadlineAtMs
                if (softAt == null) {
                    "+0m"
                } else {
                    val deltaMs = (nowMs - softAt).coerceAtLeast(0L)
                    "+${deltaMs / 60_000L}m"
                }
            }
            BirdType.RED_HARD -> {
                val hardAt = hardDeadlineAtMs ?: return "hi"
                val diffMs = hardAt - nowMs
                val absMinutes = (abs(diffMs) + 59_999L) / 60_000L
                val sign = if (diffMs >= 0L) "-" else "+"
                "$sign${absMinutes}m"
            }
            BirdType.PREDATORY -> "-1 KARMA"
        }
    }

    private fun applyBirdTint(
        bird: ImageView,
        type: BirdType,
    ) {
        bird.clearColorFilter()
        if (type != BirdType.PREDATORY) {
            bird.background = null
        }
    }

    private fun applyBadgeStyle(badge: TextView, type: BirdType) {
        val (bgColor, strokeColor) = when (type) {
            BirdType.GREEN_NOW -> Color.parseColor("#DCFCE7") to Color.parseColor("#22C55E")
            BirdType.PURPLE_SOFT -> Color.parseColor("#F3E8FF") to Color.parseColor("#A855F7")
            BirdType.RED_HARD -> Color.parseColor("#FEE2E2") to Color.parseColor("#EF4444")
            BirdType.PREDATORY -> Color.parseColor("#FCA5A5") to Color.parseColor("#B91C1C")
        }
        badge.setTextColor(
            if (type == BirdType.PREDATORY) Color.WHITE else Color.parseColor("#FF0F172A")
        )
        val bg = (badge.background as? GradientDrawable) ?: return
        bg.setColor(bgColor)
        bg.setStroke(if (type == BirdType.PREDATORY) dp(2) else dp(1), strokeColor)
    }

    private fun withAlpha(color: Int, alphaFraction: Float): Int {
        val a = (alphaFraction.coerceIn(0f, 1f) * 255f).toInt()
        return Color.argb(a, Color.red(color), Color.green(color), Color.blue(color))
    }

    private fun dismissConversationBannerInternal() {
        setConversationBannerFocusable(false)
        conversationBannerView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to remove conversation banner overlay", e)
            }
        }
        conversationBannerView = null
        conversationBannerParams = null
        conversationBannerBodyView = null
        conversationBannerReplyInput = null
    }

    private fun dismissAwayShieldInternal() {
        awayShieldEscalationRunnable?.let { handler.removeCallbacks(it) }
        awayShieldEscalationRunnable = null
        awayShieldView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to remove away shield overlay", e)
            }
        }
        awayShieldView = null
        awayShieldPromptButton = null
    }

    private fun setConversationBannerFocusable(
        focusable: Boolean,
        requestInputFocus: Boolean = false,
    ) {
        val bannerView = conversationBannerView ?: return
        val params = conversationBannerParams ?: return
        val replyInput = conversationBannerReplyInput

        val currentlyFocusable =
            (params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) == 0
        if (currentlyFocusable == focusable && !requestInputFocus) return

        params.flags = if (focusable) {
            params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        } else {
            params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }

        try {
            windowManager.updateViewLayout(bannerView, params)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update conversation banner focus mode", e)
            return
        }

        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        if (focusable) {
            replyInput?.requestFocus()
            if (requestInputFocus) {
                replyInput?.post {
                    imm?.showSoftInput(replyInput, InputMethodManager.SHOW_IMPLICIT)
                }
            }
        } else {
            replyInput?.clearFocus()
            imm?.hideSoftInputFromWindow(replyInput?.windowToken, 0)
        }
    }

    private data class BubbleEntry(
        val id: Int,
        val container: View,
        val bird: ImageView,
        val badge: TextView,
        val birdType: BirdType,
        val params: WindowManager.LayoutParams,
        val minX: Int,
        val maxX: Int,
        val minY: Int,
        val maxY: Int,
        var velocityX: Int,
        var velocityY: Int,
    )

    private enum class BirdType {
        GREEN_NOW,
        PURPLE_SOFT,
        RED_HARD,
        PREDATORY,
    }

    companion object {
        private const val TAG = "OverlayNudgeManager"
        private const val AWAY_SHIELD_ESCALATION_DELAY_MS = 60_000L
        private const val AWAY_SHIELD_PASSIVE_COLOR = "#1A000000"
        private const val AWAY_SHIELD_ACTIVE_COLOR = "#33000000"
        // Lower overlay churn to reduce UI-thread load on slower devices/emulators.
        private const val BIRD_FRAME_DELAY_MS = 80L
        private const val MIN_BIRD_SPEED_DP = 1
        private const val MAX_BIRD_SPEED_DP = 2
    }
}
