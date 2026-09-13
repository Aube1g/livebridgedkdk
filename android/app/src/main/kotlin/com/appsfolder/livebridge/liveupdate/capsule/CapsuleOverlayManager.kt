package com.appsfolder.livebridge.liveupdate.capsule

import android.app.Notification
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.ProgressBar
import android.util.Log
import android.widget.TextView
import com.appsfolder.livebridge.MainActivity
import com.appsfolder.livebridge.R
import com.appsfolder.livebridge.liveupdate.ConverterPrefs

data class CapsulePayload(
    val title: String,
    val text: String,
    val packageName: String,
    val progress: Int,
    val progressMax: Int
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
            return CapsulePayload(
                title = title,
                text = text,
                packageName = packageName,
                progress = progress,
                progressMax = progressMax
            )
        }
    }
}

/**
 * Renders the in-app "capsule" — an island-style floating pill drawn above
 * other apps for converted notifications. Used on API 33–35, where the OS
 * Live Updates island surface is unavailable; on API 36+ the native island is
 * used instead.
 *
 * The overlay view is owned by the notification listener service, so it
 * lives and dies together with the app process.
 */
class CapsuleOverlayManager(appContext: Context) {
    private val context: Context = appContext.applicationContext
    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var view: View? = null
    private var lastPayload: CapsulePayload? = null
    private var suppressedKey: String? = null

    fun show(payload: CapsulePayload) {
        val skip = showSkipReason(payload)
        if (skip != null) {
            Log.d(TAG, "show skipped ($skip): ${payload.packageName} | ${payload.title}")
            removeView()
            return
        }
        lastPayload = payload
        var target = view
        if (target == null) {
            val created = buildView()
            try {
                windowManager.addView(created, createLayoutParams())
            } catch (error: Throwable) {
                Log.w(TAG, "addView failed", error)
                return
            }
            view = created
            target = created
            Log.d(TAG, "capsule shown: ${payload.packageName} | ${payload.title}")
        }
        bindView(target, payload)
    }

    /** The conversion ended; the capsule is gone until a new one arrives. */
    fun hide() {
        lastPayload = null
        suppressedKey = null
        removeView()
    }

    /** Keeps the payload but hides the pill while LiveBridge is on screen. */
    fun suspendWhileAppVisible() {
        if (lastPayload != null) {
            removeView()
        }
    }

    /** Re-shows the pill if the conversion is still active. */
    fun resumeIfActive() {
        val payload = lastPayload ?: return
        show(payload)
    }

    fun release() {
        hide()
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

        title.text = payload.title
        if (payload.text.isEmpty()) {
            text.visibility = View.GONE
        } else {
            text.visibility = View.VISIBLE
            text.text = payload.text
        }

        if (payload.progressMax > 0 && !payload.text.isEmpty()) {
            progress.max = payload.progressMax
            progress.progress = payload.progress
            progress.visibility = View.VISIBLE
        } else {
            progress.visibility = View.GONE
        }

        root.alpha = 1f
        root.translationY = 0f
    }

    private fun attachInteractions(root: View) {
        val dismissThresholdPx = dp(48f).toFloat()
        val dragStartPx = dp(12f).toFloat()
        val maxTranslationPx = dp(96f).toFloat()
        var downY = 0f
        var dragging = false
        root.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downY = event.rawY
                    dragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val delta = event.rawY - downY
                    if (delta > dragStartPx) {
                        dragging = true
                    }
                    if (dragging) {
                        v.alpha = (1f - delta / dismissThresholdPx).coerceIn(0.08f, 1f)
                        v.translationY = (delta * 0.35f).coerceAtMost(maxTranslationPx)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (dragging && event.rawY - downY > dismissThresholdPx) {
                        dismissed()
                    } else if (!dragging) {
                        v.animate().alpha(1f).translationY(0f).setDuration(120).start()
                        openApp()
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    v.animate().alpha(1f).translationY(0f).setDuration(120).start()
                    true
                }
                else -> false
            }
        }
    }

    private fun dismissed() {
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
        params.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        params.y = statusBarHeightPx() + dp(6f)
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
