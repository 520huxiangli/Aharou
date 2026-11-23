package com.aharou.feature.voice.data

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaPlayer
import com.aharou.core.util.FileLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 流式播放的结局。 */
internal enum class StreamPlayResult {
    /** 一个采样都没放出去，调用方可以整段重播。 */
    NotStarted,

    /** 整段放完。 */
    Finished,

    /** 已经出过声但流在中途断了；不要再整段重播，否则同一句会念两遍。 */
    Broken,

    /** 被 stop() / 新一轮播放打断，什么都没放完。 */
    Cancelled,
}

/** [TtsPlayer.playStream] 的返回值；[fullAudio] 是完整放完后打包好的 WAV，供调用方落缓存。 */
internal class StreamOutcome(
    val result: StreamPlayResult,
    val fullAudio: ByteArray? = null,
)

/**
 * 朗读播放器：把云端合成返回的音频字节播出来。
 *
 * MediaPlayer 不接受内存里的字节，只能给路径或 file descriptor，所以先落到 cacheDir 的临时文件
 * 再播。同一时刻只保留一段音频（新朗读替换旧的），退出时删干净。
 */
@Singleton
internal class TtsPlayer @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val audioSession: VoiceAudioSession,
) {

    private var player: MediaPlayer? = null
    private var currentFile: File? = null

    /** 流式播放用的 AudioTrack；和 [player] 互斥，同一时刻只有一个在响。 */
    @Volatile
    private var track: AudioTrack? = null

    /** 正在读的流式响应；[stop] 靠它把阻塞中的 read 打断。 */
    @Volatile
    private var activeStream: PcmSpeechStream? = null

    /** [playAndWait] 的等待句柄。被打断时 [stop] 要主动完成它，调用方才能醒过来。 */
    private var pendingCompletion: CompletableDeferred<Unit>? = null

    /** 每次 [play] 自增的唯一代号，回调据此判断自己还是不是当前这一轮。 */
    @Volatile
    private var playbackId = 0L

    /** 是否正在播放。 */
    val isPlaying: Boolean
        get() = player?.isPlaying == true ||
            runCatching { track?.playState == AudioTrack.PLAYSTATE_PLAYING }.getOrDefault(false)

    /**
     * 播放一段音频。会先停掉正在播的。
     *
     * @param tag 文件名后缀。语音通话的播报队列里可能同时存在多段音频，靠它区分，
     *            免得后一段把前一段的文件覆盖掉。
     * @param onCompletion 结束（自然播完或出错）时回调（被下一次朗读打断则不回调）
     * @return 是否已开始播放；false 表示写盘或准备失败，不会再有回调，调用方无需等待
     */
    suspend fun play(
        bytes: ByteArray,
        tag: String = DEFAULT_TAG,
        onCompletion: () -> Unit = {},
    ): Boolean = withContext(Dispatchers.IO) {
        stop()
        val id = ++playbackId
        // 文件名加唯一后缀：同一 tag 的多段音频共用文件名时，后一段会覆盖掉前一段
        val file = File(context.cacheDir, "tts_playback_${tag}_${System.nanoTime()}.mp3")
        runCatching { file.writeBytes(bytes) }
            .onFailure {
                FileLogger.e(TAG, "写入朗读音频失败", it)
                runCatching { file.delete() }
                return@withContext false
            }
        // 播放期持有音频焦点；被别的 App 抢占或输出设备被切走时停掉当前这段
        audioSession.onInterrupted = { stop() }
        audioSession.requestFocus()
        try {
            val mp = MediaPlayer().apply {
                setAudioAttributes(playbackAttributes())
                setDataSource(file.absolutePath)
                setOnCompletionListener {
                    finishPlayback(id, file, onCompletion)
                }
                setOnErrorListener { _, what, extra ->
                    FileLogger.e(TAG, "朗读播放出错 what=$what extra=$extra")
                    // onError 返回 true 时框架不会回调 onCompletion，这里自己收尾并唤醒等待方
                    finishPlayback(id, file, onCompletion)
                    true
                }
                prepare()
                start()
            }
            player = mp
            currentFile = file
            true
        } catch (e: Exception) {
            FileLogger.e(TAG, "朗读播放失败", e)
            runCatching { file.delete() }
            audioSession.abandonFocus()
            false
        }
    }

    /**
     * 流式播放一段 PCM：收到的分块直接写进 AudioTrack，不等整段合成结束。
     *
     * 返回的结局决定调用方要不要整段回退：只有 [StreamPlayResult.NotStarted]（一个采样都
     * 没放出去）才能重播整段，否则同一句会念两遍。
     *
     * @param captureAudio 是否把整段音频收下来（给缓存用）；只短句开，长句别占内存
     */
    suspend fun playStream(
        stream: PcmSpeechStream,
        captureAudio: Boolean = false,
    ): StreamOutcome = withContext(Dispatchers.IO) {
        stop()
        val id = ++playbackId
        activeStream = stream
        audioSession.onInterrupted = { stop() }
        audioSession.requestFocus()

        val channelCount = stream.channelCount.coerceIn(1, 2)
        val channelMask =
            if (channelCount == 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
        val frameBytes = 2 * channelCount
        // 最短缓冲的两倍：流式生成速度不均匀，太小会一顿一顿，太大首字又拖
        val minBuffer = runCatching {
            AudioTrack.getMinBufferSize(
                stream.sampleRate,
                channelMask,
                AudioFormat.ENCODING_PCM_16BIT,
            )
        }.getOrDefault(0)
        val track = if (minBuffer > 0) {
            runCatching {
                AudioTrack.Builder()
                    .setAudioAttributes(playbackAttributes())
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(stream.sampleRate)
                            .setChannelMask(channelMask)
                            .build()
                    )
                    .setBufferSizeInBytes(maxOf(minBuffer * 2, 8 * 1024))
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
            }.getOrNull()
        } else {
            null
        }
        if (track == null || track.state != AudioTrack.STATE_INITIALIZED) {
            runCatching { track?.release() }
            closeStream(stream)
            if (id == playbackId) audioSession.abandonFocus()
            return@withContext StreamOutcome(StreamPlayResult.NotStarted)
        }
        this@TtsPlayer.track = track

        // +1 字节：接住上一块剩下的半个采样（PCM16 一帧两字节，奇数写进去会被丢掉半帧）
        val buffer = ByteArray(CHUNK_BYTES + 1)
        val collected = if (captureAudio) ByteArrayOutputStream() else null
        var captured = 0
        var overflow = false
        var playedAny = false
        var failed = false
        var pendingByte = -1
        try {
            track.play()
            while (id == playbackId) {
                val offset = if (pendingByte >= 0) {
                    buffer[0] = pendingByte.toByte()
                    pendingByte = -1
                    1
                } else {
                    0
                }
                val read = try {
                    stream.read(buffer, offset, CHUNK_BYTES)
                } catch (e: IOException) {
                    // 被 stop() 取消或连接断开都走这里；算不算「播过」由 playedAny 决定
                    failed = true
                    break
                }
                if (read < 0) break
                if (read == 0) continue
                var length = read
                if ((offset + length) % frameBytes != 0) {
                    length -= 1
                    pendingByte = buffer[offset + length].toInt() and 0xff
                }
                if (length <= 0) continue
                var written = 0
                while (written < length) {
                    val n = track.write(buffer, offset + written, length - written)
                    if (n <= 0) {
                        failed = true
                        break
                    }
                    written += n
                }
                if (written > 0) playedAny = true
                if (collected != null && written > 0 && !overflow) {
                    if (captured + written <= MAX_CAPTURE_BYTES) {
                        collected.write(buffer, offset, written)
                        captured += written
                    } else {
                        // 超出缓存预算就不收：长音频留在内存里比省一次往返更贵
                        overflow = true
                    }
                }
                if (failed) break
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // AudioTrack 被 stop() 并发释放时 play/write 会抛 IllegalStateException；按失败收尾
            FileLogger.w(TAG, "流式播放失败", e)
            failed = true
        } finally {
            val cancelled = id != playbackId
            if (cancelled) abortTrack(track) else finishTrack(track)
            closeStream(stream)
            if (!cancelled) audioSession.abandonFocus()
        }

        val cancelled = id != playbackId
        val result = when {
            cancelled -> StreamPlayResult.Cancelled
            failed && playedAny -> StreamPlayResult.Broken
            failed -> StreamPlayResult.NotStarted
            // 流正常结束但一个采样都没读到：同样当没播，让调用方回退整段
            playedAny -> StreamPlayResult.Finished
            else -> StreamPlayResult.NotStarted
        }
        val full = if (
            result == StreamPlayResult.Finished && collected != null && !overflow && captured > 0
        ) {
            Wav.header(captured, stream.sampleRate, channelCount) + collected.toByteArray()
        } else {
            null
        }
        StreamOutcome(result, full)
    }

    /**
     * 收尾并回调，但只对仍是当前这一轮的播放生效；已被下一段接管的迟到回调直接忽略，
     * 否则会误删新段的文件、误释放新段的 player。
     */
    private fun finishPlayback(id: Long, file: File, onCompletion: () -> Unit) {
        if (id != playbackId) return
        releasePlayer()
        runCatching { file.delete() }
        currentFile = null
        audioSession.abandonFocus()
        onCompletion()
    }

    /**
     * 播放并挂起到这段音频结束（或被 [stop] 打断）。
     *
     * 播报队列要一段接一段念，得等前一段放完。自然播完走 [play] 的 onCompletion；
     * 被打断时 onCompletion 不触发，靠 [stop] 主动唤醒，否则调用方会一直挂着。
     */
    suspend fun playAndWait(bytes: ByteArray, tag: String = DEFAULT_TAG) {
        val done = CompletableDeferred<Unit>()
        val started = play(bytes, tag) { done.complete(Unit) }
        if (!started) {
            // 没播起来（写盘/准备失败）就不会有回调，自己唤醒自己，免得调用方永久挂起
            done.complete(Unit)
            return
        }
        pendingCompletion = done
        done.await()
    }

    /** 停止播放并清理临时文件。 */
    fun stop() {
        // 先作废旧代号，再取消流：正在读流的循环看到代号变了就不再把新的块写进已释放的 track
        playbackId++
        activeStream?.let { closeStream(it) }
        track?.let { abortTrack(it) }
        releasePlayer()
        currentFile?.delete()
        currentFile = null
        pendingCompletion?.complete(Unit)
        pendingCompletion = null
        audioSession.abandonFocus()
    }

    private fun closeStream(stream: PcmSpeechStream) {
        if (activeStream === stream) activeStream = null
        // cancel 先于 close：只有它能唤醒可能正阻塞在 read 上的线程
        stream.cancel()
        stream.close()
    }

    /** 自然放完：MODE_STREAM 下 stop 会把已写入缓冲的部分放完，不会截尾。 */
    private fun finishTrack(target: AudioTrack) {
        if (track === target) track = null
        runCatching { target.stop() }
        runCatching { target.release() }
    }

    /** 打断：flush 丢弃缓冲，立刻静音。 */
    private fun abortTrack(target: AudioTrack) {
        if (track === target) track = null
        runCatching { target.pause() }
        runCatching { target.flush() }
        runCatching { target.stop() }
        runCatching { target.release() }
    }

    private fun playbackAttributes(): AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

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

        /** 每次从流里读多少字节；4KB 在 24kHz 单声道下约 85ms，足够消灭卡顿。 */
        const val CHUNK_BYTES = 4096

        /** 缓存收声上限：超过就放弃缓存，宁可多一次往返也不扛一段长音频在内存里。 */
        const val MAX_CAPTURE_BYTES = 4 * 1024 * 1024
    }
}
