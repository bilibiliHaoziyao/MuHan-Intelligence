package com.muhan.intelligence

import android.app.Application
import com.muhan.intelligence.BuildConfig
import com.muhan.intelligence.data.local.LogRepository
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class MuHanApplication : Application() {

    @Inject
    lateinit var logRepository: LogRepository

    override fun onCreate() {
        super.onCreate()
        // 0.2.0 Fix2：全局崩溃日志——任何未捕获异常先落盘再交还系统。
        logRepository.installCrashHandler()
        // 0.3.0：记录启动 INFO，便于在崩溃/异常后从日志还原现场。
        logRepository.info("App", "慕寒智能启动，版本 ${BuildConfig.VERSION_NAME} (code ${BuildConfig.VERSION_CODE})")
    }
}
