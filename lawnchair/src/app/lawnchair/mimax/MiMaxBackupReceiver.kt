/*
 * Copyright 2026, Mi_Max Launcher contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package app.lawnchair.mimax

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.AtomicFile
import java.io.File

/** Performs scheduled backups off the main thread and retains the newest seven files. */
class MiMaxBackupReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED) {
            MiMaxBackupScheduler.scheduleIfEnabled(context)
            return
        }
        if (intent?.action != MiMaxBackupScheduler.ACTION_DAILY_BACKUP ||
            !MiMaxBackupScheduler.isEnabled(context)
        ) return

        val pending = goAsync()
        val appContext = context.applicationContext
        Thread({
            try {
                val directory = MiMaxBackupScheduler.backupDirectory(appContext)
                check(directory.exists() || directory.mkdirs()) {
                    "تعذر إنشاء مجلد النسخ التلقائي"
                }
                val timestamp = System.currentTimeMillis()
                val target = File(directory, "MiMax-Auto-$timestamp.json")
                val atomicFile = AtomicFile(target)
                val stream = atomicFile.startWrite()
                try {
                    stream.write(MiMaxBackupManager.export(appContext))
                    atomicFile.finishWrite(stream)
                } catch (failure: Throwable) {
                    atomicFile.failWrite(stream)
                    throw failure
                }

                directory.listFiles()
                    ?.filter { it.isFile && it.extension.equals("json", ignoreCase = true) }
                    ?.sortedByDescending { it.lastModified() }
                    ?.drop(MAX_BACKUPS)
                    ?.forEach { old -> old.delete() }

                appContext.getSharedPreferences(
                    MiMaxBackupScheduler.PREFS_NAME,
                    Context.MODE_PRIVATE
                ).edit()
                    .putLong(KEY_LAST_SUCCESS, timestamp)
                    .remove(KEY_LAST_ERROR)
                    .apply()
            } catch (failure: Throwable) {
                appContext.getSharedPreferences(
                    MiMaxBackupScheduler.PREFS_NAME,
                    Context.MODE_PRIVATE
                ).edit()
                    .putString(KEY_LAST_ERROR, (failure.message ?: "Backup failed").take(200))
                    .apply()
            } finally {
                pending.finish()
            }
        }, "MiMaxBackup").start()
    }

    companion object {
        const val KEY_LAST_SUCCESS = "auto_backup_last_success"
        const val KEY_LAST_ERROR = "auto_backup_last_error"
        private const val MAX_BACKUPS = 7
    }
}
