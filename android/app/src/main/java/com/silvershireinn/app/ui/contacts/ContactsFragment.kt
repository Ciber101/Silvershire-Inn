package com.silvershireinn.app.ui.contacts

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.text.InputType
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayout
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.silvershireinn.app.databinding.FragmentContactsBinding
import com.silvershireinn.app.model.Contact
import com.silvershireinn.app.ui.chat.ChatActivity
import com.silvershireinn.app.utils.AccentColorHelper
import com.silvershireinn.app.utils.PreferenceManager
import com.silvershireinn.app.utils.toast
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collectLatest
import java.io.File

class ContactsFragment : Fragment() {

    private var _binding: FragmentContactsBinding? = null
    private val binding get() = _binding!!
    private val viewModel: ContactsViewModel by viewModels()

    private lateinit var contactAdapter: ContactAdapter
    private lateinit var inviteAdapter: InviteAdapter

    private var activeSearchDialog: AlertDialog? = null
    private var searchTil: TextInputLayout? = null
    private var searchProgress: ProgressBar? = null

    // Track which tab is currently visible so collectors don't fight with tab switching
    private var currentTab = 0

    // Refresh avatar column whenever a contact's profile pic is downloaded
    private val profileUpdatedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (::contactAdapter.isInitialized) contactAdapter.notifyItemRangeChanged(0, contactAdapter.itemCount)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentContactsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // ── Adapters ──────────────────────────────────────────────────────────
        contactAdapter = ContactAdapter(
            onClick      = { c -> showContactProfile(c.username) },
            onLongPress  = { c -> showContactContextMenu(c) }
        )
        inviteAdapter = InviteAdapter(
            onAccept  = { c -> viewModel.acceptRequest(requireContext(), c.username) },
            onDecline = { c -> viewModel.declineRequest(c.username) }
        )

        binding.rvContacts.layoutManager = LinearLayoutManager(requireContext())
        binding.rvContacts.adapter = contactAdapter

        binding.rvInvites.layoutManager = LinearLayoutManager(requireContext())
        binding.rvInvites.adapter = inviteAdapter

        // ── Tabs ──────────────────────────────────────────────────────────────
        binding.tabContacts.addTab(binding.tabContacts.newTab().setText("Contacts"))
        binding.tabContacts.addTab(binding.tabContacts.newTab().setText("Invites"))

        binding.tabContacts.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                currentTab = tab.position
                when (tab.position) {
                    0    -> showContactsTab()
                    else -> showInvitesTab()
                }
            }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })

        showContactsTab()   // start on contacts

        // Apply accent now that tabs exist so TabLayout tint fires correctly.
        AccentColorHelper.apply(binding.root, PreferenceManager.accentColorInt())
        com.silvershireinn.app.utils.FontHelper.apply(binding.root, PreferenceManager.fontFamily)

        // ── FAB ───────────────────────────────────────────────────────────────
        binding.fabAddContact.setOnClickListener { showSearchDialog() }

        // ── Data collection ───────────────────────────────────────────────────
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.contacts.collect { list ->
                contactAdapter.submitList(list)
                // Only touch contacts visibility when on contacts tab
                if (currentTab == 0) {
                    val empty = list.isEmpty()
                    binding.tvEmptyContacts.visibility = if (empty) View.VISIBLE else View.GONE
                }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.friendRequests.collect { list ->
                inviteAdapter.submitList(list)
                // Update badge on Invites tab
                val tab = binding.tabContacts.getTabAt(1)
                tab?.text = if (list.isEmpty()) "Invites" else "Invites (${list.size})"
                val bannerTv = binding.root.findViewById<android.widget.TextView>(com.silvershireinn.app.R.id.tvFriendRequestBanner)
                if (bannerTv != null) {
                    bannerTv.visibility = if (list.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
                    bannerTv.text = if (list.size == 1) "1 friend request. Tap to approve or decline"
                                    else "${list.size} friend requests. Tap to approve or decline"
                    bannerTv.setOnClickListener { binding.tabContacts.getTabAt(1)?.select() }
                }
                // Only touch invites visibility when on invites tab
                if (currentTab == 1) {
                    val empty = list.isEmpty()
                    binding.tvEmptyInvites.visibility = if (empty) View.VISIBLE else View.GONE
                }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.searchState.collect { handleSearchState(it) }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            com.silvershireinn.app.network.OnlineStatusManager.statusFlow.collectLatest {
                kotlinx.coroutines.delay(250)   // batch bursts of presence updates
                contactAdapter.notifyItemRangeChanged(0, contactAdapter.itemCount)
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.exportResult.collect { result ->
                result.onSuccess { (fileName, bytes) ->
                    saveExportFile(fileName, bytes)
                }.onFailure {
                    context?.toast("Export failed: ${it.message}")
                }
            }
        }
    }

    // ── Tab visibility helpers ────────────────────────────────────────────────

    private fun showContactsTab() {
        binding.rvContacts.visibility      = View.VISIBLE
        binding.rvInvites.visibility       = View.GONE
        binding.tvEmptyInvites.visibility  = View.GONE
        // Apply empty state immediately based on current adapter data
        val empty = contactAdapter.itemCount == 0
        binding.tvEmptyContacts.visibility = if (empty) View.VISIBLE else View.GONE
    }

    private fun showInvitesTab() {
        binding.rvContacts.visibility      = View.GONE
        binding.tvEmptyContacts.visibility = View.GONE
        binding.rvInvites.visibility       = View.VISIBLE
        // Apply empty state immediately based on current adapter data
        val empty = inviteAdapter.itemCount == 0
        binding.tvEmptyInvites.visibility  = if (empty) View.VISIBLE else View.GONE
    }

    // ── Open chat ─────────────────────────────────────────────────────────────

    private fun openChat(contact: Contact) {
        startActivity(
            Intent(requireContext(), ChatActivity::class.java)
                .putExtra("contact_username", contact.username)
        )
    }

    // ── Long-press context menu ───────────────────────────────────────────────

    private fun showContactContextMenu(contact: Contact) {
        val options = arrayOf(
            "👤  View profile",
            "💬  Message",
            "📄  Export chat",
            "🗑️  Remove contact",
            "🚫  Block"
        )
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(PreferenceManager.displayNameFor(contact.username))
            .setItems(options.map { com.silvershireinn.app.utils.ThemeIcons.iconize(requireContext(), it) }.toTypedArray()) { _, which ->
                when (which) {
                    0 -> showContactProfile(contact.username)
                    1 -> openChat(contact)
                    2 -> viewModel.exportChatHistory(contact.username)
                    3 -> confirmDelete(contact)
                    4 -> confirmBlock(contact)
                }
            }
            .show()
    }

    private fun showContactProfile(username: String) {
        // The sheet removes / blocks by itself (works from any screen).
        ContactProfileBottomSheet.newInstance(username).show(parentFragmentManager, "contact_profile")
    }

    private fun confirmDelete(contact: Contact) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Remove contact?")
            .setMessage("Remove ${PreferenceManager.displayNameFor(contact.username)} from your contacts? You can add each other again later.")
            .setPositiveButton("Remove") { _, _ ->
                viewModel.deleteContact(contact.username)
                context?.toast("${PreferenceManager.displayNameFor(contact.username)} removed")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmBlock(contact: Contact) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Block?")
            .setMessage("Block ${PreferenceManager.displayNameFor(contact.username)}? They will be removed from your contacts and can't message you or send you friend requests. You can unblock them in Settings.")
            .setPositiveButton("Block") { _, _ ->
                val name = PreferenceManager.displayNameFor(contact.username)
                viewModel.blockContact(contact.username)
                context?.let { c -> com.silvershireinn.app.ui.chathead.ChatHeadService.dismiss(c, contact.username) }
                context?.toast("$name blocked")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ── Export save ───────────────────────────────────────────────────────────

    private fun saveExportFile(fileName: String, bytes: ByteArray) {
        val ctx = context ?: return
        // Into Downloads where you can find it (it went into a hidden app folder before).
        viewLifecycleOwner.lifecycleScope.launch {
            val ok = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                com.silvershireinn.app.repository.FileRepository.saveToDownloads(ctx, fileName, "text/plain", bytes)
            }
            ctx.toast(if (ok) "Chat saved to Downloads" else "Export failed")
        }
    }

    // ── Search dialog ─────────────────────────────────────────────────────────

    private fun showSearchDialog() {
        val ctx = requireContext()

        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(64, 24, 64, 8)
        }

        val til = TextInputLayout(ctx, null,
            com.google.android.material.R.attr.textInputOutlinedStyle).apply {
            hint = "Username to search"
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        val et = TextInputEditText(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            maxLines = 1
        }
        til.addView(et)
        container.addView(til)

        val progress = ProgressBar(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = 16 }
            visibility = View.GONE
        }
        container.addView(progress)

        searchTil      = til
        searchProgress = progress

        val dialog = MaterialAlertDialogBuilder(ctx)
            .setTitle("Find a friend")
            .setView(container)
            .setPositiveButton("Search", null)
            .setNegativeButton("Cancel") { _, _ -> viewModel.resetSearch() }
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val query = et.text?.toString()?.trim() ?: ""
                if (query.isBlank()) {
                    til.error = "Please enter a username"
                    return@setOnClickListener
                }
                til.error = null
                progress.visibility = View.VISIBLE
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
                et.isEnabled = false
                viewModel.searchUser(query)
            }
        }
        dialog.setOnDismissListener {
            if (activeSearchDialog == dialog) activeSearchDialog = null
        }

        activeSearchDialog = dialog
        dialog.show()
    }

    private fun handleSearchState(state: SearchState) {
        val ctx = context ?: return
        when (state) {
            is SearchState.Idle -> {
                searchProgress?.visibility = View.GONE
                activeSearchDialog?.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = true
            }
            is SearchState.Searching -> { /* spinner already shown */ }
            is SearchState.NoAnswer -> {
                searchProgress?.visibility = View.GONE
                activeSearchDialog?.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = true
                (searchTil?.editText as? TextInputEditText)?.isEnabled = true
                searchTil?.error = "Couldn't search right now. Check your connection and try again"
            }
            is SearchState.Found -> {
                activeSearchDialog?.dismiss()
                activeSearchDialog = null
                // Yourself / already friends / already asked: say so instead of re-sending.
                val me = PreferenceManager.username
                if (state.username.equals(me, ignoreCase = true)) {
                    viewModel.resetSearch(); ctx.toast("That's you"); return
                }
                viewLifecycleOwner.lifecycleScope.launch {
                    val existing = com.silvershireinn.app.SilvershireApp.database.contactDao().getContact(state.username)
                    if (!isAdded) return@launch
                    when {
                        existing != null && !existing.isPending && !existing.isRequest -> {
                            viewModel.resetSearch()
                            MaterialAlertDialogBuilder(ctx)
                                .setTitle("Already friends")
                                .setMessage("${PreferenceManager.displayNameFor(state.username)} is already in your contacts.")
                                .setPositiveButton("Message") { _, _ -> openChat(existing) }
                                .setNegativeButton("Close", null)
                                .show()
                        }
                        existing != null && existing.isRequest -> {
                            viewModel.resetSearch()
                            ctx.toast("They already sent you a request. Check Invites")
                            binding.tabContacts.getTabAt(1)?.select()
                        }
                        else -> showSendRequest(ctx, state.username)
                    }
                }
            }
            is SearchState.NotFound -> {
                searchProgress?.visibility = View.GONE
                activeSearchDialog?.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = true
                (searchTil?.editText as? TextInputEditText)?.isEnabled = true
                searchTil?.error = "No user found with that username"
            }
        }
    }

    private fun showSendRequest(ctx: Context, username: String) {
        MaterialAlertDialogBuilder(ctx)
            .setTitle("Send a friend request?")
            .setMessage("Found \"$username\". They'll be able to chat with you once they accept.")
            .setPositiveButton("Send request") { _, _ ->
                viewModel.sendFriendRequest(username)
                viewModel.resetSearch()
                ctx.toast("Friend request sent to $username")
            }
            .setNegativeButton("Cancel") { _, _ -> viewModel.resetSearch() }
            .show()
    }

    override fun onStart() {
        super.onStart()
        AccentColorHelper.apply(binding.root, PreferenceManager.accentColorInt())
        com.silvershireinn.app.utils.FontHelper.apply(binding.root, PreferenceManager.fontFamily)
        // Register for profile pic update broadcasts
        val filter = IntentFilter("com.silvershireinn.PROFILE_UPDATED")
        filter.addAction(PreferenceManager.ACTION_NAMES_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requireContext().registerReceiver(profileUpdatedReceiver, filter,
                Context.RECEIVER_NOT_EXPORTED)
        } else {
            requireContext().registerReceiver(profileUpdatedReceiver, filter)
        }
    }

    override fun onStop() {
        super.onStop()
        try { requireContext().unregisterReceiver(profileUpdatedReceiver) } catch (_: Exception) {}
    }

    override fun onDestroyView() {
        activeSearchDialog?.dismiss()
        activeSearchDialog = null
        searchTil      = null
        searchProgress = null
        super.onDestroyView()
        _binding = null
    }
}
