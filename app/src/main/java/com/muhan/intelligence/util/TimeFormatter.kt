package com.muhan.intelligence.util

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Human-friendly timestamp formatting used across history and settings. */
object TimeFormatter {

    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val dateFormat = SimpleDateFormat("M月d日", Locale.getDefault())
    private val fullFormat = SimpleDateFormat("yyyy年M月d日 HH:mm", Locale.getDefault())

    /** "刚刚" / "12 分钟前" / "14:30" / "昨天" / "3月2日". */
    fun relative(timestamp: Long): String {
        val now = System.currentTimeMillis()
        val diff = now - timestamp

        return when {
            diff < 60_000 -> "刚刚"
            diff < 3_600_000 -> "${diff / 60_000} 分钟前"
            isSameDay(timestamp, now) -> timeFormat.format(Date(timestamp))
            isYesterday(timestamp, now) -> "昨天"
            diff < 7 * 24 * 3_600_000L -> "${diff / (24 * 3_600_000L)} 天前"
            isSameYear(timestamp, now) -> dateFormat.format(Date(timestamp))
            else -> fullFormat.format(Date(timestamp))
        }
    }

    fun full(timestamp: Long): String = fullFormat.format(Date(timestamp))

    /** "0.1.0-dev" -> "0.1.0-dev" (kept simple; here as a single place to change). */
    fun formatVersion(versionName: String): String = "v$versionName"

    private fun isSameDay(a: Long, b: Long): Boolean {
        val ca = Calendar.getInstance().apply { timeInMillis = a }
        val cb = Calendar.getInstance().apply { timeInMillis = b }
        return ca.get(Calendar.YEAR) == cb.get(Calendar.YEAR) &&
            ca.get(Calendar.DAY_OF_YEAR) == cb.get(Calendar.DAY_OF_YEAR)
    }

    private fun isYesterday(timestamp: Long, now: Long): Boolean {
        val yesterday = Calendar.getInstance().apply {
            timeInMillis = now
            add(Calendar.DAY_OF_YEAR, -1)
        }
        val target = Calendar.getInstance().apply { timeInMillis = timestamp }
        return yesterday.get(Calendar.YEAR) == target.get(Calendar.YEAR) &&
            yesterday.get(Calendar.DAY_OF_YEAR) == target.get(Calendar.DAY_OF_YEAR)
    }

    private fun isSameYear(a: Long, b: Long): Boolean {
        val ca = Calendar.getInstance().apply { timeInMillis = a }
        val cb = Calendar.getInstance().apply { timeInMillis = b }
        return ca.get(Calendar.YEAR) == cb.get(Calendar.YEAR)
    }
}
