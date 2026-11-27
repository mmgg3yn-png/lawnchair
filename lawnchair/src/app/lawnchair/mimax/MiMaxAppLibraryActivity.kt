/*
 * Copyright 2026, Mi_Max Launcher contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package app.lawnchair.mimax

import android.app.Activity
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

/** Native searchable and category-filtered library of launchable installed apps. */
class MiMaxAppLibraryActivity : Activity() {
    private data class Entry(
        val label: String,
        val packageName: String,
        val category: String,
        val icon: android.graphics.drawable.Drawable?
    )

    private lateinit var adapter: LibraryAdapter
    private var allEntries: List<Entry> = emptyList()
    private var selectedCategory = "الكل"
    private var searchText = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        loadApps()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(20), dp(16), dp(8))
            setBackgroundColor(Color.rgb(16, 17, 22))
        }
        setContentView(root)
        root.addView(TextView(this).apply {
            text = "مكتبة التطبيقات"
            textSize = 24f
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, dp(12))
        })
        val search = EditText(this).apply {
            hint = "ابحث عن تطبيق أو حزمة"
            setSingleLine(true)
            setTextColor(Color.WHITE)
            setHintTextColor(0xFF9999A5.toInt())
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setBackgroundColor(0xFF252731.toInt())
        }
        root.addView(search, LinearLayout.LayoutParams(-1, dp(48)))
        val categoryScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
        }
        val categoryRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10), 0, dp(10))
        }
        val categories = listOf("الكل", "ألعاب", "اجتماعي", "إنتاجية", "وسائط", "صور", "خرائط", "أخبار", "أخرى")
        categories.forEach { category ->
            categoryRow.addView(TextView(this).apply {
                text = category
                textSize = 13f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                setPadding(dp(14), dp(9), dp(14), dp(9))
                setBackgroundColor(if (category == selectedCategory) 0xFF536CA8.toInt() else 0xFF292C35.toInt())
                setOnClickListener {
                    selectedCategory = category
                    refreshRows()
                    for (index in 0 until categoryRow.childCount) {
                        val child = categoryRow.getChildAt(index) as TextView
                        child.setBackgroundColor(
                            if (child.text == selectedCategory) 0xFF536CA8.toInt()
                            else 0xFF292C35.toInt()
                        )
                    }
                }
            }.also { view ->
                (view.layoutParams as? LinearLayout.LayoutParams)?.let { it.marginEnd = dp(6) }
            })
        }
        categoryScroll.addView(categoryRow)
        root.addView(categoryScroll)
        val list = ListView(this).apply {
            divider = null
            cacheColorHint = Color.TRANSPARENT
        }
        adapter = LibraryAdapter()
        list.adapter = adapter
        list.setOnItemClickListener { _, _, position, _ ->
            val entry = adapter.entryAt(position) ?: return@setOnItemClickListener
            val launch = packageManager.getLaunchIntentForPackage(entry.packageName)
            if (launch != null) {
                runCatching { startActivity(launch) }.onFailure {
                    Toast.makeText(this, "تعذر فتح التطبيق", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(this, "لا يوجد نشاط تشغيل لهذا التطبيق", Toast.LENGTH_SHORT).show()
            }
        }
        root.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                searchText = s?.toString().orEmpty()
                refreshRows()
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
    }

    private fun loadApps() {
        val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved: List<ResolveInfo> = packageManager.queryIntentActivities(query, 0)
        allEntries = resolved.mapNotNull { info ->
            val activity = info.activityInfo ?: return@mapNotNull null
            val app = activity.applicationInfo ?: return@mapNotNull null
            val label = info.loadLabel(packageManager)?.toString()?.trim().orEmpty()
            if (label.isBlank()) return@mapNotNull null
            Entry(
                label = label,
                packageName = activity.packageName,
                category = categoryFor(app),
                icon = runCatching { info.loadIcon(packageManager) }.getOrNull()
            )
        }.distinctBy { it.packageName }
            .sortedBy { it.label.lowercase(Locale.getDefault()) }
        refreshRows()
    }

    private fun categoryFor(info: ApplicationInfo): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return "أخرى"
        return when (info.category) {
            ApplicationInfo.CATEGORY_GAME -> "ألعاب"
            ApplicationInfo.CATEGORY_SOCIAL -> "اجتماعي"
            ApplicationInfo.CATEGORY_PRODUCTIVITY -> "إنتاجية"
            ApplicationInfo.CATEGORY_AUDIO, ApplicationInfo.CATEGORY_VIDEO -> "وسائط"
            ApplicationInfo.CATEGORY_IMAGE -> "صور"
            ApplicationInfo.CATEGORY_MAPS -> "خرائط"
            ApplicationInfo.CATEGORY_NEWS -> "أخبار"
            else -> "أخرى"
        }
    }

    private fun refreshRows() {
        if (!::adapter.isInitialized) return
        val query = searchText.trim().lowercase(Locale.getDefault())
        adapter.submit(allEntries.filter { entry ->
            (selectedCategory == "الكل" || entry.category == selectedCategory) &&
                (query.isBlank() || entry.label.lowercase(Locale.getDefault()).contains(query) ||
                    entry.packageName.lowercase(Locale.getDefault()).contains(query))
        })
    }

    private inner class LibraryAdapter : BaseAdapter() {
        private var entries: List<Entry> = emptyList()
        fun submit(value: List<Entry>) {
            entries = value
            notifyDataSetChanged()
        }
        fun entryAt(position: Int): Entry? = entries.getOrNull(position)
        override fun getCount(): Int = entries.size
        override fun getItem(position: Int): Entry = entries[position]
        override fun getItemId(position: Int): Long = entries[position].packageName.hashCode().toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = convertView as? LinearLayout ?: LinearLayout(this@MiMaxAppLibraryActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), dp(9), dp(8), dp(9))
                addView(ImageView(this@MiMaxAppLibraryActivity).apply {
                    tag = "app_icon"
                }, LinearLayout.LayoutParams(dp(44), dp(44)))
                addView(LinearLayout(this@MiMaxAppLibraryActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(12), 0, 0, 0)
                    addView(TextView(this@MiMaxAppLibraryActivity).apply {
                        tag = "app_label"
                        textSize = 15f
                        setTextColor(Color.WHITE)
                    })
                    addView(TextView(this@MiMaxAppLibraryActivity).apply {
                        tag = "app_package"
                        textSize = 10f
                        setTextColor(0xFF9DA2B0.toInt())
                    })
                }, LinearLayout.LayoutParams(0, -2, 1f))
            }
            val entry = entries[position]
            row.findViewWithTag<ImageView>("app_icon").setImageDrawable(entry.icon)
            row.findViewWithTag<TextView>("app_label").text = entry.label
            row.findViewWithTag<TextView>("app_package").text = entry.packageName
            return row
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
