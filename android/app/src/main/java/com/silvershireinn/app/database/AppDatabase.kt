package com.silvershireinn.app.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.silvershireinn.app.model.Contact
import com.silvershireinn.app.model.Conversation
import com.silvershireinn.app.model.Group
import com.silvershireinn.app.model.Message
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import net.sqlcipher.database.SQLiteDatabase
import net.sqlcipher.database.SupportFactory

@Database(
    entities = [Message::class, Contact::class, Conversation::class, Group::class],
    version = 5,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun messageDao(): MessageDao
    abstract fun contactDao(): ContactDao
    abstract fun conversationDao(): ConversationDao
    abstract fun groupDao(): GroupDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        /** v2 → v3: add isUploading column */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE messages ADD COLUMN isUploading INTEGER NOT NULL DEFAULT 0"
                )
            }
        }


        /** v4 -> v5: isServerAck for 4-step receipts */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE messages ADD COLUMN isServerAck INTEGER NOT NULL DEFAULT 0"
                )
                // Prior isDelivered meant server ack; promote to isServerAck and clear false deliveries
                database.execSQL(
                    "UPDATE messages SET isServerAck = 1 WHERE isDelivered = 1 AND isSent = 1"
                )
                database.execSQL(
                    "UPDATE messages SET isDelivered = 0 WHERE isSent = 1 AND isRead = 0"
                )
            }
        }

        /** v3 → v4: add group support columns + groups table */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Add groupId to messages (nullable)
                database.execSQL("ALTER TABLE messages ADD COLUMN groupId TEXT")
                // Add groupName to conversations (empty = 1:1 chat)
                database.execSQL("ALTER TABLE conversations ADD COLUMN groupName TEXT NOT NULL DEFAULT ''")
                // Create groups table
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS groups (
                        groupId TEXT NOT NULL PRIMARY KEY,
                        name TEXT NOT NULL,
                        membersJson TEXT NOT NULL,
                        createdBy TEXT NOT NULL,
                        createdAt TEXT NOT NULL DEFAULT ''
                    )
                """.trimIndent())
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                // Re-check inside the lock: two threads could otherwise both build a database.
                INSTANCE?.let { return it }
                // This phone's own database key (Keystore-wrapped); old installs are re-keyed once.
                val passphrase = DbKey.passphrase(context)
                val factory = SupportFactory(passphrase)
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "silvershire.db"
                )
                    .openHelperFactory(factory)
                    .addMigrations(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
