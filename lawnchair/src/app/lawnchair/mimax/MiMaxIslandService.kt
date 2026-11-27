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
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
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
    private var weatherStatusText = ""
    private var callStatusText = ""
    private var telephonyManager: TelephonyManager? = null
    private var phoneStateListener: PhoneStateListener? = null
    private val notificationTicker = object : Runnable {
        override fun run() {
            updateActivityMessages()
            val current = activeEvents[activeKey]
            if (current != null && hasSystemChronometer(current)) main.postDelayed(this, 1000L)
        }
    }
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
            if (intent.action == MiMaxWeatherProvider.ACTION_WEATHER_UPDATED) {
                weatherStatusText = getSharedPreferences("mimax_launcher", Context.MODE_PRIVATE)
                    .getString("island_weather_text", "").orEmpty()
                updateActivityMessages()
                if (activeKey == null && timerStatusText.isBlank() && island == null && weatherStatusText.isNotBlank()) {
                    showStandaloneActivity("Weather")
                }
                return
            }
            if (intent.action == MiMaxIslandPreferences.ACTION_CALL_MONITOR_PERMISSION_UPDATED) {
                registerCallStateMonitor()
                return
            }
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
                    else if (batteryStatusText.startsWith("Charging")) showStandaloneActivity("Charging")
                }
                return
            }
            if (intent.action == MiMaxIslandPreferences.ACTION_THEME_UPDATED) {
                style = MiMaxIslandPreferences.style(this@MiMaxIslandService)
                val active = activeEvents[activeKey]
                removeIsland()
                if (active != null) show(active)
                else if (timerStatusText.isNotBlank()) showStandaloneTimer()
                else if (batteryStatusText.startsWith("Charging")) showStandaloneActivity("Charging")
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
            val wasCharging = batteryStatusText.startsWith("Charging")
            val status = if (plugged) "Charging" else "Battery"
            batteryStatusText = if (percent >= 0) "$status • $percent%" else status
            if (plugged && !wasCharging && island == null && activeKey == null && timerStatusText.isBlank()) {
                showStandaloneActivity("Charging")
                main.removeCallbacks(dismiss)
                main.postDelayed(dismiss, 5000L)
            } else if (!plugged && wasCharging && activeKey == null && timerStatusText.isBlank()) {
                removeIsland()
            }
            updateActivityMessages()
        }
    }

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        weatherStatusText = getSharedPreferences("mimax_launcher", Context.MODE_PRIVATE)
            .getString("island_weather_text", "").orEmpty()
        runCatching {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_BATTERY_CHANGED)
                addAction(Intent.ACTION_TIME_TICK)
                addAction(MiMaxIslandPreferences.ACTION_TIMER_UPDATED)
                addAction(MiMaxIslandPreferences.ACTION_CALL_MONITOR_PERMISSION_UPDATED)
                addAction(MiMaxWeatherProvider.ACTION_WEATHER_UPDATED)
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
        registerCallStateMonitor()
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        activeEvents.clear()
        activeNotifications?.forEach { activeEvents[it.key] = it }
        activeEvents.values.lastOrNull()?.let { show(it) }
        timerTicker.run()
        registerCallStateMonitor()
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
        val mediaMetadata = if (isCurrentMediaActivity(sbn)) mediaController?.metadata else null
        val eventPrefix = eventPrefix(sbn)
        val title = mediaMetadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?.takeIf { it.isNotBlank() } ?: notificationTitle
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
        main.removeCallbacks(notificationTicker)
        if (hasSystemChronometer(sbn)) main.postDelayed(notificationTicker, 1000L)
        floatingPanel?.findViewWithTag<TextView>("float_title")?.text = title
        floatingPanel?.findViewWithTag<TextView>("float_message")?.text = displayMessage()
        if (title.isBlank() && message.isBlank()) return

        main.removeCallbacks(dismiss)
        if (island == null) {
            val islandView = makeView(title)
            island = islandView
            val params = WindowManager.LayoutParams(
                dp(when (style) { IslandStyle.HIS -> 208; IslandStyle.LTIN -> 126; IslandStyle.COLOROS -> 224 }),
                dp(when (style) { IslandStyle.HIS -> 58; IslandStyle.LTIN -> 42; IslandStyle.COLOROS -> 52 }),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            ).apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                }
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                val positionPrefs = getSharedPreferences("mimax_launcher", Context.MODE_PRIVATE)
                val customPosition = positionPrefs.getBoolean("island_position_custom", false)
                x = if (customPosition) positionPrefs.getInt("island_position_x", 0)
                    else defaultIslandXOffset()
                y = if (customPosition) positionPrefs.getInt("island_position_y", dp(8))
                    else defaultIslandYOffset(when (style) {
                        IslandStyle.HIS -> 58
                        IslandStyle.LTIN -> 42
                        IslandStyle.COLOROS -> 52
                    })
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
            island?.findViewWithTag<TextView>("activity_icon")?.apply {
                text = if (style == IslandStyle.LTIN && mediaArtwork() != null) "" else activityIcon(sbn)
                background = activityIconBackground()
            }
            island?.findViewWithTag<TextView>("activity_eyebrow")?.text = activityEyebrow(sbn)
            island?.findViewWithTag<TextView>("activity_status")?.text = activityStatusLabel(sbn)
            island?.findViewWithTag<android.widget.ProgressBar>("activity_progress")?.let { bar ->
                val progress = sbn.notification.extras.getInt("android.progress", -1)
                val maximum = sbn.notification.extras.getInt("android.progressMax", 0)
                bar.max = maximum.coerceAtLeast(1)
                bar.progress = progress.coerceIn(0, bar.max)
                bar.visibility = if (maximum > 0 && progress >= 0) View.VISIBLE else View.GONE
            }
            island?.findViewWithTag<LinearLayout>("event_switcher")?.visibility =
                if (activeEvents.size > 1) View.VISIBLE else View.GONE
            island?.findViewWithTag<TextView>("event_count")?.text = eventPositionLabel()
            island?.findViewWithTag<TextView>("message")?.apply {
                text = primaryActivityMessage(sbn)
                visibility = if (expanded && text.isNotBlank()) View.VISIBLE else View.GONE
            }
            island?.findViewWithTag<LinearLayout>("media_controls")?.visibility =
                if (expanded && isCurrentMediaActivity(sbn)) View.VISIBLE else View.GONE
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
        val hasProgress = extras.getInt("android.progress", -1) >= 0 &&
            extras.getInt("android.progressMax", 0) > 0
        val ongoing = sbn.isOngoing ||
            (notification.flags and Notification.FLAG_ONGOING_EVENT) != 0
        val hasCallAction = notification.actions.orEmpty().any { action ->
            val label = action.title?.toString()?.lowercase().orEmpty()
            listOf("answer", "accept", "decline", "reject", "hang up", "رد", "قبول", "رفض").any { keyword -> label.contains(keyword) }
        }
        val callText = text.contains("incoming call") || text.contains("ongoing call") ||
            text.contains("call in progress") || text.contains("مكالمة واردة") ||
            text.contains("اتصال وارد") || text.contains("مكالمة جارية") ||
            text.contains("مكالمة نشطة")
        val hasCallChronometer = extras.getBoolean("android.showChronometer", false)
        val timerPackage = sbn.packageName.lowercase().let {
            it.contains("deskclock") || it.contains("clock") ||
                it.contains("alarm")
        }
        val timerText = text.contains("timer") || text.contains("countdown") ||
            text.contains("stopwatch") || text.contains("مؤقت") ||
            text.contains("العد التنازلي") || text.contains("ساعة الإيقاف")
        val timerNotification = timerText && (ongoing || timerPackage ||
            category == Notification.CATEGORY_ALARM ||
            extras.getBoolean("android.showChronometer", false))
        return when {
            (category == Notification.CATEGORY_CALL && (ongoing || hasCallAction || hasCallChronometer)) ||
                (hasCallAction && callText) || (ongoing && callText) -> "CALL"
            category == Notification.CATEGORY_NAVIGATION -> "NAVIGATION"
            category == Notification.CATEGORY_ALARM ||
                category == Notification.CATEGORY_REMINDER || timerNotification -> "TIMER"
            hasProgress || (ongoing && (text.contains("download") || text.contains("downloading") ||
                text.contains("تنزيل") || text.contains("تحميل"))) -> "DOWNLOAD"
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
        return sbn.isOngoing || eventPrefix(sbn) == "CALL" ||
            eventPrefix(sbn) == "TIMER" || category == Notification.CATEGORY_NAVIGATION ||
            category == Notification.CATEGORY_ALARM ||
            category == Notification.CATEGORY_REMINDER
    }

    @Suppress("DEPRECATION")
    private fun registerCallStateMonitor() {
        if (checkSelfPermission(android.Manifest.permission.READ_PHONE_STATE) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED) return
        val manager = getSystemService(TELEPHONY_SERVICE) as? TelephonyManager ?: return
        if (phoneStateListener != null) return
        telephonyManager = manager
        val listener = object : PhoneStateListener() {
            override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                callStatusText = when (state) {
                    TelephonyManager.CALL_STATE_RINGING -> "Incoming call"
                    TelephonyManager.CALL_STATE_OFFHOOK -> "Call in progress"
                    else -> ""
                }
                if (callStatusText.isNotBlank() &&
                    MiMaxIslandPreferences.enabled(this@MiMaxIslandService) &&
                    Settings.canDrawOverlays(this@MiMaxIslandService) &&
                    !getSharedPreferences("mimax_launcher", Context.MODE_PRIVATE)
                        .getBoolean("focus_mode_enabled", false)) {
                    if (activeKey == null) showStandaloneActivity(callStatusText)
                } else if (state == TelephonyManager.CALL_STATE_IDLE && activeKey == null) {
                    removeIsland()
                }
                updateActivityMessages()
            }
        }
        phoneStateListener = listener
        manager.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
    }

    @Suppress("DEPRECATION")
    private fun unregisterCallStateMonitor() {
        val listener = phoneStateListener ?: return
        runCatching { telephonyManager?.listen(listener, PhoneStateListener.LISTEN_NONE) }
        phoneStateListener = null
        telephonyManager = null
        callStatusText = ""
    }

    private fun displayMessage(): String {
        val systemChronometer = activeEvents[activeKey]?.let(::systemChronometerText).orEmpty()
        return listOf(lastMessage, systemChronometer, callStatusText, batteryStatusText, clockStatusText, timerStatusText, weatherStatusText)
            .filter { it.isNotBlank() }.joinToString("  •  ")
    }

    private fun hasSystemChronometer(sbn: StatusBarNotification): Boolean {
        val hasBaseTime = sbn.notification.`when` > 0L
        val isSystemTimer = eventPrefix(sbn) == "TIMER"
        return hasBaseTime && (isSystemTimer ||
            sbn.notification.extras.getBoolean("android.showChronometer", false))
    }

    /**
     * Uses chronometer metadata published by the source app (for example Clock or Telecom).
     * Android does not expose another app's private timer state when it publishes no notification.
     */
    private fun systemChronometerText(sbn: StatusBarNotification): String {
        if (!hasSystemChronometer(sbn)) return ""
        val notification = sbn.notification
        val isSystemTimer = eventPrefix(sbn) == "TIMER"
        val countdown = if (isSystemTimer) notification.`when` > System.currentTimeMillis()
            else notification.extras.getBoolean("android.chronometerCountDown", false)
        val delta = if (countdown) notification.`when` - System.currentTimeMillis()
            else System.currentTimeMillis() - notification.`when`
        val seconds = delta.coerceAtLeast(0L) / 1000L
        val clock = if (seconds >= 3600L) {
            "%d:%02d:%02d".format(Locale.getDefault(), seconds / 3600L, (seconds / 60L) % 60L, seconds % 60L)
        } else {
            "%02d:%02d".format(Locale.getDefault(), seconds / 60L, seconds % 60L)
        }
        val isCall = eventPrefix(sbn) == "CALL"
        return (if (countdown) "Timer" else if (isCall) "Call" else "Elapsed") + " • " + clock
    }

    private fun updateActivityMessages() {
        island?.findViewWithTag<TextView>("message")?.let {
            it.text = primaryActivityMessage(activeEvents[activeKey])
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

    private fun makeView(title: String): LinearLayout {
        val sbn = activeEvents[activeKey]
        val accent = getSharedPreferences("mimax_launcher", Context.MODE_PRIVATE)
            .getInt("mt1_accent_color", when (style) {
                IslandStyle.HIS -> 0xFF8EA8FF.toInt()
                IslandStyle.LTIN -> 0xFF79E6A5.toInt()
                IslandStyle.COLOROS -> 0xFF69D7C2.toInt()
            })
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(
                dp(when (style) { IslandStyle.LTIN -> 12; IslandStyle.HIS -> 10; IslandStyle.COLOROS -> 12 }),
                dp(when (style) { IslandStyle.LTIN -> 7; IslandStyle.HIS -> 8; IslandStyle.COLOROS -> 8 }),
                dp(when (style) { IslandStyle.LTIN -> 14; IslandStyle.HIS -> 12; IslandStyle.COLOROS -> 12 }),
                dp(when (style) { IslandStyle.LTIN -> 7; IslandStyle.HIS -> 8; IslandStyle.COLOROS -> 8 })
            )
            background = islandBackground()
            elevation = dp(if (style == IslandStyle.LTIN) 18 else 12).toFloat()
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
                        if (!dragging && (kotlin.math.abs(dx) > dp(10) || kotlin.math.abs(dy) > dp(10))) dragging = true
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
                                    .putBoolean("island_position_custom", true).apply()
                            }
                        } else if (kotlin.math.abs(dx) > dp(28) && kotlin.math.abs(dy) < dp(24)) {
                            cycleEvent(if (dx < 0) 1 else -1)
                        } else view.performClick()
                        true
                    }
                    android.view.MotionEvent.ACTION_CANCEL -> { dragging = false; true }
                    else -> true
                }
            }
            setOnLongClickListener {
                runCatching { activeEvents[activeKey]?.notification?.contentIntent?.send() }
                true
            }
            setOnClickListener {
                expanded = !expanded
                findViewWithTag<TextView>("message")?.visibility =
                    if (expanded && findViewWithTag<TextView>("message")?.text?.isNotBlank() == true) View.VISIBLE else View.GONE
                findViewWithTag<LinearLayout>("media_controls")?.visibility =
                    if (expanded && isCurrentMediaActivity(sbn)) View.VISIBLE else View.GONE
                findViewWithTag<LinearLayout>("notification_actions")?.visibility =
                    if (expanded && (findViewWithTag<LinearLayout>("notification_actions")?.childCount ?: 0) > 0) View.VISIBLE else View.GONE
                findViewWithTag<TextView>("float_panel_button")?.visibility =
                    if (expanded) View.VISIBLE else View.GONE
                findViewWithTag<LinearLayout>("event_switcher")?.visibility =
                    if (expanded && activeEvents.size > 1) View.VISIBLE else View.GONE
                updateIslandSize(this)
                if (!reduceMotionEnabled()) {
                    animate().scaleX(if (expanded) 1.025f else 1f)
                        .scaleY(if (expanded) 1.025f else 1f)
                        .setDuration(animationDuration(190L))
                        .setInterpolator(android.view.animation.OvershootInterpolator(0.55f))
                        .start()
                }
            }
        }

        // The three layouts are intentionally different, not just three background colours.
        val iconTile = TextView(this).apply {
            tag = "activity_icon"
            text = if (style == IslandStyle.LTIN && mediaArtwork() != null) "" else activityIcon(sbn)
            gravity = Gravity.CENTER
            textSize = when (style) { IslandStyle.LTIN -> 16f; IslandStyle.HIS -> 17f; IslandStyle.COLOROS -> 15f }
            setTextColor(if (style == IslandStyle.LTIN) 0xFF050608.toInt() else Color.WHITE)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            background = activityIconBackground()
        }
        val iconSize = when (style) { IslandStyle.LTIN -> 24; IslandStyle.HIS -> 34; IslandStyle.COLOROS -> 30 }
        root.addView(iconTile, LinearLayout.LayoutParams(dp(iconSize), dp(iconSize)).apply {
            marginEnd = dp(when (style) { IslandStyle.LTIN -> 9; IslandStyle.HIS -> 10; IslandStyle.COLOROS -> 9 })
        })

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = if (style == IslandStyle.COLOROS) Gravity.CENTER_VERTICAL else Gravity.CENTER
        }
        if (style == IslandStyle.HIS) {
            content.addView(TextView(this).apply {
                tag = "activity_eyebrow"
                text = activityEyebrow(sbn)
                setTextColor(0xFF9FB7FF.toInt())
                textSize = 8f
                letterSpacing = 0.08f
                gravity = Gravity.START
            }, LinearLayout.LayoutParams(-1, -2))
        }
        val titleView = TextView(this).apply {
            tag = "title"
            text = title
            setTextColor(Color.WHITE)
            textSize = when (style) { IslandStyle.HIS -> 12f; IslandStyle.LTIN -> 12f; IslandStyle.COLOROS -> 13f }
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = if (style == IslandStyle.LTIN) Gravity.CENTER_VERTICAL else Gravity.START
        }
        content.addView(titleView, LinearLayout.LayoutParams(-1, -2))
        content.addView(TextView(this).apply {
            tag = "message"
            text = primaryActivityMessage(sbn)
            setTextColor(when (style) {
                IslandStyle.LTIN -> 0xFFB7B7C0.toInt()
                IslandStyle.HIS -> 0xFFB9C7E4.toInt()
                IslandStyle.COLOROS -> 0xFFB8C7C9.toInt()
            })
            textSize = 10f
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = Gravity.START
            visibility = if (expanded) View.VISIBLE else View.GONE
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) })

        val eventSwitcher = LinearLayout(this).apply {
            tag = "event_switcher"
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            visibility = if (expanded && activeEvents.size > 1) View.VISIBLE else View.GONE
        }
        fun eventButton(label: String, direction: Int) = TextView(this).apply {
            text = label
            textSize = 15f
            setTextColor(accent)
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
                setPadding(dp(16), dp(4), dp(16), dp(4))
            }
        controls.addView(mediaButton("⏮", "media_previous") { runCatching { mediaController?.transportControls?.skipToPrevious() } })
        controls.addView(mediaButton(if (mediaController?.playbackState?.state == PlaybackState.STATE_PLAYING) "Ⅱ" else "▶", "media_play") {
            runCatching {
                val transport = mediaController?.transportControls
                if (mediaController?.playbackState?.state == PlaybackState.STATE_PLAYING) transport?.pause() else transport?.play()
            }
            island?.findViewWithTag<TextView>("media_play")?.text =
                if (mediaController?.playbackState?.state == PlaybackState.STATE_PLAYING) "Ⅱ" else "▶"
        })
        controls.addView(mediaButton("⏭", "media_next") { runCatching { mediaController?.transportControls?.skipToNext() } })
        controls.addView(mediaButton("▣", "float_toggle") {
            toggleFloatingPanel(title = island?.findViewWithTag<TextView>("title")?.text?.toString().orEmpty())
        })
        content.addView(controls, LinearLayout.LayoutParams(-1, -2))

        content.addView(TextView(this).apply {
            tag = "float_panel_button"
            text = if (floatingPanel == null) "▣  نافذة عائمة" else "إغلاق النافذة العائمة"
            setTextColor(accent)
            textSize = 10f
            gravity = Gravity.CENTER
            isClickable = true
            visibility = if (expanded) View.VISIBLE else View.GONE
            setOnClickListener {
                toggleFloatingPanel(title = island?.findViewWithTag<TextView>("title")?.text?.toString().orEmpty())
                text = if (floatingPanel == null) "▣  نافذة عائمة" else "إغلاق النافذة العائمة"
            }
        }, LinearLayout.LayoutParams(-1, -2))

        if (style == IslandStyle.COLOROS) {
            content.addView(TextView(this).apply {
                tag = "activity_status"
                text = activityStatusLabel(sbn)
                setTextColor(accent)
                textSize = 9f
                gravity = Gravity.START
                setPadding(0, dp(3), 0, 0)
            }, LinearLayout.LayoutParams(-1, -2))
        }
        val progressExtras = sbn?.notification?.extras
        val progressMaximum = progressExtras?.getInt("android.progressMax", 0) ?: 0
        val progressValue = progressExtras?.getInt("android.progress", -1) ?: -1
        content.addView(android.widget.ProgressBar(
            this, null, android.R.attr.progressBarStyleHorizontal
        ).apply {
            tag = "activity_progress"
            max = progressMaximum.coerceAtLeast(1)
            progress = progressValue.coerceIn(0, max)
            visibility = if (progressMaximum > 0 && progressValue >= 0) View.VISIBLE else View.GONE
        }, LinearLayout.LayoutParams(-1, dp(3)).apply { topMargin = dp(4) })
        root.addView(content, LinearLayout.LayoutParams(0, -2, 1f))
        if (style == IslandStyle.LTIN && !expanded) {
            root.findViewWithTag<TextView>("message")?.visibility = View.GONE
        }
        applyIslandEnterAnimation(root)
        return root
    }

    private fun isCurrentMediaActivity(sbn: StatusBarNotification?): Boolean {
        val controller = mediaController ?: return false
        return sbn != null && (sbn.packageName == controller.packageName ||
            sbn.notification.category == Notification.CATEGORY_TRANSPORT)
    }

    private fun mediaArtwork(): android.graphics.Bitmap? {
        if (!isCurrentMediaActivity(activeEvents[activeKey])) return null
        return mediaController?.metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: mediaController?.metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
    }

    private fun activityIconBackground(): android.graphics.drawable.Drawable {
        val art = if (style == IslandStyle.LTIN) mediaArtwork() else null
        if (art != null) return android.graphics.drawable.BitmapDrawable(resources, art)
        val accent = getSharedPreferences("mimax_launcher", Context.MODE_PRIVATE)
            .getInt("mt1_accent_color", when (style) {
                IslandStyle.HIS -> 0xFF8EA8FF.toInt()
                IslandStyle.LTIN -> 0xFF79E6A5.toInt()
                IslandStyle.COLOROS -> 0xFF69D7C2.toInt()
            })
        return GradientDrawable().apply {
            setColor(when (style) {
                IslandStyle.LTIN -> accent
                IslandStyle.HIS -> 0xFF283653.toInt()
                IslandStyle.COLOROS -> accent
            })
            cornerRadius = dp(when (style) {
                IslandStyle.LTIN -> 50
                IslandStyle.HIS -> 12
                IslandStyle.COLOROS -> 9
            }).toFloat()
        }
    }

    private fun activityIcon(sbn: StatusBarNotification?): String {
        val category = sbn?.notification?.category
        val text = sbn?.notification?.extras?.let { extras ->
            (extras.getCharSequence("android.title").toString() + " " +
                extras.getCharSequence("android.text").toString()).lowercase()
        }.orEmpty()
        return when {
            category == Notification.CATEGORY_CALL || text.contains("call") || text.contains("مكالمة") -> "☎"
            category == Notification.CATEGORY_ALARM || category == Notification.CATEGORY_REMINDER ||
                text.contains("timer") || text.contains("مؤقت") -> "◷"
            text.contains("download") || text.contains("تحميل") || text.contains("تنزيل") -> "↓"
            category == Notification.CATEGORY_NAVIGATION -> "➤"
            isCurrentMediaActivity(sbn) -> "♫"
            text.contains("record") || text.contains("تسجيل") -> "●"
            sbn == null && batteryStatusText.startsWith("Charging") -> "⚡"
            sbn == null && timerStatusText.isNotBlank() -> "◷"
            else -> "●"
        }
    }

    private fun activityEyebrow(sbn: StatusBarNotification?): String = when {
        sbn != null && eventPrefix(sbn) == "CALL" ->
            if (sbn.notification.extras.getBoolean("android.showChronometer", false))
                "CALL IN PROGRESS" else "INCOMING CALL"
        sbn != null && eventPrefix(sbn) == "TIMER" -> "LIVE TIMER"
        sbn != null && eventPrefix(sbn) == "NAVIGATION" -> "LIVE NAVIGATION"
        sbn != null && eventPrefix(sbn) == "DOWNLOAD" -> "DOWNLOAD"
        sbn != null && eventPrefix(sbn) == "SCREEN RECORDING" -> "SCREEN RECORDING"
        sbn != null && eventPrefix(sbn) == "VOICE RECORDING" -> "VOICE RECORDING"
        isCurrentMediaActivity(sbn) -> "NOW PLAYING"
        sbn == null && batteryStatusText.startsWith("Charging") -> "CHARGING"
        sbn == null && timerStatusText.isNotBlank() -> "LIVE TIMER"
        else -> "LIVE ACTIVITY"
    }

    private fun primaryActivityMessage(sbn: StatusBarNotification?): String {
        if (style != IslandStyle.COLOROS || sbn == null) return displayMessage()
        val chronometer = systemChronometerText(sbn)
        return listOf(chronometer.ifBlank { lastMessage }, weatherStatusText)
            .filter { it.isNotBlank() }.joinToString("  •  ")
    }

    private fun activityStatusLabel(sbn: StatusBarNotification?): String {
        val extras = sbn?.notification?.extras
        val sourceSubText = (extras?.getCharSequence("android.subText")
            ?: extras?.getCharSequence("android.summaryText"))?.toString()?.trim().orEmpty()
        if (sourceSubText.isNotBlank()) return sourceSubText.take(90)
        return when {
            sbn != null && eventPrefix(sbn) == "CALL" ->
                if (sbn.notification.extras.getBoolean("android.showChronometer", false))
                    "مكالمة جارية" else "مكالمة واردة"
            sbn != null && eventPrefix(sbn) == "TIMER" -> "المؤقت يعمل"
            sbn != null && eventPrefix(sbn) == "NAVIGATION" -> "التنقل نشط"
            sbn != null && eventPrefix(sbn) == "DOWNLOAD" -> "التنزيل جارٍ"
            sbn != null && eventPrefix(sbn) == "SCREEN RECORDING" -> "تسجيل الشاشة"
            sbn != null && eventPrefix(sbn) == "VOICE RECORDING" -> "تسجيل صوتي"
            isCurrentMediaActivity(sbn) -> "تشغيل الوسائط"
            sbn == null && batteryStatusText.startsWith("Charging") -> "الشحن جارٍ"
            sbn == null && timerStatusText.isNotBlank() -> "المؤقت يعمل"
            else -> "نشاط مباشر"
        }
    }

    private fun showStandaloneTimer() = showStandaloneActivity("Timer")

    private fun showStandaloneActivity(title: String) {
        if (island != null) return
        if (!MiMaxIslandPreferences.enabled(this) || !Settings.canDrawOverlays(this)) return
        if (MiMaxCrashRecovery.isDisabled(this)) return
        val windowManager = wm ?: return
        if (getSharedPreferences("mimax_launcher", Context.MODE_PRIVATE)
                .getBoolean("focus_mode_enabled", false)) return
        style = MiMaxIslandPreferences.style(this)
        val view = makeView(title)
        island = view
        val params = WindowManager.LayoutParams(
            dp(when (style) {
                IslandStyle.HIS -> 208
                IslandStyle.LTIN -> 126
                IslandStyle.COLOROS -> 224
            }), dp(when (style) {
                IslandStyle.HIS -> 58
                IslandStyle.LTIN -> 42
                IslandStyle.COLOROS -> 52
            }),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            val positionPrefs = getSharedPreferences("mimax_launcher", Context.MODE_PRIVATE)
            val customPosition = positionPrefs.getBoolean("island_position_custom", false)
            x = if (customPosition) positionPrefs.getInt("island_position_x", 0)
                else defaultIslandXOffset()
            y = if (customPosition) positionPrefs.getInt("island_position_y", dp(8))
                else defaultIslandYOffset(when (style) {
                    IslandStyle.HIS -> 58
                    IslandStyle.LTIN -> 42
                    IslandStyle.COLOROS -> 52
                })
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
            }
            .onFailure {
                floatingPanel = null
            }
    }

    private fun removeFloatingPanel() {
        floatingPanel?.let { runCatching { wm?.removeView(it) } }
        floatingPanel = null
    }

    /** 0 = Origin-style springy reveal, 1 = zoom, 2 = fade, 3 = slide. */
    private fun animationStyle(): Int =
        getSharedPreferences("mimax_launcher", Context.MODE_PRIVATE)
            .getInt("island_animation_style", 0).coerceIn(0, 3)

    private fun applyIslandEnterAnimation(view: View) {
        if (reduceMotionEnabled()) {
            view.alpha = 1f
            view.scaleX = 1f
            view.scaleY = 1f
            view.translationY = 0f
            return
        }
        val enter = view.animate()
            .alpha(1f)
            .setDuration(animationDuration(240L))
            .setInterpolator(android.view.animation.OvershootInterpolator(0.65f))
        when (animationStyle()) {
            0 -> {
                view.alpha = 0f
                view.scaleX = .84f
                view.scaleY = .90f
                view.translationY = -dp(10).toFloat()
                enter.scaleX(1f).scaleY(1f).translationY(0f)
            }
            1 -> {
                view.alpha = 0f
                view.scaleX = .72f
                view.scaleY = .72f
                view.translationY = 0f
                enter.scaleX(1f).scaleY(1f).translationY(0f)
            }
            2 -> {
                view.alpha = 0f
                view.scaleX = 1f
                view.scaleY = 1f
                view.translationY = 0f
                enter.scaleX(1f).scaleY(1f).translationY(0f)
            }
            else -> {
                view.alpha = 0f
                view.scaleX = 1f
                view.scaleY = 1f
                view.translationY = -dp(22).toFloat()
                enter.scaleX(1f).scaleY(1f).translationY(0f)
            }
        }
        enter.start()
    }

    /**
     * Aligns the island's center with the largest top-edge display cutout when Android exposes
     * its geometry. User-dragged positions take precedence and are persisted separately.
     */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        main.post {
            val prefs = getSharedPreferences("mimax_launcher", Context.MODE_PRIVATE)
            if (prefs.getBoolean("island_position_custom", false)) return@post
            val view = island ?: return@post
            val params = view.layoutParams as? WindowManager.LayoutParams ?: return@post
            val height = when (style) {
                IslandStyle.HIS -> if (expanded) 132 else 58
                IslandStyle.LTIN -> if (expanded) 148 else 42
                IslandStyle.COLOROS -> if (expanded) 138 else 52
            }
            params.x = defaultIslandXOffset()
            params.y = defaultIslandYOffset(height)
            runCatching { wm?.updateViewLayout(view, params) }
            updateIslandSize(view)
        }
    }

    private fun defaultIslandXOffset(): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return 0
        return runCatching {
            val manager = getSystemService(WINDOW_SERVICE) as WindowManager
            val metrics = manager.currentWindowMetrics
            val cutout = metrics.windowInsets.displayCutout
            val rect = cutout?.boundingRects
                ?.filter { it.top <= dp(100) }
                ?.maxByOrNull { it.width().toLong() * it.height().toLong() }
                ?: return 0
            rect.centerX() - metrics.bounds.centerX()
        }.getOrDefault(0)
    }

    private fun defaultIslandYOffset(heightDp: Int): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return dp(8)
        return runCatching {
            val manager = getSystemService(WINDOW_SERVICE) as WindowManager
            val metrics = manager.currentWindowMetrics
            val rect = metrics.windowInsets.displayCutout?.boundingRects
                ?.filter { it.top <= dp(100) }
                ?.maxByOrNull { it.width().toLong() * it.height().toLong() }
                ?: return dp(8)
            (rect.centerY() - dp(heightDp) / 2).coerceAtLeast(dp(4))
        }.getOrDefault(dp(8))
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
        params.width = dp(if (expanded) customWidth else when (style) {
            IslandStyle.LTIN -> 126
            IslandStyle.HIS -> 208
            IslandStyle.COLOROS -> 224
        })
        params.height = dp(when {
            expanded -> when (style) {
                IslandStyle.LTIN -> 148
                IslandStyle.HIS -> 132
                IslandStyle.COLOROS -> 138
            }
            style == IslandStyle.HIS -> 58
            style == IslandStyle.LTIN -> 42
            else -> 52
        })
        runCatching { wm?.updateViewLayout(view, params) }
    }

    private fun islandBackground() = GradientDrawable().apply {
        val prefs = getSharedPreferences("mimax_launcher", Context.MODE_PRIVATE)
        setColor(prefs.getInt("mt1_background_color", when (style) {
            IslandStyle.HIS -> 0xF0181D2B.toInt()
            IslandStyle.LTIN -> 0xF0000000.toInt()
            IslandStyle.COLOROS -> 0xF026292D.toInt()
        }))
        cornerRadius = dp(prefs.getInt("mt1_corner_radius", when (style) {
            IslandStyle.HIS -> 22
            IslandStyle.LTIN -> 32
            IslandStyle.COLOROS -> 18
        })).toFloat()
        val accent = prefs.getInt("mt1_accent_color", when (style) {
            IslandStyle.HIS -> 0xFF8EA8FF.toInt()
            IslandStyle.LTIN -> 0xFF79E6A5.toInt()
            IslandStyle.COLOROS -> 0xFF69D7C2.toInt()
        })
        if (style != IslandStyle.LTIN) setStroke(dp(1), accent)
    }

    private fun removeIsland() {
        main.removeCallbacks(dismiss)
        island?.let { view ->
            if (reduceMotionEnabled()) {
                runCatching { wm?.removeView(view) }
            } else {
                val exit = view.animate().alpha(0f)
                    .setDuration(animationDuration(160L))
                    .withEndAction { runCatching { wm?.removeView(view) } }
                when (animationStyle()) {
                    0 -> exit.scaleX(.94f).scaleY(.94f).translationY(-dp(6).toFloat())
                    1 -> exit.scaleX(.78f).scaleY(.78f).translationY(0f)
                    2 -> exit.scaleX(1f).scaleY(1f).translationY(0f)
                    else -> exit.scaleX(1f).scaleY(1f).translationY(-dp(18).toFloat())
                }
                exit.start()
            }
        }
        island = null
        activeKey = null
        expanded = false
    }

    override fun onListenerDisconnected() {
        main.removeCallbacks(notificationTicker)
        activeEvents.clear()
        mediaController = null
        removeFloatingPanel()
        removeIsland()
        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        if (batteryReceiverRegistered) runCatching { unregisterReceiver(batteryReceiver) }
        unregisterCallStateMonitor()
        batteryReceiverRegistered = false
        main.removeCallbacks(timerTicker)
        main.removeCallbacks(notificationTicker)
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
    const val ACTION_CALL_MONITOR_PERMISSION_UPDATED = "app.lawnchair.mimax.CALL_MONITOR_PERMISSION_UPDATED"
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
