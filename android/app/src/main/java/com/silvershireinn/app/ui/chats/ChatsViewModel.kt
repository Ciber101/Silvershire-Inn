package com.silvershireinn.app.ui.chats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.silvershireinn.app.SilvershireApp
import com.silvershireinn.app.model.Conversation
import com.silvershireinn.app.repository.GroupRepository
import com.silvershireinn.app.repository.MessageRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ChatsViewModel : ViewModel() {

    private val repo      = MessageRepository(SilvershireApp.database)
    private val groupRepo = GroupRepository(SilvershireApp.database)

    /**
     * Backed by a StateFlow so the last-emitted value is cached in the ViewModel.
     * When the fragment's view is recreated (e.g. after screen unlock), the collector
     * immediately receives the cached list — no blank flash while Room re-queries.
     */
    val conversations: StateFlow<List<Conversation>> = repo.getConversations()
        .stateIn(
            scope        = viewModelScope,
            started      = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList()
        )

    /** Mark a chat read from the list: here, on my other devices, and receipts if enabled. */
    fun markRead(conv: com.silvershireinn.app.model.Conversation) {
        viewModelScope.launch {
            val ids = repo.markAllReadAndGetUnread(conv.contactUsername)
            val me = com.silvershireinn.app.utils.PreferenceManager.username
            if (!conv.isGroupChat && com.silvershireinn.app.utils.PreferenceManager.readReceipts) {
                ids.forEach { com.silvershireinn.app.network.WebSocketManager.sendReadReceipt(me, conv.contactUsername, it) }
            }
            com.silvershireinn.app.network.WebSocketManager.sendConversationRead(me, conv.contactUsername)
            com.silvershireinn.app.notifications.NotificationHelper.cancelMessageNotifications(SilvershireApp.instance, conv.contactUsername)
            com.silvershireinn.app.ui.chathead.ChatHeadService.clearUnread(SilvershireApp.instance, conv.contactUsername)
        }
    }

    fun deleteConversation(conv: com.silvershireinn.app.model.Conversation) {
        viewModelScope.launch {
            // Remember the cut-off so the next history sync doesn't restore the deleted chat.
            com.silvershireinn.app.utils.PreferenceManager.setClearedAt(conv.contactUsername)
            repo.deleteConversation(conv.contactUsername)
            if (conv.isGroupChat) {
                // Notify server so other members see the leave announcement
                com.silvershireinn.app.network.WebSocketManager.leaveGroup(conv.contactUsername)
                // Remove from local groups table so it never reappears on reconnect
                groupRepo.deleteGroup(conv.contactUsername)
            }
        }
    }
}