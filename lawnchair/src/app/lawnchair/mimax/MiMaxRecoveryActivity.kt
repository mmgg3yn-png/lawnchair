/*
 * Copyright 2026, Mi_Max Launcher contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package app.lawnchair.mimax

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * Small maintenance surface that does not depend on the main launcher settings screen.
 * It offers safe theme recovery, extension shutdown, and Me Island retry controls.
 */
class MiMaxRecoveryActivity : Activity() {
    private lateinit var root: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MiMaxExtensionManager.recoverIfPending(this)
        render()
    }

    private fun render() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(24), dp(22), dp(24))
            setBackgroundColor(0xFF101116.toInt())
        }
        setContentView(root)
        root.addView(TextView(this).apply {
            text = "Mi_Max Recovery"
            textSize = 24f
            setTextColor(0xFFFFFFFF.toInt())
        })
        root.addView(TextView(this).apply {
            text = "وضع صيانة مستقل عن شاشة إعدادات المشغّل الرئيسية. استخدمه لاستعادة الثيم أو إيقاف الإضافات أو إعادة محاولة الجزيرة."
            textSize = 14f
            setTextColor(0xFFB8B8C2.toInt())
            setPadding(0, dp(10), 0, dp(14))
        })

        val extensions = MiMaxExtensionManager.getAll(this)
        val enabledCount = extensions.count { it.enabled }
        val islandError = MiMaxCrashRecovery.lastError(this)
        val summary = buildString {
            appendLine("الإضافات المثبتة: ${extensions.size}")
            appendLine("الإضافات المفعّلة: $enabledCount")
            appendLine("حالة الجزيرة: ${MiMaxCrashRecovery.status(this@MiMaxRecoveryActivity)}")
            if (!islandError.isNullOrBlank()) {
                appendLine("آخر خطأ للجزيرة: $islandError")
            }
            extensions.filter { !it.lastError.isNullOrBlank() }.forEach {
                appendLine()
                appendLine("${it.definition.name}: ${it.lastError}")
            }
        }
        root.addView(TextView(this).apply {
            text = summary
            textSize = 13f
            setTextColor(0xFFE0E0E8.toInt())
            setPadding(0, dp(8), 0, dp(12))
        })

        addButton("استعادة العملية المعلّقة أو آخر ثيم آمن") {
            val recovered = MiMaxExtensionManager.recoverIfPending(this)
            val restored = MiMaxExtensionManager.restoreLastKnownGood(this)
            sendBroadcast(Intent(MiMaxIslandPreferences.ACTION_THEME_UPDATED))
            Toast.makeText(
                this,
                when {
                    restored -> "تمت استعادة آخر ثيم آمن"
                    recovered -> "تم تنفيذ الاستعادة المعلّقة"
                    else -> "لا توجد نقطة استعادة صالحة"
                },
                Toast.LENGTH_LONG
            ).show()
            render()
        }

        addButton("تعطيل كل الإضافات دون حذفها") {
            AlertDialog.Builder(this)
                .setTitle("وضع آمن للإضافات")
                .setMessage("سيتم إيقاف جميع إضافات MXE مع الاحتفاظ ببياناتها وصلاحياتها المحفوظة. يمكنك تفعيلها لاحقاً واحدة تلو الأخرى.")
                .setPositiveButton("تعطيل الكل") { _, _ ->
                    MiMaxExtensionManager.disableAllForRecovery(this)
                    Toast.makeText(this, "تم تعطيل كل الإضافات", Toast.LENGTH_SHORT).show()
                    render()
                }
                .setNegativeButton("إلغاء", null)
                .show()
        }

        addButton("إعادة ضبط حماية أعطال Me Island") {
            MiMaxCrashRecovery.retry(this)
            sendBroadcast(Intent(MiMaxIslandPreferences.ACTION_THEME_UPDATED))
            Toast.makeText(this, "تمت إعادة ضبط حماية الجزيرة", Toast.LENGTH_SHORT).show()
            render()
        }

        addButton("فتح إعدادات Mi_Max الرئيسية") {
            startActivity(Intent(this, MiMaxControlActivity::class.java))
        }
    }

    private fun addButton(label: String, action: () -> Unit) {
        root.addView(Button(this).apply {
            text = label
            isAllCaps = false
            setOnClickListener { action() }
        }, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(8)
        })
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
