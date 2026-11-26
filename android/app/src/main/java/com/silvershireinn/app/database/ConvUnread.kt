package com.silvershireinn.app.database

/** A conversation and how many of its received messages are still unread. */
data class ConvUnread(val conversationId: String, val n: Int)
