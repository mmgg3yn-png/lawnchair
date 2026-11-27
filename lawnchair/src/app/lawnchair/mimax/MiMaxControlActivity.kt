/*
 * Copyright 2026, Mi_Max Launcher contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package app.lawnchair.mimax

import android.app.Activity
import android.content.Intent
import android.content.ComponentName
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/** Native settings entry for Me Island and its Android permission prerequisites. */
class MiMaxControlActivity : Activity() {
    private lateinit var root: LinearLayout

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
        Toast.makeText(this, "تم اختيار تصميم " + style.name, Toast.LENGTH_SHORT).show()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
