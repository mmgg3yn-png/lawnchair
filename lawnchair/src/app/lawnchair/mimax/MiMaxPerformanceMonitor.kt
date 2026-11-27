/*
 * Copyright 2026, Mi_Max Launcher contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package app.lawnchair.mimax

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Debug
import android.os.StatFs
import java.util.Locale

/**
 * Read-only, on-demand diagnostic snapshot. It does not keep a background
 * service running and does not upload or persist device metrics.
 */
object MiMaxPerformanceMonitor {

    fun snapshot(context: Context): String {
        val app = context.applicationContext
        val manager = app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memory = ActivityManager.MemoryInfo()
        manager.getMemoryInfo(memory)

        val processPssMb = Debug.getPss() / 1024.0
        val runtime = Runtime.getRuntime()
        val heapUsedMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024.0 * 1024.0)
        val heapMaxMb = runtime.maxMemory() / (1024.0 * 1024.0)

        val totalRamMb = memory.totalMem / (1024.0 * 1024.0)
        val freeRamMb = memory.availMem / (1024.0 * 1024.0)
        val ramUsedPercent = if (memory.totalMem > 0L) {
            ((memory.totalMem - memory.availMem).toDouble() * 100.0 / memory.totalMem).toInt()
        } else 0

        val stat = StatFs(app.filesDir.absolutePath)
        val storageTotal = stat.totalBytes
        val storageAvailable = stat.availableBytes
        val storageUsed = (storageTotal - storageAvailable).coerceAtLeast(0L)

        val battery = app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra("level", -1) ?: -1
        val scale = battery?.getIntExtra("scale", 100)?.coerceAtLeast(1) ?: 100
        val percent = if (level >= 0) (level * 100 / scale) else -1
        val temperature = battery?.getIntExtra("temperature", -1) ?: -1
        val plugged = (battery?.getIntExtra("plugged", 0) ?: 0) != 0

        return buildString {
            appendLine("RAM الجهاز: ${formatMb(totalRamMb)}")
            appendLine("RAM المتاحة: ${formatMb(freeRamMb)}")
            appendLine("استخدام RAM: $ramUsedPercent%")
            appendLine()
            appendLine("استهلاك Mi_Max: ${formatMb(processPssMb)} (PSS)")
            appendLine("Heap المستخدم: ${formatMb(heapUsedMb)} / ${formatMb(heapMaxMb)}")
            appendLine()
            appendLine("التخزين المتاح: ${formatBytes(storageAvailable)}")
            appendLine("التخزين المستخدم: ${formatBytes(storageUsed)} / ${formatBytes(storageTotal)}")
            appendLine()
            appendLine("البطارية: ${if (percent >= 0) "$percent%" else "غير متاح"}")
            appendLine("حالة الشحن: ${if (plugged) "موصول بالشاحن" else "غير موصول"}")
            if (temperature >= 0) {
                appendLine("حرارة البطارية: ${String.format(Locale.getDefault(), "%.1f", temperature / 10.0)} °C")
            }
            appendLine()
            append("هذه قراءة لحظية؛ لا يوجد تتبع دائم أو إرسال للبيانات.")
        }
    }

    private fun formatMb(value: Double): String =
        String.format(Locale.getDefault(), "%.1f MB", value)

    private fun formatBytes(value: Long): String {
        val mib = value / (1024.0 * 1024.0)
        return if (mib >= 1024.0) {
            String.format(Locale.getDefault(), "%.2f GB", mib / 1024.0)
        } else {
            String.format(Locale.getDefault(), "%.0f MB", mib)
        }
    }
}
