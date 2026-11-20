package com.silvershireinn.app.repository

import com.silvershireinn.app.database.AppDatabase
import com.silvershireinn.app.model.Group
import kotlinx.coroutines.flow.Flow

class GroupRepository(private val db: AppDatabase) {

    fun getGroups(): Flow<List<Group>> = db.groupDao().getGroups()

    suspend fun getGroup(groupId: String): Group? = db.groupDao().getGroup(groupId)

    suspend fun upsertGroup(group: Group) = db.groupDao().insertGroup(group)

    suspend fun deleteGroup(groupId: String) = db.groupDao().deleteGroup(groupId)

    suspend fun deleteAll() = db.groupDao().deleteAll()
}
