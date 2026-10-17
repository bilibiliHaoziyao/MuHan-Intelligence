package com.muhan.intelligence.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

/**
 * 共享网络栈（3.1.0）。
 *
 * 此前每个数据源各自 new 一个 [OkHttpClient]，7 个实例意味着 7 套连接池与
 * Dispatcher 线程池，连接无法复用、常驻线程更多，属于隐性的内存与耗电来源。
 * 这里统一成一个单例，各处通过 `newBuilder()` 只覆盖超时——派生实例共享
 * 连接池与调度器，是 OkHttp 官方推荐做法。
 */
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
}
