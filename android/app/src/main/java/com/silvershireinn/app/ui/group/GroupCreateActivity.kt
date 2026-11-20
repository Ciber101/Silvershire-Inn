package com.silvershireinn.app.ui.group

import android.os.Bundle
import android.widget.CheckBox
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import android.view.ViewGroup
import androidx.lifecycle.lifecycleScope
import com.silvershireinn.app.SilvershireApp
import com.silvershireinn.app.databinding.ActivityGroupCreateBinding
import com.silvershireinn.app.network.WebSocketManager
import com.silvershireinn.app.repository.ContactRepository
import com.silvershireinn.app.utils.AccentColorHelper
import com.silvershireinn.app.utils.PreferenceManager
import com.silvershireinn.app.utils.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

class GroupCreateActivity : AppCompatActivity() {

    private lateinit var binding: ActivityGroupCreateBinding
    private val contactRepo by lazy { ContactRepository(SilvershireApp.database) }
    private val memberCheckboxes = mutableMapOf<String, CheckBox>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Let content draw behind status/nav bars, then pad the toolbar down
        WindowCompat.setDecorFitsSystemWindows(window, false)

        binding = ActivityGroupCreateBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Push toolbar down by status bar height, button up by nav bar height
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.toolbar.updatePadding(top = bars.top)
            binding.btnCreateGroup.updateLayoutParams<android.view.ViewGroup.MarginLayoutParams> {
                val density = resources.displayMetrics.density
                bottomMargin = bars.bottom + (16 * density).toInt()
            }
            insets
        }

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "New group"
        // Group names are kept to a sensible length.
        binding.etGroupName.filters = arrayOf(android.text.InputFilter.LengthFilter(50))
        binding.toolbar.setNavigationOnClickListener { finish() }

        // Apply user's accent color
        AccentColorHelper.apply(binding.root, PreferenceManager.accentColorInt())

        loadContacts()
        binding.btnCreateGroup.setOnClickListener { createGroup() }
    }

    private fun loadContacts() {
        lifecycleScope.launch {
            val contacts = withContext(Dispatchers.IO) {
                contactRepo.getAllConfirmedContacts()
            }.sortedBy { PreferenceManager.displayNameFor(it.username).lowercase() }
            withContext(Dispatchers.Main) {
                binding.layoutMembers.removeAllViews()
                memberCheckboxes.clear()
                if (contacts.isEmpty()) {
                    binding.layoutMembers.addView(android.widget.TextView(this@GroupCreateActivity).apply {
                        text = "Add some friends first, then you can make a group with them."
                        setTextColor(0xFFAAAAAA.toInt()); textSize = 14f; setPadding(0, 24, 0, 24)
                    })
                }
                for (contact in contacts) {
                    // Names you know them by (nickname / display name), username underneath.
                    val dn = PreferenceManager.displayNameFor(contact.username)
                    val cb = CheckBox(this@GroupCreateActivity).apply {
                        text = if (dn != contact.username) "$dn  (@${contact.username})" else dn
                        setTextColor(0xFFE0D6C8.toInt())   // color_text_primary-ish
                        textSize = 15f
                        setPadding(0, 16, 0, 16)
                    }
                    binding.layoutMembers.addView(cb)
                    memberCheckboxes[contact.username] = cb
                }
            }
        }
    }

    private fun createGroup() {
        val name = binding.etGroupName.text?.toString()?.trim() ?: ""
        if (name.isBlank()) { toast("Enter a group name"); return }
        val members = memberCheckboxes.filter { it.value.isChecked }.keys.toList()
        if (members.isEmpty()) { toast("Select at least one member"); return }

        if (!WebSocketManager.createGroup(name, members)) {
            toast("Not connected. Please try again")
            return
        }
        // Wait for the server to confirm, then open the new group straight away
        // (it used to just close and leave you to find it in the list).
        binding.btnCreateGroup.isEnabled = false
        binding.btnCreateGroup.text = "Creating..."
        val me = PreferenceManager.username
        lifecycleScope.launch {
            val created = kotlinx.coroutines.withTimeoutOrNull(8_000) {
                WebSocketManager.events.first { j ->
                    j.optString("type") == "group_created" && j.optString("name") == name &&
                        (j.optString("createdBy").isBlank() || j.optString("createdBy") == me)
                }
            }
            if (isFinishing || isDestroyed) return@launch
            if (created != null) {
                kotlinx.coroutines.delay(300)   // let the service store it first
                startActivity(android.content.Intent(this@GroupCreateActivity, com.silvershireinn.app.ui.chat.ChatActivity::class.java).apply {
                    putExtra("contact_username", created.optString("groupId"))
                    putExtra("group_id", created.optString("groupId"))
                    putExtra("group_name", name)
                })
            } else {
                toast("Group requested. It will appear in your chats")
            }
            finish()
        }
    }
}
