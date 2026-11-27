/*
 * Copyright (C) 2026 Mi_Max Launcher contributors
 *
 * Licensed under the Apache License, Version 2.0.
 */
package app.lawnchair.mimax

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persistent model for Mi_Max stacked folders.
 *
 * A stack is a folder that contains references to child folders. The actual
 * Launcher folder/database remains the source of truth for shortcuts; this
 * store only records the stack relationship and presentation order.
 */
class MiMaxFolderStackStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    data class Stack(
        val id: Long,
        val title: String,
        val childFolderIds: List<Long>,
        val selectedIndex: Int
    ) {
        fun selectedFolderId(): Long? =
            childFolderIds.getOrNull(selectedIndex.coerceIn(0, (childFolderIds.size - 1).coerceAtLeast(0)))
    }

    @Synchronized
    fun getAll(): List<Stack> {
        val raw = prefs.getString(KEY_STACKS, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    val children = item.optJSONArray("children") ?: JSONArray()
                    val ids = buildList {
                        for (j in 0 until children.length()) {
                            val id = children.optLong(j, INVALID_ID)
                            if (id != INVALID_ID && id !in this) add(id)
                        }
                    }
                    if (ids.isNotEmpty()) {
                        add(
                            Stack(
                                id = item.optLong("id", INVALID_ID),
                                title = item.optString("title", "Stack"),
                                childFolderIds = ids,
                                selectedIndex = item.optInt("selected", 0)
                                    .coerceIn(0, ids.lastIndex)
                            )
                        )
                    }
                }
            }
        }.getOrDefault(emptyList())
    }

    @Synchronized
    fun create(title: String, childFolderIds: List<Long>): Stack? {
        val ids = childFolderIds.filter { it != INVALID_ID }.distinct()
        if (ids.size < 2) return null
        val stack = Stack(
            id = nextId(),
            title = title.trim().ifEmpty { "Stack" }.take(MAX_TITLE_LENGTH),
            childFolderIds = ids,
            selectedIndex = 0
        )
        save(getAll() + stack)
        return stack
    }

    @Synchronized
    fun addFolder(stackId: Long, folderId: Long): Boolean {
        if (folderId == INVALID_ID) return false
        val stacks = getAll().toMutableList()
        val index = stacks.indexOfFirst { it.id == stackId }
        if (index < 0) return false
        val stack = stacks[index]
        if (folderId in stack.childFolderIds) return false
        stacks[index] = stack.copy(childFolderIds = stack.childFolderIds + folderId)
        save(stacks)
        return true
    }

    @Synchronized
    fun removeFolder(stackId: Long, folderId: Long): Stack? {
        val stacks = getAll().toMutableList()
        val index = stacks.indexOfFirst { it.id == stackId }
        if (index < 0) return null
        val old = stacks[index]
        val remaining = old.childFolderIds.filterNot { it == folderId }
        if (remaining.isEmpty()) {
            stacks.removeAt(index)
            save(stacks)
            return null
        }
        val updated = old.copy(
            childFolderIds = remaining,
            selectedIndex = old.selectedIndex.coerceIn(0, remaining.lastIndex)
        )
        stacks[index] = updated
        save(stacks)
        return updated
    }

    @Synchronized
    fun select(stackId: Long, index: Int): Stack? {
        val stacks = getAll().toMutableList()
        val position = stacks.indexOfFirst { it.id == stackId }
        if (position < 0) return null
        val stack = stacks[position]
        val updated = stack.copy(selectedIndex = index.coerceIn(0, stack.childFolderIds.lastIndex))
        stacks[position] = updated
        save(stacks)
        return updated
    }

    @Synchronized
    fun rename(stackId: Long, title: String): Boolean {
        val stacks = getAll().toMutableList()
        val index = stacks.indexOfFirst { it.id == stackId }
        if (index < 0) return false
        stacks[index] = stacks[index].copy(
            title = title.trim().ifEmpty { "Stack" }.take(MAX_TITLE_LENGTH)
        )
        save(stacks)
        return true
    }

    @Synchronized
    fun delete(stackId: Long): Boolean {
        val stacks = getAll().toMutableList()
        val removed = stacks.removeAll { it.id == stackId }
        if (removed) save(stacks)
        return removed
    }

    private fun nextId(): Long {
        val current = prefs.getLong(KEY_NEXT_ID, 1L).coerceAtLeast(1L)
        prefs.edit().putLong(KEY_NEXT_ID, current + 1L).apply()
        return current
    }

    private fun save(stacks: List<Stack>) {
        val array = JSONArray()
        stacks.forEach { stack ->
            val item = JSONObject()
                .put("id", stack.id)
                .put("title", stack.title)
                .put("selected", stack.selectedIndex)
            val children = JSONArray()
            stack.childFolderIds.forEach(children::put)
            item.put("children", children)
            array.put(item)
        }
        prefs.edit().putString(KEY_STACKS, array.toString()).apply()
    }

    companion object {
        private const val PREFS_NAME = "mimax_stacked_folders"
        private const val KEY_STACKS = "stacks_v1"
        private const val KEY_NEXT_ID = "next_stack_id"
        private const val MAX_TITLE_LENGTH = 48
        private const val INVALID_ID = -1L
    }
}
