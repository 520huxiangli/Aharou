package com.aharou.feature.voice.data

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import com.aharou.core.util.FileLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 朗读播放器：把云端合成返回的音频字节播出来。
 *
 * MediaPlayer 不接受内存里的字节，只能给路径或 file descriptor，所以先落到 cacheDir 的临时文件
 * 再播。同一时刻只保留一段音频（新朗读替换旧的），退出时删干净。
 */
@Singleton
internal class TtsPlayer @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {

    private var player: MediaPlayer? = null
    private var currentFile: File? = null

    /** [playAndWait] 的等待句柄。被打断时 [stop] 要主动完成它，调用方才能醒过来。 */
    private var pendingCompletion: CompletableDeferred<Unit>? = null

    /** 是否正在播放。 */
    val isPlaying: Boolean get() = player?.isPlaying == true

    /**
     * 播放一段音频。会先停掉正在播的。
     *
     * @param tag 文件名后缀。语音通话的播报队列里可能同时存在多段音频，靠它区分，
     *            免得后一段把前一段的文件覆盖掉。
     * @param onCompletion 自然播完时回调（被下一次朗读打断则不回调）
     */
    suspend fun play(
        bytes: ByteArray,
        tag: String = DEFAULT_TAG,
        onCompletion: () -> Unit = {},
    ) = withContext(Dispatchers.IO) {
        stop()
        val file = File(context.cacheDir, "tts_playback_$tag.mp3")
        runCatching { file.writeBytes(bytes) }
            .onFailure {
                FileLogger.e(TAG, "写入朗读音频失败", it)
                return@withContext
            }
        try {
            val mp = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                setDataSource(file.absolutePath)
                setOnCompletionListener {
                    releasePlayer()
                    file.delete()
                    currentFile = null
                    onCompletion()
                }
                setOnErrorListener { _, what, extra ->
                    FileLogger.e(TAG, "朗读播放出错 what=$what extra=$extra")
                    releasePlayer()
                    file.delete()
                    currentFile = null
                    true
                }
                prepare()
                start()
            }
            player = mp
            currentFile = file
        } catch (e: Exception) {
            FileLogger.e(TAG, "朗读播放失败", e)
            file.delete()
        }
    }

    /**
     * 播放并挂起到这段音频结束（或被 [stop] 打断）。
     *
     * 播报队列要一段接一段念，得等前一段放完。自然播完走 [play] 的 onCompletion；
     * 被打断时 onCompletion 不触发，靠 [stop] 主动唤醒，否则调用方会一直挂着。
     */
    suspend fun playAndWait(bytes: ByteArray, tag: String = DEFAULT_TAG) {
        val done = CompletableDeferred<Unit>()
        play(bytes, tag) { done.complete(Unit) }
        pendingCompletion = done
        done.await()
    }

    /** 停止播放并清理临时文件。 */
    fun stop() {
        releasePlayer()
        currentFile?.delete()
        currentFile = null
        pendingCompletion?.complete(Unit)
        pendingCompletion = null
    }

    private fun releasePlayer() {
        player?.let {
            runCatching { if (it.isPlaying) it.stop() }
            runCatching { it.release() }
        }
        player = null
    }

    private companion object {
        const val TAG = "TtsPlayer"

        /** 不指定 [play] 的 tag 时的默认文件名后缀（单段朗读用）。 */
        const val DEFAULT_TAG = "default"
    }
}
