package com.appsfolder.livebridge.liveupdate.capsule

import android.app.Notification
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
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
 * Renders the in-app "capsule" — an island-style floating pill drawn above
 * other apps. Used on API 33–35, where the OS Live Updates island surface is
 * unavailable; on API 36+ the native island is used instead.
 *
 * Two sources feed the pill:
 *  - converted notifications (mirrors) — always take priority;
 *  - the network speed foreground service — a persistent, self-updating pill
 *    shown while no conversion is active.
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
    private var lastPayload: CapsulePayload? = null
    private var lastSpeed: CapsulePayload? = null
    private var activeKind: CapsuleKind? = null
    private var suppressedKey: String? = null
    private var speedMutedUntilMs = 0L
    private var boundText = ""
    private var isDragging = false
    private var isLongPressDragging = false
    private var longPressPending = false
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

    /** A conversion (mirror) is active — the pill takes priority. */
    fun show(payload: CapsulePayload) {
        val skip = showSkipReason(payload)
        if (skip != null) {
            Log.d(TAG, "show skipped ($skip): ${payload.packageName} | ${payload.title}")
            removeView()
            return
        }
        lastPayload = payload
        render(payload)
    }

    /** Network speed tick from the FGS — shown while no conversion is active. */
    fun showSpeed(speedText: String) {
        if (SystemClock.elapsedRealtime() < speedMutedUntilMs) {
            lastSpeed = null
            return
        }
        val payload = CapsulePayload.speed(context.packageName, speedText)
        lastSpeed = payload
        if (lastPayload == null) {
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

    /** The conversion ended; fall back to the speed pill if it is live. */
    fun hide() {
        lastPayload = null
        suppressedKey = null
        val speed = lastSpeed
        if (speed != null) {
            render(speed)
        } else {
            removeView()
        }
    }

    /** Keeps the payload but hides the pill while LiveBridge is on screen. */
    fun suspendWhileAppVisible() {
        if (lastPayload != null || lastSpeed != null) {
            removeView()
        }
    }

    /** Re-shows the pill if a source is still active. */
    fun resumeIfActive() {
        val payload = lastPayload
        if (payload != null) {
            show(payload)
            return
        }
        val speed = lastSpeed ?: return
        render(speed)
    }

    fun release() {
        positionHandler.removeCallbacksAndMessages(null)
        hide()
    }

    private fun render(payload: CapsulePayload) {
        val skip = showSkipReason(payload)
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
        val previousText = boundText
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
        } else if (boundText != previousText && boundText.isNotEmpty()) {
            // Content changed in place (speed tick, code refresh, ...) — crossfade.
            target?.animate()?.alpha(0.45f)?.setDuration(60)
                ?.withEndAction {
                    target?.animate()?.alpha(1f)?.setDuration(160)?.start()
                }
                ?.start()
        }
    }

    private fun showSkipReason(payload: CapsulePayload): String? {
        if (Build.VERSION.SDK_INT >= LIVE_UPDATES_MIN_SDK_INT) {
            return "sdk-36-plus"
        }
        if (MainActivity.appInForeground) {
            return "app-foreground"
        }
        if (suppressedKey == payload.suppressKey) {
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
        boundText = payload.text

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
                    positionHandler.removeCallbacks(longPressRunnable)
                    positionHandler.postDelayed(longPressRunnable, 450L)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    lastX = event.rawX
                    lastY = event.rawY
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (longPressPending && (abs(dx) > dragStartPx || abs(dy) > dragStartPx)) {
                        longPressPending = false
                        positionHandler.removeCallbacks(longPressRunnable)
                    }
                    if (isLongPressDragging) {
                        moveWindow((lastX - grabOffsetX).toInt(), (lastY - grabOffsetY).toInt())
                    } else {
                        if (dy > dragStartPx) {
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
                    when {
                        isLongPressDragging -> {
                            isLongPressDragging = false
                            persistWindowPosition()
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
                    isDragging = false
                    isLongPressDragging = false
                    v.animate().alpha(1f).translationY(0f).setDuration(120).start()
                    true
                }
                else -> false
            }
        }
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

    private fun dismissed() {
        if (activeKind == CapsuleKind.SPEED) {
            // Swiping the speed pill away mutes it for a minute.
            speedMutedUntilMs = SystemClock.elapsedRealtime() + 60_000L
            lastSpeed = null
            removeView()
            return
        }
        lastPayload?.let { suppressedKey = it.suppressKey }
        view?.let { pill ->
            pill.animate()
                .alpha(0f)
                .translationY(dp(72f).toFloat())
                .setDuration(160)
                .withEndAction {
                    if (suppressedKey == lastPayload?.suppressKey) {
                        lastPayload = null
                        removeView()
                    }
                }
                .start()
        }
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
    }
}
