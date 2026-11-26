/*
 * Copyright 2026, Mi_Max Launcher contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package app.lawnchair.mimax

import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.content.Context
import kotlin.math.roundToInt

/** Notification-backed Me Island. User-granted notification and overlay access are required. */
class MiMaxIslandService : NotificationListenerService() {
    private val main = Handler(Looper.getMainLooper())
    private var wm: WindowManager? = null
    private var island: LinearLayout? = null
    private var activeKey: String? = null
    private var expanded = false
    private var style = IslandStyle.HIS
    private val dismiss = Runnable { removeIsland() }

    override fun onListenerConnected() {
        super.onListenerConnected()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        activeNotifications?.lastOrNull()?.let { show(it) }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName != packageName) main.post { show(sbn) }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (sbn.key == activeKey) main.post { removeIsland() }
    }

    private fun show(sbn: StatusBarNotification) {
        if (!MiMaxIslandPreferences.enabled(this) || !Settings.canDrawOverlays(this)) return
        val extras = sbn.notification.extras
        val title = extras.getCharSequence("android.title")?.toString()?.take(80)
            ?: appLabel(sbn.packageName)
        val message = (extras.getCharSequence("android.text")
            ?: extras.getCharSequence("android.bigText"))?.toString()?.take(180).orEmpty()
        if (title.isBlank() && message.isBlank()) return

        activeKey = sbn.key
        style = MiMaxIslandPreferences.style(this)
        main.removeCallbacks(dismiss)
        if (island == null) {
            island = makeView(title, message)
            val params = WindowManager.LayoutParams(
                dp(220), dp(48),
                if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = dp(8)
            }
            runCatching { wm?.addView(island, params) }.onFailure { island = null }
        } else {
            island?.findViewWithTag<TextView>("title")?.text = title
            island?.findViewWithTag<TextView>("message")?.apply {
                text = message
                visibility = if (expanded && message.isNotBlank()) View.VISIBLE else View.GONE
            }
            island?.background = islandBackground()
        }
        if (!sbn.isOngoing && sbn.notification.category != "call" &&
            sbn.notification.category != "navigation") main.postDelayed(dismiss, 6500L)
    }

    private fun makeView(title: String, message: String): LinearLayout {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(9), dp(18), dp(9))
            background = islandBackground()
            elevation = dp(12).toFloat()
            isClickable = true
            setOnClickListener {
                expanded = !expanded
                findViewWithTag<TextView>("message")?.visibility =
                    if (expanded && message.isNotBlank()) View.VISIBLE else View.GONE
                val lp = layoutParams as? WindowManager.LayoutParams
                lp?.width = dp(if (expanded) 320 else 220)
                lp?.height = dp(if (expanded) 92 else 48)
                lp?.let { runCatching { wm?.updateViewLayout(this, it) } }
                animate().scaleX(if (expanded) 1.025f else 1f)
                    .scaleY(if (expanded) 1.025f else 1f).setDuration(180).start()
            }
        }
        root.addView(TextView(this).apply {
            tag = "title"
            text = title
            setTextColor(Color.WHITE)
            textSize = 12f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            maxLines = 1
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(-1, -2))
        root.addView(TextView(this).apply {
            tag = "message"
            text = message
            setTextColor(0xFFE6E6E6.toInt())
            textSize = 11f
            maxLines = 2
            gravity = Gravity.CENTER
            visibility = View.GONE
        }, LinearLayout.LayoutParams(-1, -2))
        root.alpha = 0f
        root.scaleX = .82f
        root.scaleY = .82f
        root.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(220).start()
        return root
    }

    private fun islandBackground() = GradientDrawable().apply {
        setColor(when (style) {
            IslandStyle.HIS -> 0xF0181A20.toInt()
            IslandStyle.LTIN -> 0xF008080A.toInt()
            IslandStyle.COLOROS -> 0xF0262730.toInt()
        })
        cornerRadius = dp(when (style) {
            IslandStyle.HIS -> 30
            IslandStyle.LTIN -> 36
            IslandStyle.COLOROS -> 18
        }).toFloat()
        setStroke(dp(1), if (style == IslandStyle.COLOROS) 0x668C9BFF else 0x44FFFFFF)
    }

    private fun removeIsland() {
        main.removeCallbacks(dismiss)
        island?.let { view ->
            view.animate().alpha(0f).scaleX(.82f).scaleY(.82f).setDuration(140)
                .withEndAction { runCatching { wm?.removeView(view) } }.start()
        }
        island = null
        activeKey = null
        expanded = false
    }

    override fun onDestroy() {
        removeIsland()
        super.onDestroy()
    }

    private fun appLabel(pkg: String) = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()
}

enum class IslandStyle { HIS, LTIN, COLOROS }

object MiMaxIslandPreferences {
    private const val PREFS = "mimax_launcher"
    fun enabled(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("island_enabled", true)
    fun setEnabled(context: Context, value: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("island_enabled", value).apply()
    }
    fun style(context: Context) = runCatching {
        IslandStyle.valueOf(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("island_style", "HIS")!!)
    }.getOrDefault(IslandStyle.HIS)
    fun setStyle(context: Context, value: IslandStyle) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("island_style", value.name).apply()
    }
}
