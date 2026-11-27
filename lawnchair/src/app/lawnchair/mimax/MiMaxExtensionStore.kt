/*
 * Copyright 2026, Mi_Max Launcher contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package app.lawnchair.mimax

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * Online catalog for declarative MXE themes. Only validated theme data is
 * accepted; the store never downloads or executes code.
 */
object MiMaxExtensionStore {
    private const val CATALOG_URL =
        "https://raw.githubusercontent.com/mmgg3yn-png/lawnchair/mimax/feature-batch-staging/mimax-extension-catalog/catalog.json"
    private const val MAX_CATALOG_BYTES = 256 * 1024
    private const val MAX_ENTRIES = 100

    fun fetchCatalog(): List<MiMaxExtensionCodec.Definition> {
        val connection = URL(CATALOG_URL).openConnection() as HttpsURLConnection
        try {
            connection.connectTimeout = 8_000
            connection.readTimeout = 8_000
            connection.instanceFollowRedirects = true
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "Mi_Max-Launcher-Extension-Store")
            connection.connect()
            require(connection.url.protocol.equals("https", ignoreCase = true)) {
                "رابط متجر الإضافات غير آمن"
            }
            require(connection.responseCode in 200..299) {
                "تعذر تحميل المتجر (HTTP ${connection.responseCode})"
            }
            val output = ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buffer = ByteArray(4096)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= MAX_CATALOG_BYTES) {
                        "ملف كتالوج المتجر أكبر من الحد المسموح"
                    }
                    output.write(buffer, 0, count)
                }
            }
            val root = JSONObject(output.toByteArray().toString(Charsets.UTF_8))
            require(root.optString("format") == "MIMAX_EXTENSION_CATALOG") {
                "تنسيق كتالوج المتجر غير معروف"
            }
            require(root.optInt("schemaVersion", -1) == 1) {
                "إصدار كتالوج المتجر غير مدعوم"
            }
            val entries = root.getJSONArray("extensions")
            require(entries.length() <= MAX_ENTRIES) {
                "عدد إضافات المتجر أكبر من الحد المسموح"
            }
            val ids = mutableSetOf<String>()
            return buildList {
                for (index in 0 until entries.length()) {
                    val item = entries.getJSONObject(index)
                    val definition = MiMaxExtensionCodec.decode(
                        item.toString().toByteArray(Charsets.UTF_8)
                    )
                    if (ids.add(definition.id)) add(definition)
                }
            }
        } finally {
            connection.disconnect()
        }
    }
}
