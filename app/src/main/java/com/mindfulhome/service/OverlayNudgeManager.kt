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
import com.mindfulhome.locale.LocaleHelper
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

    private fun locString(id: Int, vararg formatArgs: Any): String {
        val localized = LocaleHelper.wrap(context)
        return if (formatArgs.isEmpty()) {
            localized.getString(id)
        } else {
            localized.getString(id, *formatArgs)
        }
    }
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
    private var transientToastView: View? = null
    private var transientToastHideRunnable: Runnable? = null

    var onDismissed: (() -> Unit)? = null
    var onNotificationRequested: (() -> Unit)? = null
    var onBannerReplySubmitted: ((String) -> Unit)? = null
    var onBannerReplyFocusChanged: ((Boolean) -> Unit)? = null
    var onAwayShieldTapped: (() -> Unit)? = null
    var onAwayReturnRequested: (() -> Unit)? = null

    fun canDrawOverlay(): Boolean = Settings.canDrawOverlays(context)

    /** Semi-transparent overlay toast (non-focusable) for nudge grace expiry and similar. */
    fun showTransientToast(message: String, durationMs: Long = 3_500L) {
        handler.post { showTransientToastInternal(message, durationMs) }
    }

    private fun showTransientToastInternal(message: String, durationMs: Long) {
        if (!canDrawOverlay()) return
        dismissTransientToastInternal()
        val dp = { value: Int ->
            TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value.toFloat(),
                context.resources.displayMetrics,
            ).toInt()
        }
        val text = TextView(context).apply {
            this.text = message
            setTextColor(Color.parseColor("#F8FAFC"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            gravity = Gravity.CENTER
            setPadding(dp(20), dp(12), dp(20), dp(12))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(12).toFloat()
                setColor(Color.parseColor("#990F172A"))
            }
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayLayoutType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = dp(96)
        }
        try {
            windowManager.addView(text, params)
            transientToastView = text
            val hide = Runnable { dismissTransientToastInternal() }
            transientToastHideRunnable = hide
            handler.postDelayed(hide, durationMs)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show transient toast", e)
        }
    }

    private fun dismissTransientToastInternal() {
        transientToastHideRunnable?.let { handler.removeCallbacks(it) }
        transientToastHideRunnable = null
        transientToastView?.let { view ->
            try {
                windowManager.removeView(view)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to remove transient toast", e)
            }
        }
        transientToastView = null
    }

    fun showQuickLaunchFrame(level: QuickLaunchFrameLevel = QuickLaunchFrameLevel.RED) {
        handler.post { showQuickLaunchFrameInternal(level) }
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

    private fun showQuickLaunchFrameInternal(level: QuickLaunchFrameLevel) {
        if (!canDrawOverlay()) return
        val borderColor = quickLaunchFrameColor(level)
        if (quickLaunchBorderViews.isNotEmpty()) {
            quickLaunchBorderViews.forEach { it.setBackgroundColor(borderColor) }
            return
        }
        addQuickLaunchBorderEdges(borderColor, dp(4), overlayLayoutType())
    }

    private fun addQuickLaunchBorderEdges(
        borderColor: Int,
        borderThickness: Int,
        layoutType: Int,
    ) {
        OverlayNudgeLogic.quickLaunchBorderEdges().forEach { edge ->
            val view = View(context).apply { setBackgroundColor(borderColor) }
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                borderThickness,
                layoutType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = edge.gravity
                x = 0
                y = 0
            }
            try {
                windowManager.addView(view, params)
                quickLaunchBorderViews.add(view)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to add quick-launch ${edge.name} border overlay", e)
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
        val content = OverlayNudgeLogic.formatBannerPreviewText(previewLines)
        if (OverlayNudgeLogic.shouldRefreshExistingBanner(conversationBannerView != null)) {
            conversationBannerBodyView?.text = content
            return
        }
        val container = FrameLayout(context).apply {
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        val card = buildConversationBannerCard(content)
        container.addView(
            card,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        attachConversationBanner(container)
    }

    private fun buildConversationBannerCard(content: String): LinearLayout {
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
            setOnClickListener { onNotificationRequested?.invoke() }
        }
        val body = TextView(context).apply {
            text = content
            setTextColor(Color.parseColor("#FFF3F4F6"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            maxLines = 4
        }
        conversationBannerBodyView = body
        val replyInput = buildConversationBannerReplyInput()
        conversationBannerReplyInput = replyInput
        card.addView(buildBannerTitleView(), matchParentWrap())
        card.addView(body, matchParentWrap())
        card.addView(buildBannerFooterView(), matchParentWrap())
        card.addView(buildBannerComposerRow(replyInput), matchParentWrap())
        return card
    }

    private fun matchParentWrap() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT,
    )

    private fun buildBannerTitleView(): TextView = TextView(context).apply {
        text = OverlayNudgeLogic.conversationBannerTitle(context)
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        typeface = Typeface.DEFAULT_BOLD
    }

    private fun buildBannerFooterView(): TextView = TextView(context).apply {
        text = OverlayNudgeLogic.conversationBannerFooter(context)
        setTextColor(Color.parseColor("#FFFFCC80"))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
    }

    private fun buildConversationBannerReplyInput(): EditText = EditText(context).apply {
        hint = locString(R.string.notif_banner_reply_hint)
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
            handleBannerEditorAction(actionId, this)
        }
        setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                setConversationBannerFocusable(true, requestInputFocus = true)
            }
            false
        }
        setOnFocusChangeListener { _, hasFocus ->
            onBannerReplyFocusChanged?.invoke(hasFocus)
            if (!hasFocus) setConversationBannerFocusable(false)
        }
    }

    private fun handleBannerEditorAction(actionId: Int, input: EditText): Boolean {
        if (actionId != EditorInfo.IME_ACTION_SEND) return false
        val payload = input.text?.toString()?.trim().orEmpty()
        if (payload.isNotBlank()) {
            onBannerReplySubmitted?.invoke(payload)
            input.setText("")
        }
        // Keep focus so conversation grace continues while the user is still composing.
        return true
    }

    private fun buildBannerComposerRow(replyInput: EditText): LinearLayout {
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
            setOnClickListener { submitBannerReply(replyInput) }
        }
        return LinearLayout(context).apply {
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
    }

    private fun submitBannerReply(replyInput: EditText) {
        val payload = replyInput.text?.toString()?.trim().orEmpty()
        if (payload.isBlank()) return
        onBannerReplySubmitted?.invoke(payload)
        replyInput.setText("")
        // Keep focus so conversation grace continues while the user is still composing.
    }

    private fun attachConversationBanner(container: FrameLayout) {
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
        if (OverlayNudgeLogic.shouldSkipAwayShieldShow(
                alreadyShowing = awayShieldView != null,
                canDraw = canDrawOverlay(),
            )
        ) {
            return
        }
        showAwayShieldViews()
    }

    private fun showAwayShieldViews() {
        val container = FrameLayout(context).apply {
            setBackgroundColor(Color.parseColor(AWAY_SHIELD_PASSIVE_COLOR))
            isClickable = true
            isFocusable = true
            setOnClickListener {
                onAwayShieldTapped?.invoke()
                dismissAwayShieldInternal()
            }
        }
        val promptButton = buildAwayShieldPromptButton()
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

    private fun buildAwayShieldPromptButton(): TextView = TextView(context).apply {
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
        setOnClickListener { onAwayReturnRequested?.invoke() }
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

        val birdType = if (isPredatory) NudgeBirdType.PREDATORY else randomSmallBirdType()
        val badgeText = badgeTextForType(birdType, System.currentTimeMillis())
        val sizeLayout = OverlayNudgeLogic.bubbleSizeLayout(isPredatory, dp)
        val birdSize = sizeLayout.birdSizePx
        val badgeWidth = sizeLayout.badgeWidthPx
        val badgeHeight = sizeLayout.badgeHeightPx
        val containerWidth = sizeLayout.containerWidthPx
        val containerHeight = sizeLayout.containerHeightPx

        val container = FrameLayout(context)
        val bird = buildBirdImageView(birdType, birdSize, dp)
        container.addView(
            bird,
            FrameLayout.LayoutParams(birdSize, birdSize).apply {
                gravity = Gravity.BOTTOM or Gravity.START
            },
        )
        val badge = buildBirdBadgeView(birdType, badgeText, badgeWidth, badgeHeight, dp)
        container.addView(
            badge,
            FrameLayout.LayoutParams(badgeWidth, badgeHeight).apply {
                gravity = Gravity.TOP or Gravity.END
            },
        )

        val metrics = context.resources.displayMetrics
        val spawn = OverlayNudgeLogic.clampSpawnPosition(
            ranges = OverlayNudgeLogic.computeSpawnRanges(
                screenWidthPx = metrics.widthPixels,
                screenHeightPx = metrics.heightPixels,
                containerWidthPx = containerWidth,
                containerHeightPx = containerHeight,
                padXPx = dp(8),
                padYTopPx = dp(60),
                padYBottomPx = dp(120),
            ),
            attemptIndex = bubbleEntries.size,
            attemptOffsetStepPx = dp(14),
            nextIntInclusive = { from, toInclusive -> Random.nextInt(from, toInclusive + 1) },
        )
        val params = WindowManager.LayoutParams(
            containerWidth,
            containerHeight,
            overlayLayoutType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = spawn.x
            y = spawn.y
        }
        attachBubbleTouchListener(container, params, dragThresholdPx = dp(10))
        addBubbleToWindow(
            container = container,
            bird = bird,
            badge = badge,
            birdType = birdType,
            params = params,
            spawn = spawn,
        )
    }

    private fun addBubbleToWindow(
        container: FrameLayout,
        bird: ImageView,
        badge: TextView,
        birdType: NudgeBirdType,
        params: WindowManager.LayoutParams,
        spawn: BubbleSpawnPoint,
    ) {
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
                    minX = spawn.ranges.minX,
                    maxX = spawn.ranges.maxX,
                    minY = spawn.ranges.minY,
                    maxY = spawn.ranges.maxY,
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

    private fun buildBirdImageView(
        birdType: NudgeBirdType,
        birdSize: Int,
        dp: (Int) -> Int,
    ): ImageView {
        val bird = ImageView(context).apply {
            setImageResource(birdDrawableResIdForType(birdType))
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            elevation = dp(6).toFloat()
            val birdPadding = dp(OverlayNudgeLogic.birdPaddingDp(birdType))
            setPadding(birdPadding, birdPadding, birdPadding, birdPadding)
            if (OverlayNudgeLogic.isPredatoryBird(birdType)) {
                rotation = -18f
                scaleX = 1.08f
                scaleY = 1.08f
            }
        }
        applyBirdTint(bird, birdType)
        return bird
    }

    private fun buildBirdBadgeView(
        birdType: NudgeBirdType,
        badgeText: String,
        badgeWidth: Int,
        badgeHeight: Int,
        dp: (Int) -> Int,
    ): TextView {
        val badge = TextView(context).apply {
            text = badgeText
            setTextColor(Color.parseColor("#FF0F172A"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, OverlayNudgeLogic.badgeTextSizeSp(birdType))
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(9).toFloat()
                setColor(Color.parseColor("#1A0F172A"))
                setStroke(dp(1), Color.parseColor("#55334155"))
            }
            setPadding(dp(4), 0, dp(4), 0)
            elevation = dp(7).toFloat()
            if (OverlayNudgeLogic.isPredatoryBird(birdType)) {
                typeface = Typeface.DEFAULT_BOLD
            }
        }
        applyBadgeStyle(badge, birdType)
        return badge
    }

    @Suppress("ClickableViewAccessibility")
    private fun attachBubbleTouchListener(
        container: View,
        params: WindowManager.LayoutParams,
        dragThresholdPx: Int,
    ) {
        val state = BubbleDragState()
        container.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    state.onDown(params, event); true
                }
                MotionEvent.ACTION_MOVE -> {
                    applyBubbleDragMove(container, params, dragThresholdPx, state, event); true
                }
                MotionEvent.ACTION_UP -> {
                    if (!state.isDragging) onNotificationRequested?.invoke()
                    true
                }
                else -> false
            }
        }
    }

    private class BubbleDragState {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDragging = false

        fun onDown(params: WindowManager.LayoutParams, event: MotionEvent) {
            initialX = params.x
            initialY = params.y
            initialTouchX = event.rawX
            initialTouchY = event.rawY
            isDragging = false
        }
    }

    private fun applyBubbleDragMove(
        container: View,
        params: WindowManager.LayoutParams,
        dragThresholdPx: Int,
        state: BubbleDragState,
        event: MotionEvent,
    ) {
        val dx = event.rawX - state.initialTouchX
        val dy = event.rawY - state.initialTouchY
        if (OverlayNudgeLogic.exceededDragThreshold(dx, dy, dragThresholdPx)) {
            state.isDragging = true
        }
        params.x = state.initialX + dx.toInt()
        params.y = state.initialY + dy.toInt()
        try {
            windowManager.updateViewLayout(container, params)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update bird position while dragging", e)
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
        dismissTransientToastInternal()
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

    private fun birdDrawableResIdForType(type: NudgeBirdType): Int {
        return when (type) {
            NudgeBirdType.GREEN_NOW -> R.drawable.ic_nudge_bird
            NudgeBirdType.PURPLE_SOFT -> R.drawable.ic_nudge_bird_alt1
            NudgeBirdType.RED_HARD -> R.drawable.ic_nudge_bird_alt2
            NudgeBirdType.PREDATORY -> R.drawable.ic_nudge_bird_predatory
        }
    }

    private fun randomSmallBirdType(): NudgeBirdType {
        val types = arrayOf(NudgeBirdType.GREEN_NOW, NudgeBirdType.PURPLE_SOFT, NudgeBirdType.RED_HARD)
        return types[Random.nextInt(types.size)]
    }

    private fun refreshBubbleBadges(nowMs: Long, force: Boolean = false) {
        val nowSecond = nowMs / 1000L
        if (!force && nowSecond == lastBadgeRefreshSecond) return
        lastBadgeRefreshSecond = nowSecond
        bubbleEntries.forEach { entry ->
            entry.badge.text = badgeTextForType(entry.birdType, nowMs)
        }
    }

    private fun badgeTextForType(type: NudgeBirdType, nowMs: Long): String {
        return OverlayNudgeLogic.badgeTextForType(
            type = type,
            nowMs = nowMs,
            softDeadlineAtMs = softDeadlineAtMs,
            hardDeadlineAtMs = hardDeadlineAtMs,
        )
    }

    private fun applyBirdTint(
        bird: ImageView,
        type: NudgeBirdType,
    ) {
        bird.clearColorFilter()
        if (type != NudgeBirdType.PREDATORY) {
            bird.background = null
        }
    }

    private fun applyBadgeStyle(badge: TextView, type: NudgeBirdType) {
        val style = OverlayNudgeLogic.badgeStyleColors(type)
        badge.setTextColor(style.textColor)
        val bg = (badge.background as? GradientDrawable) ?: return
        bg.setColor(style.backgroundColor)
        bg.setStroke(dp(style.strokeWidthDp), style.strokeColor)
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
        val nextFlags = OverlayNudgeLogic.nextBannerFocusableFlags(
            currentlyFocusable = currentlyFocusable,
            focusable = focusable,
            requestInputFocus = requestInputFocus,
            flags = params.flags,
            notFocusableFlag = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        ) ?: return
        params.flags = nextFlags
        try {
            windowManager.updateViewLayout(bannerView, params)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update conversation banner focus mode", e)
            return
        }
        applyBannerInputFocus(replyInput, focusable, requestInputFocus)
    }

    private fun applyBannerInputFocus(
        replyInput: android.widget.EditText?,
        focusable: Boolean,
        requestInputFocus: Boolean,
    ) {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        if (focusable) {
            replyInput?.requestFocus()
            if (OverlayNudgeLogic.shouldShowSoftInputAfterFocus(focusable, requestInputFocus)) {
                replyInput?.post { imm?.showSoftInput(replyInput, 0) }
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
        val birdType: NudgeBirdType,
        val params: WindowManager.LayoutParams,
        val minX: Int,
        val maxX: Int,
        val minY: Int,
        val maxY: Int,
        var velocityX: Int,
        var velocityY: Int,
    )

    enum class QuickLaunchFrameLevel {
        GREEN,
        YELLOW,
        RED,
    }

    private fun quickLaunchFrameColor(level: QuickLaunchFrameLevel): Int {
        return when (level) {
            QuickLaunchFrameLevel.GREEN -> Color.parseColor("#D022C55E")
            QuickLaunchFrameLevel.YELLOW -> Color.parseColor("#D0EAB308")
            QuickLaunchFrameLevel.RED -> Color.parseColor("#D0EF4444")
        }
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
