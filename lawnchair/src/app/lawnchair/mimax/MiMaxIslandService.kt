/*
 * Copyright 2026, Mi_Max Launcher contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package app.lawnchair.mimax

import android.app.Notification
import android.content.ComponentName
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.app.NotificationChannel
import android.app.NotificationManager
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
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import kotlin.math.roundToInt
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Notification-backed Me Island. User-granted notification and overlay access are required. */
class MiMaxIslandService : NotificationListenerService() {
    private val main = Handler(Looper.getMainLooper())
    private var wm: WindowManager? = null
    private var island: LinearLayout? = null
    private var floatingPanel: LinearLayout? = null
    private var activeKey: String? = null
    private val activeEvents = linkedMapOf<String, StatusBarNotification>()
    private var expanded = false
    private var style = IslandStyle.HIS
    private var mediaController: MediaController? = null
    private val dismiss = Runnable { removeIsland() }
    private var lastMessage = ""
    private var batteryStatusText = ""
    private var clockStatusText = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
    private var timerStatusText = ""
    private val timerTicker = object : Runnable {
        override fun run() {
            val end = getSharedPreferences("mimax_launcher", Context.MODE_PRIVATE)
                .getLong("island_timer_end", 0L)
            val prefs = getSharedPreferences("mimax_launcher", Context.MODE_PRIVATE)
            val paused = prefs.getBoolean("island_timer_paused", false)
            val remaining = if (paused) prefs.getLong("island_timer_remaining", 0L)
                else end - System.currentTimeMillis()
            timerStatusText = if (remaining > 0L) {
                val seconds = (remaining / 1000L).coerceAtLeast(0L)
                (if (paused) "Timer paused • " else "Timer • ") +
                    "%02d:%02d".format(Locale.getDefault(), seconds / 60, seconds % 60)
            } else {
                if (end != 0L || paused) prefs.edit()
                    .remove("island_timer_end").remove("island_timer_remaining")
                    .putBoolean("island_timer_paused", false).apply()
                ""
            }
            if (remaining > 0L && island == null && activeKey == null) showStandaloneTimer()
            if (remaining <= 0L && activeKey == null) removeIsland()
            updateActivityMessages()
            if (!paused && end > System.currentTimeMillis()) main.postDelayed(this, 1000L)
        }
    }
    private var batteryReceiverRegistered = false
    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == MiMaxIslandPreferences.ACTION_TIMER_UPDATED) {
                main.removeCallbacks(timerTicker)
                timerTicker.run()
                return
            }
            if (intent.action == "app.lawnchair.mimax.ACTION_FOCUS_MODE_CHANGED") {
                if (getSharedPreferences("mimax_launcher", Context.MODE_PRIVATE)
                        .getBoolean("focus_mode_enabled", false)) {
                    removeIsland()
                    removeFloatingPanel()
                } else {
                    val latest = activeEvents.values.lastOrNull()
                    if (latest != null) show(latest)
                    else if (timerStatusText.isNotBlank()) showStandaloneTimer()
                }
                return
            }
            if (intent.action == MiMaxIslandPreferences.ACTION_THEME_UPDATED) {
                style = MiMaxIslandPreferences.style(this@MiMaxIslandService)
                val active = activeEvents[activeKey]
                removeIsland()
                if (active != null) show(active)
                else if (timerStatusText.isNotBlank()) showStandaloneTimer()
                floatingPanel?.background = islandBackground()
                return
            }
            if (intent.action == Intent.ACTION_TIME_TICK) {
                clockStatusText = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
                updateActivityMessages()
                return
            }
            if (intent.action != Intent.ACTION_BATTERY_CHANGED) return
            val level = intent.getIntExtra("level", -1)
            val scale = intent.getIntExtra("scale", 100).coerceAtLeast(1)
            val percent = if (level >= 0) (level * 100 / scale) else -1
            val plugged = intent.getIntExtra("plugged", 0) != 0
            val status = if (plugged) "Charging" else "Battery"
            batteryStatusText = if (percent >= 0) "$status • $percent%" else status
            island?.findViewWithTag<TextView>("message")?.let { view ->
                view.text = displayMessage()
                view.visibility = if (expanded) View.VISIBLE else View.GONE
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        runCatching {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_BATTERY_CHANGED)
                addAction(Intent.ACTION_TIME_TICK)
                addAction(MiMaxIslandPreferences.ACTION_TIMER_UPDATED)
                addAction(MiMaxIslandPreferences.ACTION_THEME_UPDATED)
                addAction("app.lawnchair.mimax.ACTION_FOCUS_MODE_CHANGED")
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(batteryReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                registerReceiver(batteryReceiver, filter)
            }
            batteryReceiverRegistered = true
        }
        timerTicker.run()
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        activeEvents.clear()
        activeNotifications?.forEach { activeEvents[it.key] = it }
        activeEvents.values.lastOrNull()?.let { show(it) }
        timerTicker.run()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        main.post {
            activeEvents[sbn.key] = sbn
            show(sbn)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        main.post {
            activeEvents.remove(sbn.key)
            island?.findViewWithTag<LinearLayout>("event_switcher")?.visibility =
                if (expanded && activeEvents.size > 1) View.VISIBLE else View.GONE
            island?.findViewWithTag<TextView>("event_count")?.text = eventPositionLabel()
            if (sbn.key == activeKey) {
                activeEvents.values.lastOrNull()?.let { show(it) } ?: removeIsland()
            }
        }
    }

    private fun show(sbn: StatusBarNotification) {
        if (!MiMaxIslandPreferences.enabled(this) || !Settings.canDrawOverlays(this)) return
        if (MiMaxCrashRecovery.isDisabled(this)) {
            removeIsland()
            removeFloatingPanel()
            return
        }
        if (getSharedPreferences("mimax_launcher", Context.MODE_PRIVATE)
                .getBoolean("focus_mode_enabled", false)) {
            removeIsland()
            removeFloatingPanel()
            return
        }
        activeKey = sbn.key
        style = MiMaxIslandPreferences.style(this)
        refreshMediaSession()
        val extras = sbn.notification.extras
        val notificationTitle = extras.getCharSequence("android.title")?.toString()?.take(80)
            ?: appLabel(sbn.packageName)
        val notificationMessage = (extras.getCharSequence("android.text")
            ?: extras.getCharSequence("android.bigText"))?.toString()?.take(180).orEmpty()
        val mediaMetadata = mediaController?.metadata
        val eventPrefix = eventPrefix(sbn)
        val title = (mediaMetadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?.takeIf { it.isNotBlank() } ?: notificationTitle).let { base ->
                if (eventPrefix.isBlank()) base else "$eventPrefix • $base"
            }
        val baseMessage = if (mediaMetadata != null) {
            listOfNotNull(mediaMetadata.getString(MediaMetadata.METADATA_KEY_ARTIST),
                mediaMetadata.getString(MediaMetadata.METADATA_KEY_ALBUM)).joinToString(" • ")
                .ifBlank { notificationMessage }
        } else notificationMessage
        val progress = extras.getInt("android.progress", -1)
        val progressMax = extras.getInt("android.progressMax", 0)
        val message = if (eventPrefix == "DOWNLOAD" && progress >= 0 && progressMax > 0) {
            "$baseMessage • ${(progress * 100 / progressMax).coerceIn(0, 100)}%"
        } else baseMessage
        lastMessage = message
        floatingPanel?.findViewWithTag<TextView>("float_title")?.text = title
        floatingPanel?.findViewWithTag<TextView>("float_message")?.text = displayMessage()
        if (title.isBlank() && message.isBlank()) return

        main.removeCallbacks(dismiss)
        if (island == null) {
            val islandView = makeView(title, message)
            island = islandView
            val params = WindowManager.LayoutParams(
                dp(220), dp(when (style) { IslandStyle.HIS -> 58; IslandStyle.LTIN -> 48; IslandStyle.COLOROS -> 54 }),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                x = getSharedPreferences("mimax_launcher", Context.MODE_PRIVATE)
                    .getInt("island_position_x", 0)
                y = getSharedPreferences("mimax_launcher", Context.MODE_PRIVATE)
                    .getInt("island_position_y", dp(8)).coerceAtLeast(dp(4))
            }
            val windowManager = wm
            if (windowManager != null) {
                runCatching { windowManager.addView(islandView, params) }
                    .onSuccess { MiMaxCrashRecovery.recordSuccess(this) }
                    .onFailure { failure ->
                        island = null
                        if (MiMaxCrashRecovery.recordFailure(this, failure)) {
                            removeFloatingPanel()
                        }
                    }
            } else {
                island = null
            }
        } else {
            island?.findViewWithTag<TextView>("title")?.text = title
            island?.findViewWithTag<LinearLayout>("event_switcher")?.visibility =
                if (activeEvents.size > 1) View.VISIBLE else View.GONE
            island?.findViewWithTag<TextView>("event_count")?.text = eventPositionLabel()
            island?.findViewWithTag<TextView>("message")?.apply {
                text = displayMessage()
                visibility = if (expanded && message.isNotBlank()) View.VISIBLE else View.GONE
            }
            island?.findViewWithTag<LinearLayout>("media_controls")?.visibility =
                if (expanded && mediaController != null) View.VISIBLE else View.GONE
            island?.findViewWithTag<TextView>("media_play")?.text =
                if (mediaController?.playbackState?.state == PlaybackState.STATE_PLAYING) "Ⅱ" else "▶"
            island?.findViewWithTag<LinearLayout>("notification_actions")?.let {
                populateNotificationActions(it)
            }
            island?.background = islandBackground()
            island?.let { updateIslandSize(it) }
        }
        if (!isPersistentActivity(sbn)) main.postDelayed(dismiss, 6500L)
    }

    private fun eventPrefix(sbn: StatusBarNotification): String {
        val notification = sbn.notification
        val category = notification.category.orEmpty()
        val extras = notification.extras
        val text = buildString {
            append(extras.getCharSequence("android.title") ?: "")
            append(' ')
            append(extras.getCharSequence("android.text") ?: "")
            append(' ')
            append(extras.getCharSequence("android.subText") ?: "")
        }.lowercase()
        return when {
            category == Notification.CATEGORY_CALL -> "CALL"
            category == Notification.CATEGORY_NAVIGATION -> "NAVIGATION"
            category == Notification.CATEGORY_ALARM ||
                category == Notification.CATEGORY_REMINDER -> "TIMER"
            (notification.flags and Notification.FLAG_ONGOING_EVENT) != 0 &&
                (text.contains("download") || text.contains("downloading") ||
                    text.contains("تنزيل") || text.contains("تحميل")) -> "DOWNLOAD"
            text.contains("screen recording") || text.contains("recording screen") ||
                text.contains("تسجيل الشاشة") || text.contains("مسجل الشاشة") -> "SCREEN RECORDING"
            text.contains("voice recording") || text.contains("recording audio") ||
                text.contains("تسجيل صوت") -> "VOICE RECORDING"
            text.contains("stopwatch") || text.contains("timer") ||
                text.contains("مؤقت") || text.contains("ساعة الإيقاف") -> "TIMER"
            else -> ""
        }
    }

    private fun isPersistentActivity(sbn: StatusBarNotification): Boolean {
        val category = sbn.notification.category
        return sbn.isOngoing || category == Notification.CATEGORY_CALL ||
            category == Notification.CATEGORY_NAVIGATION ||
            category == Notification.CATEGORY_ALARM ||
            category == Notification.CATEGORY_REMINDER
    }

    private fun displayMessage(): String =
        listOf(lastMessage, batteryStatusText, clockStatusText, timerStatusText)
            .filter { it.isNotBlank() }.joinToString("  •  ")

    private fun updateActivityMessages() {
        island?.findViewWithTag<TextView>("message")?.let {
            it.text = displayMessage()
            it.visibility = if (expanded) View.VISIBLE else View.GONE
        }
        floatingPanel?.findViewWithTag<TextView>("float_message")?.text = displayMessage()
    }

    private fun refreshMediaSession() {
        mediaController = runCatching {
            getSystemService(MediaSessionManager::class.java)
                ?.getActiveSessions(ComponentName(this, MiMaxIslandService::class.java))
                ?.firstOrNull { controller ->
                    val state = controller.playbackState?.state
                    state == PlaybackState.STATE_PLAYING ||
                        state == PlaybackState.STATE_PAUSED ||
                        state == PlaybackState.STATE_BUFFERING
                }
        }.getOrNull()
    }

    private fun populateNotificationActions(container: LinearLayout) {
        container.removeAllViews()
        val actions = activeEvents[activeKey]?.notification?.actions.orEmpty().take(3)
        if (actions.isEmpty()) {
            container.visibility = View.GONE
            return
        }
        actions.forEach { action ->
            val label = action.title?.toString()?.take(18).orEmpty()
            if (label.isNotBlank()) {
                container.addView(TextView(this).apply {
                    text = label
                    textSize = 11f
                    gravity = Gravity.CENTER
                    setTextColor(0xFFB9D7FF.toInt())
                    setPadding(dp(8), dp(5), dp(8), dp(5))
                    isClickable = true
                    setOnClickListener {
                        runCatching { action.actionIntent?.send() }
                    }
                }, LinearLayout.LayoutParams(0, -2, 1f))
            }
        }
        container.visibility = if (expanded && container.childCount > 0) View.VISIBLE else View.GONE
    }

    private fun eventPositionLabel(): String {
        val items = activeEvents.keys.toList()
        val index = items.indexOf(activeKey)
        return if (items.isEmpty() || index < 0) "0/0" else "${index + 1}/${items.size}"
    }

    private fun cycleEvent(direction: Int) {
        val items = activeEvents.values.toList()
        if (items.size < 2) return
        val index = items.indexOfFirst { it.key == activeKey }.let { if (it < 0) 0 else it }
        val next = (index + direction + items.size) % items.size
        show(items[next])
    }

    private fun makeView(title: String, message: String): LinearLayout {
        val root = LinearLayout(this).apply {
            orientation = if (style == IslandStyle.COLOROS) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(
                dp(if (style == IslandStyle.LTIN) 20 else 14),
                dp(if (style == IslandStyle.HIS) 10 else 8),
                dp(if (style == IslandStyle.LTIN) 20 else 14),
                dp(if (style == IslandStyle.HIS) 10 else 8),
            )
            background = islandBackground()
            elevation = dp(14).toFloat()
            isClickable = true
            contentDescription = "Me Island: $title"
            var downX = 0f
            var downY = 0f
            var startX = 0
            var startY = 0
            var dragging = false
            setOnTouchListener { view, event ->
                when (event.action) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        downX = event.rawX
                        downY = event.rawY
                        val params = view.layoutParams as? WindowManager.LayoutParams
                        startX = params?.x ?: 0
                        startY = params?.y ?: dp(8)
                        dragging = false
                        true
                    }
                    android.view.MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - downX
                        val dy = event.rawY - downY
                        if (!dragging && (kotlin.math.abs(dx) > dp(10) || kotlin.math.abs(dy) > dp(10))) {
                            dragging = true
                        }
                        if (dragging) {
                            (view.layoutParams as? WindowManager.LayoutParams)?.let { params ->
                                params.x = startX + dx.toInt()
                                params.y = (startY + dy.toInt()).coerceAtLeast(dp(4))
                                runCatching { wm?.updateViewLayout(view, params) }
                            }
                        }
                        true
                    }
                    android.view.MotionEvent.ACTION_UP -> {
                        val dx = event.rawX - downX
                        val dy = event.rawY - downY
                        if (dragging) {
                            (view.layoutParams as? WindowManager.LayoutParams)?.let { params ->
                                getSharedPreferences("mimax_launcher", Context.MODE_PRIVATE).edit()
                                    .putInt("island_position_x", params.x)
                                    .putInt("island_position_y", params.y)
                                    .apply()
                            }
                        } else if (kotlin.math.abs(dx) > dp(28) && kotlin.math.abs(dy) < dp(24)) {
                            cycleEvent(if (dx < 0) 1 else -1)
                        } else {
                            view.performClick()
                        }
                        true
                    }
                    android.view.MotionEvent.ACTION_CANCEL -> {
                        dragging = false
                        true
                    }
                    else -> true
                }
            }
            setOnLongClickListener {
                val current = activeEvents[activeKey]
                runCatching { current?.notification?.contentIntent?.send() }
                true
            }
            setOnClickListener {
                expanded = !expanded
                findViewWithTag<TextView>("message")?.visibility =
                    if (expanded && findViewWithTag<TextView>("message")?.text?.isNotBlank() == true) View.VISIBLE else View.GONE
                findViewWithTag<LinearLayout>("media_controls")?.visibility =
                    if (expanded && mediaController != null) View.VISIBLE else View.GONE
                findViewWithTag<LinearLayout>("notification_actions")?.visibility =
                    if (expanded && (findViewWithTag<LinearLayout>("notification_actions")?.childCount ?: 0) > 0) View.VISIBLE else View.GONE
                findViewWithTag<LinearLayout>("event_switcher")?.visibility =
                    if (expanded && activeEvents.size > 1) View.VISIBLE else View.GONE
                updateIslandSize(this)
                animate().scaleX(if (expanded && !reduceMotionEnabled()) 1.025f else 1f)
                    .scaleY(if (expanded && !reduceMotionEnabled()) 1.025f else 1f)
                    .setDuration(animationDuration(180L)).start()
            }
        }

        if (style == IslandStyle.COLOROS) {
            val accent = View(this).apply {
                background = GradientDrawable().apply {
                    setColor(getSharedPreferences("mimax_launcher", Context.MODE_PRIVATE)
                        .getInt("mt1_accent_color", 0xFF8C9BFF.toInt()))
                    cornerRadius = dp(4).toFloat()
                }
            }
            root.addView(accent, LinearLayout.LayoutParams(dp(4), -1).apply {
                marginEnd = dp(10)
            })
        }

        val content = if (style == IslandStyle.COLOROS) {
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
            }
        } else root

        if (style == IslandStyle.HIS) {
            content.addView(TextView(this).apply {
                text = "●  LIVE ACTIVITY"
                setTextColor(0xFF9EB6FF.toInt())
                textSize = 8f
                letterSpacing = 0.08f
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(-1, -2))
        } else if (style == IslandStyle.LTIN) {
            content.addView(TextView(this).apply {
                text = "●"
                setTextColor(0xFF7DDBA5.toInt())
                textSize = 7f
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(-1, dp(8)))
        }

        val eventSwitcher = LinearLayout(this).apply {
            tag = "event_switcher"
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            visibility = if (expanded && activeEvents.size > 1) View.VISIBLE else View.GONE
        }
        fun eventButton(label: String, direction: Int) = TextView(this).apply {
            text = label
            textSize = 15f
            setTextColor(0xFFB9D7FF.toInt())
            gravity = Gravity.CENTER
            isClickable = true
            setPadding(dp(18), dp(2), dp(18), dp(2))
            setOnClickListener { cycleEvent(direction) }
        }
        eventSwitcher.addView(eventButton("‹", -1))
        eventSwitcher.addView(TextView(this).apply {
            tag = "event_count"
            text = eventPositionLabel()
            textSize = 10f
            setTextColor(0xFFB9C0D4.toInt())
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(0, -2, 1f))
        eventSwitcher.addView(eventButton("›", 1))
        content.addView(eventSwitcher, LinearLayout.LayoutParams(-1, -2))

        content.addView(TextView(this).apply {
            tag = "title"
            text = title
            setTextColor(Color.WHITE)
            textSize = when (style) {
                IslandStyle.HIS -> 12f
                IslandStyle.LTIN -> 13f
                IslandStyle.COLOROS -> 13f
            }
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            maxLines = 1
            gravity = if (style == IslandStyle.COLOROS) Gravity.START else Gravity.CENTER
        }, LinearLayout.LayoutParams(-1, -2))

        content.addView(TextView(this).apply {
            tag = "message"
            text = message
            setTextColor(if (style == IslandStyle.COLOROS) 0xFFB9C0D4.toInt() else 0xFFE6E6E6.toInt())
            textSize = 11f
            maxLines = 2
            gravity = if (style == IslandStyle.COLOROS) Gravity.START else Gravity.CENTER
            visibility = View.GONE
        }, LinearLayout.LayoutParams(-1, -2))

        val notificationActions = LinearLayout(this).apply {
            tag = "notification_actions"
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        populateNotificationActions(notificationActions)
        content.addView(notificationActions, LinearLayout.LayoutParams(-1, -2))

        val controls = LinearLayout(this).apply {
            tag = "media_controls"
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        fun mediaButton(label: String, tagName: String, action: () -> Unit) =
            TextView(this).apply {
                tag = tagName
                text = label
                setTextColor(Color.WHITE)
                textSize = 17f
                gravity = Gravity.CENTER
                isClickable = true
                setOnClickListener { action() }
                setPadding(dp(18), dp(4), dp(18), dp(4))
            }
        controls.addView(mediaButton("⏮", "media_previous") {
            runCatching { mediaController?.transportControls?.skipToPrevious() }
        })
        controls.addView(mediaButton(
            if (mediaController?.playbackState?.state == PlaybackState.STATE_PLAYING) "Ⅱ" else "▶",
            "media_play",
        ) {
            runCatching {
                val controls = mediaController?.transportControls
                if (mediaController?.playbackState?.state == PlaybackState.STATE_PLAYING) controls?.pause()
                else controls?.play()
            }
            island?.findViewWithTag<TextView>("media_play")?.text =
                if (mediaController?.playbackState?.state == PlaybackState.STATE_PLAYING) "Ⅱ" else "▶"
        })
        controls.addView(mediaButton("⏭", "media_next") {
            runCatching { mediaController?.transportControls?.skipToNext() }
        })
        controls.addView(mediaButton("▣", "float_toggle") {
            toggleFloatingPanel(title = island?.findViewWithTag<TextView>("title")?.text?.toString().orEmpty())
        })
        content.addView(controls, LinearLayout.LayoutParams(-1, -2))
        content.addView(TextView(this).apply {
            text = if (floatingPanel == null) "▣  نافذة عائمة" else "إغلاق النافذة العائمة"
            setTextColor(0xFFB9D7FF.toInt())
            textSize = 10f
            gravity = Gravity.CENTER
            isClickable = true
            setOnClickListener {
                toggleFloatingPanel(title = island?.findViewWithTag<TextView>("title")?.text?.toString().orEmpty())
                text = if (floatingPanel == null) "▣  نافذة عائمة" else "إغلاق النافذة العائمة"
            }
        }, LinearLayout.LayoutParams(-1, -2))

        if (content !== root) {
            root.addView(content, LinearLayout.LayoutParams(0, -1, 1f))
        }
        if (reduceMotionEnabled()) {
            root.alpha = 1f
            root.scaleX = 1f
            root.scaleY = 1f
        } else {
            root.alpha = 0f
            root.scaleX = .82f
            root.scaleY = .82f
            root.animate().alpha(1f).scaleX(1f).scaleY(1f)
                .setDuration(animationDuration(220L)).start()
        }
        return root
    }

    private fun showStandaloneTimer() {
        if (!MiMaxIslandPreferences.enabled(this) || !Settings.canDrawOverlays(this)) return
        if (MiMaxCrashRecovery.isDisabled(this)) return
        val windowManager = wm ?: return
        if (getSharedPreferences("mimax_launcher", Context.MODE_PRIVATE)
                .getBoolean("focus_mode_enabled", false)) return
        style = MiMaxIslandPreferences.style(this)
        val view = makeView("Timer", displayMessage())
        island = view
        val params = WindowManager.LayoutParams(
            dp(220), dp(when (style) {
                IslandStyle.HIS -> 58
                IslandStyle.LTIN -> 48
                IslandStyle.COLOROS -> 54
            }),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            x = getSharedPreferences("mimax_launcher", Context.MODE_PRIVATE)
                .getInt("island_position_x", 0)
            y = getSharedPreferences("mimax_launcher", Context.MODE_PRIVATE)
                .getInt("island_position_y", dp(8)).coerceAtLeast(dp(4))
        }
        runCatching { windowManager.addView(view, params) }
            .onSuccess { MiMaxCrashRecovery.recordSuccess(this) }
            .onFailure { failure ->
                island = null
                if (MiMaxCrashRecovery.recordFailure(this, failure)) removeFloatingPanel()
            }
    }

    private fun toggleFloatingPanel(title: String) {
        if (MiMaxCrashRecovery.isDisabled(this)) return
        if (floatingPanel != null) {
            removeFloatingPanel()
            return
        }
        if (!Settings.canDrawOverlays(this)) return
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(14))
            background = islandBackground()
            elevation = dp(18).toFloat()
            addView(TextView(this@MiMaxIslandService).apply {
                tag = "float_title"
                text = title
                setTextColor(Color.WHITE)
                textSize = 15f
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                maxLines = 1
                var downX = 0f
                var downY = 0f
                var startX = 0
                var startY = 0
                setOnTouchListener { view, event ->
                    when (event.action) {
                        android.view.MotionEvent.ACTION_DOWN -> {
                            downX = event.rawX
                            downY = event.rawY
                            val lp = floatingPanel?.layoutParams as? WindowManager.LayoutParams
                            startX = lp?.x ?: 0
                            startY = lp?.y ?: 0
                            true
                        }
                        android.view.MotionEvent.ACTION_MOVE -> {
                            val lp = floatingPanel?.layoutParams as? WindowManager.LayoutParams
                            if (lp != null) {
                                lp.x = startX + (event.rawX - downX).toInt()
                                lp.y = startY + (event.rawY - downY).toInt()
                                floatingPanel?.let { panel ->
                                    runCatching { wm?.updateViewLayout(panel, lp) }
                                }
                            }
                            true
                        }
                        else -> true
                    }
                }
            }, LinearLayout.LayoutParams(-1, -2))
            addView(TextView(this@MiMaxIslandService).apply {
                tag = "float_message"
                text = displayMessage()
                setTextColor(0xFFE6E6E6.toInt())
                textSize = 12f
                maxLines = 3
                setPadding(0, dp(6), 0, dp(8))
            }, LinearLayout.LayoutParams(-1, -2))
            addView(TextView(this@MiMaxIslandService).apply {
                text = "إغلاق النافذة  ×"
                setTextColor(0xFFB9D7FF.toInt())
                textSize = 12f
                gravity = Gravity.END
                isClickable = true
                setOnClickListener { removeFloatingPanel() }
            }, LinearLayout.LayoutParams(-1, -2))
        }
        val params = WindowManager.LayoutParams(
            dp(280), WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.CENTER }
        val windowManager = wm ?: return
        runCatching { windowManager.addView(panel, params) }
            .onSuccess {
                floatingPanel = panel
                MiMaxCrashRecovery.recordSuccess(this)
            }
            .onFailure { failure ->
                floatingPanel = null
                if (MiMaxCrashRecovery.recordFailure(this, failure)) removeIsland()
            }
    }

    private fun removeFloatingPanel() {
        floatingPanel?.let { runCatching { wm?.removeView(it) } }
        floatingPanel = null
    }

    private fun reduceMotionEnabled(): Boolean =
        getSharedPreferences("mimax_launcher", Context.MODE_PRIVATE)
            .getBoolean("island_reduce_motion", false)

    private fun animationDuration(default: Long): Long {
        if (reduceMotionEnabled()) return 0L
        return getSharedPreferences("mimax_launcher", Context.MODE_PRIVATE)
            .getInt("island_animation_duration", default.toInt())
            .coerceIn(80, 500).toLong()
    }

    private fun updateIslandSize(view: View) {
        val params = view.layoutParams as? WindowManager.LayoutParams ?: return
        val customWidth = getSharedPreferences("mimax_launcher", Context.MODE_PRIVATE)
            .getInt("mt1_width", 320)
        params.width = dp(if (expanded) customWidth else 220)
        params.height = dp(when {
            expanded -> if (style == IslandStyle.COLOROS) 124 else 116
            style == IslandStyle.HIS -> 58
            style == IslandStyle.LTIN -> 48
            else -> 54
        })
        runCatching { wm?.updateViewLayout(view, params) }
    }

    private fun islandBackground() = GradientDrawable().apply {
        val prefs = getSharedPreferences("mimax_launcher", Context.MODE_PRIVATE)
        setColor(prefs.getInt("mt1_background_color", when (style) {
            IslandStyle.HIS -> 0xF0181D2B.toInt()
            IslandStyle.LTIN -> 0xF0060608.toInt()
            IslandStyle.COLOROS -> 0xF0262730.toInt()
        }))
        cornerRadius = dp(prefs.getInt("mt1_corner_radius", when (style) {
            IslandStyle.HIS -> 22
            IslandStyle.LTIN -> 36
            IslandStyle.COLOROS -> 16
        })).toFloat()
        val accent = prefs.getInt("mt1_accent_color", 0xFF8C9BFF.toInt())
        setStroke(dp(1), accent)
    }

    private fun removeIsland() {
        main.removeCallbacks(dismiss)
        island?.let { view ->
            if (reduceMotionEnabled()) {
                runCatching { wm?.removeView(view) }
            } else {
                view.animate().alpha(0f).scaleX(.82f).scaleY(.82f)
                    .setDuration(animationDuration(140L))
                    .withEndAction { runCatching { wm?.removeView(view) } }.start()
            }
        }
        island = null
        activeKey = null
        expanded = false
    }

    override fun onListenerDisconnected() {
        removeFloatingPanel()
        removeIsland()
        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        if (batteryReceiverRegistered) runCatching { unregisterReceiver(batteryReceiver) }
        batteryReceiverRegistered = false
        main.removeCallbacks(timerTicker)
        removeFloatingPanel()
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
    const val ACTION_TIMER_UPDATED = "app.lawnchair.mimax.TIMER_UPDATED"
    const val ACTION_THEME_UPDATED = "app.lawnchair.mimax.THEME_UPDATED"
    private const val PREFS = "mimax_launcher"
    private const val TEST_CHANNEL = "mimax_island_test"
    private const val TEST_NOTIFICATION_ID = 91427

    /** Posts a real Android notification so the user can verify listener + overlay integration. */
    fun postTestNotification(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(TEST_CHANNEL, "Me Island test", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
        val notification = Notification.Builder(context, TEST_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Mi_Max Launcher")
            .setContentText("Me Island is receiving notifications")
            .setCategory(Notification.CATEGORY_EVENT)
            .setAutoCancel(true)
            .build()
        manager.notify(TEST_NOTIFICATION_ID, notification)
    }
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
