/*
 * Copyright 2026, Mi_Max Launcher contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package app.lawnchair.mimax

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * Opt-in weather lookup. It uses only the device's last known location and Open-Meteo's
 * public forecast endpoint; no location is requested until the user taps Refresh.
 */
object MiMaxWeatherProvider {
    const val ACTION_WEATHER_UPDATED = "app.lawnchair.mimax.ACTION_WEATHER_UPDATED"
    private const val PREFS = "mimax_launcher"

    fun refresh(context: Context, callback: (Boolean, String) -> Unit) {
        val appContext = context.applicationContext
        if (appContext.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            callback(false, "إذن الموقع غير ممنوح")
            return
        }
        Thread {
            var connection: HttpURLConnection? = null
            try {
                val manager = appContext.getSystemService(LocationManager::class.java)
                    ?: error("خدمة الموقع غير متاحة")
                val location: Location = manager.getProviders(true)
                    .mapNotNull { provider ->
                        runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
                    }
                    .maxByOrNull { it.time }
                    ?: error("لا يوجد موقع حديث؛ فعّل الموقع وانتظر تحديده ثم أعد المحاولة")

                val endpoint = "https://api.open-meteo.com/v1/forecast" +
                    "?latitude=${location.latitude}&longitude=${location.longitude}" +
                    "&current=temperature_2m,relative_humidity_2m,apparent_temperature,weather_code,wind_speed_10m" +
                    "&timezone=auto"
                connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 7000
                    readTimeout = 7000
                    setRequestProperty("Accept", "application/json")
                }
                if (connection.responseCode !in 200..299) error("خادم الطقس أعاد HTTP ${connection.responseCode}")
                val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                val current = JSONObject(body).getJSONObject("current")
                val temp = current.getDouble("temperature_2m").roundToInt()
                val feels = current.getDouble("apparent_temperature").roundToInt()
                val humidity = current.getInt("relative_humidity_2m")
                val wind = current.getDouble("wind_speed_10m").roundToInt()
                val code = current.getInt("weather_code")
                val description = when (code) {
                    0 -> "سماء صافية"
                    1, 2 -> "غائم جزئياً"
                    3 -> "غائم"
                    45, 48 -> "ضباب"
                    51, 53, 55, 56, 57 -> "رذاذ"
                    61, 63, 65, 66, 67 -> "أمطار"
                    71, 73, 75, 77 -> "ثلوج"
                    80, 81, 82 -> "زخات مطر"
                    85, 86 -> "زخات ثلج"
                    95, 96, 99 -> "عواصف رعدية"
                    else -> "حالة جوية متغيرة"
                }
                val display = "$description • $temp° • محسوسة $feels° • رطوبة $humidity% • رياح $wind"
                appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putString("island_weather_text", display)
                    .putLong("island_weather_updated_at", System.currentTimeMillis())
                    .apply()
                appContext.sendBroadcast(Intent(ACTION_WEATHER_UPDATED).setPackage(appContext.packageName))
                Handler(Looper.getMainLooper()).post { callback(true, display) }
            } catch (error: Exception) {
                Handler(Looper.getMainLooper()).post {
                    callback(false, error.message ?: "خطأ غير معروف")
                }
            } finally {
                connection?.disconnect()
            }
        }.start()
    }
}
