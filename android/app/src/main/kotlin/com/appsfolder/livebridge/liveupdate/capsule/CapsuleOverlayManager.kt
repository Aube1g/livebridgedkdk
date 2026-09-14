package com.appsfolder.livebridge.liveupdate.capsule

import android.app.Notification
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.appsfolder.livebridge.MainActivity
import com.appsfolder.livebridge.R
import com.appsfolder.livebridge.liveupdate.ConverterPrefs
import kotlin.math.abs

enum class CapsuleKind {
    GENERIC,
    OTP,
    PROGRESS,
    PASSWORD,
    VPN,
    SPEED
}

data class CapsulePayload(
    val title: String,
    val text: String,
    val packageName: String,
    val progress: Int,
    val progressMax: Int,
    val kind: CapsuleKind
) {
    val suppressKey: String
        get() = "$packageName|$title"

    companion object {
        fun from(notification: Notification, packageName: String): CapsulePayload {
            val extras = notification.extras
            val title = extras.getCharSequence(Notification.EXTRA_TITLE)
                ?.toString()?.trim().orEmpty()
            val text = extras.getCharSequence(Notification.EXTRA_TEXT)
                ?.toString()?.trim().orEmpty()
            val progressMax = extras.getInt(Notification.EXTRA_PROGRESS_MAX, 0)
            val indeterminate =
                extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE, false)
            val progress = if (indeterminate || progressMax <= 0) {
                0
            } else {
                extras.getInt(Notification.EXTRA_PROGRESS, 0)
            }
            val combined = "$title $text"
            val kind = when {
                progressMax > 0 && !indeterminate -> CapsuleKind.PROGRESS
                hasCopyAction(notification) -> CapsuleKind.OTP
                PASSWORD_REGEX.containsMatchIn(combined) -> CapsuleKind.PASSWORD
                VPN_REGEX.containsMatchIn(combined) -> CapsuleKind.VPN
                else -> CapsuleKind.GENERIC
            }
            return CapsulePayload(
                title = title,
                text = text,
                packageName = packageName,
                progress = progress,
                progressMax = progressMax,
                kind = kind
            )
        }

        fun vpnFallback(packageName: String): CapsulePayload {
            return CapsulePayload(
                title = "VPN",
                text = "",
                packageName = packageName,
                progress = 0,
                progressMax = 0,
                kind = CapsuleKind.VPN
            )
        }

        fun speed(packageName: String, speedText: String): CapsulePayload {
            return CapsulePayload(
                title = "",
                text = speedText,
                packageName = packageName,
                progress = 0,
                progressMax = 0,
                kind = CapsuleKind.SPEED
            )
        }

        private fun hasCopyAction(notification: Notification): Boolean {
            val actions = notification.actions ?: return false
            return actions.any { action ->
                COPY_ACTION_REGEX.containsMatchIn(action.title?.toString().orEmpty())
            }
        }

        private val PASSWORD_REGEX = Regex("(парол|password)", RegexOption.IGNORE_CASE)
        private val VPN_REGEX = Regex("\\bvpn\\b", RegexOption.IGNORE_CASE)
        private val COPY_ACTION_REGEX =
            Regex("copy|скопир|копир|kopyla|копира", RegexOption.IGNORE_CASE)
    }
}

/**
 * A single active state occupying the island (e.g. one download in flight).
 * Several slots can be alive at once — the user swipes horizontally between
 * them; the little dots under the pill are the pager indicator.
 */
data class CapsuleSlot(
    val payload: CapsulePayload,
    val key: String
)

/**
 * Renders the in-app "capsule" — an island-style floating pill drawn above
 * other apps. Used on API 33–35, where the OS Live Updates island surface is
 * unavailable; on API 36+ the native island is used instead.
 *
 * Sources feeding the pill, by priority:
 *  - transient states (OTP code, "password required", plain messages) —
 *    overlay whatever is on screen for a few seconds, then the pill returns
 *    to the state underneath;
 *  - persistent conversion (mirror) slots — several can be active at once,
 *    swipe horizontally between them (dots indicator);
 *  - the system VPN fallback pill (ConnectivityManager-based, VpnStateMonitor);
 *  - the network speed foreground service — a persistent, self-updating pill
 *    shown while nothing else is active.
 *
 * The overlay view is owned by the notification listener service, so it
 * lives and dies together with the app process.
 */
class CapsuleOverlayManager(appContext: Context) {
    private val context: Context = appContext.applicationContext
    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val positionHandler = Handler(Looper.getMainLooper())

    private var view: View? = null
    private var wmParams: WindowManager.LayoutParams? = null
    private val slots = ArrayDeque<CapsuleSlot>()
    private var currentSlotIndex = 0
    private var overlayPayload: CapsulePayload? = null
    private var lastSpeed: CapsulePayload? = null
    private var lastVpnFallback: CapsulePayload? = null
    private var activeKind: CapsuleKind? = null
    private var suppressedKey: String? = null
    private var speedMutedUntilMs = 0L
    private var boundText = ""
    private var isDragging = false
    private var isLongPressDragging = false
    private var longPressPending = false
    private var velocityTracker: VelocityTracker? = null
    private var grabOffsetX = 0
    private var grabOffsetY = 0
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f

    private val longPressRunnable = Runnable {
        longPressPending = false
        isLongPressDragging = true
        val v = view ?: return@Runnable
        val params = wmParams ?: return@Runnable
        if (params.gravity and Gravity.CENTER_HORIZONTAL != 0) {
            val loc = IntArray(2)
            v.getLocationOnScreen(loc)
            params.gravity = Gravity.TOP or Gravity.START
            params.x = loc[0]
            params.y = loc[1]
            try {
                windowManager.updateViewLayout(v, params)
            } catch (error: Throwable) {
                // keep the previous layout
            }
        }
        grabOffsetX = (lastX - params.x).toInt()
        grabOffsetY = (lastY - params.y).toInt()
    }

    private val overlayReturnRunnable = Runnable {
        if (overlayPayload == null) {
            return@Runnable
        }
        overlayPayload = null
        val payload = displayedPayload()
        if (payload != null) {
            render(payload)
        } else {
            removeView()
        }
    }

    private fun isPersistent(kind: CapsuleKind): Boolean {
        return kind == CapsuleKind.PROGRESS ||
            kind == CapsuleKind.VPN ||
            kind == CapsuleKind.SPEED
    }

    /**
     * The payload currently owning the island: transient overlay > active
     * slot > system VPN fallback > network speed.
     */
    private fun displayedPayload(): CapsulePayload? {
        overlayPayload?.let { return it }
        slots.getOrNull(currentSlotIndex)?.let { return it.payload }
        lastVpnFallback?.let { return it }
        return lastSpeed
    }

    /**
     * A converted notification (mirror) is active or updated.
     *
     * @param sourceKey stable per-mirror key from the notifier; persistent
     *   states are tracked as slots keyed by it so that several concurrent
     *   states (two downloads, download + VPN, ...) can be swiped between.
     */
    fun show(payload: CapsulePayload, sourceKey: String? = null) {
        val key = sourceKey ?: payload.suppressKey
        val skip = showSkipReason(payload, key)
        if (skip != null) {
            Log.d(TAG, "show skipped ($skip): ${payload.packageName} | ${payload.title}")
            if (skip != "suppressed") {
                removeView()
            }
            return
        }
        if (!isPersistent(payload.kind)) {
            // Transient state (OTP code, "password required", plain message)
            // overlays the current state for a few seconds, then the pill
            // returns to whatever was underneath.
            overlayPayload = payload
            positionHandler.removeCallbacks(overlayReturnRunnable)
            positionHandler.postDelayed(overlayReturnRunnable, TRANSIENT_OVERLAY_MS)
            render(payload)
            return
        }
        val hadSlots = slots.isNotEmpty()
        upsertSlot(payload, key)
        if (overlayPayload != null) {
            // Transient overlay is on screen — the slot data is updated, the
            // overlay finishes first and the return renders the fresh slot.
            return
        }
        if (!hadSlots) {
            currentSlotIndex = 0
        }
        Log.d(
            TAG,
            "slot upserted: $key (total=${slots.size}, index=$currentSlotIndex)"
        )
        render(displayedPayload())
    }

    /** The source of a slot (its mirror) is gone. */
    fun removeSlot(sourceKey: String, animateExit: Boolean = false) {
        val index = slots.indexOfFirst { it.key == sourceKey }
        if (index < 0) {
            return
        }
        slots.removeAt(index)
        if (index < currentSlotIndex) {
            currentSlotIndex--
        }
        if (currentSlotIndex >= slots.size) {
            currentSlotIndex = (slots.size - 1).coerceAtLeast(0)
        }
        Log.d(
            TAG,
            "slot removed: $sourceKey (total=${slots.size}, index=$currentSlotIndex)"
        )
        val payload = displayedPayload()
        if (payload != null) {
            render(payload)
        } else if (animateExit && view != null) {
            view?.let { pill ->
                pill.animate()
                    .alpha(0f)
                    .translationY(dp(72f).toFloat())
                    .setDuration(160)
                    .withEndAction { removeView() }
                    .start()
            }
        } else {
            removeView()
        }
    }

    /** Network speed tick from the FGS — shown while nothing else is active. */
    fun showSpeed(speedText: String) {
        if (SystemClock.elapsedRealtime() < speedMutedUntilMs) {
            lastSpeed = null
            return
        }
        val payload = CapsulePayload.speed(context.packageName, speedText)
        lastSpeed = payload
        if (slots.isEmpty() && lastVpnFallback == null && overlayPayload == null) {
            render(payload)
        }
    }

    fun clearSpeed() {
        lastSpeed = null
        if (activeKind == CapsuleKind.SPEED) {
            Log.d(TAG, "speed pill cleared")
            removeView()
        }
    }

    /** System VPN is active but the client is silent — minimal pill. */
    fun showVpnFallback(packageName: String?) {
        val payload = CapsulePayload.vpnFallback(packageName ?: context.packageName)
        lastVpnFallback = payload
        if (slots.isEmpty() && overlayPayload == null) {
            render(payload)
        }
    }

    fun clearVpnFallback() {
        lastVpnFallback = null
        if (slots.isEmpty() && overlayPayload == null) {
            val speed = lastSpeed
            if (speed != null) {
                render(speed)
            } else {
                removeView()
            }
        }
    }

    /** All mirrors are gone; fall back to VPN/speed sources if they are live. */
    fun hide() {
        slots.clear()
        currentSlotIndex = 0
        overlayPayload = null
        positionHandler.removeCallbacks(overlayReturnRunnable)
        suppressedKey = null
        val payload = lastVpnFallback ?: lastSpeed
        if (payload != null) {
            render(payload)
        } else {
            removeView()
        }
    }

    /** Keeps the state but hides the pill while LiveBridge is on screen. */
    fun suspendWhileAppVisible() {
        if (displayedPayload() != null) {
            removeView()
        }
    }

    /** Re-shows the pill if any source is still active. */
    fun resumeIfActive() {
        val payload = displayedPayload()
        if (payload != null) {
            render(payload)
        } else {
            removeView()
        }
    }

    fun release() {
        positionHandler.removeCallbacksAndMessages(null)
        velocityTracker?.recycle()
        velocityTracker = null
        hide()
    }

    private fun upsertSlot(payload: CapsulePayload, key: String) {
        val existingIndex = slots.indexOfFirst { it.key == key }
        if (existingIndex >= 0) {
            slots[existingIndex] = CapsuleSlot(payload, key)
        } else {
            slots.addLast(CapsuleSlot(payload, key))
        }
    }

    private fun render(payload: CapsulePayload) {
        val skip = showSkipReason(payload, keyForPayload(payload))
        if (skip != null) {
            Log.d(TAG, "render skipped ($skip): ${payload.kind}")
            removeView()
            return
        }
        var target = view
        val isNew = target == null
        if (isNew) {
            val created = buildView()
            val params = createLayoutParams()
            try {
                windowManager.addView(created, params)
            } catch (error: Throwable) {
                Log.w(TAG, "addView failed", error)
                return
            }
            wmParams = params
            view = created
            target = created
            Log.d(TAG, "capsule shown: ${payload.kind} | ${payload.packageName}")
        }
        val previousSignature = boundText
        bindView(target, payload)
        activeKind = payload.kind
        if (isNew) {
            target?.let { pill ->
                pill.alpha = 0f
                pill.translationY = -dp(20f).toFloat()
                pill.animate().alpha(1f).translationY(0f).setDuration(220).start()
                if (payload.kind == CapsuleKind.OTP) {
                    pill.animate()
                        .scaleX(1.05f)
                        .scaleY(1.05f)
                        .setDuration(140)
                        .withEndAction {
                            pill.animate().scaleX(1f).scaleY(1f).setDuration(220).start()
                        }
                        .start()
                }
            }
        } else if (boundText != previousSignature && boundText.isNotEmpty()) {
            // Content changed in place (speed tick, code refresh, slot update) —
            // crossfade.
            target?.animate()?.alpha(0.45f)?.setDuration(60)
                ?.withEndAction {
                    target?.animate()?.alpha(1f)?.setDuration(160)?.start()
                }
                ?.start()
        }
    }

    /** Best-effort slot key for a payload (fallback/speed payloads are unkeyed). */
    private fun keyForPayload(payload: CapsulePayload): String {
        slots.firstOrNull { it.payload == payload }?.let { return it.key }
        return payload.suppressKey
    }

    private fun showSkipReason(payload: CapsulePayload, key: String): String? {
        if (Build.VERSION.SDK_INT >= LIVE_UPDATES_MIN_SDK_INT) {
            return "sdk-36-plus"
        }
        if (MainActivity.appInForeground) {
            return "app-foreground"
        }
        if (suppressedKey == key) {
            return "suppressed"
        }
        return try {
            if (
                Settings.canDrawOverlays(context) &&
                ConverterPrefs(context).getCapsuleOverlayEnabled()
            ) {
                null
            } else {
                "permission-or-pref"
            }
        } catch (error: Throwable) {
            "error: $error"
        }
    }

    private fun buildView(): View {
        val root = LayoutInflater.from(context).inflate(R.layout.capsule_overlay, null)
        attachInteractions(root)
        return root
    }

    private fun bindView(root: View, payload: CapsulePayload) {
        val icon = root.findViewById<ImageView>(R.id.capsule_icon)
        val title = root.findViewById<TextView>(R.id.capsule_title)
        val text = root.findViewById<TextView>(R.id.capsule_text)
        val progress = root.findViewById<ProgressBar>(R.id.capsule_progress)

        try {
            icon.setImageDrawable(
                context.packageManager.getApplicationIcon(payload.packageName)
            )
            icon.visibility = View.VISIBLE
        } catch (error: Throwable) {
            icon.setImageDrawable(null)
            icon.visibility = View.GONE
        }

        when (payload.kind) {
            CapsuleKind.SPEED -> {
                title.visibility = View.GONE
                title.text = ""
                text.visibility = View.VISIBLE
                text.text = payload.text
                text.setTextSize(13f)
                text.letterSpacing = 0.02f
                text.setTypeface(null, android.graphics.Typeface.NORMAL)
                text.setTextColor(Color.parseColor("#CBB4FF"))
            }
            CapsuleKind.OTP -> {
                title.visibility = if (payload.title.isEmpty()) {
                    View.GONE
                } else {
                    View.VISIBLE
                }
                title.text = payload.title
                title.setTextSize(11f)
                title.setTextColor(Color.parseColor("#9B9B9B"))
                text.visibility = View.VISIBLE
                text.text = payload.text
                text.setTextSize(15f)
                text.letterSpacing = 0.12f
                text.setTypeface(null, android.graphics.Typeface.BOLD)
                text.setTextColor(Color.parseColor("#B79CFF"))
            }
            CapsuleKind.PASSWORD -> {
                title.visibility = View.VISIBLE
                title.text = payload.title
                title.setTextSize(13f)
                title.setTextColor(Color.parseColor("#FFFFFF"))
                text.visibility = if (payload.text.isEmpty()) {
                    View.GONE
                } else {
                    View.VISIBLE
                }
                text.text = payload.text
                text.setTextSize(11.5f)
                text.setTextColor(Color.parseColor("#FF8A80"))
            }
            CapsuleKind.VPN -> {
                title.visibility = View.VISIBLE
                title.text = payload.title
                title.setTextSize(13f)
                title.setTextColor(Color.parseColor("#FFFFFF"))
                text.visibility = if (payload.text.isEmpty()) {
                    View.GONE
                } else {
                    View.VISIBLE
                }
                text.text = payload.text
                text.setTextSize(11.5f)
                text.setTextColor(Color.parseColor("#CBB4FF"))
            }
            else -> {
                title.visibility = View.VISIBLE
                title.text = payload.title
                title.setTextSize(13f)
                title.setTextColor(Color.parseColor("#FFFFFF"))
                text.visibility = if (payload.text.isEmpty()) {
                    View.GONE
                } else {
                    View.VISIBLE
                }
                text.text = payload.text
                text.setTextSize(11.5f)
                text.setTextColor(Color.parseColor("#B3FFFFFF"))
            }
        }
        boundText = "${payload.title}|${payload.text}"

        if (payload.kind == CapsuleKind.PROGRESS) {
            progress.max = payload.progressMax
            progress.progress = payload.progress
            progress.visibility = View.VISIBLE
        } else {
            progress.visibility = View.GONE
        }

        if (!isDragging && !isLongPressDragging) {
            root.alpha = 1f
            root.translationY = 0f
        }

        updateDots(root)
    }

    /** Pager indicator: one dot per active slot, visible with 2+ slots. */
    private fun updateDots(root: View) {
        val dots = root.findViewById<LinearLayout>(R.id.capsule_dots) ?: return
        dots.removeAllViews()
        if (slots.size <= 1 || overlayPayload != null) {
            dots.visibility = View.GONE
            return
        }
        dots.visibility = View.VISIBLE
        val size = dp(5f)
        val margin = dp(3f)
        for (i in slots.indices) {
            val dot = View(context)
            val lp = LinearLayout.LayoutParams(size, size)
            lp.marginStart = margin
            lp.marginEnd = margin
            dot.layoutParams = lp
            dot.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(
                    if (i == currentSlotIndex) {
                        Color.parseColor("#B79CFF")
                    } else {
                        Color.parseColor("#40FFFFFF")
                    }
                )
            }
            dots.addView(dot)
        }
    }

    private fun attachInteractions(root: View) {
        val dismissThresholdPx = dp(48f).toFloat()
        val dragStartPx = dp(12f).toFloat()
        val maxTranslationPx = dp(96f).toFloat()
        root.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    lastX = event.rawX
                    lastY = event.rawY
                    isDragging = false
                    isLongPressDragging = false
                    longPressPending = true
                    velocityTracker?.recycle()
                    velocityTracker = VelocityTracker.obtain().apply {
                        addMovement(event)
                    }
                    positionHandler.removeCallbacks(longPressRunnable)
                    positionHandler.postDelayed(longPressRunnable, 450L)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    lastX = event.rawX
                    lastY = event.rawY
                    velocityTracker?.addMovement(event)
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (longPressPending && (abs(dx) > dragStartPx || abs(dy) > dragStartPx)) {
                        longPressPending = false
                        positionHandler.removeCallbacks(longPressRunnable)
                    }
                    if (isLongPressDragging) {
                        moveWindow((lastX - grabOffsetX).toInt(), (lastY - grabOffsetY).toInt())
                    } else {
                        // Vertical motion only becomes drag-to-dismiss when it is
                        // also the dominant axis — horizontal swipes must not
                        // trigger it.
                        if (dy > dragStartPx && abs(dy) >= abs(dx)) {
                            isDragging = true
                        }
                        if (isDragging) {
                            v.alpha = (1f - dy / dismissThresholdPx).coerceIn(0.08f, 1f)
                            v.translationY = (dy * 0.35f).coerceAtMost(maxTranslationPx)
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    positionHandler.removeCallbacks(longPressRunnable)
                    longPressPending = false
                    var vx = 0f
                    var vy = 0f
                    val tracker = velocityTracker
                    if (tracker != null) {
                        tracker.addMovement(event)
                        tracker.computeCurrentVelocity(1000)
                        vx = tracker.xVelocity
                        vy = tracker.yVelocity
                        tracker.recycle()
                        velocityTracker = null
                    }
                    when {
                        isLongPressDragging -> {
                            isLongPressDragging = false
                            persistWindowPosition()
                        }
                        !isDragging &&
                            slots.size > 1 &&
                            abs(vx) > SWIPE_MIN_VELOCITY &&
                            abs(vx) > abs(vy) * 1.5f -> {
                            // Horizontal fling — switch between active slots.
                            swipeToSlot(
                                if (vx < 0) currentSlotIndex + 1 else currentSlotIndex - 1
                            )
                        }
                        isDragging && event.rawY - downY > dismissThresholdPx -> {
                            dismissed()
                        }
                        else -> {
                            v.animate().alpha(1f).translationY(0f).setDuration(120).start()
                            if (!isDragging) {
                                openApp()
                            }
                        }
                    }
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    positionHandler.removeCallbacks(longPressRunnable)
                    longPressPending = false
                    velocityTracker?.recycle()
                    velocityTracker = null
                    isDragging = false
                    isLongPressDragging = false
                    v.animate().alpha(1f).translationY(0f).setDuration(120).start()
                    true
                }
                else -> false
            }
        }
    }

    /** Fling between active slots: crossfade with a horizontal slide. */
    private fun swipeToSlot(target: Int) {
        val v = view ?: return
        val next = slots.getOrNull(target)?.payload
        if (next == null) {
            v.animate().translationX(0f).setDuration(120).start()
            return
        }
        val direction = if (target > currentSlotIndex) 1 else -1
        currentSlotIndex = target
        val slide = dp(56f).toFloat()
        Log.d(
            TAG,
            "swipe to slot $target: ${next.packageName} | ${next.title}"
        )
        v.animate()
            .translationX(-direction * slide)
            .alpha(0f)
            .setDuration(90)
            .withEndAction {
                bindView(v, next)
                v.translationX = direction * slide
                v.alpha = 0f
                v.animate().translationX(0f).alpha(1f).setDuration(140).start()
            }
            .start()
    }

    private fun moveWindow(xPx: Int, yPx: Int) {
        val v = view ?: return
        val params = wmParams ?: return
        val metrics = context.resources.displayMetrics
        params.x = xPx.coerceIn(0, (metrics.widthPixels - v.width).coerceAtLeast(0))
        params.y = yPx.coerceIn(0, metrics.heightPixels - v.height)
        try {
            windowManager.updateViewLayout(v, params)
        } catch (error: Throwable) {
            Log.w(TAG, "updateViewLayout failed", error)
        }
    }

    private fun persistWindowPosition() {
        val params = wmParams ?: return
        val prefs = ConverterPrefs(context)
        prefs.setCapsulePositionX(params.x)
        prefs.setCapsulePositionY(params.y)
    }

    /** Called from the settings UI: left / center / right preset. */
    fun applyPreset(preset: String) {
        val metrics = context.resources.displayMetrics
        val v = view
        val width = if (v != null && v.width > 0) v.width else dp(200f)
        val x = when (preset) {
            "left" -> dp(12f)
            "right" -> (metrics.widthPixels - width - dp(12f)).coerceAtLeast(0)
            else -> ((metrics.widthPixels - width) / 2).coerceAtLeast(0)
        }
        val prefs = ConverterPrefs(context)
        val y = prefs.getCapsulePositionY().let {
            if (it >= 0) it else statusBarHeightPx() + dp(6f)
        }
        prefs.setCapsulePositionX(x)
        prefs.setCapsulePositionY(y)
        if (v != null) {
            moveWindow(x, y)
        }
        Log.d(TAG, "position preset applied: $preset")
    }

    private fun dismissed() {
        if (activeKind == CapsuleKind.SPEED) {
            // Swiping the speed pill away mutes it for a minute.
            speedMutedUntilMs = SystemClock.elapsedRealtime() + 60_000L
            lastSpeed = null
            removeView()
            return
        }
        if (overlayPayload != null) {
            // Swiping a transient overlay away returns to the state underneath.
            overlayPayload = null
            positionHandler.removeCallbacks(overlayReturnRunnable)
            val payload = displayedPayload()
            if (payload != null) {
                render(payload)
            } else {
                removeView()
            }
            return
        }
        val current = slots.getOrNull(currentSlotIndex)
        if (current != null) {
            // Suppress this key so the same mirror does not immediately
            // reappear on the next update; the pill moves to the next slot.
            suppressedKey = current.key
            val hadMore = slots.size > 1 || lastVpnFallback != null || lastSpeed != null
            removeSlot(current.key, animateExit = !hadMore)
            return
        }
        if (activeKind == CapsuleKind.VPN && lastVpnFallback != null) {
            // System VPN pill swiped away — the monitor may re-add it on the
            // next network change.
            clearVpnFallback()
            return
        }
        removeView()
    }

    private fun openApp() {
        try {
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            }
            context.startActivity(intent)
        } catch (error: Throwable) {
            // Best effort — the notification remains tappable as a fallback.
        }
    }

    private fun removeView() {
        view?.let { pill ->
            try {
                windowManager.removeView(pill)
            } catch (error: Throwable) {
                // Already detached.
            }
        }
        view = null
        wmParams = null
        activeKind = null
        boundText = ""
    }

    private fun createLayoutParams(): WindowManager.LayoutParams {
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        val prefs = ConverterPrefs(context)
        val savedX = prefs.getCapsulePositionX()
        val savedY = prefs.getCapsulePositionY()
        if (savedX >= 0 && savedY >= 0) {
            params.gravity = Gravity.TOP or Gravity.START
            val metrics = context.resources.displayMetrics
            params.x = savedX.coerceIn(0, (metrics.widthPixels - dp(80f)).coerceAtLeast(0))
            params.y = savedY.coerceIn(0, (metrics.heightPixels - dp(60f)).coerceAtLeast(0))
        } else {
            params.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            params.y = statusBarHeightPx() + dp(6f)
        }
        return params
    }

    private fun statusBarHeightPx(): Int {
        val resourceId =
            context.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resourceId > 0) {
            context.resources.getDimensionPixelSize(resourceId)
        } else {
            dp(24f)
        }
    }

    private fun dp(value: Float): Int {
        val density = context.resources.displayMetrics.density
        return (value * density + 0.5f).toInt()
    }

    private companion object {
        const val TAG = "CapsuleOverlay"
        const val LIVE_UPDATES_MIN_SDK_INT = 36
        const val TRANSIENT_OVERLAY_MS = 3_500L
        const val SWIPE_MIN_VELOCITY = 300f
    }
}
