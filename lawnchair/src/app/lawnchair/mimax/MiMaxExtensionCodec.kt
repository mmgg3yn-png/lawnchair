/*
 * Copyright 2026, Mi_Max Launcher contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package app.lawnchair.mimax

import org.json.JSONArray
import org.json.JSONObject

/**
 * Declarative MXE package codec. MXE files contain theme data and metadata,
 * never executable classes, scripts, reflection targets, or remote code.
 */
object MiMaxExtensionCodec {
    private const val FORMAT = "MIMAX_EXTENSION"
    private const val SCHEMA_VERSION = 1
    private const val MAX_BYTES = 1024 * 1024
    private val allowedPermissions = setOf("theme.apply")

    data class Definition(
        val id: String,
        val name: String,
        val versionCode: Long,
        val minLauncherVersionCode: Long,
        val description: String,
        val dependencies: List<String>,
        val permissions: List<String>,
        val theme: MiMaxMt1Codec.Theme
    )

    fun decode(bytes: ByteArray): Definition {
        require(bytes.isNotEmpty() && bytes.size <= MAX_BYTES) {
            "حجم إضافة MXE غير صالح"
        }
        val root = JSONObject(String(bytes, Charsets.UTF_8))
        require(root.optString("format") == FORMAT) { "تنسيق الإضافة غير معروف" }
        require(root.optInt("schemaVersion", -1) == SCHEMA_VERSION) {
            "إصدار تنسيق الإضافات غير مدعوم"
        }

        val id = root.getString("id")
        require(ID_PATTERN.matches(id)) { "معرّف الإضافة غير صالح" }
        val name = root.getString("name").trim()
        require(name.isNotEmpty() && name.length <= 64) { "اسم الإضافة غير صالح" }
        val versionCode = root.getLong("versionCode")
        val minVersion = root.optLong("minLauncherVersionCode", 0L)
        require(versionCode in 1L..MAX_VERSION_CODE) { "رقم إصدار الإضافة غير صالح" }
        require(minVersion in 0L..MAX_VERSION_CODE) { "حد التوافق غير صالح" }

        val dependencies = readStringArray(root.optJSONArray("dependencies"), MAX_DEPENDENCIES)
            .onEach { require(ID_PATTERN.matches(it) && it != id) { "اعتمادية غير صالحة" } }
            .distinct()
        val permissions = readStringArray(root.optJSONArray("permissions"), MAX_PERMISSIONS)
            .distinct()
        require(permissions.all { it in allowedPermissions }) {
            "الإضافة تطلب صلاحية غير مدعومة"
        }
        require("theme.apply" in permissions) {
            "إضافة الثيم يجب أن تطلب صلاحية theme.apply"
        }

        val themeJson = root.getJSONObject("theme")
        val style = runCatching { IslandStyle.valueOf(themeJson.getString("style")) }
            .getOrElse { throw IllegalArgumentException("نمط الثيم غير مدعوم") }
        val themeName = themeJson.optString("name", name).trim().ifEmpty { name }.take(64)
        val accent = themeJson.getLong("accentColor")
        val background = themeJson.getLong("backgroundColor")
        val radius = themeJson.getInt("cornerRadiusDp")
        val width = themeJson.getInt("widthDp")
        require(accent in 0L..0xFFFFFFFFL && background in 0L..0xFFFFFFFFL) {
            "لون داخل الإضافة خارج النطاق"
        }
        require(radius in 8..48 && width in 180..360) {
            "أبعاد الثيم داخل الإضافة خارج النطاق"
        }

        return Definition(
            id = id,
            name = name,
            versionCode = versionCode,
            minLauncherVersionCode = minVersion,
            description = root.optString("description", "").take(240),
            dependencies = dependencies,
            permissions = permissions,
            theme = MiMaxMt1Codec.Theme(
                themeName, style, accent.toInt(), background.toInt(), radius, width
            )
        )
    }

    fun encode(definition: Definition): ByteArray {
        require(ID_PATTERN.matches(definition.id))
        require(definition.name.isNotBlank() && definition.name.length <= 64)
        require(definition.versionCode in 1L..MAX_VERSION_CODE)
        require(definition.minLauncherVersionCode in 0L..MAX_VERSION_CODE)
        require(definition.dependencies.size <= MAX_DEPENDENCIES)
        require(definition.dependencies.distinct().size == definition.dependencies.size)
        require(definition.dependencies.all {
            ID_PATTERN.matches(it) && it != definition.id
        })
        require(definition.permissions.size <= MAX_PERMISSIONS)
        require(definition.permissions.all { it in allowedPermissions })
        require("theme.apply" in definition.permissions)
        require(definition.description.length <= 240)

        val theme = definition.theme
        require(theme.name.isNotBlank() && theme.name.length <= 64) {
            "اسم الثيم داخل الإضافة غير صالح"
        }
        require(theme.cornerRadiusDp in 8..48 && theme.widthDp in 180..360)
        val themeJson = JSONObject()
            .put("name", theme.name)
            .put("style", theme.style.name)
            .put("accentColor", theme.accentColor.toLong() and 0xFFFFFFFFL)
            .put("backgroundColor", theme.backgroundColor.toLong() and 0xFFFFFFFFL)
            .put("cornerRadiusDp", theme.cornerRadiusDp)
            .put("widthDp", theme.widthDp)
        val root = JSONObject()
            .put("format", FORMAT)
            .put("schemaVersion", SCHEMA_VERSION)
            .put("id", definition.id)
            .put("name", definition.name)
            .put("versionCode", definition.versionCode)
            .put("minLauncherVersionCode", definition.minLauncherVersionCode)
            .put("description", definition.description)
            .put("dependencies", JSONArray(definition.dependencies))
            .put("permissions", JSONArray(definition.permissions))
            .put("theme", themeJson)
        val bytes = root.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_BYTES) { "ملف الإضافة أكبر من الحد المسموح" }
        return bytes
    }

    private fun readStringArray(array: JSONArray?, maxItems: Int): List<String> {
        if (array == null) return emptyList()
        require(array.length() <= maxItems) { "قائمة الإضافة أطول من الحد المسموح" }
        return buildList {
            for (index in 0 until array.length()) {
                val value = array.getString(index)
                require(value.isNotBlank() && value.length <= 96) {
                    "قيمة غير صالحة داخل قائمة الإضافة"
                }
                add(value)
            }
        }
    }

    private val ID_PATTERN = Regex("[a-z0-9][a-z0-9._-]{2,63}")
    private const val MAX_DEPENDENCIES = 12
    private const val MAX_PERMISSIONS = 4
    private const val MAX_VERSION_CODE = 2_147_483_647L
}
