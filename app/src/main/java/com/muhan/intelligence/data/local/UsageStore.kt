package com.muhan.intelligence.data.local

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import androidx.core.content.ContextCompat
import com.muhan.intelligence.domain.model.MONTHLY_TOKEN_LIMIT
import com.muhan.intelligence.domain.model.TokenQuota
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/** 月度额度读取结果。 */
data class QuotaView(
    val quota: TokenQuota,
    /** 是否有可用的存储写入通道（无权限时为 false）。 */
    val storageReady: Boolean,
)

/**
 * 内置 LLM 的月度 Token 额度账本（2.0.2）。
 *
 * 数据落盘在公共存储 `MuHan/llm/llm-runtime.log`，伪装成一行普通日志：
 * - 应用卸载后文件仍保留，从而无法通过卸载重置额度；
 * - 行内带 SHA-256 摘要，被手改数字会失配，此时按「额度已用尽」锁定到月末；
 * - 每月 1 日惰性结算：上月超出 10 万的部分结转为本月欠账（carry），先扣欠账再用。
 */
@Singleton
class UsageStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val mutex = Mutex()

    private val dir: File get() = File(Environment.getExternalStorageDirectory(), "MuHan/llm")
    private val file: File get() = File(dir, "llm-runtime.log")

    /** 是否具备写公共目录的通道（Android 11+ 需要「所有文件访问」权限）。 */
    fun hasStorageAccess(): Boolean = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ->
            Environment.isExternalStorageManager()
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
            // Android 10 无 MANAGE 概念，尽力写（写失败时额度不会增长，属已知边缘情况）。
            true
        else ->
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.WRITE_EXTERNAL_STORAGE,
            ) == PackageManager.PERMISSION_GRANTED
    }

    /** 读取当前额度（含惰性跨月结算）。 */
    suspend fun current(): QuotaView = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!hasStorageAccess()) {
                return@withLock QuotaView(TokenQuota(period = currentPeriod()), storageReady = false)
            }
            val (parsed, tampered) = readRaw()
            val now = currentPeriod()
            val quota = when {
                // 手改文件 → 锁定到月末，杜绝靠改数字绕过。
                tampered -> TokenQuota(period = now, used = MONTHLY_TOKEN_LIMIT)
                // 首次使用。
                parsed == null -> TokenQuota(period = now)
                parsed.period == now -> parsed
                else -> {
                    // 跨月：超出部分结转为下月欠账。
                    val carry = (parsed.used - (parsed.limit - parsed.carry)).coerceAtLeast(0L)
                    TokenQuota(period = now, carry = carry).also { writeRaw(it) }
                }
            }
            QuotaView(quota, storageReady = true)
        }
    }

    /** 记入一笔内置 LLM 用量。 */
    suspend fun addBuiltInUsage(tokens: Long) {
        if (tokens <= 0L) return
        withContext(Dispatchers.IO) {
            mutex.withLock {
                if (!hasStorageAccess()) return@withLock
                val (parsed, tampered) = readRaw()
                if (tampered) return@withLock // 已锁定，拒绝记账
                val now = currentPeriod()
                val base = when {
                    parsed == null -> TokenQuota(period = now)
                    parsed.period == now -> parsed
                    else -> {
                        val carry = (parsed.used - (parsed.limit - parsed.carry)).coerceAtLeast(0L)
                        TokenQuota(period = now, carry = carry)
                    }
                }
                writeRaw(base.copy(used = base.used + tokens))
            }
        }
    }

    private fun readRaw(): Pair<TokenQuota?, Boolean> {
        val text = runCatching { file.readText() }.getOrNull() ?: return null to false
        val m = Regex(
            """period=(\S+)\s+used=(\d+)\s+carry=(\d+)\s+sig=([0-9a-f]+)""",
        ).find(text) ?: return null to true
        val period = m.groupValues[1]
        val used = m.groupValues[2].toLongOrNull() ?: return null to true
        val carry = m.groupValues[3].toLongOrNull() ?: return null to true
        val sig = m.groupValues[4]
        if (sig != signature(period, used, carry)) return null to true
        return TokenQuota(period = period, used = used, carry = carry) to false
    }

    private fun writeRaw(q: TokenQuota): Boolean = runCatching {
        dir.mkdirs()
        val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        val line = "$ts  I  MuHanLLM  quota.heartbeat " +
            "period=${q.period} used=${q.used} carry=${q.carry} " +
            "sig=${signature(q.period, q.used, q.carry)}"
        val tmp = File(dir, "llm-runtime.log.tmp")
        tmp.writeText(line)
        if (file.exists()) file.delete()
        tmp.renameTo(file)
    }.isSuccess

    private fun currentPeriod(): String =
        SimpleDateFormat("yyyy-MM", Locale.US).format(Date())

    private fun signature(period: String, used: Long, carry: Long): String {
        val raw = "$period|$used|$carry|$SALT"
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray())
        return digest.take(6).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private companion object {
        const val SALT = "muhan-llm-quota-v1"
    }
}
