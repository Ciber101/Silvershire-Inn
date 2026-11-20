package com.silvershireinn.app.ui.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.silvershireinn.app.SilvershireApp
import com.silvershireinn.app.network.WebSocketManager
import com.silvershireinn.app.repository.ContactRepository
import com.silvershireinn.app.repository.MessageRepository
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class MainViewModel : ViewModel() {

    val connectionState: StateFlow<WebSocketManager.ConnectionState> = WebSocketManager.state

    private val messageRepo = MessageRepository(SilvershireApp.database)
    private val contactRepo = ContactRepository(SilvershireApp.database)
}