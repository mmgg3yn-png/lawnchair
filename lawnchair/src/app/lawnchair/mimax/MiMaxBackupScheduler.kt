/*
 * Copyright 2026, Mi_Max Launcher contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package app.lawnchair.mimax

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

/** Schedules a daily inexact backup without requiring exact-alarm access. */
object MiMaxBackupScheduler {
    const val ACTION_DAILY_BACKUP = "app.lawnchair.mimax.ACTION_DAILY_BACKUP"
    const val PREFS_NAME = "mimax_launcher"
    const val KEY_ENABLED = "auto_backup_enabled"
    private const val REQUEST_CODE = 45871
    private const val DAILY_INTERVAL_MS = AlarmManager.INTERVAL_DAY

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, enabled).apply()
        if (enabled) schedule(context) else cancel(context)
    }

    fun scheduleIfEnabled(context: Context) {
        if (isEnabled(context)) schedule(context)
    }

    fun schedule(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pendingIntent = pendingIntent(context)
        val firstRun = System.currentTimeMillis() + DAILY_INTERVAL_MS
        alarmManager.setInexactRepeating(
            AlarmManager.RTC_WAKEUP,
            firstRun,
            DAILY_INTERVAL_MS,
            pendingIntent
        )
    }

    fun cancel(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.cancel(pendingIntent(context))
    }

    fun latestBackup(context: Context): java.io.File? =
        backupDirectory(context).listFiles()
            ?.filter { it.isFile && it.extension.equals("json", ignoreCase = true) }
            ?.maxByOrNull { it.lastModified() }

    internal fun backupDirectory(context: Context): java.io.File =
        java.io.File(context.filesDir, "mimax-automatic-backups")

    private fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, MiMaxBackupReceiver::class.java)
            .setAction(ACTION_DAILY_BACKUP)
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
