package com.silvershireinn.app.ui.contacts

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.silvershireinn.app.SilvershireApp
import com.silvershireinn.app.model.Contact
import com.silvershireinn.app.network.WebSocketManager
import com.silvershireinn.app.notifications.NotificationHelper
import com.silvershireinn.app.repository.ContactRepository
import com.silvershireinn.app.repository.MessageRepository
import com.silvershireinn.app.utils.PreferenceManager
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed class SearchState {
    object Idle      : SearchState()
    object Searching : SearchState()
    data class Found(val username: String) : SearchState()
    object NotFound  : SearchState()
    /** No answer from the server (offline): the spinner used to spin forever. */
    object NoAnswer  : SearchState()
}

class ContactsViewModel : ViewModel() {

    private val repo    = ContactRepository(SilvershireApp.database)
    private val msgRepo = MessageRepository(SilvershireApp.database)

    /**
     * Cached StateFlows — the ViewModel holds the latest DB snapshot so fragments
     * receive data immediately on re-subscription (e.g. after screen unlock),
     * preventing the temporary blank-list flash.
     */
    // Sorted by the name you see (nickname / display name), not the raw username.
    val contacts: StateFlow<List<Contact>> = repo.getContacts()
        .map { list -> list.sortedBy { PreferenceManager.displayNameFor(it.username).lowercase() } }
        .stateIn(
            scope        = viewModelScope,
            started      = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList()
        )

    val friendRequests: StateFlow<List<Contact>> = repo.getFriendRequests()
        .stateIn(
            scope        = viewModelScope,
            started      = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList()
        )

    private val _searchState = MutableStateFlow<SearchState>(SearchState.Idle)
    val searchState: StateFlow<SearchState> = _searchState

    // One-shot events for export results
    private val _exportResult = MutableSharedFlow<Result<Pair<String, ByteArray>>>()
    val exportResult: SharedFlow<Result<Pair<String, ByteArray>>> = _exportResult

    init {
        viewModelScope.launch {
            WebSocketManager.events.collect { json ->
                if (json.optString("type") == "search_result") {
                    val foundBool = json.optBoolean("found")
                    val foundName = json.optString("name")
                    _searchState.value =
                        if (foundBool && foundName.isNotBlank()) SearchState.Found(foundName)
                        else SearchState.NotFound
                }
            }
        }
    }

    private var searchTimeout: kotlinx.coroutines.Job? = null

    fun searchUser(query: String) {
        _searchState.value = SearchState.Searching
        WebSocketManager.searchUser(PreferenceManager.username, query)
        searchTimeout?.cancel()
        searchTimeout = viewModelScope.launch {
            kotlinx.coroutines.delay(10_000)
            if (_searchState.value == SearchState.Searching) _searchState.value = SearchState.NoAnswer
        }
    }

    fun sendFriendRequest(to: String) {
        // If they previously deleted this contact, allow re-adding
        PreferenceManager.removeDeletedContact(to)
        WebSocketManager.sendFriendRequest(PreferenceManager.username, to)
    }

    fun resetSearch() {
        _searchState.value = SearchState.Idle
    }

    fun acceptRequest(context: Context, username: String) {
        viewModelScope.launch {
            repo.confirmFriend(username)
            WebSocketManager.sendFriendResponse(PreferenceManager.username, username, true)
            NotificationHelper.cancelFriendRequestNotification(context)
        }
    }

    fun declineRequest(username: String) {
        viewModelScope.launch {
            repo.deleteContact(username)
            WebSocketManager.sendFriendResponse(PreferenceManager.username, username, false)
        }
    }

    fun deleteContact(username: String) {
        viewModelScope.launch { ContactActions.remove(SilvershireApp.instance, username) }
    }

    fun blockContact(username: String) {
        viewModelScope.launch { ContactActions.block(SilvershireApp.instance, username) }
    }

    fun exportChatHistory(contactUsername: String) {
        viewModelScope.launch {
            runCatching {
                val me = PreferenceManager.username
                val messages = msgRepo.getMessages(contactUsername).first()
                val sb = StringBuilder()
                val them = PreferenceManager.displayNameFor(contactUsername)
                sb.appendLine("Silvershire chat export")
                sb.appendLine("Conversation: You and $them")
                sb.appendLine("Exported: ${com.silvershireinn.app.utils.nowIso().let { com.silvershireinn.app.utils.isoToMillis(it) }?.let { java.util.Date(it) }}")
                sb.appendLine("─────────────────────────────")
                sb.appendLine()
                messages.forEach { msg ->
                    val sender = if (msg.isSent) "You" else them
                    val body = when {
                        msg.isDeleted -> "[deleted]"
                        msg.isAttachment -> "[file] ${msg.attachmentName ?: ""}"
                        else -> msg.encryptedPayload
                    }
                    sb.appendLine("[${com.silvershireinn.app.utils.isoToMillis(msg.timestamp)?.let { java.text.DateFormat.getDateTimeInstance().format(java.util.Date(it)) } ?: msg.timestamp}]  $sender:")
                    sb.appendLine("  $body")
                    sb.appendLine()
                }
                val safe = them.replace(Regex("[^A-Za-z0-9 _.-]"), "").trim().ifBlank { "chat" }
                val fileName = "Silvershire chat - $safe - ${System.currentTimeMillis()}.txt"
                Pair(fileName, sb.toString().toByteArray(Charsets.UTF_8))
            }.let { _exportResult.emit(it) }
        }
    }
}
