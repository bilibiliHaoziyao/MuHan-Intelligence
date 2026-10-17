package com.muhan.intelligence.audio

import android.content.Context
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/** 播放合成语音（2.0.0）。写临时 WAV 后用 [MediaPlayer] 播放。 */
@Singleton
class AudioPlayer @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private var player: MediaPlayer? = null
    private var currentFile: File? = null
    private val playing = AtomicBoolean(false)

    // 3.1.0：复用单线程执行 prepare，避免每次播放新建 Thread。
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "tts-player").apply { isDaemon = true }
    }

    val isPlaying: Boolean get() = playing.get()

    /** 播放一段 WAV。返回 true 表示已开始播放。 */
    fun play(wavBytes: ByteArray, onComplete: () -> Unit): Boolean {
        stop()
        return try {
            val file = File(context.cacheDir, "tts_${System.currentTimeMillis()}.wav")
            file.writeBytes(wavBytes)
            currentFile = file

            val mp = MediaPlayer()
            mp.setOnCompletionListener {
                // 3.1.0：播完立即释放并删除临时文件，避免音频资源与缓存持续占用。
                playing.set(false)
                releaseInternal(mp)
                onComplete()
            }
            mp.setOnErrorListener { _, _, _ ->
                playing.set(false)
                releaseInternal(mp)
                onComplete()
                true
            }
            playing.set(true)
            player = mp

            // prepare()/start() 放到后台线程，避免长语音在主线程解码造成卡顿。
            executor.execute {
                try {
                    mp.setDataSource(file.absolutePath)
                    mp.prepare()
                    mp.start()
                } catch (t: Throwable) {
                    playing.set(false)
                    releaseInternal(mp)
                    Handler(Looper.getMainLooper()).post(onComplete)
                }
            }
            true
        } catch (t: Throwable) {
            playing.set(false)
            return false
        }
    }

    fun stop() {
        playing.set(false)
        val mp = player ?: return
        releaseInternal(mp)
    }

    fun release() = stop()

    private fun releaseInternal(mp: MediaPlayer) {
        runCatching { mp.stop() }
        runCatching { mp.release() }
        if (player === mp) player = null
        currentFile?.let { file ->
            runCatching { file.delete() }
            currentFile = null
        }
    }
}
