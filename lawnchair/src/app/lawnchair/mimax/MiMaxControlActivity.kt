/*
 * Copyright 2026, Mi_Max Launcher contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package app.lawnchair.mimax

import android.app.Activity
import android.content.Intent
import android.content.ComponentName
import android.net.Uri
import java.io.ByteArrayOutputStream
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

/** Native settings entry for Me Island and its Android permission prerequisites. */
class MiMaxControlActivity : Activity() {
    private lateinit var root: LinearLayout
    private val requestImportMt1 = 7101
    private val requestExportMt1 = 7102

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
            text = "محرر ثيم الجزيرة"
            textSize = 18f
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(0, dp(24), 0, dp(8))
        })
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
            .putInt("dock_pages", launcherPrefs.dockPages.get())
            .putInt("dock_rows", launcherPrefs.hotseatRows.get())
            .putInt("folder_rows", launcherPrefs.folderRows.get())
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
            .apply()
        val launcherPrefs = PreferenceManager.getInstance(this)
        launcherPrefs.dockPages.set(source.getInt("dock_pages", 1).coerceIn(1, 5))
        launcherPrefs.hotseatRows.set(source.getInt("dock_rows", 1).coerceIn(1, 2))
        launcherPrefs.folderRows.set(source.getInt("folder_rows", launcherPrefs.folderRows.get()).coerceIn(2, 6))
        sendBroadcast(Intent(MiMaxIslandPreferences.ACTION_THEME_UPDATED))
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

    private fun choose(style: IslandStyle) {
        MiMaxIslandPreferences.setStyle(this, style)
        sendBroadcast(Intent(MiMaxIslandPreferences.ACTION_THEME_UPDATED))
        Toast.makeText(this, "تم اختيار تصميم " + style.name, Toast.LENGTH_SHORT).show()
    }


    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        when (requestCode) {
            requestImportMt1 -> importMt1(uri)
            requestExportMt1 -> exportMt1(uri)
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
