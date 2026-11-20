package com.silvershireinn.app.database

/** (id, timestamp) pair used by the one-time timestamp clean-up. */
data class IdTs(val id: String, val ts: String)
