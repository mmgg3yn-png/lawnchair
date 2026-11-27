/*
 * Copyright 2026, Mi_Max Launcher contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package app.lawnchair.mimax

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * Portable, checksummed backup for Mi_Max-owned preferences.
 *
 * It does not claim to back up Lawnchair's workspace database, installed
 * applications, live AppWidget instances, or Android-granted permissions.
 */
object MiMaxBackupManager {
    private const val FORMAT = "MIMAX_BACKUP"
    private const val VERSION_LEGACY = 1
    private const val VERSION_CURRENT = 2
    private const val MAX_BYTES = 2 * 1024 * 1024
    private val TRANSIENT_BACKUP_KEYS = setOf(
        MiMaxBackupReceiver.KEY_LAST_SUCCESS,
        MiMaxBackupReceiver.KEY_LAST_ERROR
    )
    private val preferenceFiles = listOf(
        "mimax_launcher",
        "mimax_stacked_folders",
        "mimax_profile_1",
        "mimax_profile_2",
        "mimax_profile_3",
        "mimax_extensions",
        "mimax_extension_recovery"
    )

    fun export(context: Context): ByteArray {
        val files = JSONObject()
        preferenceFiles.forEach { name ->
            val entries = JSONObject()
            context.getSharedPreferences(name, Context.MODE_PRIVATE).all.forEach { (key, value) ->
                val encoded = if (name == "mimax_launcher" && key in TRANSIENT_BACKUP_KEYS) {
                    null
                } else when (value) {
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

        // Store the serialized payload as a string so the digest is calculated
        // over the exact bytes that will be parsed during restore.
        val payload = files.toString()
        val payloadBytes = payload.toByteArray(Charsets.UTF_8)
        val root = JSONObject()
            .put("format", FORMAT)
            .put("version", VERSION_CURRENT)
            .put("package", context.packageName)
            .put("createdAt", System.currentTimeMillis())
            .put("sha256", sha256(payloadBytes))
            .put("payload", payload)
        val bytes = root.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_BYTES) { "النسخة الاحتياطية أكبر من الحد المسموح" }
        return bytes
    }

    fun restore(context: Context, bytes: ByteArray) {
        require(bytes.isNotEmpty() && bytes.size <= MAX_BYTES) {
            "حجم ملف النسخة الاحتياطية غير صالح"
        }
        val root = JSONObject(String(bytes, Charsets.UTF_8))
        require(root.optString("format") == FORMAT) { "هذا ليس ملف Mi_Max Backup" }
        require(root.optString("package") == context.packageName) {
            "النسخة تخص حزمة تطبيق مختلفة"
        }

        val version = root.optInt("version", -1)
        val files = when (version) {
            VERSION_LEGACY -> root.getJSONObject("preferences") // Backward compatible v1.
            VERSION_CURRENT -> {
                val payload = root.getString("payload")
                val payloadBytes = payload.toByteArray(Charsets.UTF_8)
                val expected = root.getString("sha256")
                require(expected.matches(Regex("[0-9a-f]{64}"))) {
                    "بصمة النسخة الاحتياطية غير صالحة"
                }
                require(MessageDigest.isEqual(
                    expected.toByteArray(Charsets.US_ASCII),
                    sha256(payloadBytes).toByteArray(Charsets.US_ASCII)
                )) { "النسخة الاحتياطية تالفة أو تم تعديلها" }
                JSONObject(payload)
            }
            else -> error("إصدار النسخة الاحتياطية غير مدعوم")
        }

        // Decode and validate every included file before touching preferences.
        // v1 did not include profile files, so absent v1 entries are left intact.
        val decoded = linkedMapOf<String, Map<String, Any>>()
        preferenceFiles.forEach { name ->
            val entries = files.optJSONObject(name) ?: run {
                require(version == VERSION_LEGACY) {
                    "ملف إعدادات $name غير موجود في النسخة"
                }
                return@forEach
            }
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

        // Keep a rollback snapshot. SharedPreferences cannot atomically commit
        // multiple files, so restore previous values if any file write fails.
        val original = decoded.keys.associateWith { name ->
            context.getSharedPreferences(name, Context.MODE_PRIVATE).all.mapNotNull { (key, value) ->
                val safeValue: Any? = when (value) {
                    is Set<*> -> value.filterIsInstance<String>().toSet()
                    is String, is Boolean, is Int, is Long, is Float -> value
                    else -> null
                }
                safeValue?.let { key to it }
            }.toMap()
        }

        try {
            decoded.forEach { (name, values) ->
                applyValues(context.getSharedPreferences(name, Context.MODE_PRIVATE), values)
            }
        } catch (failure: Throwable) {
            var rollbackSucceeded = true
            original.forEach { (name, values) ->
                runCatching {
                    applyValues(context.getSharedPreferences(name, Context.MODE_PRIVATE), values)
                }.onFailure {
                    rollbackSucceeded = false
                }
            }
            if (!rollbackSucceeded) {
                throw IllegalStateException(
                    "تعذرت الاستعادة واكتمل التراجع عن بعض الملفات فقط", failure
                )
            }
            throw failure
        }
        MiMaxBackupScheduler.syncWithPreferences(context)
    }

    private fun applyValues(
        preferences: SharedPreferences,
        values: Map<String, *>
    ) {
        val editor = preferences.edit().clear()
        values.forEach { (key, value) ->
            when (value) {
                is String -> editor.putString(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
                null -> editor.remove(key)
                else -> error("نوع إعداد غير مدعوم")
            }
        }
        check(editor.commit()) { "تعذر حفظ ملف الإعدادات" }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
            "%02x".format(it.toInt() and 0xff)
        }
}
