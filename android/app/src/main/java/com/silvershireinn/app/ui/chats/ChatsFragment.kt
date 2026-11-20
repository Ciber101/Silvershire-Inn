package com.silvershireinn.app.ui.chats

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.silvershireinn.app.R
import com.silvershireinn.app.databinding.FragmentChatsBinding
import com.silvershireinn.app.model.Conversation
import com.silvershireinn.app.network.OnlineStatusManager
import com.silvershireinn.app.ui.chat.ChatActivity
import com.silvershireinn.app.ui.group.GroupCreateActivity
import com.silvershireinn.app.utils.AccentColorHelper
import com.silvershireinn.app.utils.PreferenceManager
import com.silvershireinn.app.utils.hide
import com.silvershireinn.app.utils.show
import com.silvershireinn.app.utils.toast
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collectLatest
import androidx.lifecycle.repeatOnLifecycle

class ChatsFragment : Fragment() {

    private var _binding: FragmentChatsBinding? = null
    private val binding get() = _binding!!
    private val viewModel: ChatsViewModel by viewModels()
    private lateinit var adapter: ConversationAdapter

    // Refresh avatar column whenever a contact's profile pic is downloaded
    private val profileUpdatedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (::adapter.isInitialized) adapter.notifyItemRangeChanged(0, adapter.itemCount)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentChatsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // Apply immediately on creation — before the view is ever shown.
        AccentColorHelper.apply(binding.root, PreferenceManager.accentColorInt())
        com.silvershireinn.app.utils.FontHelper.apply(binding.root, PreferenceManager.fontFamily)

        adapter = ConversationAdapter(
            onClick = { conversation ->
                val intent = Intent(requireContext(), ChatActivity::class.java).apply {
                    putExtra("contact_username", conversation.contactUsername)
                    if (conversation.isGroupChat) {
                        putExtra("group_id",   conversation.contactUsername)
                        putExtra("group_name", conversation.groupName)
                    }
                }
                startActivity(intent)
            },
            onLongPress = { conversation -> showDeleteDialog(conversation) }
        )

        binding.rvConversations.layoutManager = LinearLayoutManager(requireContext())
        binding.rvConversations.itemAnimator = null
        binding.rvConversations.adapter = adapter

        binding.fabNewChat.setOnClickListener {
            // Switch to Contacts tab to start a 1:1 chat
            requireActivity()
                .findViewById<BottomNavigationView>(R.id.bottomNav)
                .selectedItemId = R.id.contactsFragment
        }

        binding.fabNewGroup.setOnClickListener {
            startActivity(Intent(requireContext(), GroupCreateActivity::class.java))
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.conversations.collect { list ->
                adapter.submitList(list)
                if (list.isEmpty()) {
                    binding.emptyState.show()
                    binding.rvConversations.hide()
                } else {
                    binding.emptyState.hide()
                    binding.rvConversations.show()
                }
            }
        }

        // Re-bind when online status changes (bursts of presence events are batched), and
        // once a minute so "Last seen 5m ago" doesn't go stale while the list is open.
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                launch {
                    OnlineStatusManager.statusFlow.collectLatest {
                        kotlinx.coroutines.delay(250)
                        adapter.notifyItemRangeChanged(0, adapter.itemCount)
                    }
                }
                launch {
                    while (true) {
                        kotlinx.coroutines.delay(60_000)
                        adapter.notifyItemRangeChanged(0, adapter.itemCount)
                    }
                }
            }
        }
    }

    /** Long-press: mark as read (when unread), then delete / leave. */
    private fun showDeleteDialog(conv: Conversation) {
        if (conv.unreadCount > 0) {
            val items = arrayOf("Mark as read", if (conv.isGroupChat) "Leave group" else "Delete chat")
            MaterialAlertDialogBuilder(requireContext())
                .setItems(items) { _, which ->
                    if (which == 0) viewModel.markRead(conv) else confirmDelete(conv)
                }
                .show()
        } else confirmDelete(conv)
    }

    private fun confirmDelete(conv: Conversation) {
        if (!isAdded) return
        val displayName = if (conv.isGroupChat) conv.groupName else PreferenceManager.displayNameFor(conv.contactUsername)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(if (conv.isGroupChat) "Leave \"$displayName\"?" else "Delete chat with $displayName?")
            .setMessage("All messages in this conversation will be permanently removed from this device.")
            .setPositiveButton(if (conv.isGroupChat) "Leave" else "Delete") { _, _ ->
                // Its chat head goes too (a deleted 1:1 chat's head used to stay on screen).
                com.silvershireinn.app.ui.chathead.ChatHeadService.dismiss(requireContext(), conv.contactUsername)
                com.silvershireinn.app.notifications.NotificationHelper.cancelMessageNotifications(requireContext(), conv.contactUsername)
                if (conv.isGroupChat) context?.toast("You left \"${displayName}\"")
                viewModel.deleteConversation(conv)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onStart() {
        super.onStart()
        // Apply before the first draw so there is no gold→accent flash.
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
        super.onDestroyView()
        _binding = null
    }
}