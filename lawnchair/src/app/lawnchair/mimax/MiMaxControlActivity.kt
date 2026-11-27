/*
 * Copyright 2026, Mi_Max Launcher contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package app.lawnchair.mimax

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.ComponentName
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.os.Bundle
import android.provider.Settings
import android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.text.InputType
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import app.lawnchair.preferences.PreferenceManager
import app.lawnchair.preferences2.PreferenceManager2
import app.lawnchair.icons.shape.IconShape
import com.patrykmichalik.opto.core.setBlocking

/** Native settings entry for Me Island and its Android permission prerequisites. */
class MiMaxControlActivity : Activity() {
    private lateinit var root: LinearLayout
    private val requestImportMt1 = 7101
    private val requestExportMt1 = 7102
    private val requestImportBackup = 7103
    private val requestExportBackup = 7104
    private val requestImportExtension = 7105
    private val requestExportExtension = 7106
    private var pendingExtensionExport: MiMaxExtensionCodec.Definition? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (MiMaxExtensionManager.recoverIfPending(this)) {
            Toast.makeText(this, "تمت استعادة آخر ثيم آمن بعد توقف سابق", Toast.LENGTH_LONG).show()
        }
        render()
    }

    override fun onResume() {
        super.onResume()
        if (::root.isInitialized) render()
    }

    private fun render() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(24), dp(22), dp(24))
            setBackgroundColor(0xFF101116.toInt())
        }
        setContentView(root)
        root.addView(TextView(this).apply {
            text = "Mi_Max Launcher"
            textSize = 25f
            setTextColor(0xFFFFFFFF.toInt())
        })
        root.addView(TextView(this).apply {
            text = "Me Island"
            textSize = 19f
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(0, dp(24), 0, dp(8))
        })
        root.addView(TextView(this).apply {
            text = "تحتاج الجزيرة إلى إذن الظهور فوق التطبيقات وإذن الوصول للإشعارات. لن تعمل قبل منح الإذنين من إعدادات أندرويد."
            textSize = 14f
            setTextColor(0xFFCBCBD2.toInt())
        })
        addButton(if (MiMaxIslandPreferences.enabled(this)) "إيقاف Me Island" else "تشغيل Me Island") {
            MiMaxIslandPreferences.setEnabled(this, !MiMaxIslandPreferences.enabled(this))
            render()
        }
        addButton("فتح وضع الصيانة والاسترداد") {
            startActivity(Intent(this, MiMaxRecoveryActivity::class.java))
        }
        root.addView(TextView(this).apply {
            val lastError = MiMaxCrashRecovery.lastError(this@MiMaxControlActivity)
            text = buildString {
                append("حماية أعطال الجزيرة: ")
                appendLine(MiMaxCrashRecovery.status(this@MiMaxControlActivity))
                if (!lastError.isNullOrBlank()) {
                    append("آخر خطأ: ")
                    append(lastError)
                }
            }
            textSize = 12f
            setTextColor(0xFFB8B8C2.toInt())
            setPadding(0, dp(8), 0, dp(4))
        })
        addButton(
            if (MiMaxCrashRecovery.isDisabled(this))
                "إعادة محاولة تشغيل الجزيرة" else "تصفير سجل أعطال الجزيرة"
        ) {
            MiMaxCrashRecovery.retry(this)
            sendBroadcast(Intent(MiMaxIslandPreferences.ACTION_THEME_UPDATED))
            Toast.makeText(this, "تمت إعادة ضبط حماية الأعطال", Toast.LENGTH_SHORT).show()
            render()
        }
        addButton(
            if (getSharedPreferences("mimax_launcher", MODE_PRIVATE).getBoolean("focus_mode_enabled", false))
                "إيقاف وضع التركيز" else "تشغيل وضع التركيز"
        ) {
            val prefs = getSharedPreferences("mimax_launcher", MODE_PRIVATE)
            val enabled = !prefs.getBoolean("focus_mode_enabled", false)
            prefs.edit().putBoolean("focus_mode_enabled", enabled).apply()
            sendBroadcast(Intent("app.lawnchair.mimax.ACTION_FOCUS_MODE_CHANGED"))
            Toast.makeText(this, if (enabled) "تم تشغيل وضع التركيز وإخفاء إشعارات الجزيرة" else "تم إيقاف وضع التركيز", Toast.LENGTH_SHORT).show()
            render()
        }
        addButton("منح إذن الظهور فوق التطبيقات") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        addButton("منح إذن الوصول للإشعارات") {
            startActivity(Intent(ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        val timerMinutes = EditText(this).apply {
            hint = "مدة المؤقت بالدقائق"
            inputType = InputType.TYPE_CLASS_NUMBER
            setSingleLine(true)
            setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(0xFF9A9AA3.toInt())
        }
        root.addView(timerMinutes, LinearLayout.LayoutParams(-1, dp(52)).apply {
            topMargin = dp(16)
        })
        addButton("بدء المؤقت داخل Me Island") {
            val minutes = timerMinutes.text.toString().toLongOrNull()
            if (minutes == null || minutes !in 1..5999) {
                Toast.makeText(this, "أدخل مدة بين دقيقة و5999 دقيقة", Toast.LENGTH_LONG).show()
            } else {
                getSharedPreferences("mimax_launcher", MODE_PRIVATE).edit()
                    .putLong("island_timer_end", System.currentTimeMillis() + minutes * 60_000L)
                    .putLong("island_timer_remaining", 0L)
                    .putBoolean("island_timer_paused", false)
                    .apply()
                sendBroadcast(Intent(MiMaxIslandPreferences.ACTION_TIMER_UPDATED))
                Toast.makeText(this, "بدأ المؤقت", Toast.LENGTH_SHORT).show()
            }
        }
        addButton("إيقاف المؤقت") {
            getSharedPreferences("mimax_launcher", MODE_PRIVATE).edit()
                .remove("island_timer_end")
                .remove("island_timer_remaining")
                .putBoolean("island_timer_paused", false)
                .apply()
            sendBroadcast(Intent(MiMaxIslandPreferences.ACTION_TIMER_UPDATED))
            Toast.makeText(this, "تم إيقاف المؤقت", Toast.LENGTH_SHORT).show()
        }
        addButton("إيقاف مؤقت / متابعة") {
            val prefs = getSharedPreferences("mimax_launcher", MODE_PRIVATE)
            val edit = prefs.edit()
            if (prefs.getBoolean("island_timer_paused", false)) {
                val remaining = prefs.getLong("island_timer_remaining", 0L)
                if (remaining > 0L) {
                    edit.putLong("island_timer_end", System.currentTimeMillis() + remaining)
                        .putBoolean("island_timer_paused", false)
                    Toast.makeText(this, "تمت متابعة المؤقت", Toast.LENGTH_SHORT).show()
                } else {
                    edit.remove("island_timer_end").remove("island_timer_remaining")
                        .putBoolean("island_timer_paused", false)
                    Toast.makeText(this, "لا يوجد وقت متبقٍ", Toast.LENGTH_SHORT).show()
                }
            } else {
                val end = prefs.getLong("island_timer_end", 0L)
                val remaining = end - System.currentTimeMillis()
                if (remaining > 0L) {
                    edit.putLong("island_timer_remaining", remaining)
                        .putBoolean("island_timer_paused", true)
                        .remove("island_timer_end")
                    Toast.makeText(this, "تم إيقاف المؤقت مؤقتاً", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "لا يوجد مؤقت يعمل حالياً", Toast.LENGTH_SHORT).show()
                }
            }
            edit.apply()
            sendBroadcast(Intent(MiMaxIslandPreferences.ACTION_TIMER_UPDATED))
        }
        addButton("إرسال إشعار تجريبي للجزيرة") {
            if (isNotificationListenerEnabled()) {
                MiMaxIslandPreferences.postTestNotification(this)
                Toast.makeText(this, "تم إرسال إشعار الاختبار", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "امنح إذن الوصول للإشعارات أولاً", Toast.LENGTH_LONG).show()
                startActivity(Intent(ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
        }
        addButton("إعادة موضع الجزيرة إلى الافتراضي") {
            getSharedPreferences("mimax_launcher", MODE_PRIVATE).edit()
                .remove("island_position_x")
                .remove("island_position_y")
                .putBoolean("island_position_custom", false)
                .apply()
            Toast.makeText(this, "سيُطبّق الموضع الافتراضي عند ظهور الجزيرة التالية", Toast.LENGTH_LONG).show()
        }
        addButton("تصميم HyperOS — HIS") { choose(IslandStyle.HIS) }
        addButton("تصميم iPhone — LTIN") { choose(IslandStyle.LTIN) }
        addButton("تصميم ColorOS") { choose(IslandStyle.COLOROS) }
        addButton(
            if (themePrefsMotionEnabled()) "تقليل الحركة: مفعّل" else "تقليل الحركة: متوقف"
        ) {
            val prefs = getSharedPreferences("mimax_launcher", MODE_PRIVATE)
            val next = !prefs.getBoolean("island_reduce_motion", false)
            prefs.edit().putBoolean("island_reduce_motion", next).apply()
            Toast.makeText(this, if (next) "تم تقليل الحركة" else "عادت الحركات", Toast.LENGTH_SHORT).show()
            render()
        }
        addButton("سرعة حركة الجزيرة: تغيير السرعة") {
            val prefs = getSharedPreferences("mimax_launcher", MODE_PRIVATE)
            val current = prefs.getInt("island_animation_duration", 220)
            val next = when (current) { 120 -> 220; 350 -> 120; else -> 350 }
            prefs.edit().putInt("island_animation_duration", next).apply()
            Toast.makeText(this, "مدة الحركة: $next ms", Toast.LENGTH_SHORT).show()
        }
        val animationLabels = listOf("OriginOS / ارتداد ناعم", "تكبير وتصغير", "تلاشي", "انزلاق")
        val currentAnimationStyle = getSharedPreferences("mimax_launcher", MODE_PRIVATE)
            .getInt("island_animation_style", 0).coerceIn(0, 3)
        addButton("نمط الحركة: ${animationLabels[currentAnimationStyle]}") {
            val prefs = getSharedPreferences("mimax_launcher", MODE_PRIVATE)
            val next = (prefs.getInt("island_animation_style", 0) + 1) % animationLabels.size
            prefs.edit().putInt("island_animation_style", next).apply()
            sendBroadcast(Intent(MiMaxIslandPreferences.ACTION_THEME_UPDATED))
            render()
        }
        addButton("استيراد ثيم MT1") {
            startActivityForResult(
                Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                }, requestImportMt1
            )
        }
        addButton("تصدير ثيم MT1") {
            startActivityForResult(
                Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "application/octet-stream"
                    putExtra(Intent.EXTRA_TITLE, "MiMax-Theme.mt1")
                }, requestExportMt1
            )
        }
        root.addView(TextView(this).apply {
            text = "الإضافات ومتجر الثيمات"
            textSize = 18f
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(0, dp(24), 0, dp(8))
        })
        addButton("مكتبة التطبيقات الذكية") {
            startActivity(Intent(this, MiMaxAppLibraryActivity::class.java))
        }
        addButton("إدارة الإضافات المثبتة") { showExtensionManager() }
        addButton("فتح كتالوج القوالب المدمج") { showExtensionCatalog() }
        addButton("فتح متجر الثيمات عبر الإنترنت") { showOnlineExtensionStore() }
        addButton("استيراد إضافة MXE") {
            startActivityForResult(
                Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "application/json"
                }, requestImportExtension
            )
        }
        addButton("تصدير الثيم الحالي كإضافة MXE") {
            pendingExtensionExport = MiMaxExtensionManager.createCurrentThemeExtension(this)
            launchExtensionExport(pendingExtensionExport!!)
        }
        addButton("أدوات المطور: إنشاء حزمة MXE مخصصة") {
            showExtensionDeveloperTool()
        }
        addButton("استعادة آخر ثيم معروف السلامة") {
            val restored = MiMaxExtensionManager.restoreLastKnownGood(this)
            Toast.makeText(
                this,
                if (restored) "تمت استعادة آخر ثيم آمن" else "لا توجد نقطة استعادة محفوظة",
                Toast.LENGTH_LONG
            ).show()
        }
        root.addView(TextView(this).apply {
            text = "إضافات MXE الحالية إضافات ثيمات بياناتية فقط؛ لا تشغّل كوداً خارجياً. يتضمن النظام فحص التوافق والاعتماديات وصلاحية theme.apply والتحديث عبر استيراد إصدار أعلى."
            textSize = 12f
            setTextColor(0xFFB8B8C2.toInt())
        })
        root.addView(TextView(this).apply {
            text = "النسخ الاحتياطي والاستعادة"
            textSize = 18f
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(0, dp(24), 0, dp(8))
        })
        addButton("تصدير نسخة احتياطية من إعدادات Mi_Max") {
            startActivityForResult(
                Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "application/json"
                    putExtra(Intent.EXTRA_TITLE, "MiMax-Backup.json")
                }, requestExportBackup
            )
        }
        addButton("استعادة إعدادات Mi_Max من نسخة احتياطية") {
            startActivityForResult(
                Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "application/json"
                }, requestImportBackup
            )
        }
        root.addView(TextView(this).apply {
            text = "تشمل النسخة إعدادات Mi_Max والجزيرة والثيمات والأنماط الثلاثة والمجلدات المكدسة والإضافات ونقطة استعادة الثيم، مع فحص SHA-256. لا تشمل ترتيب الشاشة الرئيسية أو قاعدة بيانات Lawnchair أو التطبيقات والويدجت الحية أو أذونات أندرويد."
            textSize = 12f
            setTextColor(0xFFB8B8C2.toInt())
        })
        val backupPrefs = getSharedPreferences("mimax_launcher", MODE_PRIVATE)
        val lastAutomaticBackup = backupPrefs.getLong(
            MiMaxBackupReceiver.KEY_LAST_SUCCESS, 0L
        )
        val lastBackupError = backupPrefs.getString(
            MiMaxBackupReceiver.KEY_LAST_ERROR, null
        )
        root.addView(TextView(this).apply {
            text = buildString {
                append("النسخ اليومي التلقائي: ")
                append(if (MiMaxBackupScheduler.isEnabled(this@MiMaxControlActivity)) "مفعّل" else "متوقف")
                appendLine()
                append("آخر نسخة: ")
                append(if (lastAutomaticBackup > 0L) {
                    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                        .format(Date(lastAutomaticBackup))
                } else "لم تُنشأ بعد")
                if (!lastBackupError.isNullOrBlank()) {
                    appendLine()
                    append("آخر خطأ: ")
                    append(lastBackupError)
                }
            }
            textSize = 12f
            setTextColor(0xFFB8B8C2.toInt())
            setPadding(0, dp(8), 0, dp(4))
        })
        addButton(
            if (MiMaxBackupScheduler.isEnabled(this))
                "إيقاف النسخ الاحتياطي اليومي" else "تفعيل النسخ الاحتياطي اليومي"
        ) {
            MiMaxBackupScheduler.setEnabled(
                this, !MiMaxBackupScheduler.isEnabled(this)
            )
            render()
        }
        addButton("استعادة آخر نسخة احتياطية تلقائية") {
            val latest = MiMaxBackupScheduler.latestBackup(this)
            if (latest == null) {
                Toast.makeText(this, "لا توجد نسخة تلقائية محفوظة", Toast.LENGTH_LONG).show()
            } else {
                AlertDialog.Builder(this)
                    .setTitle("استعادة نسخة تلقائية")
                    .setMessage("سيتم استبدال إعدادات Mi_Max الحالية بنسخة ${latest.name}.")
                    .setPositiveButton("استعادة") { _, _ ->
                        runCatching {
                            MiMaxBackupManager.restore(this, latest.readBytes())
                            sendBroadcast(Intent(MiMaxIslandPreferences.ACTION_THEME_UPDATED))
                            sendBroadcast(Intent("app.lawnchair.mimax.ACTION_FOCUS_MODE_CHANGED"))
                            Toast.makeText(this, "تمت استعادة النسخة التلقائية", Toast.LENGTH_LONG).show()
                            render()
                        }.onFailure {
                            Toast.makeText(
                                this,
                                "تعذرت الاستعادة: ${it.message ?: "خطأ غير معروف"}",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                    .setNegativeButton("إلغاء", null)
                    .show()
            }
        }
        root.addView(TextView(this).apply {
            text = "ملفات الثيمات والأنماط"
            textSize = 18f
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(0, dp(24), 0, dp(8))
        })
        for (slot in 1..3) {
            addButton("حفظ الإعدادات الحالية في النمط $slot") { saveProfile(slot) }
            addButton("تطبيق النمط $slot") { applyProfile(slot) }
        }
        root.addView(TextView(this).apply {
            text = "Dock والمجلدات"
            textSize = 18f
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(0, dp(24), 0, dp(8))
        })
        val launcherPrefs = PreferenceManager.getInstance(this)
        addButton("صفحات Dock: ${launcherPrefs.dockPages.get()} — زيادة") {
            val next = (launcherPrefs.dockPages.get() + 1).coerceAtMost(5)
            launcherPrefs.dockPages.set(next)
            Toast.makeText(this, "عدد صفحات Dock: $next", Toast.LENGTH_SHORT).show()
            render()
        }
        addButton("صفحات Dock: تقليل") {
            val next = (launcherPrefs.dockPages.get() - 1).coerceAtLeast(1)
            launcherPrefs.dockPages.set(next)
            Toast.makeText(this, "عدد صفحات Dock: $next", Toast.LENGTH_SHORT).show()
            render()
        }
        addButton("صفوف Dock: تبديل بين صف و صفين") {
            val next = if (launcherPrefs.hotseatRows.get() == 1) 2 else 1
            launcherPrefs.hotseatRows.set(next)
            Toast.makeText(this, "عدد صفوف Dock: $next", Toast.LENGTH_SHORT).show()
            render()
        }
        addButton("صفوف المجلدات: زيادة") {
            val next = (launcherPrefs.folderRows.get() + 1).coerceAtMost(6)
            launcherPrefs.folderRows.set(next)
            Toast.makeText(this, "عدد صفوف المجلد: $next", Toast.LENGTH_SHORT).show()
            render()
        }
        addButton("صفوف المجلدات: تقليل") {
            val next = (launcherPrefs.folderRows.get() - 1).coerceAtLeast(2)
            launcherPrefs.folderRows.set(next)
            Toast.makeText(this, "عدد صفوف المجلد: $next", Toast.LENGTH_SHORT).show()
            render()
        }
        addButton("إعادة Dock إلى صفحة واحدة وصف واحد") {
            launcherPrefs.dockPages.set(1)
            launcherPrefs.hotseatRows.set(1)
            Toast.makeText(this, "تمت إعادة إعدادات Dock", Toast.LENGTH_SHORT).show()
            render()
        }
        addButton(if (launcherPrefs.hotseatBG.get()) "خلفية Dock: مفعّلة" else "خلفية Dock: متوقفة") {
            launcherPrefs.hotseatBG.set(!launcherPrefs.hotseatBG.get())
            render()
        }
        addButton("شفافية خلفية Dock: تبديل بين 70% و100%") {
            val next = if (launcherPrefs.hotseatBGAlpha.get() <= 70) 100 else 70
            launcherPrefs.hotseatBGAlpha.set(next)
            Toast.makeText(this, "شفافية Dock: $next%", Toast.LENGTH_SHORT).show()
            render()
        }
        root.addView(TextView(this).apply {
            text = "استوديو الحركة والإيماءات"
            textSize = 18f
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(0, dp(24), 0, dp(8))
        })
        val gesturePrefs = PreferenceManager.getInstance(this)
        addButton(
            if (gesturePrefs.motionEnabled.get()) "الحركة بالمستشعر: مفعّلة"
            else "الحركة بالمستشعر: متوقفة"
        ) {
            gesturePrefs.motionEnabled.set(!gesturePrefs.motionEnabled.get())
            render()
        }
        addButton(
            if (gesturePrefs.panToMoveEnabled.get()) "الإمالة لتحريك العناصر: مفعّلة"
            else "الإمالة لتحريك العناصر: متوقفة"
        ) {
            gesturePrefs.panToMoveEnabled.set(!gesturePrefs.panToMoveEnabled.get())
            render()
        }
        val gestureSensitivity = EditText(this).apply {
            hint = "حساسية الإمالة من 0.1 إلى 1.0"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setSingleLine(true)
            setText("%.2f".format(Locale.US, gesturePrefs.panToMoveSensitivity.get()))
            setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(0xFF9A9AA3.toInt())
        }
        root.addView(gestureSensitivity, LinearLayout.LayoutParams(-1, dp(50)).apply {
            topMargin = dp(8)
        })
        addButton("حفظ حساسية الإمالة") {
            val value = gestureSensitivity.text.toString().toFloatOrNull()
            if (value == null || value !in 0.1f..1f) {
                Toast.makeText(this, "أدخل قيمة بين 0.1 و1.0", Toast.LENGTH_LONG).show()
            } else {
                gesturePrefs.panToMoveSensitivity.set(value)
                Toast.makeText(this, "تم حفظ حساسية الحركة", Toast.LENGTH_SHORT).show()
            }
        }

        root.addView(TextView(this).apply {
            text = "استوديو الأيقونات — Super Icons"
            textSize = 18f
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(0, dp(24), 0, dp(8))
        })
        val modernPreferences = PreferenceManager2.getInstance(this)
        addButton("شكل أيقونات Xiaomi — Squircle") {
            modernPreferences.iconShape.setBlocking(IconShape.Squircle)
            Toast.makeText(this, "تم تطبيق شكل Squircle وإعادة تحميل الأيقونات", Toast.LENGTH_SHORT).show()
        }
        addButton("شكل أيقونات iPhone — Cupertino") {
            modernPreferences.iconShape.setBlocking(IconShape.Cupertino)
            Toast.makeText(this, "تم تطبيق شكل Cupertino", Toast.LENGTH_SHORT).show()
        }
        addButton("شكل أيقونات ColorOS — Rounded Square") {
            modernPreferences.iconShape.setBlocking(IconShape.RoundedSquare)
            Toast.makeText(this, "تم تطبيق شكل ColorOS", Toast.LENGTH_SHORT).show()
        }
        addButton("شكل دائري") {
            modernPreferences.iconShape.setBlocking(IconShape.Circle)
        }
        addButton("شكل مربع") {
            modernPreferences.iconShape.setBlocking(IconShape.Square)
        }
        addButton("حجم Super Icons: عادي 100%") {
            modernPreferences.homeIconSizeFactor.setBlocking(1.0f)
            Toast.makeText(this, "حجم الأيقونات 100%", Toast.LENGTH_SHORT).show()
        }
        addButton("حجم Super Icons: كبير 125%") {
            modernPreferences.homeIconSizeFactor.setBlocking(1.25f)
            Toast.makeText(this, "حجم الأيقونات 125%", Toast.LENGTH_SHORT).show()
        }
        addButton("حجم Super Icons: أكبر 150%") {
            modernPreferences.homeIconSizeFactor.setBlocking(1.5f)
            Toast.makeText(this, "حجم الأيقونات 150%", Toast.LENGTH_SHORT).show()
        }
        addButton("حجم Super Icons: ضخم 180%") {
            modernPreferences.homeIconSizeFactor.setBlocking(1.8f)
            Toast.makeText(this, "حجم الأيقونات 180%", Toast.LENGTH_SHORT).show()
        }

        root.addView(TextView(this).apply {
            text = "الخصوصية والأداء"
            textSize = 18f
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(0, dp(24), 0, dp(8))
        })
        addButton("لوحة الخصوصية والأذونات") { showPrivacyDashboard() }
        addButton("مراقبة أداء الجهاز وMi_Max") { showPerformanceMonitor() }

        root.addView(TextView(this).apply {
            text = "محرر ثيم الجزيرة"
            textSize = 18f
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(0, dp(24), 0, dp(8))
        })
        addButton("تطبيق قالب HyperOS — HIS") { applyThemePreset(IslandStyle.HIS) }
        addButton("تطبيق قالب iPhone — LTIN") { applyThemePreset(IslandStyle.LTIN) }
        addButton("تطبيق قالب ColorOS") { applyThemePreset(IslandStyle.COLOROS) }
        val themePrefs = getSharedPreferences("mimax_launcher", MODE_PRIVATE)
        fun themeField(hintText: String, value: String, numeric: Boolean = false) =
            EditText(this).apply {
                hint = hintText
                setSingleLine(true)
                inputType = if (numeric) InputType.TYPE_CLASS_NUMBER
                    else InputType.TYPE_CLASS_TEXT
                setText(value)
                setTextColor(0xFFFFFFFF.toInt())
                setHintTextColor(0xFF9A9AA3.toInt())
                setPadding(dp(12), dp(8), dp(12), dp(8))
            }
        val themeName = themeField("اسم الثيم", themePrefs.getString("mt1_theme_name", "Mi_Max Theme") ?: "Mi_Max Theme")
        val accentColor = themeField("لون التمييز ARGB مثل FF8C9BFF",
            "%08X".format(themePrefs.getInt("mt1_accent_color", 0xFF8C9BFF.toInt())))
        val backgroundColor = themeField("لون الخلفية ARGB مثل F0181D2B",
            "%08X".format(themePrefs.getInt("mt1_background_color", 0xF0181D2B.toInt())))
        val cornerRadius = themeField("استدارة الزوايا dp (8 إلى 48)",
            themePrefs.getInt("mt1_corner_radius", 22).toString(), true)
        val islandWidth = themeField("عرض الجزيرة dp (180 إلى 360)",
            themePrefs.getInt("mt1_width", 320).toString(), true)
        listOf(themeName, accentColor, backgroundColor, cornerRadius, islandWidth).forEach { field ->
            root.addView(field, LinearLayout.LayoutParams(-1, dp(50)).apply { topMargin = dp(6) })
        }
        addButton("تطبيق وحفظ الثيم") {
            runCatching {
                val name = themeName.text.toString().trim().take(48)
                require(name.isNotBlank()) { "اكتب اسم الثيم" }
                fun parseColor(field: EditText): Int {
                    val raw = field.text.toString().trim().removePrefix("#")
                    require(raw.matches(Regex("[0-9a-fA-F]{8}"))) { "اكتب اللون بصيغة ARGB من 8 خانات" }
                    return raw.toLong(16).toInt()
                }
                val accent = parseColor(accentColor)
                val background = parseColor(backgroundColor)
                val radius = cornerRadius.text.toString().toIntOrNull()
                    ?: error("قيمة الاستدارة غير صحيحة")
                val width = islandWidth.text.toString().toIntOrNull()
                    ?: error("عرض الجزيرة غير صحيح")
                require(radius in 8..48) { "الاستدارة يجب أن تكون بين 8 و48" }
                require(width in 180..360) { "العرض يجب أن يكون بين 180 و360" }
                themePrefs.edit()
                    .putString("mt1_theme_name", name)
                    .putInt("mt1_accent_color", accent)
                    .putInt("mt1_background_color", background)
                    .putInt("mt1_corner_radius", radius)
                    .putInt("mt1_width", width)
                    .apply()
                sendBroadcast(Intent(MiMaxIslandPreferences.ACTION_THEME_UPDATED))
                Toast.makeText(this, "تم تطبيق الثيم وحفظه", Toast.LENGTH_SHORT).show()
            }.onFailure {
                Toast.makeText(this, "تعذر تطبيق الثيم: " + (it.message ?: "قيمة غير صالحة"), Toast.LENGTH_LONG).show()
            }
        }
        root.addView(TextView(this).apply {
            text = "حالة إذن العرض: " + if (Settings.canDrawOverlays(this@MiMaxControlActivity)) "ممنوح" else "غير ممنوح"
            setTextColor(0xFFB9D7FF.toInt())
            textSize = 13f
            setPadding(0, dp(18), 0, dp(6))
        })
        root.addView(TextView(this).apply {
            text = "حالة إذن الإشعارات: " + if (isNotificationListenerEnabled()) "ممنوح" else "غير ممنوح"
            setTextColor(0xFFB9D7FF.toInt())
            textSize = 13f
        })
        root.addView(TextView(this).apply {
            text = "تعتمد الأحداث على الإشعارات والأنشطة المستمرة المتاحة لتطبيقات الطرف الثالث. لا يملك اللانشر صلاحية قراءة الحالة الخاصة لكل تطبيق أو التحكم بمكالمات النظام."
            setTextColor(0xFF9A9AA3.toInt())
            textSize = 12f
            setPadding(0, dp(12), 0, 0)
        })
    }

    private fun saveProfile(slot: Int) {
        val source = getSharedPreferences("mimax_launcher", MODE_PRIVATE)
        val target = getSharedPreferences("mimax_profile_$slot", MODE_PRIVATE)
        val launcherPrefs = PreferenceManager.getInstance(this)
        target.edit()
            .putString("island_style", source.getString("island_style", IslandStyle.HIS.name))
            .putString("mt1_theme_name", source.getString("mt1_theme_name", "Mi_Max Theme"))
            .putInt("mt1_accent_color", source.getInt("mt1_accent_color", 0xFF8C9BFF.toInt()))
            .putInt("mt1_background_color", source.getInt("mt1_background_color", 0xF0181D2B.toInt()))
            .putInt("mt1_corner_radius", source.getInt("mt1_corner_radius", 22))
            .putInt("mt1_width", source.getInt("mt1_width", 320))
            .putBoolean("island_reduce_motion", source.getBoolean("island_reduce_motion", false))
            .putInt("island_animation_duration", source.getInt("island_animation_duration", 220))
            .putInt("island_animation_style", source.getInt("island_animation_style", 0).coerceIn(0, 3))
            .putInt("dock_pages", launcherPrefs.dockPages.get())
            .putInt("dock_rows", launcherPrefs.hotseatRows.get())
            .putInt("folder_rows", launcherPrefs.folderRows.get())
            .putBoolean("dock_background", launcherPrefs.hotseatBG.get())
            .putInt("dock_background_alpha", launcherPrefs.hotseatBGAlpha.get())
            .putBoolean("motion_enabled", launcherPrefs.motionEnabled.get())
            .putBoolean("pan_to_move_enabled", launcherPrefs.panToMoveEnabled.get())
            .putFloat("pan_to_move_sensitivity", launcherPrefs.panToMoveSensitivity.get())
            .putBoolean("focus_mode_enabled", source.getBoolean("focus_mode_enabled", false))
            .putBoolean("island_enabled", MiMaxIslandPreferences.enabled(this))
            .putBoolean("profile_saved", true)
            .apply()
        Toast.makeText(this, "تم حفظ النمط $slot", Toast.LENGTH_SHORT).show()
    }

    private fun applyProfile(slot: Int) {
        val source = getSharedPreferences("mimax_profile_$slot", MODE_PRIVATE)
        if (!source.getBoolean("profile_saved", false)) {
            Toast.makeText(this, "احفظ إعدادات في هذا النمط أولاً", Toast.LENGTH_SHORT).show()
            return
        }
        val target = getSharedPreferences("mimax_launcher", MODE_PRIVATE)
        target.edit()
            .putString("island_style", source.getString("island_style", IslandStyle.HIS.name))
            .putString("mt1_theme_name", source.getString("mt1_theme_name", "Mi_Max Theme"))
            .putInt("mt1_accent_color", source.getInt("mt1_accent_color", 0xFF8C9BFF.toInt()))
            .putInt("mt1_background_color", source.getInt("mt1_background_color", 0xF0181D2B.toInt()))
            .putInt("mt1_corner_radius", source.getInt("mt1_corner_radius", 22))
            .putInt("mt1_width", source.getInt("mt1_width", 320))
            .putBoolean("island_reduce_motion", source.getBoolean("island_reduce_motion", false))
            .putInt("island_animation_duration", source.getInt("island_animation_duration", 220))
            .putInt("island_animation_style", source.getInt("island_animation_style", 0).coerceIn(0, 3))
            .putBoolean("focus_mode_enabled", source.getBoolean("focus_mode_enabled", false))
            .apply()
        val launcherPrefs = PreferenceManager.getInstance(this)
        launcherPrefs.dockPages.set(source.getInt("dock_pages", 1).coerceIn(1, 5))
        launcherPrefs.hotseatRows.set(source.getInt("dock_rows", 1).coerceIn(1, 2))
        launcherPrefs.folderRows.set(source.getInt("folder_rows", launcherPrefs.folderRows.get()).coerceIn(2, 6))
        launcherPrefs.hotseatBG.set(source.getBoolean("dock_background", launcherPrefs.hotseatBG.get()))
        launcherPrefs.hotseatBGAlpha.set(source.getInt("dock_background_alpha", launcherPrefs.hotseatBGAlpha.get()).coerceIn(0, 100))
        launcherPrefs.motionEnabled.set(source.getBoolean("motion_enabled", launcherPrefs.motionEnabled.get()))
        launcherPrefs.panToMoveEnabled.set(source.getBoolean("pan_to_move_enabled", launcherPrefs.panToMoveEnabled.get()))
        launcherPrefs.panToMoveSensitivity.set(source.getFloat("pan_to_move_sensitivity", launcherPrefs.panToMoveSensitivity.get()).coerceIn(0.1f, 1f))
        MiMaxIslandPreferences.setEnabled(this, source.getBoolean("island_enabled", MiMaxIslandPreferences.enabled(this)))
        sendBroadcast(Intent(MiMaxIslandPreferences.ACTION_THEME_UPDATED))
        sendBroadcast(Intent("app.lawnchair.mimax.ACTION_FOCUS_MODE_CHANGED"))
        Toast.makeText(this, "تم تطبيق النمط $slot", Toast.LENGTH_SHORT).show()
        render()
    }

    private fun themePrefsMotionEnabled(): Boolean =
        getSharedPreferences("mimax_launcher", MODE_PRIVATE)
            .getBoolean("island_reduce_motion", false)

    private fun addButton(label: String, action: () -> Unit) {
        root.addView(Button(this).apply {
            text = label
            isAllCaps = false
            setOnClickListener { action() }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
    }

    private fun isNotificationListenerEnabled(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
            ?: return false
        val expected = ComponentName(this, MiMaxIslandService::class.java).flattenToString()
        return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
    }

    private fun applyThemePreset(style: IslandStyle) {
        val (accent, background, radius, width, label) = when (style) {
            IslandStyle.HIS -> Quintuple(
                0xFF8EA8FF.toInt(), 0xF0181D2B.toInt(), 22, 320, "HyperOS"
            )
            IslandStyle.LTIN -> Quintuple(
                0xFF79E6A5.toInt(), 0xF0000000.toInt(), 34, 345, "iPhone"
            )
            IslandStyle.COLOROS -> Quintuple(
                0xFF69D7C2.toInt(), 0xF026292D.toInt(), 18, 330, "ColorOS"
            )
        }
        getSharedPreferences("mimax_launcher", MODE_PRIVATE).edit()
            .putString("island_style", style.name)
            .putInt("mt1_accent_color", accent)
            .putInt("mt1_background_color", background)
            .putInt("mt1_corner_radius", radius)
            .putInt("mt1_width", width)
            .putString("mt1_theme_name", "$label Preset")
            .apply()
        MiMaxIslandPreferences.setStyle(this, style)
        sendBroadcast(Intent(MiMaxIslandPreferences.ACTION_THEME_UPDATED))
        Toast.makeText(this, "تم تطبيق قالب $label ويمكنك تعديله يدوياً", Toast.LENGTH_SHORT).show()
        render()
    }

    private data class Quintuple(
        val accent: Int,
        val background: Int,
        val radius: Int,
        val width: Int,
        val label: String
    )

    private fun showPrivacyDashboard() {
        val overlayGranted = Settings.canDrawOverlays(this)
        val listenerGranted = isNotificationListenerEnabled()
        val message = buildString {
            appendLine("الظهور فوق التطبيقات: ${if (overlayGranted) "ممنوح" else "غير ممنوح"}")
            appendLine("الوصول إلى الإشعارات: ${if (listenerGranted) "ممنوح" else "غير ممنوح"}")
            appendLine()
            append("يستخدم Mi_Max هذين الإذنين لعرض Me Island. يمكنك سحب كل إذن من إعدادات أندرويد في أي وقت.")
        }
        AlertDialog.Builder(this)
            .setTitle("الخصوصية والأذونات")
            .setMessage(message)
            .setPositiveButton("إعدادات الظهور فوق التطبيقات") { _, _ ->
                startActivity(Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                ))
            }
            .setNeutralButton("إعدادات الوصول للإشعارات") { _, _ ->
                startActivity(Intent(ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
            .setNegativeButton("إغلاق", null)
            .show()
    }

    private fun showPerformanceMonitor() {
        val report = TextView(this).apply {
            text = MiMaxPerformanceMonitor.snapshot(this@MiMaxControlActivity)
            textSize = 14f
            setTextColor(0xFFECECF2.toInt())
            setPadding(dp(18), dp(8), dp(18), dp(8))
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("مراقبة الأداء")
            .setView(report)
            .setPositiveButton("تحديث", null)
            .setNegativeButton("إغلاق", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                report.text = MiMaxPerformanceMonitor.snapshot(this)
            }
        }
        dialog.show()
    }

    private fun choose(style: IslandStyle) {
        MiMaxIslandPreferences.setStyle(this, style)
        sendBroadcast(Intent(MiMaxIslandPreferences.ACTION_THEME_UPDATED))
        Toast.makeText(this, "تم اختيار تصميم " + style.name, Toast.LENGTH_SHORT).show()
    }


    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) {
            if (requestCode == requestExportExtension) pendingExtensionExport = null
            return
        }
        val uri = data?.data ?: return
        when (requestCode) {
            requestImportMt1 -> importMt1(uri)
            requestExportMt1 -> exportMt1(uri)
            requestImportBackup -> importBackup(uri)
            requestExportBackup -> exportBackup(uri)
            requestImportExtension -> importExtension(uri)
            requestExportExtension -> exportExtension(uri)
        }
    }

    private fun importExtension(uri: Uri) {
        runCatching {
            val bytes = contentResolver.openInputStream(uri)?.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= 1024 * 1024) { "ملف MXE كبير جداً" }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            } ?: error("تعذر قراءة ملف الإضافة")
            val definition = MiMaxExtensionCodec.decode(bytes)
            val installed = MiMaxExtensionManager.install(this, definition)
            Toast.makeText(
                this,
                "تم تثبيت/تحديث ${installed.definition.name}. فعّلها وامنح صلاحية الثيم قبل التطبيق.",
                Toast.LENGTH_LONG
            ).show()
            render()
            showExtensionActions(definition.id)
        }.onFailure {
            Toast.makeText(
                this,
                "تعذر تثبيت الإضافة: ${it.message ?: "ملف غير صالح"}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun showExtensionDeveloperTool() {
        val draft = MiMaxExtensionManager.createCurrentThemeExtension(this)
        val nameField = EditText(this).apply {
            hint = "اسم الإضافة"
            setSingleLine(true)
            setText(draft.name)
        }
        val idField = EditText(this).apply {
            hint = "معرّف فريد مثل com.example.theme"
            setSingleLine(true)
            setText(draft.id)
        }
        val versionField = EditText(this).apply {
            hint = "رقم الإصدار"
            inputType = InputType.TYPE_CLASS_NUMBER
            setSingleLine(true)
            setText("1")
        }
        val descriptionField = EditText(this).apply {
            hint = "وصف الإضافة (اختياري)"
            setSingleLine(true)
            setText(draft.description)
        }
        val editor = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            listOf(nameField, idField, versionField, descriptionField).forEach { field ->
                addView(field, LinearLayout.LayoutParams(-1, dp(52)).apply {
                    topMargin = dp(6)
                })
            }
        }
        AlertDialog.Builder(this)
            .setTitle("منشئ إضافات MXE")
            .setMessage("سينشأ ملف ثيم بياناتي بصلاحية theme.apply فقط. لا يدعم هذا الإصدار تشغيل كود أو سكربتات خارجية.")
            .setView(editor)
            .setPositiveButton("التحقق والتصدير") { _, _ ->
                runCatching {
                    val id = idField.text.toString().trim()
                    val name = nameField.text.toString().trim()
                    val version = versionField.text.toString().toLongOrNull()
                        ?: error("رقم الإصدار غير صالح")
                    val description = descriptionField.text.toString().trim().take(240)
                    val definition = draft.copy(
                        id = id,
                        name = name,
                        versionCode = version,
                        description = description,
                    )
                    // Encoding is also the developer-side schema validator.
                    MiMaxExtensionCodec.encode(definition)
                    pendingExtensionExport = definition
                    launchExtensionExport(definition)
                }.onFailure {
                    Toast.makeText(
                        this,
                        "بيانات الإضافة غير صالحة: ${it.message ?: "تحقق من الحقول"}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun launchExtensionExport(definition: MiMaxExtensionCodec.Definition) {
        val safeName = definition.id.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        startActivityForResult(
            Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/json"
                putExtra(Intent.EXTRA_TITLE, "$safeName.mxe")
            },
            requestExportExtension
        )
    }

    private fun exportExtension(uri: Uri) {
        runCatching {
            val definition = pendingExtensionExport
                ?: MiMaxExtensionManager.createCurrentThemeExtension(this)
            val bytes = MiMaxExtensionCodec.encode(definition)
            pendingExtensionExport = null
            contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                ?: error("تعذر إنشاء ملف الإضافة")
            Toast.makeText(this, "تم تصدير إضافة الثيم: ${definition.name}", Toast.LENGTH_LONG).show()
        }.onFailure {
            Toast.makeText(
                this,
                "فشل تصدير الإضافة: ${it.message ?: "خطأ غير معروف"}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun showExtensionManager() {
        val installed = MiMaxExtensionManager.getAll(this)
        if (installed.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("الإضافات المثبتة")
                .setMessage("لا توجد إضافات مثبتة حالياً. افتح الكتالوج أو استورد ملف MXE.")
                .setPositiveButton("فتح الكتالوج") { _, _ -> showExtensionCatalog() }
                .setNegativeButton("إغلاق", null)
                .show()
            return
        }
        val labels = installed.map { extension ->
            val state = if (extension.enabled) "مفعّلة" else "متوقفة"
            "${extension.definition.name} • v${extension.definition.versionCode} • $state"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("الإضافات المثبتة")
            .setItems(labels) { _, index ->
                showExtensionActions(installed[index].definition.id)
            }
            .setNeutralButton("الكتالوج") { _, _ -> showExtensionCatalog() }
            .setNegativeButton("إغلاق", null)
            .show()
    }

    private fun showOnlineExtensionStore() {
        Toast.makeText(this, "جارٍ تحديث متجر الثيمات…", Toast.LENGTH_SHORT).show()
        Thread {
            val result = runCatching { MiMaxExtensionStore.fetchCatalog() }
            runOnUiThread {
                result.onSuccess { catalog ->
                    if (catalog.isEmpty()) {
                        Toast.makeText(this, "المتجر لا يحتوي على ثيمات حالياً", Toast.LENGTH_LONG).show()
                        return@onSuccess
                    }
                    val labels = catalog.map { definition ->
                        val installed = MiMaxExtensionManager.getAll(this)
                            .firstOrNull { it.definition.id == definition.id }
                        val state = when {
                            installed == null -> "تثبيت"
                            definition.versionCode > installed.definition.versionCode -> "تحديث"
                            else -> "مثبت"
                        }
                        "${definition.name} • v${definition.versionCode} • $state\n${definition.description}"
                    }.toTypedArray()
                    AlertDialog.Builder(this)
                        .setTitle("متجر ثيمات Mi_Max")
                        .setItems(labels) { _, index ->
                            val definition = catalog[index]
                            runCatching {
                                val existing = MiMaxExtensionManager.getAll(this)
                                    .firstOrNull { it.definition.id == definition.id }
                                val extension = if (existing == null ||
                                    definition.versionCode > existing.definition.versionCode
                                ) {
                                    MiMaxExtensionManager.install(this, definition)
                                } else {
                                    existing
                                }
                                Toast.makeText(
                                    this,
                                    if (existing == null) "تم تثبيت ${definition.name}"
                                    else if (extension.definition.versionCode > existing.definition.versionCode)
                                        "تم تحديث ${definition.name}"
                                    else "${definition.name} مثبت بالفعل",
                                    Toast.LENGTH_SHORT
                                ).show()
                                showExtensionActions(definition.id)
                            }.onFailure {
                                Toast.makeText(
                                    this,
                                    "تعذر تثبيت الثيم: ${it.message ?: "خطأ غير معروف"}",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                        .setNegativeButton("إغلاق", null)
                        .show()
                }.onFailure {
                    AlertDialog.Builder(this)
                        .setTitle("تعذر الاتصال بمتجر الثيمات")
                        .setMessage(it.message ?: "تحقق من اتصال الإنترنت ثم حاول مجدداً.")
                        .setPositiveButton("إعادة المحاولة") { _, _ -> showOnlineExtensionStore() }
                        .setNegativeButton("إغلاق", null)
                        .show()
                }
            }
        }.start()
    }

    private fun showExtensionCatalog() {
        val catalog = MiMaxExtensionManager.catalog()
        val labels = catalog.map { "${it.name} — ${it.description}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("كتالوج قوالب Mi_Max")
            .setItems(labels) { _, index ->
                runCatching {
                    val extension = MiMaxExtensionManager.installCatalogItem(
                        this, catalog[index].id
                    )
                    if (!extension.enabled) {
                        MiMaxExtensionManager.setEnabled(this, extension.definition.id, true)
                    }
                    Toast.makeText(this, "تمت إضافة ${extension.definition.name}", Toast.LENGTH_SHORT).show()
                    showExtensionActions(extension.definition.id)
                }.onFailure {
                    Toast.makeText(this, "تعذر تثبيت القالب: ${it.message}", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("إغلاق", null)
            .show()
    }

    private fun showExtensionActions(id: String) {
        val extension = MiMaxExtensionManager.getAll(this)
            .firstOrNull { it.definition.id == id } ?: return
        val hasPermission = MiMaxExtensionManager.PERMISSION_APPLY_THEME in
            extension.grantedPermissions
        val actions = arrayOf(
            if (extension.enabled) "تعطيل الإضافة" else "تفعيل الإضافة",
            if (hasPermission) "سحب صلاحية تطبيق الثيم" else "منح صلاحية تطبيق الثيم",
            "تطبيق الثيم",
            "إلغاء تثبيت الإضافة"
        )
        val details = buildString {
            appendLine("المعرف: ${extension.definition.id}")
            appendLine("الإصدار: ${extension.definition.versionCode}")
            appendLine("الحالة: ${if (extension.enabled) "مفعّلة" else "متوقفة"}")
            appendLine("الاعتماديات: ${extension.definition.dependencies.ifEmpty { listOf("لا يوجد") }.joinToString()}")
            if (!extension.lastError.isNullOrBlank()) {
                appendLine()
                append("آخر خطأ: ${extension.lastError}")
            }
        }
        AlertDialog.Builder(this)
            .setTitle(extension.definition.name)
            .setMessage(details)
            .setItems(actions) { _, which ->
                when (which) {
                    0 -> runCatching {
                        MiMaxExtensionManager.setEnabled(this, id, !extension.enabled)
                        Toast.makeText(this, "تم تحديث حالة الإضافة", Toast.LENGTH_SHORT).show()
                        showExtensionActions(id)
                    }.onFailure {
                        Toast.makeText(this, "تعذر تغيير الحالة: ${it.message}", Toast.LENGTH_LONG).show()
                    }
                    1 -> {
                        if (hasPermission) {
                            MiMaxExtensionManager.grantPermission(
                                this, id, MiMaxExtensionManager.PERMISSION_APPLY_THEME, false
                            )
                            Toast.makeText(this, "تم سحب صلاحية الثيم", Toast.LENGTH_SHORT).show()
                        } else {
                            AlertDialog.Builder(this)
                                .setTitle("منح صلاحية الإضافة")
                                .setMessage("تحتاج الإضافة إلى صلاحية theme.apply لتغيير ثيم الجزيرة فقط. لن تحصل على صلاحية للوصول إلى الملفات أو الإشعارات.")
                                .setPositiveButton("منح الصلاحية") { _, _ ->
                                    runCatching {
                                        MiMaxExtensionManager.grantPermission(
                                            this, id, MiMaxExtensionManager.PERMISSION_APPLY_THEME, true
                                        )
                                    }.onFailure {
                                        Toast.makeText(this, "تعذر منح الصلاحية: ${it.message}", Toast.LENGTH_LONG).show()
                                    }
                                    showExtensionActions(id)
                                }
                                .setNegativeButton("إلغاء", null)
                                .show()
                        }
                    }
                    2 -> applyExtensionTheme(id)
                    3 -> AlertDialog.Builder(this)
                        .setMessage("هل تريد إلغاء تثبيت هذه الإضافة؟")
                        .setPositiveButton("إلغاء التثبيت") { _, _ ->
                            runCatching {
                                MiMaxExtensionManager.uninstall(this, id)
                                Toast.makeText(this, "تم إلغاء تثبيت الإضافة", Toast.LENGTH_SHORT).show()
                                render()
                            }.onFailure {
                                Toast.makeText(this, "تعذر إلغاء التثبيت: ${it.message}", Toast.LENGTH_LONG).show()
                            }
                        }
                        .setNegativeButton("إلغاء", null)
                        .show()
                }
            }
            .setNegativeButton("إغلاق", null)
            .show()
    }

    private fun applyExtensionTheme(id: String) {
        val extension = MiMaxExtensionManager.getAll(this)
            .firstOrNull { it.definition.id == id } ?: return
        if (!extension.enabled) {
            Toast.makeText(this, "فعّل الإضافة أولاً", Toast.LENGTH_LONG).show()
            return
        }
        if (MiMaxExtensionManager.PERMISSION_APPLY_THEME !in extension.grantedPermissions) {
            AlertDialog.Builder(this)
                .setTitle("صلاحية تطبيق الثيم")
                .setMessage("ستتمكن هذه الإضافة من تغيير ألوان الجزيرة وأبعادها ونمطها فقط.")
                .setPositiveButton("منح وتطبيق") { _, _ ->
                    runCatching {
                        MiMaxExtensionManager.grantPermission(
                            this, id, MiMaxExtensionManager.PERMISSION_APPLY_THEME, true
                        )
                        MiMaxExtensionManager.applyTheme(this, id)
                        Toast.makeText(this, "تم تطبيق ثيم الإضافة", Toast.LENGTH_LONG).show()
                        render()
                    }.onFailure {
                        Toast.makeText(this, "فشل تطبيق الثيم: ${it.message}", Toast.LENGTH_LONG).show()
                    }
                }
                .setNegativeButton("إلغاء", null)
                .show()
            return
        }
        runCatching {
            MiMaxExtensionManager.applyTheme(this, id)
            Toast.makeText(this, "تم تطبيق ثيم الإضافة", Toast.LENGTH_LONG).show()
            render()
        }.onFailure {
            Toast.makeText(this, "فشل تطبيق الثيم: ${it.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun importBackup(uri: Uri) {
        runCatching {
            val bytes = contentResolver.openInputStream(uri)?.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= 2 * 1024 * 1024) { "ملف النسخة الاحتياطية كبير جداً" }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            } ?: error("تعذر قراءة الملف")
            MiMaxBackupManager.restore(this, bytes)
            sendBroadcast(Intent(MiMaxIslandPreferences.ACTION_THEME_UPDATED))
            sendBroadcast(Intent("app.lawnchair.mimax.ACTION_FOCUS_MODE_CHANGED"))
            Toast.makeText(this, "تمت استعادة إعدادات Mi_Max. قد تحتاج بعض عناصر الشاشة إلى إعادة فتح المشغّل.", Toast.LENGTH_LONG).show()
            render()
        }.onFailure {
            Toast.makeText(this, "فشلت الاستعادة: " + (it.message ?: "ملف غير صالح"), Toast.LENGTH_LONG).show()
        }
    }

    private fun exportBackup(uri: Uri) {
        runCatching {
            val bytes = MiMaxBackupManager.export(this)
            contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                ?: error("تعذر إنشاء الملف")
            Toast.makeText(this, "تم تصدير نسخة إعدادات Mi_Max", Toast.LENGTH_LONG).show()
        }.onFailure {
            Toast.makeText(this, "فشل التصدير: " + (it.message ?: "خطأ غير معروف"), Toast.LENGTH_LONG).show()
        }
    }

    private fun importMt1(uri: Uri) {
        runCatching {
            val bytes = contentResolver.openInputStream(uri)?.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= 1024 * 1024 + 46) { "ملف MT1 كبير جداً" }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            } ?: error("تعذر قراءة الملف")
            val theme = MiMaxMt1Codec.decode(bytes)
            getSharedPreferences("mimax_launcher", MODE_PRIVATE).edit()
                .putString("island_style", theme.style.name)
                .putInt("mt1_accent_color", theme.accentColor)
                .putInt("mt1_background_color", theme.backgroundColor)
                .putInt("mt1_corner_radius", theme.cornerRadiusDp)
                .putInt("mt1_width", theme.widthDp)
                .putString("mt1_theme_name", theme.name)
                .apply()
            sendBroadcast(Intent(MiMaxIslandPreferences.ACTION_THEME_UPDATED))
            Toast.makeText(this, "تم استيراد الثيم: " + theme.name, Toast.LENGTH_LONG).show()
        }.onFailure {
            Toast.makeText(this, "فشل استيراد MT1: " + (it.message ?: "ملف غير صالح"), Toast.LENGTH_LONG).show()
        }
    }

    private fun exportMt1(uri: Uri) {
        runCatching {
            val prefs = getSharedPreferences("mimax_launcher", MODE_PRIVATE)
            val bytes = MiMaxMt1Codec.encode(
                MiMaxMt1Codec.Theme(
                    prefs.getString("mt1_theme_name", "Mi_Max Theme") ?: "Mi_Max Theme",
                    MiMaxIslandPreferences.style(this),
                    prefs.getInt("mt1_accent_color", 0xFF8C9BFF.toInt()),
                    prefs.getInt("mt1_background_color", 0xF0181D2B.toInt()),
                    prefs.getInt("mt1_corner_radius", 22),
                    prefs.getInt("mt1_width", 320)
                )
            )
            contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                ?: error("تعذر إنشاء الملف")
            Toast.makeText(this, "تم تصدير ملف MT1", Toast.LENGTH_LONG).show()
        }.onFailure {
            Toast.makeText(this, "فشل تصدير MT1: " + (it.message ?: "خطأ غير معروف"), Toast.LENGTH_LONG).show()
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
