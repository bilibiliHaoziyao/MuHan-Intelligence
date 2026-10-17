package com.muhan.intelligence.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        ConversationEntity::class,
        MessageEntity::class,
        ProviderEntity::class,
        SyncMetaEntity::class,
    ],
    version = 5,
    exportSchema = false,
)
abstract class MuHanDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao
    abstract fun providerDao(): ProviderDao
    abstract fun syncMetaDao(): SyncMetaDao

    companion object {
        const val NAME = "muhan_intelligence.db"
    }
}
