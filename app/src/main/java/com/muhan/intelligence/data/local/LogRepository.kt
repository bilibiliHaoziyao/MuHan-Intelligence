package com.muhan.intelligence.data.local

import android.content.Context
import android.util.Log
import com.muhan.intelligence.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 轻量本地日志（0.3.0）。
 *
 * 记录 INFO 及以上级别（INFO / WARN / ERROR / FATAL），写入应用内部存储
 * `filesDir/MuHan/logs/muhan-YYYY-MM-DD.log`。崩溃通过默认
 * UncaughtExceptionHandler 捕获：先同步落盘再交还系统，保证「崩溃了也有日志」。
 *
 * 用户可在「设置 → 诊断」中查看历史日志并通过邮件发送。
 */
@Singleton
class LogRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    companion object {
        const val LOG_DIR_NAME = "MuHan/logs"
        const val FEEDBACK_EMAIL = "3102916674@qq.com"
        private const val MAX_LOG_FILES = 14
        private val FILE_NAME_FORMAT = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        private val TIME_FORMAT = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    }

    val logDir: File
        get() = File(context.filesDir, LOG_DIR_NAME).apply { mkdirs() }

    private val todayFile: File
        get() = File(logDir, "muhan-${FILE_NAME_FORMAT.format(Date())}.log")

    /** 所有日志文件，按时间倒序（最新在前）。 */
    fun listLogFiles(): List<File> =
        logDir.listFiles { f -> f.isFile && f.name.endsWith(".log") }
            ?.sortedByDescending { it.name }
            ?: emptyList()

    fun readLog(file: File): String =
        runCatching { file.readText() }.getOrDefault("（无法读取日志内容）")

    /** 清空全部历史日志。 */
    fun clearLogs() {
        runCatching { listLogFiles().forEach { it.delete() } }
    }

    fun info(tag: String, message: String, throwable: Throwable? = null) =
        write("INFO", tag, message, throwable)

    fun warn(tag: String, message: String, throwable: Throwable? = null) =
        write("WARN", tag, message, throwable)

    fun error(tag: String, message: String, throwable: Throwable? = null) =
        write("ERROR", tag, message, throwable)

    fun fatal(tag: String, message: String, throwable: Throwable? = null) =
        write("FATAL", tag, message, throwable)

    /**
     * 安装全局崩溃处理器：任何未捕获异常先同步写入日志再交还系统默认行为，
     * 保证崩溃现场（含堆栈）落盘。重复调用安全。
     */
    fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                write("FATAL", "Crash", "未捕获异常，线程：${thread.name}", throwable)
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    /** 将一条流式/请求失败写入日志（ChatViewModel 等调用）。 */
    fun logStreamFailure(message: String, throwable: Throwable?) {
        error("Chat", message, throwable)
    }

    private fun write(level: String, tag: String, message: String, throwable: Throwable?) {
        val line = buildString {
            append(TIME_FORMAT.format(Date()))
            append(" [").append(level).append('/').append(tag).append("] ")
            append(message)
            if (throwable != null) {
                append('\n')
                append(stackTraceOf(throwable))
            }
            append('\n')
        }
        // Logcat 同步输出，便于 adb 调试；内部日志保留 INFO 及以上。
        when (level) {
            "INFO" -> Log.i("MuHan/$tag", message, throwable)
            "WARN" -> Log.w("MuHan/$tag", message, throwable)
            else -> Log.e("MuHan/$tag", message, throwable)
        }
        runCatching {
            todayFile.appendText(line)
            trimOldFiles()
        }
    }

    private fun stackTraceOf(throwable: Throwable): String = runCatching {
        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))
        sw.toString().trimEnd()
    }.getOrDefault("${throwable.javaClass.name}: ${throwable.message}")

    private fun trimOldFiles() {
        val files = listLogFiles()
        if (files.size > MAX_LOG_FILES) {
            files.drop(MAX_LOG_FILES).forEach { runCatching { it.delete() } }
        }
    }
}
