package com.silvershireinn.app.ui.chat

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder

import com.silvershireinn.app.databinding.ActivityChatSettingsBinding
import com.silvershireinn.app.utils.AccentColorHelper
import com.silvershireinn.app.utils.PreferenceManager
import com.silvershireinn.app.utils.toast
import java.io.File
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/**
 * Per-contact chat customisation screen.
 * Settings are persisted in a contact-specific SharedPreferences file.
 */
class ChatSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityChatSettingsBinding
    private val contactUsername by lazy { intent.getStringExtra(EXTRA_CONTACT) ?: "" }

    // All font families we would like to offer.  The picker filters this list at runtime
    // using FontHelper.isFamilyAvailable() so OEM devices (Samsung, Xiaomi, etc.) that
    // lack Roboto variants only see fonts that will actually change the appearance.
    private val ALL_FONT_FAMILIES = listOf(
        "sans-serif"          to "Default (Sans-Serif)",
        "serif"               to "Serif",
        "monospace"           to "Monospace",
        "cursive"             to "Cursive",
        "sans-serif-condensed" to "Condensed",
        "sans-serif-light"    to "Light",
        "sans-serif-medium"   to "Medium"
    )

    // Filtered to fonts that are actually installed on this device.
    private val FONT_FAMILIES by lazy {
        ALL_FONT_FAMILIES.filter { (family, _) ->
            com.silvershireinn.app.utils.FontHelper.isFamilyAvailable(family)
        }
    }

    // Available bubble colours (hex)
    private val BUBBLE_COLORS = listOf(
        "#2D3250", // default navy
        "#3A4A2F", // forest
        "#4A2F2F", // deep red
        "#2F3A4A", // steel
        "#3A2F4A", // purple
        "#2F4A3A", // teal
        "#1A1A1A", // charcoal
        "#4A3A2F", // bronze
    )

    // Available chat background colours
    private val BG_COLORS = listOf(
        "#1C1A17" to "Default (Dark)",
        "#0D1117" to "Midnight",
        "#0A1628" to "Deep Ocean",
        "#1A0A28" to "Void Purple",
        "#0F1F0A" to "Forest Night",
        "#1F0A0A" to "Ember",
        "#0A1A1A" to "Teal Dark",
        "#1A1410" to "Warm Brown",
        "#2A2A2A" to "Graphite",
        "#000000" to "Pure Black",
    )

    private var selectedBgColor: String? = null

    /**
     * Copy a picked file into the app, streamed on a background thread. These used to read the
     * whole file into memory on the main thread: a background VIDEO froze the screen and could
     * crash a phone with little memory. Written to a temp file first so a failed copy never
     * replaces a working background.
     */
    private fun copyPicked(uri: android.net.Uri, dest: File, onDone: (Boolean) -> Unit) {
        lifecycleScope.launch {
            val ok = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    dest.parentFile?.mkdirs()
                    val tmp = File(dest.parentFile, dest.name + ".part")
                    val input = contentResolver.openInputStream(uri) ?: return@withContext false
                    input.use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
                    if (tmp.length() == 0L) { tmp.delete(); return@withContext false }
                    dest.delete(); tmp.renameTo(dest)
                } catch (_: Exception) { false }
            }
            if (!isFinishing && !isDestroyed) onDone(ok)
        }
    }

    // ── Group chat head image picker ─────────────────────────────────────────
    private val groupChatHeadPicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri ?: return@registerForActivityResult
        copyPicked(uri, File(File(filesDir, "contact_pics"), "${contactUsername}.jpg")) { ok ->
            updateGroupChatHeadPreview()
            toast(if (ok) "Chat head image set" else "Couldn't use that image")
        }
    }

    // ── Chat video background picker ──────────────────────────────────────────
    private val videoPicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri ?: return@registerForActivityResult
        toast("Setting video background...")
        val destFile = File(filesDir, "chat_bg_${contactUsername}.mp4")
        copyPicked(uri, destFile) { ok ->
            if (ok) {
                // Video takes priority — clear image bg
                PreferenceManager.setChatImageBg(contactUsername, null)
                PreferenceManager.setChatVideoBg(contactUsername, destFile.absolutePath)
            }
            toast(if (ok) "Video background set" else "Couldn't use that video")
        }
    }

    // ── Chat image background picker ──────────────────────────────────────────
    private val imagePicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri ?: return@registerForActivityResult
        val destFile = File(filesDir, "chat_img_bg_${contactUsername}.jpg")
        copyPicked(uri, destFile) { ok ->
            if (ok) {
                // Image — clear any video bg first so image shows through
                PreferenceManager.setChatVideoBg(contactUsername, null)
                PreferenceManager.setChatImageBg(contactUsername, destFile.absolutePath)
            }
            toast(if (ok) "Image background set" else "Couldn't use that image")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChatSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        val groupNameExtra = intent.getStringExtra(EXTRA_GROUP_NAME) ?: ""
        // "Chat settings" with whose chat underneath (the old title used an em dash + username).
        supportActionBar?.title = "Chat settings"
        supportActionBar?.subtitle = when {
            groupNameExtra.isNotBlank() -> groupNameExtra
            contactUsername.isNotBlank() -> PreferenceManager.displayNameFor(contactUsername)
            else -> null
        }
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val prefs = contactPrefs()

        // ── Nickname ──────────────────────────────────────────────────────────
        // The nickname is the same one the profile sheet edits (this used to show an old copy).
        binding.etNickname.setText(getNickname(this, contactUsername))

        // ── Mute toggle ───────────────────────────────────────────────────────
        binding.switchMute.isChecked = prefs.getBoolean(KEY_MUTED, false)

        // ── Bubble colour swatches ─────────────────────────────────────────────
        // No ring when no custom color is set (the first swatch looked chosen before).
        buildColorSwatches(PreferenceManager.getContactBubbleColor(contactUsername) ?: "")

        // ── Per-chat font ─────────────────────────────────────────────────────
        updateChatFontLabel()
        binding.btnPickChatFont.setOnClickListener { showChatFontPicker() }

        // ── Per-chat text color ───────────────────────────────────────────────
        updateChatTextColorSwatch()
        binding.btnPickChatTextColor.setOnClickListener {
            val current = try {
                Color.parseColor(PreferenceManager.getChatTextColor(contactUsername) ?: "#F0F0F5")
            } catch (_: Exception) { 0xFFF0F0F5.toInt() }
            com.silvershireinn.app.ui.common.HsvColorPickerDialog(this, current) { color ->
                val hex = String.format("#%06X", color and 0xFFFFFF)
                PreferenceManager.setChatTextColor(contactUsername, hex)
                updateChatTextColorSwatch()
                // Color applied to message bubbles when user returns to ChatActivity.onResume
                setResult(RESULT_OK)
            }.show()
        }
        binding.btnResetChatTextColor.setOnClickListener {
            PreferenceManager.setChatTextColor(contactUsername, null)
            updateChatTextColorSwatch()
            setResult(RESULT_OK)
            toast("Chat text color reset")
        }

        // ── Chat background colour ─────────────────────────────────────────────
        selectedBgColor = prefs.getString(KEY_CHAT_BG_COLOR, null)
        updateBgColorSwatch()

        binding.btnPickChatBgColor.setOnClickListener { showBgColorPicker() }

        binding.btnResetChatBg.setOnClickListener {
            selectedBgColor = null
            prefs.edit().remove(KEY_CHAT_BG_COLOR).apply()
            updateBgColorSwatch()
            toast("Background reset to default")
        }

        // ── Save ──────────────────────────────────────────────────────────────
        binding.btnSaveChatSettings.setOnClickListener {
            val nickname = binding.etNickname.text?.toString()?.trim() ?: ""
            val muted    = binding.switchMute.isChecked

            prefs.edit()
                .putString(KEY_NICKNAME, nickname)
                .putBoolean(KEY_MUTED,   muted)
                .also {
                    if (selectedBgColor != null) it.putString(KEY_CHAT_BG_COLOR, selectedBgColor)
                    else it.remove(KEY_CHAT_BG_COLOR)
                }
                .apply()

            // Also sync nickname to PreferenceManager so adapters pick it up globally
            if (nickname.isBlank()) {
                PreferenceManager.clearNickname(contactUsername)
            } else {
                PreferenceManager.setNickname(contactUsername, nickname)
            }
            PreferenceManager.notifyNamesChanged(contactUsername)

            toast("Settings saved")
            setResult(RESULT_OK)
            finish()
        }

        // ── Clear / Save nickname (side by side, no scroll required) ──────────
        binding.btnClearNickname.setOnClickListener {
            binding.etNickname.setText("")
            prefs.edit().remove(KEY_NICKNAME).apply()
            PreferenceManager.clearNickname(contactUsername)
            PreferenceManager.notifyNamesChanged(contactUsername)
            toast("Nickname removed")
        }
        binding.btnSaveNickname.setOnClickListener {
            val nickname = binding.etNickname.text?.toString()?.trim() ?: ""
            if (nickname.isBlank()) {
                prefs.edit().remove(KEY_NICKNAME).apply()
                PreferenceManager.clearNickname(contactUsername)
                toast("Nickname removed")
            } else {
                prefs.edit().putString(KEY_NICKNAME, nickname).apply()
                PreferenceManager.setNickname(contactUsername, nickname)
                toast("Nickname saved")
            }
            PreferenceManager.notifyNamesChanged(contactUsername)
            setResult(RESULT_OK)
        }

        // ── Group chat head image (group chats only) ─────────────────────────
        val isGroup = intent.getStringExtra(EXTRA_GROUP_NAME) != null
        if (isGroup) {
            binding.cardGroupChatHead.visibility = android.view.View.VISIBLE
            updateGroupChatHeadPreview()
            binding.btnSetGroupChatHeadImage.setOnClickListener {
                groupChatHeadPicker.launch("image/*")
            }
            binding.btnClearGroupChatHeadImage.setOnClickListener {
                File(filesDir, "contact_pics/${contactUsername}.jpg").takeIf { it.exists() }?.delete()
                updateGroupChatHeadPreview()
                toast("Chat head image cleared")
            }
        }

        // ── Chat video background (live wallpaper) ────────────────────────────
        binding.btnSetChatVideoBg.setOnClickListener {
            videoPicker.launch("video/*")
        }

        binding.btnClearChatVideoBg.setOnClickListener {
            PreferenceManager.setChatVideoBg(contactUsername, null)
            File(filesDir, "chat_bg_${contactUsername}.mp4").takeIf { it.exists() }?.delete()
            toast("Video background cleared")
        }

        // ── Chat image background ─────────────────────────────────────────────
        binding.btnSetChatImageBg.setOnClickListener {
            imagePicker.launch("image/*")
        }

        binding.btnClearChatImageBg.setOnClickListener {
            PreferenceManager.setChatImageBg(contactUsername, null)
            File(filesDir, "chat_img_bg_${contactUsername}.jpg").takeIf { it.exists() }?.delete()
            toast("Image background cleared")
        }
    }

    // ── Chat font picker ──────────────────────────────────────────────────────

    private fun updateChatFontLabel() {
        val saved = contactPrefs().getString(KEY_FONT_FAMILY, null)
        binding.tvChatFontLabel.text = if (saved == null) "Default (App Font)"
        else FONT_FAMILIES.firstOrNull { it.first == saved }?.second ?: saved
    }

    private fun showChatFontPicker() {
        val prefs   = contactPrefs()
        val current = prefs.getString(KEY_FONT_FAMILY, null)
        // Prepend a "Default (App Font)" entry so users can clear the override
        val entries = listOf(null to "Default (App Font)") + FONT_FAMILIES
        val names   = entries.map { it.second }.toTypedArray()
        val checked = entries.indexOfFirst { it.first == current }.coerceAtLeast(0)

        MaterialAlertDialogBuilder(this)
            .setTitle("Chat font")
            .setSingleChoiceItems(names, checked) { dialog, which ->
                val chosen = entries[which].first
                if (chosen == null) {
                    prefs.edit().remove(KEY_FONT_FAMILY).apply()
                } else {
                    prefs.edit().putString(KEY_FONT_FAMILY, chosen).apply()
                }
                updateChatFontLabel()
                dialog.dismiss()
                toast("Chat font updated")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ── Chat text color swatch ────────────────────────────────────────────────

    private fun updateChatTextColorSwatch() {
        val hex = PreferenceManager.getChatTextColor(contactUsername)
        if (hex != null) {
            try {
                binding.viewChatTextColor.setBackgroundColor(Color.parseColor(hex))
                binding.viewChatTextColor.visibility = View.VISIBLE
            } catch (_: Exception) {}
        } else {
            binding.viewChatTextColor.visibility = View.INVISIBLE
        }
    }

    // ── Background colour picker ──────────────────────────────────────────────

    private fun updateBgColorSwatch() {
        val hex = selectedBgColor
        if (hex != null) {
            try {
                binding.viewChatBgColor.setBackgroundColor(Color.parseColor(hex))
                binding.viewChatBgColor.visibility = View.VISIBLE
            } catch (_: Exception) {}
        } else {
            binding.viewChatBgColor.visibility = View.INVISIBLE
        }
    }

    private fun showBgColorPicker() {
        val current = try {
            Color.parseColor(selectedBgColor ?: "#1C1A17")
        } catch (_: Exception) { 0xFF1C1A17.toInt() }
        com.silvershireinn.app.ui.common.HsvColorPickerDialog(this, current) { color ->
            val hex = String.format("#%06X", color and 0xFFFFFF)
            selectedBgColor = hex
            contactPrefs().edit().putString(KEY_CHAT_BG_COLOR, hex).apply()
            updateBgColorSwatch()
        }.show()
    }

    // ── Colour swatches ───────────────────────────────────────────────────────

    private fun buildColorSwatches(selected: String) {
        val container = binding.containerBubbleColors
        container.removeAllViews()
        val dp = resources.displayMetrics.density
        val size    = (36 * dp).toInt()
        val margin  = (6 * dp).toInt()
        val prefs   = contactPrefs()

        for (hex in BUBBLE_COLORS) {
            val card = com.google.android.material.card.MaterialCardView(this).apply {
                radius = size / 2f
                setCardBackgroundColor(Color.parseColor(hex))
                val lp = LinearLayout.LayoutParams(size, size)
                lp.marginEnd = margin
                layoutParams = lp
                isClickable   = true
                isFocusable   = true
                cardElevation = 2 * dp
            }

            // Selected ring
            if (hex == selected) {
                card.strokeWidth = (3 * dp).toInt()
                card.strokeColor = 0xFFC9A84C.toInt()
            }

            card.setOnClickListener {
                prefs.edit().putString(KEY_BUBBLE_COLOR, hex).apply()
                PreferenceManager.setContactBubbleColor(contactUsername, hex)
                buildColorSwatches(hex)
            }

            container.addView(card)
        }

        // "+" custom color card → opens HSV picker
        val customCard = com.google.android.material.card.MaterialCardView(this).apply {
            radius = size / 2f
            val lp = LinearLayout.LayoutParams(size, size)
            lp.marginEnd = margin
            layoutParams = lp
            isClickable = true; isFocusable = true
            cardElevation = 2 * dp
        }
        val plusLabel = android.widget.TextView(this).apply {
            text = "＋"
            textSize = 16f
            gravity = android.view.Gravity.CENTER
            setTextColor(0xFFC9A84C.toInt())
        }
        customCard.addView(plusLabel, android.view.ViewGroup.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.MATCH_PARENT))
        customCard.setOnClickListener {
            val current = try { Color.parseColor(prefs.getString(KEY_BUBBLE_COLOR, selected) ?: selected) }
                          catch (_: Exception) { 0xFF2D3250.toInt() }
            com.silvershireinn.app.ui.common.HsvColorPickerDialog(this, current) { color ->
                val hex = String.format("#%06X", color and 0xFFFFFF)
                prefs.edit().putString(KEY_BUBBLE_COLOR, hex).apply()
                PreferenceManager.setContactBubbleColor(contactUsername, hex)
                buildColorSwatches(hex)
            }.show()
        }
        container.addView(customCard)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun updateGroupChatHeadPreview() {
        val file = File(filesDir, "contact_pics/${contactUsername}.jpg")
        if (file.exists()) {
            binding.ivGroupChatHeadPreview.setImageBitmap(com.silvershireinn.app.utils.VideoThumbs.decodeSmall(file.absolutePath))
        } else {
            binding.ivGroupChatHeadPreview.setImageResource(android.R.drawable.ic_menu_gallery)
        }
    }

    private fun contactPrefs() =
        getSharedPreferences("chat_prefs_$contactUsername", Context.MODE_PRIVATE)

    override fun onResume() {
        super.onResume()
        AccentColorHelper.apply(binding.root, PreferenceManager.accentColorInt())
        com.silvershireinn.app.utils.FontHelper.apply(binding.root, PreferenceManager.fontFamily)
        // Apply global UI text color only — per-chat text color affects message bubbles, not this screen
        AccentColorHelper.applyTextColor(binding.root, PreferenceManager.uiTextColorInt())
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    companion object {
        const val EXTRA_CONTACT      = "contact_username"
        const val EXTRA_GROUP_NAME   = "group_name"
        const val KEY_NICKNAME       = "nickname"
        const val KEY_MUTED          = "muted"
        const val KEY_BUBBLE_COLOR   = "bubble_color"
        const val KEY_CHAT_BG_COLOR  = "chat_bg_color"
        const val KEY_FONT_FAMILY    = "font_family"

        fun start(context: Context, contactUsername: String, groupName: String = "") {
            context.startActivity(Intent(context, ChatSettingsActivity::class.java).apply {
                putExtra(EXTRA_CONTACT, contactUsername)
                if (groupName.isNotBlank()) putExtra(EXTRA_GROUP_NAME, groupName)
            })
        }

        /** Read bubble color for a contact (called from MessageAdapter) */
        fun getBubbleColor(context: Context, contactUsername: String): String? =
            context.getSharedPreferences("chat_prefs_$contactUsername", Context.MODE_PRIVATE)
                .getString(KEY_BUBBLE_COLOR, null)

        /** Read muted flag for a contact */
        fun isMuted(context: Context, contactUsername: String): Boolean =
            context.getSharedPreferences("chat_prefs_$contactUsername", Context.MODE_PRIVATE)
                .getBoolean(KEY_MUTED, false)

        /** Raw local nickname only (blank if none). Prefer PreferenceManager.displayNameFor for UI. */
        fun getNickname(context: Context, contactUsername: String): String {
            val fromPm = PreferenceManager.getNickname(contactUsername)
            if (fromPm.isNotBlank()) return fromPm
            val legacy = context.getSharedPreferences("chat_prefs_$contactUsername", Context.MODE_PRIVATE)
                .getString(KEY_NICKNAME, "") ?: ""
            if (legacy.isNotBlank()) {
                PreferenceManager.setNickname(contactUsername, legacy)
                return legacy
            }
            return ""
        }

        fun displayNameFor(context: Context, contactUsername: String): String =
            PreferenceManager.displayNameFor(contactUsername)

        /** Read custom chat background color for a contact, or null for default. */
        fun getChatBgColor(context: Context, contactUsername: String): String? =
            context.getSharedPreferences("chat_prefs_$contactUsername", Context.MODE_PRIVATE)
                .getString(KEY_CHAT_BG_COLOR, null)

        /** Persist custom chat background color for a contact. */
        fun setChatBgColor(context: Context, contactUsername: String, color: String?) {
            val prefs = context.getSharedPreferences("chat_prefs_$contactUsername", Context.MODE_PRIVATE)
            if (color == null) prefs.edit().remove(KEY_CHAT_BG_COLOR).apply()
            else               prefs.edit().putString(KEY_CHAT_BG_COLOR, color).apply()
        }

        /**
         * Read the per-chat font override for [contactUsername], or null to use the global
         * app font (PreferenceManager.fontFamily). Called from MessageAdapter.
         */
        fun getFontFamily(context: Context, contactUsername: String): String? =
            context.getSharedPreferences("chat_prefs_$contactUsername", Context.MODE_PRIVATE)
                .getString(KEY_FONT_FAMILY, null)
    }
}
