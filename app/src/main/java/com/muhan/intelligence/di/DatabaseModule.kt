package com.muhan.intelligence.di

import android.content.Context
import androidx.room.Room
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

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): MuHanDatabase =
        Room.databaseBuilder(context, MuHanDatabase::class.java, MuHanDatabase.NAME)
            .fallbackToDestructiveMigration()
            .build()

    @Provides
    fun provideConversationDao(db: MuHanDatabase): ConversationDao = db.conversationDao()

    @Provides
    fun provideMessageDao(db: MuHanDatabase): MessageDao = db.messageDao()

    @Provides
    fun provideProviderDao(db: MuHanDatabase): ProviderDao = db.providerDao()
}
