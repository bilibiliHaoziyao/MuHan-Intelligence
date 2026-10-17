package com.muhan.intelligence.audio

import android.content.Context
import android.media.MediaPlayer
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/** 播放合成语音（2.0.0 Beta）。写临时 WAV 后用 [MediaPlayer] 播放。 */
@Singleton
class AudioPlayer @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private var player: MediaPlayer? = null
    private val playing = AtomicBoolean(false)

    val isPlaying: Boolean get() = playing.get()

    /** 播放一段 WAV。返回 true 表示已开始播放。 */
    fun play(wavBytes: ByteArray, onComplete: () -> Unit): Boolean {
        stop()
        return try {
            val file = File(context.cacheDir, "tts_${System.currentTimeMillis()}.wav")
            file.writeBytes(wavBytes)
            val mp = MediaPlayer()
            mp.setOnCompletionListener {
                playing.set(false)
                onComplete()
            }
            mp.setOnErrorListener { _, _, _ ->
                playing.set(false)
                onComplete()
                true
            }
            playing.set(true)
            player = mp
            // prepare()/start() 移到后台线程，避免长语音在主线程解码造成卡顿。
            Thread {
                try {
                    mp.setDataSource(file.absolutePath)
                    mp.prepare()
                    mp.start()
                } catch (t: Throwable) {
                    playing.set(false)
                    runCatching { mp.release() }
                    android.os.Handler(android.os.Looper.getMainLooper()).post(onComplete)
                }
            }.apply { isDaemon = true; start() }
            true
        } catch (t: Throwable) {
            playing.set(false)
            false
        }
    }

    fun stop() {
        val mp = player
        player = null
        if (mp != null) {
            playing.set(false)
            runCatching { mp.stop() }
            runCatching { mp.release() }
        }
    }

    fun release() = stop()
}
