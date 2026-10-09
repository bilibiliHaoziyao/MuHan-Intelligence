package com.muhan.intelligence.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        ConversationEntity::class,
        MessageEntity::class,
        ProviderEntity::class,
    ],
    version = 2,
    exportSchema = false,
)
abstract class MuHanDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao
    abstract fun providerDao(): ProviderDao

    companion object {
        const val NAME = "muhan_intelligence.db"
    }
}
