/*
 * Copyright 2026, Mi_Max Launcher contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package app.lawnchair.mimax

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Portable backup for Mi_Max-owned preferences only.
 *
 * This deliberately does not claim to back up Lawnchair's workspace database,
 * installed applications, widgets, or Android permissions.
 */
object MiMaxBackupManager {
    private const val FORMAT = "MIMAX_BACKUP"
    private const val VERSION = 1
    private const val MAX_BYTES = 2 * 1024 * 1024
    private val preferenceFiles = listOf("mimax_launcher", "mimax_stacked_folders")

    fun export(context: Context): ByteArray {
        val root = JSONObject()
            .put("format", FORMAT)
            .put("version", VERSION)
            .put("package", context.packageName)
        val files = JSONObject()
        preferenceFiles.forEach { name ->
            val entries = JSONObject()
            context.getSharedPreferences(name, Context.MODE_PRIVATE).all.forEach { (key, value) ->
                val encoded = when (value) {
                    is String -> JSONObject().put("type", "string").put("value", value)
                    is Boolean -> JSONObject().put("type", "boolean").put("value", value)
                    is Int -> JSONObject().put("type", "int").put("value", value)
                    is Long -> JSONObject().put("type", "long").put("value", value)
                    is Float -> JSONObject().put("type", "float").put("value", value.toDouble())
                    is Set<*> -> JSONObject().put("type", "strings").put(
                        "value", JSONArray(value.filterIsInstance<String>().sorted())
                    )
                    else -> null
                }
                if (encoded != null) entries.put(key, encoded)
            }
            files.put(name, entries)
        }
        root.put("preferences", files)
        val bytes = root.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_BYTES) { "النسخة الاحتياطية أكبر من الحد المسموح" }
        return bytes
    }

    fun restore(context: Context, bytes: ByteArray) {
        require(bytes.isNotEmpty() && bytes.size <= MAX_BYTES) { "حجم ملف النسخة الاحتياطية غير صالح" }
        val root = JSONObject(String(bytes, Charsets.UTF_8))
        require(root.optString("format") == FORMAT) { "هذا ليس ملف Mi_Max Backup" }
        require(root.optInt("version", -1) == VERSION) { "إصدار النسخة الاحتياطية غير مدعوم" }
        require(root.optString("package") == context.packageName) { "النسخة تخص حزمة تطبيق مختلفة" }
        val files = root.getJSONObject("preferences")

        // Decode and validate every entry before modifying any preference file.
        val decoded = linkedMapOf<String, Map<String, Any>>()
        preferenceFiles.forEach { name ->
            val entries = files.getJSONObject(name)
            val values = linkedMapOf<String, Any>()
            val keys = entries.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                require(key.isNotBlank() && key.length <= 160) { "مفتاح إعداد غير صالح" }
                val item = entries.getJSONObject(key)
                val value: Any = when (item.getString("type")) {
                    "string" -> item.getString("value").also {
                        require(it.length <= 100_000) { "قيمة نصية كبيرة جداً" }
                    }
                    "boolean" -> item.getBoolean("value")
                    "int" -> item.getInt("value")
                    "long" -> item.getLong("value")
                    "float" -> item.getDouble("value").toFloat().also {
                        require(it.isFinite()) { "قيمة رقمية غير صالحة" }
                    }
                    "strings" -> {
                        val array = item.getJSONArray("value")
                        require(array.length() <= 500) { "قائمة إعدادات كبيرة جداً" }
                        buildSet {
                            for (i in 0 until array.length()) {
                                add(array.getString(i).also {
                                    require(it.length <= 1000) { "عنصر قائمة طويل جداً" }
                                })
                            }
                        }
                    }
                    else -> error("نوع إعداد غير مدعوم")
                }
                values[key] = value
            }
            decoded[name] = values
        }

        preferenceFiles.forEach { name ->
            val editor = context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear()
            decoded.getValue(name).forEach { (key, value) ->
                when (value) {
                    is String -> editor.putString(key, value)
                    is Boolean -> editor.putBoolean(key, value)
                    is Int -> editor.putInt(key, value)
                    is Long -> editor.putLong(key, value)
                    is Float -> editor.putFloat(key, value)
                    is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>())
                }
            }
            check(editor.commit()) { "تعذر حفظ إعدادات $name" }
        }
    }
}
