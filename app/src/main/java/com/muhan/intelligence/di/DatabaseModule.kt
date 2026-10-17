package com.muhan.intelligence.di

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.muhan.intelligence.data.local.ConversationDao
import com.muhan.intelligence.data.local.MessageDao
import com.muhan.intelligence.data.local.MuHanDatabase
import com.muhan.intelligence.data.local.ProviderDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    /** 0.1.0 → 0.2.0: message attachments, generated images, provider image model. */
    private val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE messages ADD COLUMN attachments TEXT NOT NULL DEFAULT '[]'")
            db.execSQL("ALTER TABLE messages ADD COLUMN image_url TEXT")
            db.execSQL("ALTER TABLE providers ADD COLUMN image_model TEXT")
        }
    }

    /** 0.6.1 → 1.0.0: provider 展示用模型名（display_model_name）。 */
    private val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE providers ADD COLUMN display_model_name TEXT")
        }
    }

    /** 2.0.0 → 3.0.0: 会话模式（chat/agent）与 Agent 工具轨迹。 */
    private val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE conversations ADD COLUMN mode TEXT NOT NULL DEFAULT 'chat'")
            db.execSQL("ALTER TABLE messages ADD COLUMN agent_trace TEXT NOT NULL DEFAULT '[]'")
        }
    }

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): MuHanDatabase =
        Room.databaseBuilder(context, MuHanDatabase::class.java, MuHanDatabase.NAME)
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
            .fallbackToDestructiveMigration()
            .build()

    @Provides
    fun provideConversationDao(db: MuHanDatabase): ConversationDao = db.conversationDao()

    @Provides
    fun provideMessageDao(db: MuHanDatabase): MessageDao = db.messageDao()

    @Provides
    fun provideProviderDao(db: MuHanDatabase): ProviderDao = db.providerDao()
}
