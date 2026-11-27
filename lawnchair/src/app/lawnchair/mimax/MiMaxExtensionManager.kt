/*
 * Copyright 2026, Mi_Max Launcher contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package app.lawnchair.mimax

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Local registry for declarative theme extensions.
 *
 * MXE extensions are data-only. This manager intentionally does not load
 * dex/jar files, scripts, reflection targets, or code from extension packages.
 */
object MiMaxExtensionManager {
    const val PERMISSION_APPLY_THEME = "theme.apply"
    private const val REGISTRY_PREFS = "mimax_extensions"
    private const val REGISTRY_KEY = "installed_v1"
    private const val RECOVERY_PREFS = "mimax_extension_recovery"
    private const val KEY_PENDING = "theme_apply_pending"
    private const val KEY_PENDING_SNAPSHOT = "pending_theme_snapshot"
    private const val KEY_LAST_GOOD = "last_good_theme_snapshot"

    data class InstalledExtension(
        val definition: MiMaxExtensionCodec.Definition,
        val enabled: Boolean,
        val grantedPermissions: Set<String>,
        val lastError: String?
    )

    fun getAll(context: Context): List<InstalledExtension> {
        val raw = context.getSharedPreferences(REGISTRY_PREFS, Context.MODE_PRIVATE)
            .getString(REGISTRY_KEY, null) ?: return emptyList()
        val parsed = runCatching {
            val array = JSONArray(raw)
            val seenIds = mutableSetOf<String>()
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val entry = runCatching {
                        val definition = MiMaxExtensionCodec.decode(
                            item.getString("manifest").toByteArray(Charsets.UTF_8)
                        )
                        val grantsArray = item.optJSONArray("grantedPermissions") ?: JSONArray()
                        val grants = buildSet {
                            for (grantIndex in 0 until grantsArray.length()) {
                                add(grantsArray.getString(grantIndex))
                            }
                        }.intersect(definition.permissions.toSet())
                        InstalledExtension(
                            definition = definition,
                            enabled = item.optBoolean("enabled", false),
                            grantedPermissions = grants,
                            lastError = item.optString("lastError", "").takeIf { it.isNotBlank() }
                        )
                    }.getOrNull() ?: continue
                    if (seenIds.add(entry.definition.id)) add(entry)
                }
            }
        }.getOrDefault(emptyList())
        return normalizeDependencyState(parsed)
    }

    private fun normalizeDependencyState(
        extensions: List<InstalledExtension>
    ): List<InstalledExtension> {
        val byId = extensions.associateBy { it.definition.id }
        val graph = extensions.associate { it.definition.id to it.definition.dependencies }
        val visiting = mutableListOf<String>()
        val visited = mutableSetOf<String>()
        val cyclic = mutableSetOf<String>()

        fun visit(id: String) {
            if (id in visiting) {
                val cycleStart = visiting.indexOf(id)
                if (cycleStart >= 0) cyclic.addAll(visiting.subList(cycleStart, visiting.size))
                return
            }
            if (!visited.add(id)) return
            visiting.add(id)
            graph[id].orEmpty().forEach { dependency ->
                if (dependency in graph) visit(dependency)
            }
            visiting.removeAt(visiting.lastIndex)
        }
        graph.keys.forEach(::visit)

        val disabledIds = cyclic.toMutableSet()
        var changed: Boolean
        do {
            changed = false
            extensions.forEach { extension ->
                if (extension.enabled && extension.definition.dependencies.any { dependency ->
                        byId[dependency]?.enabled != true || dependency in disabledIds
                    }
                ) {
                    if (disabledIds.add(extension.definition.id)) changed = true
                }
            }
        } while (changed)

        return extensions.map { extension ->
            if (extension.enabled && extension.definition.id in disabledIds) {
                extension.copy(
                    enabled = false,
                    lastError = extension.lastError
                        ?: "تم إيقاف الإضافة بسبب اعتماد مفقود أو حلقة اعتماديات"
                )
            } else extension
        }
    }

    fun catalog(): List<MiMaxExtensionCodec.Definition> = listOf(
        MiMaxExtensionCodec.Definition(
            id = "mimax.theme.hyperos",
            name = "HyperOS Island Theme",
            versionCode = 1L,
            minLauncherVersionCode = 0L,
            description = "قالب الجزيرة بطابع HyperOS",
            dependencies = emptyList(),
            permissions = listOf(PERMISSION_APPLY_THEME),
            theme = MiMaxMt1Codec.Theme(
                "HyperOS", IslandStyle.HIS, 0xFF8C9BFF.toInt(),
                0xF0181D2B.toInt(), 22, 320
            )
        ),
        MiMaxExtensionCodec.Definition(
            id = "mimax.theme.iphone",
            name = "iPhone Island Theme",
            versionCode = 1L,
            minLauncherVersionCode = 0L,
            description = "قالب داكن مستوحى من تصميم الجزيرة في iPhone",
            dependencies = emptyList(),
            permissions = listOf(PERMISSION_APPLY_THEME),
            theme = MiMaxMt1Codec.Theme(
                "iPhone", IslandStyle.LTIN, 0xFFFFFFFF.toInt(),
                0xF0060608.toInt(), 28, 345
            )
        ),
        MiMaxExtensionCodec.Definition(
            id = "mimax.theme.coloros",
            name = "ColorOS Island Theme",
            versionCode = 1L,
            minLauncherVersionCode = 0L,
            description = "قالب الجزيرة بطابع ColorOS",
            dependencies = emptyList(),
            permissions = listOf(PERMISSION_APPLY_THEME),
            theme = MiMaxMt1Codec.Theme(
                "ColorOS", IslandStyle.COLOROS, 0xFF52C7A5.toInt(),
                0xF01B2228.toInt(), 24, 330
            )
        )
    )

    @Synchronized
    fun install(context: Context, definition: MiMaxExtensionCodec.Definition): InstalledExtension {
        val currentVersion = currentLauncherVersionCode(context)
        require(definition.minLauncherVersionCode <= currentVersion) {
            "هذه الإضافة تتطلب إصداراً أحدث من Mi_Max Launcher"
        }
        val installed = getAll(context)
        ensureAcyclicDependencies(installed, definition)
        val previous = installed.firstOrNull { it.definition.id == definition.id }
        if (previous != null) {
            require(definition.versionCode > previous.definition.versionCode) {
                "تحديث الإضافة يجب أن يحمل رقم إصدار أعلى"
            }
        }
        val installedById = installed.associateBy { it.definition.id }
        val unavailable = definition.dependencies.filter { dependency ->
            val match = installedById[dependency]
            match == null || !match.enabled
        }
        require(unavailable.isEmpty()) {
            "اعتماديات الإضافة غير مثبتة أو متوقفة: ${unavailable.joinToString()}"
        }

        val entry = InstalledExtension(
            definition = definition,
            enabled = previous?.enabled ?: false,
            grantedPermissions = previous?.grantedPermissions
                ?.intersect(definition.permissions.toSet()) ?: emptySet(),
            lastError = null
        )
        save(context, installed.filterNot { it.definition.id == definition.id } + entry)
        return entry
    }

    fun installCatalogItem(context: Context, id: String): InstalledExtension {
        getAll(context).firstOrNull { it.definition.id == id }?.let { return it }
        val definition = catalog().firstOrNull { it.id == id }
            ?: error("القالب غير موجود في الكتالوج")
        return install(context, definition)
    }

    @Synchronized
    fun setEnabled(context: Context, id: String, enabled: Boolean): InstalledExtension {
        val installed = getAll(context)
        val current = installed.firstOrNull { it.definition.id == id }
            ?: error("الإضافة غير مثبتة")
        if (enabled) {
            val unavailable = current.definition.dependencies.filter { dependency ->
                installed.none { it.definition.id == dependency && it.enabled }
            }
            require(unavailable.isEmpty()) {
                "لا يمكن تشغيل الإضافة قبل تشغيل اعتمادياتها: ${unavailable.joinToString()}"
            }
        }
        if (!enabled) {
            val activeDependents = installed.filter {
                it.enabled && id in it.definition.dependencies
            }
            require(activeDependents.isEmpty()) {
                "لا يمكن إيقاف الإضافة لأنها مطلوبة من إضافات مفعّلة"
            }
        }
        val updated = current.copy(enabled = enabled, lastError = null)
        save(context, installed.map { if (it.definition.id == id) updated else it })
        return updated
    }

    @Synchronized
    fun grantPermission(
        context: Context,
        id: String,
        permission: String,
        granted: Boolean
    ): InstalledExtension {
        val installed = getAll(context)
        val current = installed.firstOrNull { it.definition.id == id }
            ?: error("الإضافة غير مثبتة")
        require(permission in current.definition.permissions) {
            "الإضافة لم تطلب هذه الصلاحية"
        }
        val grants = current.grantedPermissions.toMutableSet()
        if (granted) grants.add(permission) else grants.remove(permission)
        val updated = current.copy(grantedPermissions = grants)
        save(context, installed.map { if (it.definition.id == id) updated else it })
        return updated
    }

    @Synchronized
    fun uninstall(context: Context, id: String): Boolean {
        val installed = getAll(context)
        require(installed.none { id in it.definition.dependencies }) {
            "لا يمكن إزالة الإضافة لأنها مطلوبة من إضافة أخرى"
        }
        val updated = installed.filterNot { it.definition.id == id }
        if (updated.size == installed.size) return false
        save(context, updated)
        return true
    }

    fun createCurrentThemeExtension(context: Context): MiMaxExtensionCodec.Definition {
        val prefs = context.getSharedPreferences("mimax_launcher", Context.MODE_PRIVATE)
        val theme = MiMaxMt1Codec.Theme(
            name = (prefs.getString("mt1_theme_name", "Mi_Max Theme") ?: "Mi_Max Theme")
                .trim().ifEmpty { "My Mi_Max Theme" }.take(64),
            style = MiMaxIslandPreferences.style(context),
            accentColor = prefs.getInt("mt1_accent_color", 0xFF8C9BFF.toInt()),
            backgroundColor = prefs.getInt("mt1_background_color", 0xF0181D2B.toInt()),
            cornerRadiusDp = prefs.getInt("mt1_corner_radius", 22).coerceIn(8, 48),
            widthDp = prefs.getInt("mt1_width", 320).coerceIn(180, 360)
        )
        val stamp = System.currentTimeMillis()
        return MiMaxExtensionCodec.Definition(
            id = "local.mimax.theme.$stamp",
            name = theme.name.take(64).ifBlank { "My Mi_Max Theme" },
            versionCode = 1L,
            minLauncherVersionCode = 0L,
            description = "ثيم تم تصديره من Mi_Max Launcher",
            dependencies = emptyList(),
            permissions = listOf(PERMISSION_APPLY_THEME),
            theme = theme
        )
    }

    @Synchronized
    fun applyTheme(context: Context, id: String) {
        val extension = getAll(context).firstOrNull { it.definition.id == id }
            ?: error("الإضافة غير مثبتة")
        require(extension.enabled) { "فعّل الإضافة أولاً" }
        require(PERMISSION_APPLY_THEME in extension.grantedPermissions) {
            "يجب منح صلاحية تطبيق الثيم أولاً"
        }

        val targetPrefs = context.getSharedPreferences("mimax_launcher", Context.MODE_PRIVATE)
        val previous = captureTheme(targetPrefs)
        val recovery = context.getSharedPreferences(RECOVERY_PREFS, Context.MODE_PRIVATE)
        check(recovery.edit()
            .putBoolean(KEY_PENDING, true)
            .putString(KEY_PENDING_SNAPSHOT, previous.toString())
            .commit()) { "تعذر حفظ نقطة الاستعادة" }

        try {
            writeTheme(targetPrefs, extension.definition.theme)
            val applied = captureTheme(targetPrefs)
            val expectedTheme = extension.definition.theme
            require(
                applied.optString("name") == expectedTheme.name &&
                    applied.optString("style") == expectedTheme.style.name &&
                    applied.optLong("accent") == expectedTheme.accentColor.toLong() &&
                    applied.optLong("background") == expectedTheme.backgroundColor.toLong() &&
                    applied.optInt("radius") == expectedTheme.cornerRadiusDp &&
                    applied.optInt("width") == expectedTheme.widthDp
            ) { "تعذر التحقق من جميع إعدادات الثيم بعد التطبيق" }
            check(recovery.edit()
                .putString(KEY_LAST_GOOD, applied.toString())
                .remove(KEY_PENDING)
                .remove(KEY_PENDING_SNAPSHOT)
                .commit()) { "تعذر تثبيت نقطة الاستعادة" }
            context.sendBroadcast(Intent(MiMaxIslandPreferences.ACTION_THEME_UPDATED))
            clearError(context, id)
        } catch (failure: Throwable) {
            val rollback = runCatching { restoreTheme(context, previous) }
            disableExtensionAndDependents(
                context,
                id,
                failure.message ?: "فشل تطبيق الثيم"
            )
            if (rollback.isFailure) {
                recordError(
                    context,
                    id,
                    "فشل تطبيق الثيم وتعذر التراجع: ${rollback.exceptionOrNull()?.message ?: "unknown"}"
                )
            }
            recovery.edit().remove(KEY_PENDING).remove(KEY_PENDING_SNAPSHOT).apply()
            context.sendBroadcast(Intent(MiMaxIslandPreferences.ACTION_THEME_UPDATED))
            throw failure
        }
    }

    /** Rolls back an interrupted theme transaction on the next settings launch. */
    fun recoverIfPending(context: Context): Boolean {
        val recovery = context.getSharedPreferences(RECOVERY_PREFS, Context.MODE_PRIVATE)
        if (!recovery.getBoolean(KEY_PENDING, false)) return false
        val pendingSnapshot = recovery.getString(KEY_PENDING_SNAPSHOT, null)
        val lastGoodSnapshot = recovery.getString(KEY_LAST_GOOD, null)
        val restored = runCatching {
            val snapshot = pendingSnapshot?.takeIf { it.isNotBlank() }
                ?: lastGoodSnapshot?.takeIf { it.isNotBlank() }
                ?: error("لا توجد نقطة استعادة صالحة")
            restoreTheme(context, JSONObject(snapshot))
        }.isSuccess
        recovery.edit()
            .remove(KEY_PENDING)
            .remove(KEY_PENDING_SNAPSHOT)
            .apply()
        context.sendBroadcast(Intent(MiMaxIslandPreferences.ACTION_THEME_UPDATED))
        return restored
    }

    fun restoreLastKnownGood(context: Context): Boolean {
        val recovery = context.getSharedPreferences(RECOVERY_PREFS, Context.MODE_PRIVATE)
        val snapshot = recovery.getString(KEY_LAST_GOOD, null) ?: return false
        return runCatching {
            restoreTheme(context, JSONObject(snapshot))
            context.sendBroadcast(Intent(MiMaxIslandPreferences.ACTION_THEME_UPDATED))
        }.isSuccess
    }

    private fun captureTheme(prefs: android.content.SharedPreferences): JSONObject =
        JSONObject()
            .put("name", prefs.getString("mt1_theme_name", "Mi_Max Theme"))
            .put("style", prefs.getString("island_style", IslandStyle.HIS.name))
            .put("accent", prefs.getInt("mt1_accent_color", 0xFF8C9BFF.toInt()).toLong())
            .put("background", prefs.getInt("mt1_background_color", 0xF0181D2B.toInt()).toLong())
            .put("radius", prefs.getInt("mt1_corner_radius", 22))
            .put("width", prefs.getInt("mt1_width", 320))

    private fun writeTheme(
        prefs: android.content.SharedPreferences,
        theme: MiMaxMt1Codec.Theme
    ) {
        check(theme.cornerRadiusDp in 8..48 && theme.widthDp in 180..360)
        check(prefs.edit()
            .putString("mt1_theme_name", theme.name)
            .putString("island_style", theme.style.name)
            .putInt("mt1_accent_color", theme.accentColor)
            .putInt("mt1_background_color", theme.backgroundColor)
            .putInt("mt1_corner_radius", theme.cornerRadiusDp)
            .putInt("mt1_width", theme.widthDp)
            .commit()) { "تعذر حفظ إعدادات الثيم" }
    }

    private fun restoreTheme(context: Context, snapshot: JSONObject) {
        val style = runCatching { IslandStyle.valueOf(snapshot.getString("style")) }
            .getOrDefault(IslandStyle.HIS)
        val theme = MiMaxMt1Codec.Theme(
            name = snapshot.optString("name", "Mi_Max Theme").take(64).ifBlank { "Mi_Max Theme" },
            style = style,
            accentColor = snapshot.optLong("accent", 0xFF8C9BFFL).toInt(),
            backgroundColor = snapshot.optLong("background", 0xF0181D2BL).toInt(),
            cornerRadiusDp = snapshot.optInt("radius", 22).coerceIn(8, 48),
            widthDp = snapshot.optInt("width", 320).coerceIn(180, 360)
        )
        writeTheme(context.getSharedPreferences("mimax_launcher", Context.MODE_PRIVATE), theme)
    }

    private fun ensureAcyclicDependencies(
        installed: List<InstalledExtension>,
        candidate: MiMaxExtensionCodec.Definition
    ) {
        val graph = installed
            .filterNot { it.definition.id == candidate.id }
            .associate { it.definition.id to it.definition.dependencies }
            .toMutableMap()
        graph[candidate.id] = candidate.dependencies

        val visiting = mutableSetOf<String>()
        val visited = mutableSetOf<String>()
        fun visit(id: String): Boolean {
            if (id in visiting) return false
            if (id in visited) return true
            visiting.add(id)
            for (dependency in graph[id].orEmpty()) {
                if (dependency in graph && !visit(dependency)) return false
            }
            visiting.remove(id)
            visited.add(id)
            return true
        }
        require(graph.keys.all(::visit)) {
            "تم اكتشاف حلقة في اعتماديات الإضافات"
        }
    }

    /** Disable the failed theme extension and every enabled extension that depends on it. */
    private fun disableExtensionAndDependents(
        context: Context,
        failedId: String,
        message: String
    ) {
        val installed = getAll(context)
        val disabledIds = mutableSetOf(failedId)
        var changed: Boolean
        do {
            changed = false
            installed.forEach { extension ->
                if (extension.enabled &&
                    extension.definition.dependencies.any { it in disabledIds }
                ) {
                    if (disabledIds.add(extension.definition.id)) changed = true
                }
            }
        } while (changed)

        save(context, installed.map { extension ->
            when {
                extension.definition.id == failedId ->
                    extension.copy(enabled = false, lastError = message.take(240))
                extension.definition.id in disabledIds ->
                    extension.copy(
                        enabled = false,
                        lastError = "تم إيقاف الإضافة بسبب اعتماد مفقود: $failedId"
                    )
                else -> extension
            }
        })
    }

    private fun recordError(context: Context, id: String, message: String) {
        val installed = getAll(context)
        save(context, installed.map {
            if (it.definition.id == id) it.copy(lastError = message.take(240)) else it
        })
    }

    private fun clearError(context: Context, id: String) {
        val installed = getAll(context)
        save(context, installed.map {
            if (it.definition.id == id) it.copy(lastError = null) else it
        })
    }

    private fun save(context: Context, extensions: List<InstalledExtension>) {
        val array = JSONArray()
        extensions.forEach { extension ->
            val item = JSONObject()
                .put("manifest", String(
                    MiMaxExtensionCodec.encode(extension.definition), Charsets.UTF_8
                ))
                .put("enabled", extension.enabled)
                .put("grantedPermissions", JSONArray(extension.grantedPermissions.sorted()))
            extension.lastError?.let { item.put("lastError", it.take(240)) }
            array.put(item)
        }
        check(context.getSharedPreferences(REGISTRY_PREFS, Context.MODE_PRIVATE)
            .edit().putString(REGISTRY_KEY, array.toString()).commit()) {
            "تعذر حفظ سجل الإضافات"
        }
    }

    @Suppress("DEPRECATION")
    private fun currentLauncherVersionCode(context: Context): Long =
        context.packageManager.getPackageInfo(context.packageName, 0).versionCode.toLong()
}
