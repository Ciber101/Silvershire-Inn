package com.silvershireinn.app.database

import android.content.Context
import android.util.Log
import com.silvershireinn.app.utils.DeviceVault
import net.sqlcipher.database.SQLiteDatabase
import java.security.SecureRandom

/**
 * The message database key. It used to be the same text built into every copy of the app,
 * so anyone with the database file could open it. Now each phone gets its own random key,
 * kept wrapped by the Android Keystore. An existing database is re-keyed once (in place).
 *
 * Crash-safe order: the new key is saved as "pending" first, then the database is re-keyed,
 * then the key is marked current. Whatever step a crash interrupts, the next start finds a
 * key that opens the file.
 */
object DbKey {
    private const val LEGACY = "silvershire_secure_key_v1"
    private const val DB_NAME = "silvershire.db"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("silvershire_dbkey", Context.MODE_PRIVATE)

    private fun canOpen(path: String, pass: String): Boolean = try {
        SQLiteDatabase.openDatabase(path, pass, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT count(*) FROM sqlite_master", null).use { it.moveToFirst() }
        }
        true
    } catch (_: Throwable) { false }

    @Synchronized
    fun passphrase(context: Context): ByteArray {
        val ctx = context.applicationContext
        val p = prefs(ctx)
        p.getString("k", null)?.let { stored ->
            try { return SQLiteDatabase.getBytes(DeviceVault.openString(stored)!!.toCharArray()) }
            catch (e: Throwable) {
                // The keystore can't open it any more (it was reset): this database is lost.
                // Start a new one; chats come back from the server's history.
                Log.e("DbKey", "database key unreadable (${e.javaClass.simpleName}); setting the database aside and starting a new one")
                try { ctx.getDatabasePath(DB_NAME).renameTo(java.io.File(ctx.getDatabasePath(DB_NAME).parentFile, "$DB_NAME.unreadable-${System.currentTimeMillis()}")) } catch (_: Throwable) {}
                p.edit().remove("k").remove("p").commit()
            }
        }
        val fresh = p.getString("p", null)?.let { try { DeviceVault.openString(it) } catch (_: Throwable) { null } }
            ?: ByteArray(32).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
                .also { p.edit().putString("p", DeviceVault.wrapString(it)).commit() }
        val dbFile = ctx.getDatabasePath(DB_NAME)
        if (dbFile.exists()) {
            SQLiteDatabase.loadLibs(ctx)
            val path = dbFile.absolutePath
            when {
                canOpen(path, fresh) -> {}
                canOpen(path, LEGACY) -> try {
                    SQLiteDatabase.openDatabase(path, LEGACY, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                        // Fold the write-ahead log into the file and leave WAL mode before re-keying
                        // (Room turns WAL back on when it opens the database).
                        try { db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() } } catch (_: Throwable) {}
                        try { db.rawQuery("PRAGMA journal_mode = DELETE", null).use { it.moveToFirst() } } catch (_: Throwable) {}
                        db.changePassword(fresh)
                    }
                    Log.i("DbKey", "database re-keyed with this phone's own key")
                } catch (e: Throwable) {
                    Log.e("DbKey", "re-key failed, keeping the old key for now: ${e.message}")
                    return SQLiteDatabase.getBytes(LEGACY.toCharArray())
                }
                else -> {
                    // Never delete it: set it aside (could be recovered) and start a new one.
                    Log.e("DbKey", "database opens with no known key; setting it aside and starting a new one")
                    try {
                        val tag = ".unreadable-${System.currentTimeMillis()}"
                        for (sfx in listOf("-wal", "-journal")) java.io.File(dbFile.path + sfx).let { if (it.exists()) it.renameTo(java.io.File(dbFile.path + tag + sfx)) }
                        java.io.File(dbFile.path + "-shm").delete()
                        dbFile.renameTo(java.io.File(dbFile.path + tag))
                    } catch (_: Throwable) {}
                }
            }
        }
        p.edit().putString("k", p.getString("p", null) ?: DeviceVault.wrapString(fresh)).remove("p").commit()
        return SQLiteDatabase.getBytes(fresh.toCharArray())
    }
}
