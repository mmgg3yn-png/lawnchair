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
 * Persistent relationship model for Mi_Max stacked folders.
 *
 * Launcher remains the source of truth for the actual FolderInfo records.
 * This store only persists ordering, selection, and the stack title.
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
            childFolderIds.getOrNull(
                selectedIndex.coerceIn(0, (childFolderIds.size - 1).coerceAtLeast(0))
            )
    }

    @Synchronized
    fun getAll(): List<Stack> {
        val raw = prefs.getString(KEY_STACKS, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            val claimedFolderIds = mutableSetOf<Long>()
            buildList {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    val stackId = item.optLong("id", INVALID_ID)
                    if (stackId <= 0L || any { it.id == stackId }) continue
                    val children = item.optJSONArray("children") ?: JSONArray()
                    val candidates = buildList {
                        for (j in 0 until children.length()) {
                            val folderId = children.optLong(j, INVALID_ID)
                            if (folderId != INVALID_ID && folderId !in this) add(folderId)
                        }
                    }
                    val ids = candidates.filter { claimedFolderIds.add(it) }
                    // A stack with fewer than two valid folders is not a stack.
                    if (ids.size >= 2) {
                        val rawTitle = item.optString("title", "Stack").trim()
                        add(
                            Stack(
                                id = stackId,
                                title = rawTitle.ifEmpty { "Stack" }.take(MAX_TITLE_LENGTH),
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
        val ids = childFolderIds
            .filter { it != INVALID_ID }
            .distinct()
        if (ids.size < 2) return null

        val stacks = getAll()
        val matching = stacks.filter { stack ->
            stack.childFolderIds.any { it in ids }
        }
        val orderedIds = mutableListOf<Long>()
        matching.forEach { stack ->
            stack.childFolderIds.forEach { id ->
                if (id !in orderedIds) orderedIds.add(id)
            }
        }
        ids.forEach { id -> if (id !in orderedIds) orderedIds.add(id) }

        val retained = matching.firstOrNull()
        val selectedId = retained?.selectedFolderId()
        val created = if (retained == null) {
            Stack(
                id = nextId(),
                title = normalizeTitle(title),
                childFolderIds = orderedIds,
                selectedIndex = 0
            )
        } else {
            retained.copy(
                title = if (title.isBlank()) retained.title else normalizeTitle(title),
                childFolderIds = orderedIds,
                selectedIndex = orderedIds.indexOf(selectedId).coerceAtLeast(0)
            )
        }
        val matchingIds = matching.map { it.id }.toSet()
        save(stacks.filterNot { it.id in matchingIds } + created)
        return created
    }

    /**
     * Adds a folder to a stack. If that folder already belongs to another
     * stack, the two stacks are merged to preserve one-stack-per-folder.
     */
    @Synchronized
    fun addFolder(stackId: Long, folderId: Long): Boolean {
        if (folderId == INVALID_ID) return false
        val stacks = getAll()
        val target = stacks.firstOrNull { it.id == stackId } ?: return false
        if (folderId in target.childFolderIds) return false

        val matching = stacks.filter {
            it.id == stackId || folderId in it.childFolderIds
        }
        val orderedIds = mutableListOf<Long>()
        matching.forEach { stack ->
            stack.childFolderIds.forEach { id ->
                if (id !in orderedIds) orderedIds.add(id)
            }
        }
        if (folderId !in orderedIds) orderedIds.add(folderId)
        if (orderedIds.size < 2) return false

        val selectedId = target.selectedFolderId()
        val updated = target.copy(
            childFolderIds = orderedIds,
            selectedIndex = orderedIds.indexOf(selectedId).coerceAtLeast(0)
        )
        val matchingIds = matching.map { it.id }.toSet()
        save(stacks.filterNot { it.id in matchingIds } + updated)
        return true
    }

    /** Merges the stacks that contain either folder, then adds both IDs. */
    @Synchronized
    fun mergeFolders(title: String, firstFolderId: Long, secondFolderId: Long): Stack? {
        if (firstFolderId == INVALID_ID || secondFolderId == INVALID_ID ||
            firstFolderId == secondFolderId
        ) return null

        val stacks = getAll()
        val matching = stacks.filter { stack ->
            firstFolderId in stack.childFolderIds || secondFolderId in stack.childFolderIds
        }
        val orderedIds = mutableListOf<Long>()
        matching.forEach { stack ->
            stack.childFolderIds.forEach { id ->
                if (id !in orderedIds) orderedIds.add(id)
            }
        }
        if (firstFolderId !in orderedIds) orderedIds.add(firstFolderId)
        if (secondFolderId !in orderedIds) orderedIds.add(secondFolderId)
        if (orderedIds.size < 2) return null

        val retained = matching.firstOrNull()
        val selectedId = retained?.selectedFolderId()
        val merged = if (retained == null) {
            Stack(
                id = nextId(),
                title = normalizeTitle(title),
                childFolderIds = orderedIds,
                selectedIndex = 0
            )
        } else {
            retained.copy(
                title = if (retained.title.isBlank()) normalizeTitle(title) else retained.title,
                childFolderIds = orderedIds,
                selectedIndex = orderedIds.indexOf(selectedId).coerceAtLeast(0)
            )
        }

        val matchingIds = matching.map { it.id }.toSet()
        save(stacks.filterNot { it.id in matchingIds } + merged)
        return merged
    }

    /** Removes a folder deleted by Launcher and prunes invalid one-item stacks. */
    @Synchronized
    fun removeFolderFromAll(folderId: Long) {
        if (folderId == INVALID_ID) return
        val updated = getAll().mapNotNull { stack ->
            val selectedId = stack.selectedFolderId()
            val remaining = stack.childFolderIds.filterNot { it == folderId }
            if (remaining.size < 2) {
                null
            } else {
                stack.copy(
                    childFolderIds = remaining,
                    selectedIndex = remaining.indexOf(selectedId).let {
                        if (it < 0) 0 else it
                    }
                )
            }
        }
        save(updated)
    }

    @Synchronized
    fun removeFolder(stackId: Long, folderId: Long): Stack? {
        val stacks = getAll().toMutableList()
        val index = stacks.indexOfFirst { it.id == stackId }
        if (index < 0) return null
        val old = stacks[index]
        val selectedId = old.selectedFolderId()
        val remaining = old.childFolderIds.filterNot { it == folderId }
        if (remaining.size < 2) {
            stacks.removeAt(index)
            save(stacks)
            return null
        }
        val updated = old.copy(
            childFolderIds = remaining,
            selectedIndex = remaining.indexOf(selectedId).let {
                if (it < 0) old.selectedIndex.coerceIn(0, remaining.lastIndex) else it
            }
        )
        stacks[index] = updated
        save(stacks)
        return updated
    }

    /** Moves a folder within a stack without changing its identity or selection. */
    @Synchronized
    fun moveFolder(stackId: Long, folderId: Long, offset: Int): Stack? {
        if (offset == 0) return getAll().firstOrNull { it.id == stackId }
        val stacks = getAll().toMutableList()
        val position = stacks.indexOfFirst { it.id == stackId }
        if (position < 0) return null
        val current = stacks[position]
        val from = current.childFolderIds.indexOf(folderId)
        if (from < 0) return null
        val to = (from + offset.coerceIn(-1, 1))
            .coerceIn(0, current.childFolderIds.lastIndex)
        if (from == to) return current
        val reordered = current.childFolderIds.toMutableList()
        val moved = reordered.removeAt(from)
        reordered.add(to, moved)
        val selectedId = current.selectedFolderId()
        val updated = current.copy(
            childFolderIds = reordered,
            selectedIndex = reordered.indexOf(selectedId).coerceAtLeast(0)
        )
        stacks[position] = updated
        save(stacks)
        return updated
    }

    @Synchronized
    fun select(stackId: Long, index: Int): Stack? {
        val stacks = getAll().toMutableList()
        val position = stacks.indexOfFirst { it.id == stackId }
        if (position < 0) return null
        val stack = stacks[position]
        if (stack.childFolderIds.size < 2) return null
        val updated = stack.copy(
            selectedIndex = index.coerceIn(0, stack.childFolderIds.lastIndex)
        )
        stacks[position] = updated
        save(stacks)
        return updated
    }

    @Synchronized
    fun rename(stackId: Long, title: String): Boolean {
        val stacks = getAll().toMutableList()
        val index = stacks.indexOfFirst { it.id == stackId }
        if (index < 0) return false
        stacks[index] = stacks[index].copy(title = normalizeTitle(title))
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

    /** Sanitizes persisted data and enforces globally unique folder membership. */
    private fun save(stacks: List<Stack>) {
        val claimedFolderIds = mutableSetOf<Long>()
        val stackIds = mutableSetOf<Long>()
        val array = JSONArray()
        stacks.forEach { stack ->
            if (stack.id <= 0L || !stackIds.add(stack.id)) return@forEach
            val candidates = stack.childFolderIds
                .filter { it != INVALID_ID }
                .distinct()
            val ids = candidates.filter { it !in claimedFolderIds }
            if (ids.size < 2) return@forEach
            claimedFolderIds.addAll(ids)
            val selectedId = stack.selectedFolderId()
            val selectedIndex = ids.indexOf(selectedId).let {
                if (it < 0) 0 else it
            }
            val item = JSONObject()
                .put("id", stack.id)
                .put("title", normalizeTitle(stack.title))
                .put("selected", selectedIndex)
            val children = JSONArray()
            ids.forEach(children::put)
            item.put("children", children)
            array.put(item)
        }
        check(prefs.edit().putString(KEY_STACKS, array.toString()).commit()) {
            "Unable to persist stacked folders"
        }
    }

    private fun normalizeTitle(value: String): String =
        value.trim().ifEmpty { "Stack" }.take(MAX_TITLE_LENGTH)

    companion object {
        private const val PREFS_NAME = "mimax_stacked_folders"
        private const val KEY_STACKS = "stacks_v1"
        private const val KEY_NEXT_ID = "next_stack_id"
        private const val MAX_TITLE_LENGTH = 48
        private const val INVALID_ID = -1L
    }
}
