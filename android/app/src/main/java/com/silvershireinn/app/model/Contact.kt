package com.silvershireinn.app.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "contacts")
data class Contact(
    @PrimaryKey val username: String,
    val publicKey: String = "",
    val isOnline: Boolean = false,
    val isPending: Boolean = false,
    val isRequest: Boolean = false
)