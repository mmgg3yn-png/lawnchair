/*
 * Copyright 2026, Mi_Max Launcher contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package app.lawnchair.mimax

import android.content.Context

/** Circuit breaker and recovery record for repeated Me Island overlay failures. */
object MiMaxCrashRecovery {
    private const val PREFS = "mimax_launcher"
    private const val KEY_FAILURES = "island_overlay_failures"
    private const val KEY_LAST_FAILURE = "island_overlay_last_failure"
    private const val KEY_LAST_ERROR = "island_overlay_last_error"
    private const val KEY_DISABLED = "island_overlay_recovery_disabled"
    private const val WINDOW_MS = 5 * 60 * 1000L
    private const val FAILURE_LIMIT = 3

    fun isDisabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_DISABLED, false)

    fun status(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_DISABLED, false)) {
            return "أوقفت حماية الأعطال الجزيرة بعد عدة إخفاقات متتابعة."
        }
        val count = prefs.getInt(KEY_FAILURES, 0)
        return if (count > 0) "إخفاقات النافذة الأخيرة: $count/$FAILURE_LIMIT" else "لا توجد إخفاقات حديثة"
    }

    fun lastError(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LAST_ERROR, null)

    /** Returns true when the circuit breaker disables the overlay. */
    fun recordFailure(context: Context, failure: Throwable): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val last = prefs.getLong(KEY_LAST_FAILURE, 0L)
        val count = if (last > 0L && now - last in 0..WINDOW_MS) {
            prefs.getInt(KEY_FAILURES, 0) + 1
        } else {
            1
        }
        val disabled = count >= FAILURE_LIMIT
        prefs.edit()
            .putInt(KEY_FAILURES, count)
            .putLong(KEY_LAST_FAILURE, now)
            .putString(
                KEY_LAST_ERROR,
                "${failure.javaClass.simpleName}: ${failure.message ?: "Unknown overlay error"}".take(240)
            )
            .putBoolean(KEY_DISABLED, disabled)
            .apply()
        return disabled
    }

    fun recordSuccess(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_FAILURES, 0)
            .putLong(KEY_LAST_FAILURE, 0L)
            .remove(KEY_LAST_ERROR)
            .apply()
    }

    fun retry(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_FAILURES, 0)
            .putLong(KEY_LAST_FAILURE, 0L)
            .remove(KEY_LAST_ERROR)
            .putBoolean(KEY_DISABLED, false)
            .apply()
    }
}
