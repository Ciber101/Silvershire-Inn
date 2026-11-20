package com.silvershireinn.app.ui.chat

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.tabs.TabLayout
import com.silvershireinn.app.R
import com.silvershireinn.app.util.HapticUtil

/**
 * A bottom-sheet emoji picker that stays open so the user can insert
 * multiple emojis before manually closing it via the ✕ button.
 *
 * Usage:
 *   EmojiPickerBottomSheet { emoji -> etMessage.insert(emoji) }
 *       .show(supportFragmentManager, "emoji_picker")
 */
class EmojiPickerBottomSheet() : BottomSheetDialogFragment() {

    /**
     * Set by the chat. A constructor argument made Android crash when it re-created the sheet
     * (dark-mode switch, low memory): fragments must have an empty constructor.
     */
    var onEmojiPicked: ((String) -> Unit)? = null

    constructor(onEmojiPicked: (String) -> Unit) : this() { this.onEmojiPicked = onEmojiPicked }

    private var selectedCategory = 0

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.bottom_sheet_emoji_picker, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val pick = onEmojiPicked
        if (pick == null) { dismissAllowingStateLoss(); return }   // re-created without a chat to type into

        val tabs     = view.findViewById<TabLayout>(R.id.tabsEmojiCategories)
        val grid     = view.findViewById<RecyclerView>(R.id.rvEmojiGrid)
        val btnClose = view.findViewById<ImageButton>(R.id.btnCloseEmojiPicker)

        // ✕ button lets the user explicitly close the sheet
        btnClose.setOnClickListener { dismiss() }

        // Grid: 8 columns
        grid.layoutManager = GridLayoutManager(requireContext(), 8)

        val ctx = requireContext().applicationContext
        val onClick: (String) -> Unit = { e -> rememberRecent(ctx, e); pick(e) }
        // Recently used first (when there are any), then the categories.
        val recents = loadRecents(ctx)
        val cats = (if (recents.isNotEmpty()) listOf(EmojiData.Category("Recent", "\uD83D\uDD58", recents)) else emptyList()) +
            EmojiData.CATEGORIES

        fun showCategory(index: Int) {
            selectedCategory = index
            // No dismiss() here — user picks as many emojis as they like
            grid.adapter = EmojiGridAdapter(drawable(cats[index].emojis), onClick)
        }

        cats.forEach { cat -> tabs.addTab(tabs.newTab().setText(cat.icon).setContentDescription(cat.label)) }

        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) { showCategory(tab.position) }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })

        showCategory(0)
    }

    private companion object {
        const val PREF = "emoji_prefs"; const val KEY = "recent"; const val MAX_RECENT = 32
        private val drawableCache = HashMap<String, Boolean>()
        private val probe = android.graphics.Paint()

        fun loadRecents(c: android.content.Context): List<String> =
            c.getSharedPreferences(PREF, android.content.Context.MODE_PRIVATE).getString(KEY, "")
                .orEmpty().split('\u0001').filter { it.isNotBlank() }

        fun rememberRecent(c: android.content.Context, e: String) {
            val list = (listOf(e) + loadRecents(c).filter { it != e }).take(MAX_RECENT)
            c.getSharedPreferences(PREF, android.content.Context.MODE_PRIVATE).edit()
                .putString(KEY, list.joinToString("\u0001")).apply()
        }

        /** Hide emoji this phone can't draw (older Android shows them as empty boxes). */
        fun drawable(list: List<String>): List<String> {
            if (android.os.Build.VERSION.SDK_INT < 23) return list
            return list.filter { e -> drawableCache.getOrPut(e) { try { probe.hasGlyph(e) } catch (_: Throwable) { true } } }
        }
    }

    // ── Emoji grid adapter ────────────────────────────────────────────────────

    private class EmojiGridAdapter(
        private val emojis: List<String>,
        private val onClick: (String) -> Unit
    ) : RecyclerView.Adapter<EmojiGridAdapter.VH>() {

        inner class VH(val tv: TextView) : RecyclerView.ViewHolder(tv)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val tv = TextView(parent.context).apply {
                textSize     = 26f
                gravity      = android.view.Gravity.CENTER
                val dp44     = (44 * parent.context.resources.displayMetrics.density).toInt()
                layoutParams = RecyclerView.LayoutParams(dp44, dp44)
                setOnClickListener { v ->
                    HapticUtil.vibrate(v.context)
                    onClick(text.toString())
                }
            }
            return VH(tv)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            holder.tv.text = emojis[position]
        }

        override fun getItemCount() = emojis.size
    }
}
