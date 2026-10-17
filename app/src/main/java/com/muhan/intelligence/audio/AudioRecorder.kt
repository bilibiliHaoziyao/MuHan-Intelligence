package com.muhan.intelligence.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 基于 [AudioRecord] 的简易录音器（2.0.0 Beta）。
 *
 * 直接录 16kHz / 单声道 / 16-bit PCM，停止时封装成 WAV（语音识别接口仅支持
 * wav/mp3），全程内存操作，无需申请存储写权限。
 */
@Singleton
class AudioRecorder @Inject constructor() {

    private var record: AudioRecord? = null
    private var drainThread: Thread? = null
    private val recording = AtomicBoolean(false)
    private val pcm = ByteArrayOutputStream()

    val isRecording: Boolean get() = recording.get()

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (recording.get()) return false
        val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
            .coerceAtLeast(4096)
        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL,
                ENCODING,
                bufferSize,
            )
        } catch (t: Throwable) {
            return false
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            return false
        }
        record = rec
        pcm.reset()
        recording.set(true)
        rec.startRecording()
        drainThread = Thread { drain(rec, bufferSize) }.apply {
            isDaemon = true
            start()
        }
        return true
    }

    /** 停止并返回 WAV 字节；录音失败或过短返回 null。 */
    fun stop(): ByteArray? {
        val rec = record ?: return null
        recording.set(false)
        runCatching { rec.stop() }
        // 等读线程退出，避免它与这里争抢残余数据。
        runCatching { drainThread?.join(500) }
        drainThread = null
        drainRemaining(rec)
        rec.release()
        record = null
        val raw = pcm.toByteArray()
        if (raw.size < SAMPLE_RATE / 2 * 2) return null // 不足 0.5s
        return wrapWav(raw)
    }

    fun cancel() {
        val rec = record ?: return
        recording.set(false)
        runCatching { rec.stop() }
        runCatching { drainThread?.join(300) }
        drainThread = null
        rec.release()
        record = null
        pcm.reset()
    }

    private fun drain(rec: AudioRecord, bufferSize: Int) {
        val buf = ByteArray(bufferSize)
        while (recording.get()) {
            val n = rec.read(buf, 0, buf.size)
            if (n > 0) synchronized(pcm) { pcm.write(buf, 0, n) }
        }
    }

    private fun drainRemaining(rec: AudioRecord) {
        val buf = ByteArray(4096)
        runCatching {
            while (true) {
                val n = rec.read(buf, 0, buf.size)
                if (n <= 0) break
                synchronized(pcm) { pcm.write(buf, 0, n) }
            }
        }
    }

    private fun wrapWav(raw: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(raw.size + 44)
        fun writeString(s: String) = out.write(s.toByteArray(Charsets.US_ASCII))
        fun writeLe(value: Int, bytes: Int) {
            var v = value
            repeat(bytes) { out.write(v and 0xff); v = v shr 8 }
        }
        val byteRate = SAMPLE_RATE * 2
        writeString("RIFF")
        writeLe(36 + raw.size, 4)
        writeString("WAVE")
        writeString("fmt ")
        writeLe(16, 4)            // fmt chunk size
        writeLe(1, 2)             // PCM
        writeLe(1, 2)             // mono
        writeLe(SAMPLE_RATE, 4)
        writeLe(byteRate, 4)
        writeLe(2, 2)             // block align
        writeLe(16, 2)            // bits per sample
        writeString("data")
        writeLe(raw.size, 4)
        out.write(raw)
        return out.toByteArray()
    }

    private companion object {
        const val SAMPLE_RATE = 16_000
        const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
    }
}
