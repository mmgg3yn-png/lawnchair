/*
 * Copyright 2026, Mi_Max Launcher contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package app.lawnchair.mimax

import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.MessageDigest

/**
 * MT1 is an original Mi_Max theme-package container.
 *
 * Layout: 8-byte magic "MIMAXMT1", uint16 version (big-endian),
 * uint32 UTF-8 JSON payload length, 32-byte SHA-256 payload digest,
 * then the exact UTF-8 JSON payload. Maximum payload: 1 MiB.
 */
object MiMaxMt1Codec {
    private val MAGIC = byteArrayOf(0x4D, 0x49, 0x4D, 0x41, 0x58, 0x4D, 0x54, 0x31)
    private const val VERSION = 1
    private const val MAX_PAYLOAD = 1024 * 1024

    data class Theme(
        val name: String,
        val style: IslandStyle,
        val accentColor: Int,
        val backgroundColor: Int,
        val cornerRadiusDp: Int,
        val widthDp: Int
    )

    fun encode(theme: Theme): ByteArray {
        require(theme.name.isNotBlank() && theme.name.length <= 64)
        require(theme.cornerRadiusDp in 8..48)
        require(theme.widthDp in 180..360)
        val json = JSONObject()
            .put("format", "Mi_Max Theme")
            .put("name", theme.name)
            .put("style", theme.style.name)
            .put("accentColor", theme.accentColor.toLong() and 0xFFFFFFFFL)
            .put("backgroundColor", theme.backgroundColor.toLong() and 0xFFFFFFFFL)
            .put("cornerRadiusDp", theme.cornerRadiusDp)
            .put("widthDp", theme.widthDp)
            .toString()
        val payload = json.toByteArray(Charsets.UTF_8)
        require(payload.size <= MAX_PAYLOAD)
        val digest = MessageDigest.getInstance("SHA-256").digest(payload)
        return ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { out ->
                out.write(MAGIC)
                out.writeShort(VERSION)
                out.writeInt(payload.size)
                out.write(digest)
                out.write(payload)
            }
            bytes.toByteArray()
        }
    }

    fun decode(bytes: ByteArray): Theme {
        require(bytes.size >= 46) { "ملف MT1 أقصر من ترويسة الصيغة" }
        require(bytes.size <= MAX_PAYLOAD + 46) { "حجم ملف MT1 يتجاوز الحد المسموح" }
        val input = DataInputStream(ByteArrayInputStream(bytes))
        val magic = ByteArray(8)
        input.readFully(magic)
        require(magic.contentEquals(MAGIC)) { "هذا ليس ملف Mi_Max MT1" }
        val version = input.readUnsignedShort()
        require(version == VERSION) { "إصدار MT1 غير مدعوم: $version" }
        val length = input.readInt()
        require(length in 2..MAX_PAYLOAD && bytes.size == 46 + length) {
            "طول بيانات MT1 غير صحيح"
        }
        val expectedDigest = ByteArray(32)
        input.readFully(expectedDigest)
        val payload = ByteArray(length)
        input.readFully(payload)
        val actualDigest = MessageDigest.getInstance("SHA-256").digest(payload)
        require(MessageDigest.isEqual(expectedDigest, actualDigest)) {
            "فشل التحقق من سلامة ملف MT1"
        }
        val json = JSONObject(String(payload, Charsets.UTF_8))
        require(json.optString("format") == "Mi_Max Theme") { "نوع محتوى MT1 غير معروف" }
        val style = runCatching { IslandStyle.valueOf(json.getString("style")) }
            .getOrElse { throw IllegalArgumentException("تصميم الجزيرة داخل الملف غير صالح") }
        val name = json.getString("name")
        val radius = json.getInt("cornerRadiusDp")
        val width = json.getInt("widthDp")
        require(name.isNotBlank() && name.length <= 64) { "اسم الثيم غير صالح" }
        require(radius in 8..48 && width in 180..360) { "قيم أبعاد الثيم خارج النطاق" }
        return Theme(
            name, style,
            json.getLong("accentColor").toInt(),
            json.getLong("backgroundColor").toInt(),
            radius, width
        )
    }
}
